import SwiftUI

/// Shown while the movie's sound is playing in this device's headphones.
struct ListeningView: View {
    @ObservedObject var session: ListenerSession

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image(systemName: "headphones")
                    .font(.system(size: 64))
                    .foregroundStyle(.yellow)
                    .padding(.top, 24)

                Text(session.movieTitle.isEmpty ? session.hostName : session.movieTitle)
                    .font(.title.bold())
                    .multilineTextAlignment(.center)

                Text(statusText)
                    .font(.headline)
                    .foregroundStyle(.yellow)
                    .multilineTextAlignment(.center)

                VStack(spacing: 12) {
                    Text("Do voices match lips?")
                        .font(.headline)
                    Text("If not, tap a button a few times until they do.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        Button {
                            session.adjustSoundDelay(by: 20)
                        } label: {
                            Text("Sound is early").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        Button {
                            session.adjustSoundDelay(by: -20)
                        } label: {
                            Text("Sound is late").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Text("Sound delay: \(session.soundDelayMs) ms")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding()
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))

                Text("Pause and skip on the Android phone. Keep this screen open during the movie.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button(role: .destructive) {
                    session.leave()
                } label: {
                    Text("Leave").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                if let drift = session.driftMs {
                    Text("Sync: \(drift) ms")
                        .font(.caption2)
                        .foregroundStyle(.gray)
                }
            }
            .padding()
        }
        .navigationTitle("Listening")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var statusText: String {
        switch session.status {
        case .idle: return ""
        case .connecting: return "Connecting…"
        case .waiting: return "Waiting for the movie to start…"
        case .playing: return "Playing in your headphones"
        case .paused: return "Paused"
        case .lost(let why): return why
        }
    }
}
