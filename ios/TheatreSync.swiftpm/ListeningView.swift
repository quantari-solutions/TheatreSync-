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
                    .accessibilityHidden(true)

                    GradientTitle(text: session.movieTitle.isEmpty ? session.hostName : session.movieTitle, size: 28)
                        .multilineTextAlignment(.center)

                    syncPill

                    QCard {
                        Text("Kept in step automatically").font(.headline).foregroundStyle(Q.text)
                        Text("TheatreSync allows for your headphones' own delay (\(-session.soundDelayMs) ms now) and keeps correcting, so voices match lips.")
                            .font(.subheadline).foregroundStyle(Q.textFaint)
                    }

                    Text("Pause and skip on the Android phone. You can go back to the home page; the sound keeps playing until you tap Leave.")
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
        .navigationTitle("Listening")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Q.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
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
