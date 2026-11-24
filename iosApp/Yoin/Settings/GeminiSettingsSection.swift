import SwiftUI

/// Settings › Features › AI features (Android `AiSettingsPage`).
/// Hosts AI v2 (`AiSettingsView`) when entered as its own page (`page == true`) and preserves the
/// Pixel collapsible summary row when embedded in a flat list (`page == false`).
struct GeminiSettingsSection: View {
    var app: CoverScheme
    var row: Color
    var page = false

    @State private var settings = GeminiSettings.shared
    @State private var open = false

    var body: some View {
        if page {
            AiSettingsView(app: app, row: row)
        } else {
            listBody
        }
    }

    private var listBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Features")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)

            Button {
                open.toggle()
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("AI features")
                            .yoinRole(Tokens.type.bodyLarge)
                            .foregroundStyle(app.onSurface)
                        Text(summary)
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                            .contentTransition(.opacity)
                            .lineLimit(2)
                    }
                    Spacer(minLength: 16)
                    YoinIcon("chevron_down", size: 20)
                        .foregroundStyle(app.onSurfaceVariant)
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .background(row, in: CloudSyncView.segment(0, 1))
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .pointerHighlight()

            if open {
                AiSettingsView(app: app, row: row)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(Yoin.spatial, value: open)
        .animation(Yoin.effects, value: settings.hasKey)
        .animation(Yoin.effects, value: settings.targetLanguage)
    }

    private var summary: String {
        guard settings.hasKey else {
            return String(localized: "Song info, Ask Gemini, translation and Memory titles")
        }
        return Self.configuredSummary(settings)
    }

    static func configuredSummary(_ settings: GeminiSettings) -> String {
        let zh = Tips.showsChinese
        let p = settings.prefs
        let provider: String = switch p.provider {
        case .gemini: "Gemini"
        case .deepSeek: "DeepSeek"
        case .qwen: zh ? "千问" : "Qwen"
        case .custom: zh ? "自定义" : "Custom"
        }
        if p.provider == .custom {
            let model = p.customModel.trimmingCharacters(in: .whitespacesAndNewlines)
            return model.isEmpty ? provider : "\(provider)   \(model)"
        }
        if p.provider.tiers.count > 1 {
            let tier = p.activeTier == .best ? (zh ? "最佳" : "Best") : (zh ? "好" : "Good")
            return "\(provider)   \(tier)"
        }
        return provider
    }
}
