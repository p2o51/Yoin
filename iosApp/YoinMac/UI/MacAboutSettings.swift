import SwiftUI

/// Settings › Storage › Playback cache (iOS StorageSettingsSection): the streamed songs held on disk and
/// their size; Clear drops them. Downloads and the pages kept for offline aren't counted or cleared.
struct MacPlaybackCacheSection: View {
    @State private var bytes: Int64?
    @State private var clearing = false

    var body: some View {
        Section("Playback Cache") {
            LabeledContent("Streamed songs on this Mac",
                           value: bytes.map { $0 == 0 ? "0 B" : ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? "…")
            Button("Clear Playback Cache") {
                clearing = true
                Task {
                    await AudioCache.shared.clear()
                    await refresh()
                    clearing = false
                }
            }
            .disabled(clearing || (bytes ?? 0) == 0)
        }
        .task { await refresh() }
    }

    private func refresh() async {
        bytes = await Task.detached(priority: .userInitiated) { AudioCache.shared.sizeBytes() }.value
    }
}

/// Settings › About: the version and the open source the Mac app ships (iOS OpenSourceLicensesView's list,
/// less the Spotify iOS SDK, which the Mac doesn't link). Clicking one shows its license in full.
struct MacAboutSettings: View {
    @State private var open: OssComponent?

    private var components: [OssComponent] { openSourceComponents.filter { $0.license != .mitMPMessagePack } }

    private var version: String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "?"
        let b = info?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Yoin for Mac", value: version)
            }
            Section("Open Source Licenses") {
                ForEach(components) { c in
                    Button { open = c } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.name)
                            Text("\(c.copyright)  \(c.license.displayName)").font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .formStyle(.grouped)
        .sheet(item: $open) { c in
            VStack(alignment: .leading, spacing: 12) {
                Text(c.name).font(.gsf(17, .semibold))
                Text(c.license.displayName).font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                ScrollView {
                    Text(c.license.text).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack { Spacer(); Button("Done") { open = nil }.keyboardShortcut(.defaultAction) }
            }
            .padding(20)
            .frame(width: 560, height: 520)
        }
    }
}
