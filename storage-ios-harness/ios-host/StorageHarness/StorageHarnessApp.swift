import SwiftUI
import StorageHarness

@main
struct StorageHarnessApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// One row per harness case: a button that runs it against the real Keychain, and what came back.
struct ContentView: View {
    private let runner = HarnessRunner()
    @State private var verdicts: [String: String] = [:]
    @State private var details: [String: String] = [:]
    @State private var running: String? = nil

    var body: some View {
        NavigationView {
            List(runner.cases(), id: \.id) { info in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(info.title).font(.headline)
                        Spacer()
                        if let verdict = verdicts[info.id] { Text(verdict).bold().foregroundColor(color(verdict)) }
                    }
                    Text(info.instructions).font(.footnote).foregroundColor(.secondary)
                    Button(running == info.id ? "Running..." : (info.manual ? "Run (manual)" : "Run")) {
                        running = info.id
                        runner.run(id: info.id) { result in
                            verdicts[info.id] = result.verdict
                            details[info.id] = result.details
                            running = nil
                        }
                    }
                    .disabled(running != nil)
                    if let text = details[info.id] {
                        Text(text).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    }
                }
                .padding(.vertical, 4)
            }
            .navigationTitle("Storage harness")
        }
    }

    private func color(_ verdict: String) -> Color {
        switch verdict {
        case "PASS": return .green
        case "FAIL": return .red
        default: return .orange
        }
    }
}
