import SwiftUI
import StorageHarness

@main
struct StorageHarnessApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// Whether this process already started the `--run-automatic` sequence. `onAppear` can fire again (scene re-appearance,
/// a re-rendered NavigationView, a view rebuilt with a new identity), and a second run would interleave with the first
/// over the same Keychain names and print two `HARNESS DONE` lines. A process-wide flag, touched on the main thread only.
private enum AutomaticRun {
    static var started = false
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
        .onAppear {
            // `xcrun simctl launch --console <device> <bundle id> --run-automatic` runs every automatic case and
            // prints `<id> <verdict>` lines (details indented) and a final `HARNESS DONE` line to stdout.
            if ProcessInfo.processInfo.arguments.contains("--run-automatic") && !AutomaticRun.started {
                AutomaticRun.started = true
                runAutomatic()
            }
        }
    }

    /// Runs every non-manual case one after the other, as the buttons would, and logs each verdict to stdout.
    private func runAutomatic() {
        let automatic = runner.cases().filter { !$0.manual }
        // `notPassed` counts FAIL and `FAIL (timed out)`; a SKIPPED case (the device lacks a passcode or Face ID) is counted
        // on its own and is neither: the release check reads `notPassed=0` and names every skipped case.
        func step(_ index: Int, _ passed: Int, _ skipped: Int, _ failed: Int) {
            if index == automatic.count {
                emit("HARNESS DONE cases=\(automatic.count) passed=\(passed) skipped=\(skipped) notPassed=\(failed)")
                return
            }
            let info = automatic[index]
            running = info.id
            runner.run(id: info.id) { result in
                verdicts[info.id] = result.verdict
                details[info.id] = result.details
                running = nil
                emit("\(info.id) \(result.verdict)")
                for line in result.details.split(separator: "\n", omittingEmptySubsequences: false) { emit("    \(line)") }
                if result.verdict == "PASS" {
                    step(index + 1, passed + 1, skipped, failed)
                } else if result.verdict == "SKIPPED" {
                    step(index + 1, passed, skipped + 1, failed)
                } else {
                    step(index + 1, passed, skipped, failed + 1)
                }
            }
        }
        emit("HARNESS START automaticCases=\(automatic.count)")
        step(0, 0, 0, 0)
    }

    private func emit(_ line: String) {
        print(line)
        fflush(stdout)
    }

    private func color(_ verdict: String) -> Color {
        if verdict == "PASS" { return .green }
        if verdict.hasPrefix("FAIL") { return .red }
        return .orange
    }
}
