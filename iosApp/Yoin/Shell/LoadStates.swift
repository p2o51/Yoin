import SwiftUI

/// Loading and failure states for server lists: quiet blocks in the page's
/// own tones while a list loads (no spinner, no copy), a Retry label when it
/// failed.

/// A failed load: one tonal Retry label, and Copy details beside it when the failure has a report.
struct RetryRow: View {
    var scheme: CoverScheme
    /// What went wrong, above the button (a failed load's message).
    var message: String? = nil
    /// The failure as a tester's report (`LibraryStore.failureReports`): Copy details (Android DetailErrorState).
    var details: String? = nil
    var action: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            if let message {
                Text(message)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            HStack(spacing: 12) {
                Button(action: action) {
                    Text("Retry")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.onSecondaryContainer)
                        .padding(.horizontal, 20)
                        .frame(minHeight: 40)
                        .background(scheme.secondaryContainer, in: .capsule)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                if let details { CopyDetailsButton(report: details, scheme: scheme) }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }
}

/// Copies a failure's report for the tester to send on (Android CopyDiagnosticsButton): a text button whose
/// label turns to Copied once it has.
struct CopyDetailsButton: View {
    var report: String
    var scheme: CoverScheme
    @State private var copied: String?

    var body: some View {
        let done = copied == report
        Button {
            UIPasteboard.general.string = report
            withAnimation(Yoin.effects) { copied = report }
        } label: {
            ZStack {
                // Both labels take their room, so the swap doesn't shift Retry.
                Text("Copy details").opacity(done ? 0 : 1)
                Text("Copied").opacity(done ? 1 : 0)
            }
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(scheme.primary)
            .padding(.horizontal, 12)
            .frame(minHeight: 40)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .pointerHighlight()
        .accessibilityLabel(done ? Text("Copied") : Text("Copy details"))
    }
}

/// A placeholder block that breathes: an alpha change, so an effects spring
/// (critically damped, no overshoot), slowed to a breath and repeated. Still
/// under Reduce Motion.
struct SkeletonBlock: View {
    var color: Color
    var cornerRadius: CGFloat = Tokens.shape.THUMB.cg
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dim = false

    /// Effects-family spring (damping 1) at a breathing tempo, repeated both ways.
    @MainActor static var breath: Animation {
        .spring(response: 0.9, dampingFraction: 1)
            .speed(LaunchConfig.animationSpeed)
            .repeatForever(autoreverses: true)
    }

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(color)
            .opacity(dim ? 0.55 : 1)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(Self.breath) { dim = true }
            }
    }
}

/// Home while its first load runs: the hero card, then a row of covers.
struct HomeSkeleton: View {
    var width: CGFloat
    var scheme: CoverScheme

    var body: some View {
        let fill = scheme.surfaceContainerHigh
        let col = (width - 2 * Tokens.home.JBI_COLUMN_GAP.cg) / 3
        VStack(alignment: .leading, spacing: 18) {
            SkeletonBlock(color: fill, cornerRadius: Tokens.shape.CARD.cg).frame(height: 132)
            HStack(spacing: Tokens.home.JBI_COLUMN_GAP.cg) {
                ForEach(0..<3, id: \.self) { _ in
                    SkeletonBlock(color: fill, cornerRadius: EntityBackdrop.corner(col)).frame(width: col, height: col)
                }
            }
        }
        .padding(.top, 34)
    }
}

/// A 3-column grid of cover placeholders (Library, Search).
struct GridSkeleton: View {
    var cell: CGFloat
    var gutter: CGFloat
    var scheme: CoverScheme
    var rows = 3

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg + 40) {
            ForEach(0..<rows, id: \.self) { _ in
                HStack(spacing: gutter) {
                    ForEach(0..<3, id: \.self) { _ in
                        SkeletonBlock(color: scheme.surfaceContainerHigh, cornerRadius: EntityBackdrop.corner(cell))
                            .frame(width: cell, height: cell)
                    }
                }
            }
        }
    }
}

/// Song-row placeholders (48 thumb + two text bars).
struct RowSkeleton: View {
    var scheme: CoverScheme
    var rows = 6

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(0..<rows, id: \.self) { _ in
                HStack(spacing: Tokens.library.ROW_GAP.cg) {
                    SkeletonBlock(color: scheme.surfaceContainerHigh)
                        .frame(width: Tokens.library.ROW_THUMB.cg, height: Tokens.library.ROW_THUMB.cg)
                    VStack(alignment: .leading, spacing: 6) {
                        SkeletonBlock(color: scheme.surfaceContainerHigh, cornerRadius: 4).frame(width: 160, height: 12)
                        SkeletonBlock(color: scheme.surfaceContainerHigh, cornerRadius: 4).frame(width: 100, height: 10)
                    }
                }
                .padding(.horizontal, Tokens.library.ROW_PADDING_H.cg)
                .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
            }
        }
    }
}
