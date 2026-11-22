import SwiftUI
import UIKit

/// The Quantari / TheatreSync opening (mirror of Android's OpeningAnimation.kt), shown once when the app
/// starts fresh. Driven by its own clock (TimelineView), so it always plays in full, whatever the
/// device's Reduce Motion setting. Text and logo are drawn at the device's own resolution. Tap to skip.
struct OpeningView: View {
    var onDone: () -> Void
    @State private var start = Date()
    private let total = 6.6
    private let gold = Color(red: 232 / 255, green: 184 / 255, blue: 74 / 255)

    var body: some View {
        TimelineView(.animation) { ctx in
            let t = ctx.date.timeIntervalSince(start)
            let glowIn = seg(t, 0, 1.8), mark = seg(t, 0.3, 1.5), eyebrow = seg(t, 2.3, 0.9), title = seg(t, 2.7, 1.2)
            let bloom = seg(t, 2.9, 1.7), line = seg(t, 3.5, 1.4), tag = seg(t, 3.9, 0.9)
            let swell = bell(t, 4.3, 1.5), out = 1 - seg(t, 5.7, 0.9)
            let glow = glowIn * (1 + 0.6 * swell)
            GeometryReader { geo in
                let w = min(geo.size.width, 480)
                let inner = w - 48
                ZStack {
                    Color.black
                    RadialGradient(colors: [gold.opacity(min(1, 0.16 * glow)), gold.opacity(min(1, 0.07 * glow)), .clear],
                                   center: UnitPoint(x: 0.5, y: 0.46), startRadius: 0, endRadius: min(geo.size.width, geo.size.height) * 0.75)
                        .opacity(out)
                    VStack(spacing: 0) {
                        Image(uiImage: UIImage(named: "quantari_wordmark", in: .main, with: nil) ?? UIImage())
                            .resizable().scaledToFit()
                            .frame(width: w * 0.9 / 1.14)
                            .opacity(mark).scaleEffect(0.96 + 0.04 * mark)
                        Spacer().frame(height: 28)
                        Text("PRESENTED WITH")
                            .font(.system(size: min(inner * 0.038, 14), weight: .bold)).tracking(min(inner * 0.038, 14) * 0.42)
                            .foregroundStyle(gold).rise(eyebrow)
                        Spacer().frame(height: 10)
                        Text("TheatreSync")
                            .font(.system(size: min(inner * 0.15, 52), weight: .heavy))
                            .foregroundStyle(LinearGradient(colors: [gold, Color(red: 1, green: 0.906, blue: 0.639), gold], startPoint: .leading, endPoint: .trailing))
                            .shadow(color: gold.opacity(min(1, 0.25 + 0.45 * bloom + 0.25 * swell)), radius: 6 + 22 * bloom + 12 * swell)
                            .lineLimit(1).minimumScaleFactor(0.5)
                            .rise(title)
                        Spacer().frame(height: 14)
                        Rectangle()
                            .fill(LinearGradient(colors: [.clear, gold, .clear], startPoint: .leading, endPoint: .trailing))
                            .frame(width: w * 0.62 * line, height: 2)
                        Spacer().frame(height: 14)
                        Text("a Quantari Solutions module")
                            .font(.system(size: min(inner * 0.046, 17), weight: .medium)).tracking(1)
                            .foregroundStyle(Color(red: 0.663, green: 0.639, blue: 0.604)).rise(tag)
                    }
                    .frame(width: w)
                    .opacity(out)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .onChange(of: t >= total) { finished in if finished { finish("played in full") } }
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture { finish("tapped") }
        .accessibilityLabel("TheatreSync, a Quantari Solutions module")
        .onAppear { start = Date(); print("Opening: shown") }
    }

    /// Logged so the simulator check (CI) can prove it shows and for how long.
    private func finish(_ why: String) {
        print("Opening: finished after \(Int(Date().timeIntervalSince(start) * 1000)) ms (\(why))")
        onDone()
    }

    private func seg(_ t: Double, _ s: Double, _ d: Double) -> Double {
        let x = min(1, max(0, (t - s) / d))
        return 0.5 - 0.5 * cos(.pi * x)
    }
    private func bell(_ t: Double, _ s: Double, _ d: Double) -> Double {
        let x = min(1, max(0, (t - s) / d))
        return 0.5 - 0.5 * cos(2 * .pi * x)
    }
}

private extension View {
    /// Fade in while rising 24 points into place.
    func rise(_ p: Double) -> some View { opacity(p).offset(y: (1 - p) * 24) }
}
