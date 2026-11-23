import SwiftUI
import YoinShared

/// Finalized "Sync location" page on iOS / iPadOS / macOS (matching Android `BackupScreens.SyncLocationPage`
/// and prototype v2): lets Apple users choose between iCloud (CloudKit) and Google Drive, and runs the 4-step
/// verified migration (export & verify -> import -> verify counts -> optional old copy cleanup).
struct SyncLocationView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var sync = CloudSync.shared
    @State private var selectedLocation: String = CloudSync.shared.state.location
    @State private var switchResult: LocationSwitchResult?
    @State private var switching = false
    @State private var clearingOld = false
    @State private var oldCleared = false
    @State private var notice: String?

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    private var row: Color { app.surfaceBright }
    private var currentLocation: String { sync.state.location }
    private var canSwitch: Bool {
        !switching && selectedLocation != currentLocation &&
            (selectedLocation != SyncLocations.shared.GOOGLE_DRIVE || sync.isGoogleConfigured)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(String(localized: "Sync location"))
                        .yoinRole(Tokens.type.displaySmall)
                        .foregroundStyle(app.onSurface)
                        .padding(.horizontal, 8)
                        .padding(.top, 8)
                    Text(String(localized: "Choose where Cloud sync stores its data. Switching exports and verifies a snapshot, imports it, and checks the counts before it completes."))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .padding(.horizontal, 8)
                }

                if let result = switchResult, result.succeeded {
                    switchedSummary(result)
                } else {
                    servicePickerGroup
                    stepsGroup
                    footerNote
                    primaryActionButton
                }

                if let notice {
                    Text(notice)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.error)
                        .padding(.horizontal, 8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
        }
        .safeAreaBar(edge: .top) { topBar.topEdgeBlur(app.surfaceContainer) }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { selectedLocation = currentLocation }
    }

    // MARK: - Service picker

    private var servicePickerGroup: some View {
        group(String(localized: "Sync service")) {
            [
                AnyView(locationOption(
                    icon: "cloud",
                    title: String(localized: "iCloud"),
                    subtitle: String(localized: "CloudKit  Apple devices"),
                    badge: currentLocation == SyncLocations.shared.ICLOUD ? String(localized: "Current") : nil,
                    selected: selectedLocation == SyncLocations.shared.ICLOUD,
                    enabled: !switching
                ) {
                    notice = nil
                    selectedLocation = SyncLocations.shared.ICLOUD
                }),
                AnyView(locationOption(
                    icon: "cloud",
                    title: String(localized: "Google Drive"),
                    subtitle: currentLocation == SyncLocations.shared.GOOGLE_DRIVE
                        ? (sync.state.account?.email ?? sync.state.account?.displayName ?? String(localized: "Cross-platform"))
                        : (sync.isGoogleConfigured ? String(localized: "Cross-platform") : String(localized: "Not configured")),
                    badge: currentLocation == SyncLocations.shared.GOOGLE_DRIVE ? String(localized: "Current") : nil,
                    selected: selectedLocation == SyncLocations.shared.GOOGLE_DRIVE,
                    enabled: !switching && sync.isGoogleConfigured
                ) {
                    notice = nil
                    selectedLocation = SyncLocations.shared.GOOGLE_DRIVE
                }),
                AnyView(locationOption(
                    icon: "storage",
                    title: String(localized: "Lyra"),
                    subtitle: String(localized: "Self-hosted server"),
                    badge: String(localized: "Coming soon"),
                    badgeMuted: true,
                    selected: false,
                    enabled: false
                ) {
                    notice = String(localized: "Syncing to Lyra is coming soon.")
                }),
            ]
        }
    }

    private var stepsGroup: some View {
        let steps: [(String, String)] = [
            (String(localized: "Export and verify"),
             String(localized: "Export a full snapshot from the current location and check its SHA-256")),
            (String(localized: "Import to new service"),
             String(localized: "Connect to the new service and merge with the sync rules")),
            (String(localized: "Verify counts"),
             String(localized: "Compare record counts account by account, then switch once they match")),
            (String(localized: "Clean up old copy (optional)"),
             String(localized: "Keep the copy in the old location, and delete it later if you like")),
        ]
        return group(String(localized: "How switching works")) {
            steps.enumerated().map { index, step in
                AnyView(HStack(alignment: .center, spacing: 12) {
                    Text("\(index + 1)")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(app.onSurface)
                        .frame(width: 28, height: 28)
                        .background(app.surfaceContainerHighest, in: .circle)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(step.0)
                            .yoinRole(Tokens.type.titleMedium)
                            .foregroundStyle(app.onSurface)
                        Text(step.1)
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14))
            }
        }
    }

    private var footerNote: some View {
        HStack(alignment: .top, spacing: 10) {
            YoinIcon("info", size: 18)
                .foregroundStyle(app.onSurfaceVariant)
                .padding(.top, 2)
            Text(String(localized: "Switching only changes where the cloud copy lives. Data on this device isn't affected."))
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
    }

    private var primaryActionButton: some View {
        Button {
            Task { await performSwitch() }
        } label: {
            HStack(spacing: 8) {
                if switching {
                    ProgressView().tint(app.onPrimary)
                }
                Text(switchButtonTitle)
                    .yoinRole(Tokens.type.labelLarge)
            }
            .foregroundStyle(canSwitch ? app.onPrimary : app.onSurfaceVariant)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(canSwitch ? app.primary : app.surfaceContainerHighest, in: .capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!canSwitch)
    }

    private var switchButtonTitle: String {
        if switching { return String(localized: "Switching…") }
        if selectedLocation == currentLocation { return String(localized: "Current sync location") }
        let targetName = selectedLocation == SyncLocations.shared.ICLOUD
            ? String(localized: "iCloud")
            : String(localized: "Google Drive")
        return String(localized: "Switch to \(targetName)")
    }

    // MARK: - Verified switch result (Step 3 & optional Step 4)

    @ViewBuilder
    private func switchedSummary(_ result: LocationSwitchResult) -> some View {
        let targetName = result.toLocation == SyncLocations.shared.ICLOUD
            ? String(localized: "iCloud")
            : String(localized: "Google Drive")
        let oldName = result.fromLocation == SyncLocations.shared.ICLOUD
            ? String(localized: "iCloud")
            : String(localized: "Google Drive")

        group(String(localized: "Switched to \(targetName)")) {
            var rows: [AnyView] = [
                AnyView(infoRow(
                    String(localized: "Counts verified"),
                    String(localized: "\(result.totalLiveRecords) records uploaded and verified")
                )),
            ]
            if let sha = result.snapshotSha256, !sha.isEmpty {
                rows.append(AnyView(infoRow(String(localized: "Snapshot SHA-256"), String(sha.prefix(16)) + "…")))
            }
            for item in result.accountCounts {
                rows.append(AnyView(infoRow(item.displayName, String(localized: "\(item.liveRecords) records"))))
            }
            return rows
        }

        group(String(localized: "Old copy in \(oldName)")) {
            var rows: [AnyView] = [
                AnyView(infoRow(
                    oldCleared ? String(localized: "Old copy removed") : String(localized: "Old copy kept"),
                    oldCleared
                        ? String(localized: "Files in \(oldName) were cleared. Other devices will see the migration notice.")
                        : String(localized: "Your copy in \(oldName) stays untouched. You can remove it now or later.")
                )),
            ]
            if !oldCleared {
                rows.append(AnyView(Button {
                    Task { await clearOldCopy(oldLocation: result.fromLocation) }
                } label: {
                    Text(clearingOld ? String(localized: "Removing…") : String(localized: "Remove old copy from \(oldName)"))
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.error)
                        .padding(.horizontal, 16)
                        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                }
                .buttonStyle(PressScale())
                .disabled(clearingOld)))
            }
            return rows
        }

        Button { dismiss() } label: {
            Text(String(localized: "Done"))
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onPrimary)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(app.primary, in: .capsule)
        }
        .buttonStyle(PressScale())
    }

    // MARK: - Actions

    private func performSwitch() async {
        notice = nil
        switching = true
        defer { switching = false }
        let result = await sync.switchLocation(to: selectedLocation, clearOldCopy: false)
        if let result {
            if result.succeeded {
                switchResult = result
                oldCleared = result.oldCopyCleared
            } else {
                notice = result.error ?? String(localized: "Couldn't switch sync location.")
            }
        }
    }

    private func clearOldCopy(oldLocation: String) async {
        notice = nil
        clearingOld = true
        defer { clearingOld = false }
        if let err = await sync.clearOldLocationCopy(oldLocation: oldLocation) {
            notice = err
        } else {
            oldCleared = true
        }
    }

    // MARK: - Helpers

    private func locationOption(
        icon: String,
        title: String,
        subtitle: String,
        badge: String?,
        badgeMuted: Bool = false,
        selected: Bool,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                YoinIcon(icon, size: 22)
                    .foregroundStyle(enabled ? app.onSurfaceVariant : app.onSurfaceVariant.opacity(0.5))
                    .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(title)
                            .yoinRole(Tokens.type.bodyLarge)
                            .foregroundStyle(enabled ? app.onSurface : app.onSurfaceVariant)
                        if let badge {
                            Text(badge)
                                .yoinRole(Tokens.type.labelSmall)
                                .foregroundStyle(badgeMuted ? app.onSurfaceVariant : app.onPrimaryContainer)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(badgeMuted ? app.surfaceContainerHighest : app.primaryContainer, in: .capsule)
                        }
                    }
                    Text(subtitle)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                Circle()
                    .strokeBorder(selected ? app.primary : app.outlineVariant.opacity(enabled ? 1 : 0.5),
                                  lineWidth: selected ? 6 : 2)
                    .frame(width: 22, height: 22)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
    }

    private func infoRow(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
            Text(value).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func group(_ title: String, rows: () -> [AnyView]) -> some View {
        let items = rows()
        return VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                ForEach(items.indices, id: \.self) { i in
                    items[i]
                        .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
                        .background(row, in: CloudSyncView.segment(i, items.count))
                }
            }
        }
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .yGlassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }
}
