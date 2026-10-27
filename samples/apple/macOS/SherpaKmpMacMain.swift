import Foundation
@preconcurrency import SherpaOnnxKmp

@main
struct SherpaKmpMacSample {
    static func main() async {
        guard CommandLine.arguments.count == 3 else {
            FileHandle.standardError.write(
                Data("usage: SherpaKmpMacOS MODEL_DIR WAV_FILE\n".utf8)
            )
            Foundation.exit(2)
        }

        let modelDir = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
        let wavURL = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
        do {
            let text = try await SherpaTranscriber.transcribe(
                modelStore: FileModelStore(rootPath: modelDir.path),
                wavURL: wavURL
            )
            print(text)
        } catch {
            FileHandle.standardError.write(Data("transcription failed: \(error)\n".utf8))
            Foundation.exit(1)
        }
    }
}
