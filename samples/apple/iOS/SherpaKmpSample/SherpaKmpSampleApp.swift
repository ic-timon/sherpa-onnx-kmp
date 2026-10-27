import SwiftUI
@preconcurrency import SherpaOnnxKmp

@main
struct SherpaKmpSampleApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

@MainActor
private final class SampleViewModel: ObservableObject {
    @Published var status = "Ready"
    @Published var transcript = ""

    func transcribeBundledSample() {
        guard !status.hasPrefix("Running") else { return }
        status = "Running..."
        transcript = ""
        Task {
            do {
                guard let wavURL = Bundle.main.url(
                    forResource: "0",
                    withExtension: "wav",
                    subdirectory: "SherpaModels/models/nemo-ctc/test_wavs"
                ) else {
                    throw SherpaSampleError.missingResource("SherpaModels/models/nemo-ctc/test_wavs/0.wav")
                }
                transcript = try await SherpaTranscriber.transcribe(
                    modelStore: BundleModelStore(
                        resourceRoot: "SherpaModels/models/nemo-ctc",
                        bundle: .main
                    ),
                    modelFile: "model.onnxdata",
                    wavURL: wavURL
                )
                status = "Completed"
                print("SherpaKmpIOS transcript: \(transcript)")
                if ProcessInfo.processInfo.arguments.contains("--auto-run"),
                   let documents = FileManager.default.urls(
                       for: .documentDirectory,
                       in: .userDomainMask
                   ).first {
                    try? transcript.write(
                        to: documents.appendingPathComponent("transcript.txt"),
                        atomically: true,
                        encoding: .utf8
                    )
                }
            } catch {
                status = "Failed: \(error.localizedDescription)"
                if ProcessInfo.processInfo.arguments.contains("--auto-run"),
                   let documents = FileManager.default.urls(
                       for: .documentDirectory,
                       in: .userDomainMask
                   ).first {
                    try? "ERROR: \(error)".write(
                        to: documents.appendingPathComponent("transcript.txt"),
                        atomically: true,
                        encoding: .utf8
                    )
                }
            }
        }
    }
}

private struct ContentView: View {
    @StateObject private var viewModel = SampleViewModel()

    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 20) {
                Text("sherpa-onnx KMP")
                    .font(.title.bold())
                Text("The model and WAV file are loaded from the app bundle through BundleModelStore.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Button("Transcribe bundled sample") {
                    viewModel.transcribeBundledSample()
                }
                .buttonStyle(.borderedProminent)
                Text(viewModel.status)
                    .font(.headline)
                ScrollView {
                    Text(viewModel.transcript.isEmpty ? "—" : viewModel.transcript)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Offline ASR")
        }
        .task {
            if ProcessInfo.processInfo.arguments.contains("--auto-run") {
                viewModel.transcribeBundledSample()
            }
        }
    }
}
