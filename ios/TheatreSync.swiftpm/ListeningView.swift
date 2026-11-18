import SwiftUI

/// Shown while the movie's sound is playing in this device's headphones.
struct ListeningView: View {
    @ObservedObject var session: ListenerSession

    var body: some View {
        ZStack {
            GridBackground()
            ScrollView {
                VStack(spacing: 20) {
                    Eyebrow(text: eyebrow)
                        .padding(.top, 8)

                    ZStack {
                        Circle()
                            .fill(Q.gold)
                            .frame(width: 120, height: 120)
                            .shadow(color: Q.glow.opacity(0.55), radius: session.status == .playing ? 26 : 12)
                        Image(systemName: "headphones")
                            .font(.system(size: 52, weight: .semibold))
                            .foregroundStyle(.black)
                    }
                    .padding(.vertical, 8)

                    GradientTitle(text: session.movieTitle.isEmpty ? session.hostName : session.movieTitle, size: 28)
                        .multilineTextAlignment(.center)

                    syncPill

                    QCard {
                        Text("Do voices match lips?")
                            .font(.title3.bold())
                            .foregroundStyle(Q.text)
                        Text("If not, tap a button a few times until they do. It's remembered for next time.")
                            .font(.subheadline)
                            .foregroundStyle(Q.textFaint)
                        HStack(spacing: 10) {
                            Button("Sound is early") { session.adjustSoundDelay(by: 20) }
                                .buttonStyle(GoldButtonStyle())
                            Button("Sound is late") { session.adjustSoundDelay(by: -20) }
                                .buttonStyle(GoldButtonStyle())
                        }
                        Text("Sound delay: \(session.soundDelayMs) ms")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Q.textFaint)
                            .frame(maxWidth: .infinity)
                    }

                    Text("Pause and skip on the Android phone. Keep this screen open during the movie.")
                        .font(.footnote)
                        .foregroundStyle(Q.textFaint)
                        .multilineTextAlignment(.center)

                    Button(role: .destructive) {
                        session.leave()
                    } label: {
                        Text("Leave").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(GhostButtonStyle())
                }
                .padding(20)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    private var eyebrow: String {
        switch session.status {
        case .idle: return ""
        case .connecting: return "Connecting"
        case .waiting: return "Waiting for the movie"
        case .playing: return "Playing in your headphones"
        case .paused: return "Paused"
        case .lost: return "Connection lost"
        }
    }

    @ViewBuilder
    private var syncPill: some View {
        switch session.status {
        case .lost(let why):
            Text(why)
                .font(.subheadline)
                .foregroundStyle(Q.primaryLight)
                .multilineTextAlignment(.center)
        case .playing:
            if let drift = session.driftMs, abs(drift) <= 40 {
                StatusPill(text: "In sync · \(drift > 0 ? "+" : "")\(drift) ms", color: Q.success)
            } else if let drift = session.driftMs {
                StatusPill(text: "Syncing · \(drift > 0 ? "+" : "")\(drift) ms", color: Q.primary, pulsing: true)
            } else {
                StatusPill(text: "Starting", color: Q.primary, pulsing: true)
            }
        case .paused:
            StatusPill(text: "Paused", color: Q.textMuted)
        default:
            StatusPill(text: "Connecting", color: Q.primary, pulsing: true)
        }
    }
}
