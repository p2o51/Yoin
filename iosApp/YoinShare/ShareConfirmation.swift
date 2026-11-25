import SwiftUI

/// The small card the share ends on: what happened, what to do, Done. Public API only, system look (the
/// extension carries none of Yoin's fonts or symbols), a card at the bottom over a dim. Its words are in the
/// `Share` table (Share.xcstrings), bundled in the extension and, for the QA screenshot
/// (`-openInDemo share-confirm`), in the app.
struct ShareConfirmation: View {
    var done: () -> Void
    @State private var shown = false

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(shown ? 0.25 : 0)
                .ignoresSafeArea()
                .onTapGesture(perform: done)
                .accessibilityHidden(true)
            VStack(spacing: 16) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 40, weight: .semibold))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                VStack(spacing: 6) {
                    Text("Saved to Yoin", tableName: "Share",
                         comment: "Share extension confirmation title. Copy: Rebecca, 2026-11-25.")
                        .font(.title3.weight(.semibold))
                    Text("Open Yoin to continue", tableName: "Share",
                         comment: "Share extension confirmation line. Copy: Rebecca, 2026-11-25.")
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
                Button(action: done) {
                    Text("Done", tableName: "Share", comment: "Closes the share confirmation.")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
            }
            .padding(24)
            .frame(maxWidth: 420)
            .background(.regularMaterial, in: .rect(cornerRadius: 32, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            .offset(y: shown ? 0 : 400)
            .accessibilityElement(children: .contain)
        }
        // Yoin violet (Theme.swift `Yoin.violet` light), white on it in both modes.
        .tint(Color(red: 0x6B / 255, green: 0x4F / 255, blue: 0xD8 / 255))
        .onAppear { withAnimation(.spring(response: 0.42, dampingFraction: 0.86)) { shown = true } }
    }
}
