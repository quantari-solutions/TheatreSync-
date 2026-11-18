import SwiftUI
import UIKit

/// First screen on iPhone/iPad: pick the movie that the Android phone is playing.
struct JoinView: View {
    @ObservedObject var session: ListenerSession
    @ObservedObject var browser: HostBrowser
    @State private var manualAddress = ""

    private var deviceWord: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
    }

    private var trimmedAddress: String {
        manualAddress.trimmingCharacters(in: .whitespaces)
    }

    var body: some View {
        ZStack {
            GridBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Eyebrow(text: "Quantari · TheatreSync")
                        GradientTitle(text: "TheatreSync")
                        Text("Picture on the big screen. Sound in your headphones. Perfectly in step.")
                            .foregroundStyle(Q.textMuted)
                    }
                    .padding(.top, 8)

                    QCard(highlighted: true) {
                        Text("Join the movie")
                            .font(.title2.bold())
                            .foregroundStyle(Q.text)
                        Text("Connect your headphones to this \(deviceWord) first.")
                            .font(.subheadline)
                            .foregroundStyle(Q.textFaint)
                        if browser.hosts.isEmpty {
                            StatusPill(text: "Searching", color: Q.primary, pulsing: true)
                            Text("Start a movie on the Android phone. It will show up here.")
                                .font(.subheadline)
                                .foregroundStyle(Q.textFaint)
                        }
                        ForEach(browser.hosts) { host in
                            Button {
                                session.join(address: host.address, name: host.name)
                            } label: {
                                Label(host.name, systemImage: "headphones")
                            }
                            .buttonStyle(GoldButtonStyle())
                        }
                    }

                    QCard {
                        Text("Or type the address")
                            .font(.headline)
                            .foregroundStyle(Q.text)
                        Text("It's shown on the Android phone under Friends listening.")
                            .font(.subheadline)
                            .foregroundStyle(Q.textFaint)
                        TextField("e.g. 192.168.1.23:41234", text: $manualAddress)
                            .keyboardType(.numbersAndPunctuation)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .font(.system(.body, design: .monospaced))
                            .foregroundStyle(Q.primaryLight)
                            .padding(12)
                            .background(Q.background, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Q.borderAccent, lineWidth: 1))
                        Button("Join") {
                            session.join(address: trimmedAddress, name: "Movie")
                        }
                        .buttonStyle(GhostButtonStyle())
                        .disabled(trimmedAddress.isEmpty)
                        .opacity(trimmedAddress.isEmpty ? 0.5 : 1)
                    }

                    HStack {
                        Spacer()
                        NavigationLink {
                            DiagnosticsView()
                        } label: {
                            Label("Headphones", systemImage: "headphones")
                        }
                        .buttonStyle(GhostButtonStyle())
                        Spacer()
                    }

                    Text("TheatreSync · by Quantari Solutions")
                        .font(.caption2)
                        .foregroundStyle(Q.textFaint)
                        .frame(maxWidth: .infinity)
                }
                .padding(20)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { browser.start() }
        .onDisappear { browser.stop() }
    }
}
