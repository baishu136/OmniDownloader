import SwiftUI

/// One linear clock drives the fade; smoothstep gives both scales zero endpoint velocity.
struct LaunchContent: AnimatableModifier {
    var progress: CGFloat
    var animatableData: CGFloat { get { progress } set { progress = newValue } }
    func body(content: Content) -> some View {
        let t = min(1, max(0, progress)), eased = t * t * (3 - 2 * t)
        content.scaleEffect(0.95 + 0.05 * eased).allowsHitTesting(t >= 1)
    }
}
struct LaunchOverlay: View, Animatable {
    var progress: CGFloat
    @Environment(\.colorScheme) private var scheme
    var animatableData: CGFloat { get { progress } set { progress = newValue } }
    var body: some View {
        let t = min(1, max(0, progress)), eased = t * t * (3 - 2 * t)
        ZStack {
            OmniPalette.background.ignoresSafeArea()
            OmniIcon(name: "icloud.and.arrow.down", size: 128)
                .foregroundColor(scheme == .dark ? Color(red:184/255,green:195/255,blue:1) : Color(red:69/255,green:84/255,blue:200/255))
                .scaleEffect(1 - 0.15 * eased)
        }.opacity(Double(1 - t)).allowsHitTesting(false).accessibilityHidden(true)
    }
}
