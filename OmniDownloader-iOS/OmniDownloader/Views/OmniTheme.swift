import SwiftUI
import UIKit

/// Android Color.kt 的语义色；iOS 保留原生排版、动态字体与安全区行为。
enum OmniPalette {
    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                           green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
    static let primary = adaptive(0x3B5BDB, 0xB3C5FF)
    static let onPrimary = adaptive(0xFFFFFF, 0x0A2A6B)
    static let tertiary = adaptive(0x725572, 0xE1BAD9)
    static let primaryContainer = adaptive(0xDCE3FF, 0x2C4A9E)
    static let onPrimaryContainer = adaptive(0x0A1A5C, 0xDCE3FF)
    static let background = adaptive(0xF7F7FB, 0x111318)
    static let surface = adaptive(0xFFFFFF, 0x1B1D24)
    static let surfaceVariant = adaptive(0xE7E7F0, 0x2A2C33)
    static let onSurface = adaptive(0x1A1B21, 0xE3E2E8)
    static let secondary = adaptive(0x46464F, 0xC6C6D0)
    static let outline = adaptive(0xC8C8D2, 0x46464F)
    static let success = adaptive(0x10B981, 0x4ADE80)
    static let warning = adaptive(0xB16B00, 0xFBBF24)
    static let error = adaptive(0xBA1A1A, 0xFFB4AB)

    static func configureNativeAppearance() {
        // iOS 15 retains its native tab bar; the same tokens keep its fallback consistent.
        let bar = UITabBarAppearance()
        bar.configureWithOpaqueBackground()
        bar.backgroundColor = UIColor(surface)
        for item in [bar.stackedLayoutAppearance, bar.inlineLayoutAppearance, bar.compactInlineLayoutAppearance] {
            item.selected.iconColor = UIColor(primary)
            item.selected.titleTextAttributes = [.foregroundColor: UIColor(primary)]
            item.normal.iconColor = UIColor(secondary)
            item.normal.titleTextAttributes = [.foregroundColor: UIColor(secondary)]
        }
        UITabBar.appearance().standardAppearance = bar
        UITabBar.appearance().scrollEdgeAppearance = bar
        UITableView.appearance().backgroundColor = UIColor(background)
        UITextView.appearance().backgroundColor = .clear
    }
}

struct OmniCard: ViewModifier {
    var radius: CGFloat = 18
    func body(content: Content) -> some View {
        content
            .background(OmniPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous)
                .stroke(OmniPalette.outline.opacity(0.45), lineWidth: 0.5))
    }
}

extension View {
    func omniCard(radius: CGFloat = 18) -> some View { modifier(OmniCard(radius: radius)) }
    @ViewBuilder func omniScrollBackground() -> some View {
        if #available(iOS 16.0, *) { scrollContentBackground(.hidden) } else { self }
    }
    @ViewBuilder func omniTabBarVisibility() -> some View {
        if #available(iOS 16.0, *) { toolbar(.hidden, for: .tabBar) } else { self }
    }
}

struct OmniTaskTabs: View {
    @Binding var selection: Int
    let titles: [String]
    var body: some View {
        HStack(spacing: 8) {
            ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                Button { withAnimation(.easeInOut(duration: 0.2)) { selection = index } } label: {
                    Text(title).font(.subheadline.weight(.semibold))
                        .foregroundStyle(selection == index ? OmniPalette.onPrimaryContainer : OmniPalette.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(selection == index ? OmniPalette.primaryContainer : Color.clear, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == index ? .isSelected : [])
            }
        }
    }
}
