import SwiftUI

/// Quantari Solutions look (quantari-solutions.vercel.app), matching the Android app:
/// near-black background with a faint gold grid, gold gradient, pill buttons with a gold glow.
enum Q {
    static let background = Color(hex: 0x050508)
    static let backgroundAlt = Color(hex: 0x0A0A12)
    static let card = Color(hex: 0x0D0D16)
    static let border = Color(hex: 0x1A1608)
    static let borderAccent = Color(hex: 0x2A2410)
    static let primary = Color(hex: 0xF0C44C)
    static let primaryLight = Color(hex: 0xFFE9A8)
    static let primaryDark = Color(hex: 0xD4A017)
    static let secondary = Color(hex: 0xB8860B)
    static let text = Color(hex: 0xF5F6FA)
    static let textMuted = Color(hex: 0xC5CAD6)
    static let textFaint = Color(hex: 0x8A8FA0)
    static let success = Color(hex: 0x2ECF8A)
    static let glow = Color(hex: 0xB89B00)

    static let gold = LinearGradient(
        colors: [primary, primaryLight, primaryDark],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

struct GridBackground: View {
    var body: some View {
        ZStack {
            Q.background
            Canvas { context, size in
                var path = Path()
                let step: CGFloat = 32
                var x: CGFloat = 0
                while x < size.width {
                    path.move(to: CGPoint(x: x, y: 0))
                    path.addLine(to: CGPoint(x: x, y: size.height))
                    x += step
                }
                var y: CGFloat = 0
                while y < size.height {
                    path.move(to: CGPoint(x: 0, y: y))
                    path.addLine(to: CGPoint(x: size.width, y: y))
                    y += step
                }
                context.stroke(path, with: .color(Color(hex: 0xD4A017, opacity: 0.07)), lineWidth: 1)
            }
            RadialGradient(
                colors: [Color(hex: 0xF0C44C, opacity: 0.18), .clear],
                center: .top,
                startRadius: 0,
                endRadius: 420
            )
        }
        .ignoresSafeArea()
    }
}

struct GoldButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline.weight(.bold))
            .foregroundStyle(.black)
            .padding(.vertical, 16)
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity)
            .background(Q.gold, in: Capsule())
            .shadow(color: Q.glow.opacity(0.45), radius: 14, x: 0, y: 6)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
    }
}

struct GhostButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.bold))
            .foregroundStyle(Q.primaryLight)
            .padding(.vertical, 12)
            .padding(.horizontal, 18)
            .background(Q.card.opacity(0.6), in: Capsule())
            .overlay(Capsule().stroke(Q.borderAccent, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct QCard<Content: View>: View {
    var highlighted: Bool = false
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content()
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(colors: [Q.card, Q.backgroundAlt], startPoint: .top, endPoint: .bottom),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(highlighted ? Q.secondary.opacity(0.6) : Q.borderAccent, lineWidth: 1)
        )
        .shadow(color: highlighted ? Q.glow.opacity(0.35) : .clear, radius: 18)
    }
}

struct Eyebrow: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(LinearGradient(colors: [Q.secondary, Q.primary], startPoint: .leading, endPoint: .trailing))
                .frame(width: 20, height: 1)
            Text(text.uppercased())
                .font(.caption.weight(.semibold))
                .tracking(1.2)
                .foregroundStyle(Q.primary)
            Rectangle().fill(LinearGradient(colors: [Q.primary, Q.secondary], startPoint: .leading, endPoint: .trailing))
                .frame(width: 20, height: 1)
        }
    }
}

struct GradientTitle: View {
    let text: String
    var size: CGFloat = 34

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .heavy))
            .foregroundStyle(Q.gold)
    }
}

struct StatusPill: View {
    let text: String
    let color: Color
    var pulsing: Bool = false
    @State private var dim = false

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
                .opacity(pulsing && dim ? 0.35 : 1)
            Text(text)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(color)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(color.opacity(0.12), in: Capsule())
        .overlay(Capsule().stroke(color.opacity(0.35), lineWidth: 1))
        .onAppear {
            guard pulsing else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dim = true }
        }
    }
}
