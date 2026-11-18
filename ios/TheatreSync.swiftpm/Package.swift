// swift-tools-version: 5.9
// Swift Playgrounds app package. Open this folder (TheatreSync.swiftpm) in
// Swift Playgrounds on iPad, or in Xcode on a Mac. No third-party dependencies.

import PackageDescription
import AppleProductTypes

let package = Package(
    name: "TheatreSync",
    platforms: [
        .iOS("17.0")
    ],
    products: [
        .iOSApplication(
            name: "TheatreSync",
            targets: ["AppModule"],
            bundleIdentifier: "com.theatresync.ios",
            teamIdentifier: "",
            displayVersion: "0.3.1",
            bundleVersion: "4",
            appIcon: .placeholder(icon: .tv),
            accentColor: .presetColor(.yellow),
            supportedDeviceFamilies: [
                .pad,
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeRight,
                .landscapeLeft,
                .portraitUpsideDown(.when(deviceFamilies: [.pad]))
            ],
            capabilities: [
                .localNetwork(
                    purposeString: "TheatreSync looks for the phone that is playing the movie on your Wi-Fi.",
                    bonjourServiceTypes: ["_theatresync._tcp"]
                )
            ]
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            path: "."
        )
    ]
)
