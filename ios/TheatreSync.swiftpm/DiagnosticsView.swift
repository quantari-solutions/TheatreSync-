import SwiftUI
import AVFoundation

/// "Headphones" page: shows where the sound will play in plain words.
/// Technical details (latency, buffer, port types, protocol self-test) are behind the ⓘ button.
struct DiagnosticsView: View {
    @State private var headphones: [String] = []
    @State private var report: String = ""
    @State private var showDetails = false

    var body: some View {
        ZStack {
            GridBackground()
            ScrollView {
                VStack(spacing: 18) {
                    ZStack {
                        Circle()
                            .fill(headphones.isEmpty ? AnyShapeStyle(Q.card) : AnyShapeStyle(Q.gold))
                            .frame(width: 132, height: 132)
                            .overlay(Circle().stroke(headphones.isEmpty ? Q.borderAccent : Q.secondary, lineWidth: 1))
                            .shadow(color: headphones.isEmpty ? .clear : Q.glow.opacity(0.5), radius: 24)
                        Image(systemName: "headphones")
                            .font(.system(size: 56, weight: .semibold))
                            .foregroundStyle(headphones.isEmpty ? Q.textFaint : .black)
                    }
                    .padding(.top, 12)
                    .accessibilityHidden(true)

                    if headphones.isEmpty {
                        Text("No headphones connected")
                            .font(.title2.bold())
                            .foregroundStyle(Q.text)
                        Text("Turn your headphones on and connect them in Settings › Bluetooth.")
                            .foregroundStyle(Q.textMuted)
                            .multilineTextAlignment(.center)
                    } else {
                        ForEach(headphones, id: \.self) { name in
                            QCard(highlighted: true) {
                                HStack {
                                    GradientTitle(text: name, size: 24)
                                    Spacer()
                                    StatusPill(text: "Connected", color: Q.success)
                                }
                                Text("The movie's sound will play here.")
                                    .foregroundStyle(Q.textMuted)
                            }
                        }
                    }
                }
                .padding(20)
            }
        }
        .navigationTitle("Headphones")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showDetails = true
                } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel("Technical details")
            }
        }
        .sheet(isPresented: $showDetails) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Eyebrow(text: "Technical details")
                    Text("Take a screenshot of this if Claude asks for one.")
                        .font(.subheadline)
                        .foregroundStyle(Q.textFaint)
                    Text(report)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(Q.textMuted)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(Q.background, in: RoundedRectangle(cornerRadius: 12))
                }
                .padding(20)
            }
            .background(Q.card)
            .presentationDetents([.medium, .large])
        }
        .onAppear { refresh() }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { _ in
            refresh()
        }
    }

    private func refresh() {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs
        headphones = outputs
            .filter { [.bluetoothA2DP, .bluetoothLE, .headphones, .usbAudio].contains($0.portType) }
            .map { $0.portName }

        var lines: [String] = []
        lines.append("TheatreSync iOS 0.3.1 · protocol v\(SyncProtocol.protocolVersion)")
        lines.append("Output latency: \(Int(session.outputLatency * 1000)) ms")
        lines.append("IO buffer: \(Int(session.ioBufferDuration * 1000)) ms")
        lines.append("Sample rate: \(Int(session.sampleRate)) Hz")
        lines.append("")
        lines.append("Current outputs:")
        for port in outputs {
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
