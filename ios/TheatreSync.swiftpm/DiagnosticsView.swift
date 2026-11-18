import SwiftUI
import AVFoundation

/// Milestone 0 diagnostics: shows the iPad's current audio route and the
/// latency iOS reports for it, plus a self-test of the shared protocol code.
struct DiagnosticsView: View {
    @State private var report: String = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Take a screenshot of this screen and share it back in Claude Code.")
                        .font(.callout)
                    Button("Refresh") { refresh() }
                        .buttonStyle(.borderedProminent)
                    Text(report)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
                .padding()
            }
            .navigationTitle("TheatreSync")
        }
        .onAppear { refresh() }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { _ in
            refresh()
        }
    }

    private func refresh() {
        let session = AVAudioSession.sharedInstance()
        var lines: [String] = []
        lines.append("TheatreSync iOS 0.1.0 · protocol v\(SyncProtocol.protocolVersion)")
        lines.append("Output latency: \(Int(session.outputLatency * 1000)) ms")
        lines.append("IO buffer: \(Int(session.ioBufferDuration * 1000)) ms")
        lines.append("Sample rate: \(Int(session.sampleRate)) Hz")
        lines.append("")
        lines.append("Current outputs:")
        for port in session.currentRoute.outputs {
            lines.append("  • \(port.portName)")
            lines.append("    type=\(port.portType.rawValue) route=\(OutputRoute(port: port.portType).rawValue)")
        }
        lines.append("")
        lines.append("Protocol self-test: \(SyncProtocol.selfTest())")
        report = lines.joined(separator: "\n")
    }
}

extension OutputRoute {
    init(port: AVAudioSession.Port) {
        switch port {
        case .bluetoothA2DP: self = .bluetoothA2dp
        case .bluetoothLE: self = .bluetoothLeAudio
        case .headphones, .usbAudio: self = .wired
        case .builtInSpeaker, .builtInReceiver: self = .speaker
        case .airPlay: self = .cast
        default: self = .unknown
        }
    }
}
