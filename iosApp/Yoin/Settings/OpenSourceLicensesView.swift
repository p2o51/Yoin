import SwiftUI

/// A license the app ships the full text of (Resources/Licenses, Android res/raw).
enum OssLicense: String, CaseIterable {
    case apache2 = "license_apache_2_0"
    /// The text carries the font's copyright line, as the OFL asks.
    case ofl11 = "license_ofl_1_1"
    /// MPMessagePack's MIT license, as the Spotify iOS SDK ships it (its own Licenses folder).
    case mitMPMessagePack = "license_mit_mpmessagepack"

    var displayName: String {
        switch self {
        case .apache2: String(localized: "Apache License 2.0")
        case .ofl11: String(localized: "SIL Open Font License 1.1")
        case .mitMPMessagePack: String(localized: "MIT License")
        }
    }

    var text: String {
        guard let url = Bundle.main.url(forResource: rawValue, withExtension: "txt"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return s
    }
}

/// One acknowledged component (Android OssComponent).
struct OssComponent: Identifiable, Hashable {
    let name: String
    let copyright: String
    let license: OssLicense
    var id: String { name }
}

/// What the iOS app links: the font, the shared Kotlin module's libraries and Google Sign-In with
/// its dependencies. Keep in step with project.yml and shared/build.gradle.kts. MusicKit is Apple's,
/// under Apple's terms, and not listed; the Spotify iOS SDK is Spotify's, under the Spotify Developer
/// Terms, and not listed either, but the open source library inside it is; Yoin's symbols are its
/// own drawings.
let openSourceComponents: [OssComponent] = [
    OssComponent(name: "Google Sans Flex", copyright: "Copyright 2022 The Google Sans Flex Project Authors", license: .ofl11),
    OssComponent(name: "Material Color Utilities", copyright: "Copyright 2022 Google LLC", license: .apache2),
    OssComponent(name: "Kotlin, kotlinx.coroutines and kotlinx.serialization",
                 copyright: "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors", license: .apache2),
    OssComponent(name: "Ktor", copyright: "Copyright JetBrains s.r.o. and contributors", license: .apache2),
    OssComponent(name: "Google Sign-In for iOS", copyright: "Copyright Google LLC", license: .apache2),
    OssComponent(name: "AppAuth for iOS", copyright: "Copyright The AppAuth Authors", license: .apache2),
    OssComponent(name: "GTMAppAuth, GTM Session Fetcher, GoogleUtilities, App Check and Promises",
                 copyright: "Copyright Google LLC", license: .apache2),
    OssComponent(name: "MPMessagePack (in the Spotify iOS SDK)", copyright: "Copyright (c) 2014 Gabriel Handford",
                 license: .mitMPMessagePack),
]

/// Settings › About › Open source licenses (Android OpenSourceLicensesPage): the fonts and libraries
/// Yoin ships, each with its copyright and license; a tap shows the license in full.
struct OpenSourceLicensesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var open: OssComponent?

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Open source licenses")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                Text("Yoin is built with these fonts and open source libraries. Thanks to everyone who made them. Tap one to read its license.")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
                    .padding(.horizontal, 8)
                VStack(spacing: 2) {
                    ForEach(Array(openSourceComponents.enumerated()), id: \.element.id) { i, c in
                        row(c).background(app.surfaceBright, in: CloudSyncView.segment(i, openSourceComponents.count))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
        }
        .safeAreaBar(edge: .top) { topBar }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $open) { c in licenseSheet(c) }
    }

    private func row(_ c: OssComponent) -> some View {
        Button { open = c } label: {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.name)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(c.copyright)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                    Text(c.license.displayName)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                YoinIcon("chevron_right", size: 20).foregroundStyle(app.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private func licenseSheet(_ c: OssComponent) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text(c.name)
                    .yoinRole(Tokens.type.headlineSmall)
                    .foregroundStyle(app.onSurface)
                Text(c.copyright)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
                    .padding(.bottom, 12)
                Text(c.license.text)
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(app.onSurfaceVariant)
                    .textSelection(.enabled)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .presentationDetents([.large])
        .presentationBackground(app.surfaceContainerLow)
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            .settingsPaneBack()
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }
}

#Preview {
    NavigationStack { OpenSourceLicensesView() }
        .environment(AppModel.shared)
}
