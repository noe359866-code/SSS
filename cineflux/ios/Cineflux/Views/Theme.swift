import SwiftUI

enum Theme {
    // Apple TV + Netflix: negro absoluto, tipografía blanca y rojo de acento.
    static let ink = Color(hex: "#000000")
    static let panel = Color(hex: "#0B0B0D")
    static let panelHigh = Color(hex: "#1A1A1F")
    static let stroke = Color(hex: "#2C2C31")
    static let text = Color(hex: "#FFFFFF")
    static let muted = Color(hex: "#A1A1AA")
    static let rose = Color(hex: "#E50914")
    static let golden = Color(hex: "#F5C518")
    static let tomato = Color(hex: "#FA320A")
}

extension Color {
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var value: UInt64 = 0
        Scanner(string: cleaned).scanHexInt64(&value)
        let red, green, blue: Double
        if cleaned.count == 6 {
            red = Double((value >> 16) & 0xFF) / 255
            green = Double((value >> 8) & 0xFF) / 255
            blue = Double(value & 0xFF) / 255
        } else {
            red = 1; green = 1; blue = 1
        }
        self.init(.sRGB, red: red, green: green, blue: blue, opacity: 1)
    }
}

func colorFromHex(_ hex: String, fallback: Color = Theme.rose) -> Color {
    let cleaned = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
    return cleaned.count == 6 ? Color(hex: hex) : fallback
}

func formatRuntime(_ minutes: Int) -> String {
    guard minutes > 0 else { return "" }
    let hours = minutes / 60
    let rest = minutes % 60
    if hours == 0 { return "\(rest)min" }
    if rest == 0 { return "\(hours)h" }
    return "\(hours)h \(rest)min"
}

func formatTime(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let total = Int(seconds)
    return String(format: "%d:%02d", total / 60, total % 60)
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .bold))
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(Theme.rose.opacity(configuration.isPressed ? 0.75 : 1))
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct GhostButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background(Theme.panelHigh.opacity(configuration.isPressed ? 0.7 : 1))
            .foregroundStyle(Theme.text)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct SettingsCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Theme.stroke.opacity(0.55), lineWidth: 1)
        )
        .padding(.horizontal, 18)
    }
}
