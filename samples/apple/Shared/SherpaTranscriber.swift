import Foundation
@preconcurrency import SherpaOnnxKmp

enum SherpaSampleError: Error, LocalizedError {
    case missingResource(String)
    case malformedWav(String)

    var errorDescription: String? {
        switch self {
        case .missingResource(let path):
            return "Missing resource: \(path)"
        case .malformedWav(let reason):
            return "Malformed WAV: \(reason)"
        }
    }
}

struct SherpaTranscriber {
    static func transcribe(
        modelStore: any ModelStore,
        modelFile: String = "model.int8.onnx",
        wavURL: URL
    ) async throws -> String {
        let audio = try readMono16BitWav(wavURL)
        let runtime = try await AppleRuntimeKt.createSherpaRuntime(modelStore: modelStore)

        do {
            let recognizer = try await runtime.createOfflineRecognizer(
                config: recognizerConfig(modelFile: modelFile)
            )
            do {
                let result = try await recognizer.transcribe(audio: audio)
                try await recognizer.close()
                try await runtime.close()
                return result.text
            } catch {
                try? await recognizer.close()
                throw error
            }
        } catch {
            try? await runtime.close()
            throw error
        }
    }

    private static func recognizerConfig(modelFile: String) -> OfflineRecognizerConfig {
        let modelConfig = OfflineModelConfig(
            model: OfflineModelNemoCtc(model: modelFile),
            tokens: "tokens.txt",
            options: RuntimeOptions(
                numThreads: 2,
                provider: "cpu",
                debug: false,
                extraOptions: [:]
            )
        )
        return OfflineRecognizerConfig(
            featureConfig: FeatureConfig(sampleRate: 16_000, featureDim: 80),
            modelConfig: modelConfig,
            decodingMethod: "greedy_search",
            maxActivePaths: 4,
            hotwordsFile: nil,
            hotwordsScore: 1.5,
            extraOptions: [:]
        )
    }

    private static func readMono16BitWav(_ url: URL) throws -> AudioData {
        let data = try Data(contentsOf: url)
        guard data.count >= 12 else {
            throw SherpaSampleError.malformedWav("file is shorter than 12 bytes")
        }
        guard String(data: data[0..<4], encoding: .ascii) == "RIFF",
              String(data: data[8..<12], encoding: .ascii) == "WAVE" else {
            throw SherpaSampleError.malformedWav("expected RIFF/WAVE header")
        }

        var offset = 12
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcm: Data?

        while offset + 8 <= data.count {
            let id = String(data: data[offset..<(offset + 4)], encoding: .ascii) ?? ""
            let size = Int(data.readUInt32LE(at: offset + 4))
            let body = offset + 8
            guard body + size <= data.count else {
                throw SherpaSampleError.malformedWav("truncated \(id) chunk")
            }
            switch id {
            case "fmt ":
                guard size >= 16 else {
                    throw SherpaSampleError.malformedWav("fmt chunk is too short")
                }
                format = Int(data.readUInt16LE(at: body))
                channels = Int(data.readUInt16LE(at: body + 2))
                sampleRate = Int(data.readUInt32LE(at: body + 4))
                bitsPerSample = Int(data.readUInt16LE(at: body + 14))
            case "data":
                pcm = data.subdata(in: body..<(body + size))
            default:
                break
            }
            offset = body + size + (size & 1)
        }

        guard format == 1, channels == 1, bitsPerSample == 16, sampleRate > 0 else {
            throw SherpaSampleError.malformedWav("expected mono 16-bit PCM")
        }
        guard let pcm else {
            throw SherpaSampleError.malformedWav("missing data chunk")
        }

        let kotlinSamples = KotlinFloatArray(size: Int32(pcm.count / 2))
        for index in 0..<(pcm.count / 2) {
            let raw = Int16(bitPattern: pcm.readUInt16LE(at: index * 2))
            kotlinSamples.set(index: Int32(index), value: Float(raw) / 32_768.0)
        }
        return AudioData(samples: kotlinSamples, sampleRate: Int32(sampleRate))
    }
}

private extension Data {
    func readUInt16LE(at offset: Int) -> UInt16 {
        UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func readUInt32LE(at offset: Int) -> UInt32 {
        UInt32(self[offset]) |
            (UInt32(self[offset + 1]) << 8) |
            (UInt32(self[offset + 2]) << 16) |
            (UInt32(self[offset + 3]) << 24)
    }
}
