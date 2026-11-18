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

    var body: some View {
        List {
            Section {
                Text("Picture on the big screen, sound in your headphones.")
                    .font(.headline)
                Text("Connect your headphones to this \(deviceWord) first.")
                    .foregroundStyle(.secondary)
            }

            Section("Movies on your Wi-Fi") {
                if browser.hosts.isEmpty {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Looking for a movie… Start one on the Android phone.")
                            .foregroundStyle(.secondary)
                    }
                }
                ForEach(browser.hosts) { host in
                    Button {
                        session.join(address: host.address, name: host.name)
                    } label: {
                        Label(host.name, systemImage: "headphones")
                            .font(.title3)
                            .padding(.vertical, 8)
                    }
                }
            }

            Section("Or type the address shown on the phone") {
                TextField("e.g. 192.168.1.23:41234", text: $manualAddress)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Button("Join") {
                    session.join(address: manualAddress.trimmingCharacters(in: .whitespaces), name: "Movie")
                }
                .disabled(manualAddress.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            Section {
                NavigationLink("Check headphones") {
                    DiagnosticsView()
                }
            }
        }
        .navigationTitle("TheatreSync")
        .onAppear { browser.start() }
        .onDisappear { browser.stop() }
    }
}
