import SwiftUI

/*
 * Interactive `DemoPhone` scenes for Home Screen Widgets, Handoff / Continue On, Online AI features,
 * and Cloud Sync. Built purely with Yoin's own design tokens, `YoinIcon`, `CoverArt`, `SettingsPage`,
 * and `DemoNowPlaying` so users can touch every demo directly and never touch live user data.
 */

// MARK: - Home Screen Widgets

/// Demonstrates adding and using Yoin's Play and Memory widgets on a mini Home Screen (`HomeScreenWidgets`).
struct HomeScreenWidgetsDemo: View {
    var playing: Bool
    var scheme: CoverScheme

    @State private var pickerOpen = false
    @State private var added = false
    @State private var widgetPlaying = false

    private var playAlbum: Album { TipDemo.albums[0] }
    private var memoryAlbum: Album { TipDemo.albums[1] }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(DemoTiming.springAnimation) {
                pickerOpen = false
                added = false
                widgetPlaying = false
            }
            try await DemoTiming.delay(450)
            try await finger.moveTo("widgetEdit")
            try await finger.hold {
                withAnimation(DemoTiming.springAnimation) { pickerOpen = true }
            }
            try await DemoTiming.delay(700)
            try await finger.moveTo("widgetGalleryAdd")
            try await finger.tap {
                withAnimation(DemoTiming.springAnimation) {
                    pickerOpen = false
                    added = true
                }
            }
            try await DemoTiming.delay(700)
            try await finger.moveTo("widgetPlayToggle")
            try await finger.tap {
                withAnimation(Yoin.effects) { widgetPlaying = true }
            }
            try await finger.lift()
            try await DemoTiming.delay(2_200)
        }) {
            ZStack(alignment: .bottom) {
                LinearGradient(
                    colors: [scheme.surfaceContainerLow, scheme.primaryContainer.opacity(0.45), scheme.surface],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        Button {
                            withAnimation(DemoTiming.springAnimation) { pickerOpen.toggle() }
                        } label: {
                            Text(added ? "Done" : "Edit")
                                .yoinRole(Tokens.type.labelMedium, weight: .semibold)
                                .foregroundStyle(scheme.onSurface)
                                .padding(.horizontal, 12)
                                .frame(height: 28)
                                .background(scheme.surfaceContainerHighest.opacity(0.9), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .demoTarget("widgetEdit")
                        Spacer()
                    }

                    if added {
                        HStack(spacing: 12) {
                            miniPlayWidget
                            miniMemoryWidget
                        }
                        .transition(.scale(scale: 0.84).combined(with: .opacity))
                    }

                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 4), spacing: 16) {
                        homeAppIcon(icon: "music_note", title: "Yoin", tinted: true)
                        homeAppIcon(icon: "album", title: "Albums", tinted: false)
                        homeAppIcon(icon: "lyrics", title: "Lyrics", tinted: false)
                        homeAppIcon(icon: "library", title: "Library", tinted: false)
                    }

                    Spacer()
                }
                .padding(.top, DemoPhoneMetrics.statusBar + 10)
                .padding(.horizontal, 18)

                if pickerOpen {
                    VStack(alignment: .leading, spacing: 12) {
                        Capsule()
                            .fill(scheme.onSurfaceVariant.opacity(0.35))
                            .frame(width: 34, height: 4)
                            .frame(maxWidth: .infinity)
                        HStack(spacing: 10) {
                            YoinIcon("library", size: 20).foregroundStyle(scheme.primary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Yoin Widgets")
                                    .yoinRole(Tokens.type.titleSmall, weight: .semibold)
                                    .foregroundStyle(scheme.onSurface)
                                Text("Play • Memories")
                                    .yoinRole(Tokens.type.bodySmall)
                                    .foregroundStyle(scheme.onSurfaceVariant)
                            }
                            Spacer()
                            Button {
                                withAnimation(DemoTiming.springAnimation) {
                                    pickerOpen = false
                                    added = true
                                }
                            } label: {
                                Text("Add Widget")
                                    .yoinRole(Tokens.type.labelMedium, weight: .semibold)
                                    .foregroundStyle(scheme.onPrimary)
                                    .padding(.horizontal, 12)
                                    .frame(height: 30)
                                    .background(scheme.primary, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .demoTarget("widgetGalleryAdd")
                        }
                    }
                    .padding(16)
                    .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 24, style: .continuous))
                    .padding(12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
    }

    private var miniPlayWidget: some View {
        let trackTitle = playAlbum.tracks.first?.title ?? TipDemo.trackTitles[0]
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                CoverArt(palette: playAlbum.palette, cornerRadius: 10, seed: playAlbum.seed)
                    .frame(width: 44, height: 44)
                Spacer()
                Button {
                    withAnimation(Yoin.effects) { widgetPlaying.toggle() }
                } label: {
                    YoinIcon(widgetPlaying ? "pause_filled" : "play_filled", size: 18)
                        .foregroundStyle(scheme.onPrimary)
                        .frame(width: 32, height: 32)
                        .background(scheme.primary, in: Circle())
                }
                .buttonStyle(.plain)
                .demoTarget("widgetPlayToggle")
            }
            Spacer(minLength: 0)
            Text(trackTitle)
                .lineLimit(1)
                .yoinRole(Tokens.type.labelMedium, weight: .semibold)
                .foregroundStyle(scheme.onSurface)
            Text(playAlbum.artist)
                .lineLimit(1)
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(scheme.onSurfaceVariant)
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .frame(height: 124)
        .background(scheme.surfaceContainer, in: .rect(cornerRadius: 22, style: .continuous))
    }

    private var miniMemoryWidget: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                CoverArt(palette: memoryAlbum.palette, cornerRadius: 8, seed: memoryAlbum.seed)
                    .frame(width: 28, height: 28)
                Spacer()
                YoinIcon("edit_note", size: 15)
                    .foregroundStyle(scheme.primary)
            }
            Text("Memories")
                .yoinRole(Tokens.type.labelSmall, weight: .semibold)
                .foregroundStyle(scheme.primary)
            Text("Late rain against the glass")
                .lineLimit(2)
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(scheme.onSurface)
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .frame(height: 124)
        .background(scheme.surfaceContainer, in: .rect(cornerRadius: 22, style: .continuous))
    }

    private func homeAppIcon(icon: String, title: String, tinted: Bool) -> some View {
        VStack(spacing: 5) {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(tinted ? scheme.primary : scheme.surfaceContainerHigh)
                .frame(width: 52, height: 52)
                .overlay {
                    YoinIcon(icon, size: 22)
                        .foregroundStyle(tinted ? scheme.onPrimary : scheme.onSurfaceVariant)
                }
            Text(title)
                .lineLimit(1)
                .yoinRole(Tokens.type.labelSmall)
                .foregroundStyle(scheme.onSurfaceVariant)
        }
    }
}

// MARK: - Continue On (Handoff)

/// Demonstrates picking up playback from another Apple device via Handoff in the Dock / App Switcher (`ContinueOn`).
struct ContinueOnDemo: View {
    var playing: Bool
    var scheme: CoverScheme

    @State private var demo = TipDemo.nowPlaying()
    @State private var handedOff = false

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(DemoTiming.springAnimation) { handedOff = false }
            try await DemoTiming.delay(500)
            try await finger.moveTo("handoffPill")
            try await finger.tap {
                withAnimation(DemoTiming.springAnimation) { handedOff = true }
            }
            try await finger.lift()
            try await DemoTiming.delay(2_400)
        }) {
            ZStack {
                if handedOff {
                    DemoNowPlaying(demo: demo, playing: playing)
                        .overlay(alignment: .topTrailing) {
                            Button {
                                withAnimation(DemoTiming.springAnimation) { handedOff = false }
                            } label: {
                                YoinIcon("chevron_down", size: 18)
                                    .foregroundStyle(scheme.onSurfaceVariant)
                                    .frame(width: 32, height: 32)
                                    .background(scheme.surfaceContainerHigh.opacity(0.8), in: Circle())
                            }
                            .buttonStyle(.plain)
                            .padding(.top, DemoPhoneMetrics.statusBar + 6)
                            .padding(.trailing, 14)
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                } else {
                    handoffDockStage
                        .transition(.opacity)
                }
            }
        }
    }

    private var handoffDockStage: some View {
        let trackTitle = demo.album.tracks.first?.title ?? TipDemo.trackTitles[0]
        return VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 12) {
                CoverArt(palette: demo.album.palette, cornerRadius: 20, seed: demo.album.seed)
                    .frame(width: 112, height: 112)
                VStack(spacing: 3) {
                    Text(trackTitle)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                        .foregroundStyle(scheme.onSurface)
                    Text("\(demo.album.artist) • Playing nearby")
                        .lineLimit(1)
                        .yoinRole(Tokens.type.bodySmall)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity)
            .background(scheme.surfaceContainer, in: .rect(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, 22)

            Spacer()

            Button {
                withAnimation(DemoTiming.springAnimation) { handedOff = true }
            } label: {
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(scheme.primary)
                        .frame(width: 38, height: 38)
                        .overlay {
                            YoinIcon("music_note", size: 18)
                                .foregroundStyle(scheme.onPrimary)
                        }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Yoin")
                            .yoinRole(Tokens.type.titleSmall, weight: .semibold)
                            .foregroundStyle(scheme.onSurface)
                        HStack(spacing: 4) {
                            YoinIcon("devices", size: 13)
                                .foregroundStyle(scheme.primary)
                            Text("From iPhone • Continue")
                                .yoinRole(Tokens.type.labelSmall)
                                .foregroundStyle(scheme.primary)
                        }
                    }
                    Spacer()
                    YoinIcon("play_filled", size: 18)
                        .foregroundStyle(scheme.primary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(scheme.surfaceContainerHigh, in: Capsule())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 18)
            .padding(.bottom, 22)
            .demoTarget("handoffPill")
        }
        .background(scheme.surface)
    }
}

// MARK: - Online AI (AI features)

/// Interactive preview of Settings › AI features (`AiServices`), switching provider, tier, and trying a song.
struct AiServicesDemo: View {
    var playing: Bool
    var scheme: CoverScheme

    enum Provider: String, CaseIterable {
        case gemini = "Gemini"
        case deepSeek = "DeepSeek"
        case qwen = "千问"
        case custom = "自定义"

        var directInChina: Bool { self == .deepSeek || self == .qwen }
    }

    enum Tier: String, CaseIterable {
        case good = "好"
        case best = "最佳"
    }

    @State private var selectedProvider: Provider = .gemini
    @State private var selectedTier: Tier = .good
    @State private var triedSample = false

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.effects) {
                selectedProvider = .gemini
                selectedTier = .good
                triedSample = false
            }
            try await DemoTiming.delay(550)
            try await finger.moveTo("ai-provider-deepSeek")
            try await DemoTiming.delay(220)
            try await finger.tap {
                withAnimation(Yoin.effects) { selectedProvider = .deepSeek }
            }
            try await DemoTiming.delay(750)
            try await finger.moveTo("ai-tier-best")
            try await DemoTiming.delay(220)
            try await finger.tap {
                withAnimation(Yoin.effects) { selectedTier = .best }
            }
            try await DemoTiming.delay(750)
            try await finger.moveTo("ai-try-song")
            try await DemoTiming.delay(220)
            try await finger.tap {
                withAnimation(DemoTiming.springAnimation) { triedSample = true }
            }
            try await finger.lift()
            try await DemoTiming.delay(2_200)
        }) {
            SettingsPage(title: "AI features", demoScheme: scheme) { app, row in
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Service")
                            .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(app.onSurfaceVariant)
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                            ForEach(Provider.allCases, id: \.self) { provider in
                                providerButton(provider, app: app, row: row)
                            }
                        }
                    }
                    .padding(16)
                    .background(row, in: RoundedRectangle(cornerRadius: 20))

                    VStack(alignment: .leading, spacing: 10) {
                        Text("Quality")
                            .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(app.onSurfaceVariant)
                        HStack(spacing: 8) {
                            ForEach(Tier.allCases, id: \.self) { tier in
                                let selected = selectedTier == tier
                                Button {
                                    withAnimation(Yoin.effects) { selectedTier = tier }
                                } label: {
                                    Text(tier.rawValue)
                                        .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                                        .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 36)
                                        .background(selected ? app.primary : app.surfaceContainerHighest, in: Capsule())
                                }
                                .buttonStyle(.plain)
                                .demoTarget(tier == .best ? "ai-tier-best" : "ai-tier-good")
                            }
                        }
                    }
                    .padding(16)
                    .background(row, in: RoundedRectangle(cornerRadius: 20))

                    VStack(alignment: .leading, spacing: 8) {
                        Button {
                            withAnimation(DemoTiming.springAnimation) { triedSample.toggle() }
                        } label: {
                            HStack(spacing: 8) {
                                YoinIcon("sparkle", size: 18)
                                Text("Try a song")
                                    .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                                Spacer()
                                YoinIcon("chevron_right", size: 16)
                            }
                            .foregroundStyle(app.primary)
                        }
                        .buttonStyle(.plain)
                        .demoTarget("ai-try-song")

                        if triedSample {
                            Text("Blue Rooms • Warm analog tape feel with restrained room reverb.")
                                .yoinRole(Tokens.type.bodySmall)
                                .foregroundStyle(app.onSurfaceVariant)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .padding(16)
                    .background(row, in: RoundedRectangle(cornerRadius: 20))
                }
            }
            .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
            .scrollDisabled(true)
        }
    }

    private func providerButton(_ provider: Provider, app: CoverScheme, row: Color) -> some View {
        let selected = selectedProvider == provider
        return Button {
            withAnimation(Yoin.effects) { selectedProvider = provider }
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(provider.rawValue)
                    .lineLimit(1)
                    .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                    .foregroundStyle(selected ? app.onPrimary : app.onSurface)
                if provider.directInChina {
                    Text("中国直连")
                        .yoinRole(Tokens.type.labelSmall)
                        .foregroundStyle(selected ? app.onPrimary.opacity(0.9) : app.primary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(selected ? app.primary : app.surfaceContainerHighest,
                        in: .rect(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .demoTarget(provider == .deepSeek ? "ai-provider-deepSeek" : "ai-provider-\(provider.rawValue)")
    }
}

// MARK: - Cloud Sync

/// Interactive preview of Settings › Cloud sync (`CloudSync`) enabling Google Drive sync for notes & ratings.
struct CloudSyncDemo: View {
    var playing: Bool
    var scheme: CoverScheme

    @State private var syncOn = false
    @State private var syncing = false

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.effects) {
                syncOn = false
                syncing = false
            }
            try await DemoTiming.delay(600)
            try await finger.moveTo("cloud-sync-switch", fx: 0.9)
            try await DemoTiming.delay(250)
            try await finger.tap {
                withAnimation(Yoin.effects) {
                    syncOn = true
                    syncing = true
                }
            }
            try await finger.lift()
            try await DemoTiming.delay(700)
            withAnimation(Yoin.effects) { syncing = false }
            try await DemoTiming.delay(2_000)
        }) {
            SettingsPage(title: "Cloud sync", demoScheme: scheme) { app, row in
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 8) {
                        Toggle(isOn: Binding(get: { syncOn }, set: { next in
                            withAnimation(Yoin.effects) {
                                syncOn = next
                                syncing = false
                            }
                        })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Cloud sync")
                                    .yoinRole(Tokens.type.bodyLarge)
                                    .foregroundStyle(app.onSurface)
                                Text("Sync notes, ratings and translations")
                                    .yoinRole(Tokens.type.bodySmall)
                                    .foregroundStyle(app.onSurfaceVariant)
                            }
                        }
                        .tint(app.primary)
                        .demoTarget("cloud-sync-switch")

                        HStack(spacing: 6) {
                            YoinIcon("cloud", size: 15)
                                .foregroundStyle(syncOn ? app.primary : app.onSurfaceVariant)
                            Text(!syncOn ? "Off" : syncing ? "Syncing…" : "Synced with Google Drive")
                                .yoinRole(Tokens.type.labelMedium)
                                .foregroundStyle(syncOn ? app.primary : app.onSurfaceVariant)
                        }
                        .contentTransition(.opacity)
                    }
                    .padding(16)
                    .background(row, in: RoundedRectangle(cornerRadius: 20))

                    VStack(alignment: .leading, spacing: 10) {
                        syncRow(icon: "edit_note", title: "Notes & lyrics", active: syncOn, app: app)
                        syncRow(icon: "star_filled", title: "Ratings", active: syncOn, app: app)
                        syncRow(icon: "translate", title: "Translations", active: syncOn, app: app)
                    }
                    .padding(16)
                    .background(row, in: RoundedRectangle(cornerRadius: 20))
                }
            }
            .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
            .scrollDisabled(true)
        }
    }

    private func syncRow(icon: String, title: String, active: Bool, app: CoverScheme) -> some View {
        HStack(spacing: 12) {
            YoinIcon(icon, size: 18)
                .foregroundStyle(active ? app.primary : app.onSurfaceVariant)
            Text(title)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(app.onSurface)
            Spacer()
            if active && !syncing {
                YoinIcon("check", size: 16)
                    .foregroundStyle(app.primary)
                    .transition(.scale.combined(with: .opacity))
            }
        }
    }
}
