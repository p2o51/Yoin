import SwiftUI
import UIKit
import YoinShared

/*
 * Share as image (Android PR #335, shared rules PR #349): one sheet, three tabs — the lyrics, the song's About
 * (AI / objective text, tertiary) and one of the listener's notes (their own words, primary) — each with its own
 * layouts, Card / Story and four tones. The rules live in :shared (`YoinShareCards`, golden vectors in
 * shared/src/commonTest/resources/golden/share-cards.json); this file adapts them to Now Playing's types: the
 * frozen content, the colours on the cover's scheme, the per-device layout memory, the strings and dates.
 * ShareCardViews.swift draws the ten layouts, ShareSheetView.swift the sheet, ShareExport.swift the PNG.
 */

/// Everything one open sheet shares, frozen when it opens (the song can change under the sheet), with the
/// covers it draws loaded as images up front: `ImageRenderer` draws in one pass and doesn't wait for a load.
struct ShareRender {
    var content: ShareCardContent
    /// The AI service's name for About's sign ("由 Gemini 整理").
    var aiName: String
    /// The song's cover; nil until loaded (or none): a quiet tile stands in.
    var cover: UIImage? = nil
    /// The note's mention covers, by mention link.
    var mentionCovers: [String: UIImage] = [:]
    /// The note's date language (the app's own).
    var locale: Locale = ShareStrings.appLocale

    var song: ShareSong { content.song }
    var hasCover: Bool { cover != nil || content.song.coverArtUrl != nil }
    var tabs: [ShareTab] { YoinShareCards.shared.tabs(content: content) }

    func layouts(_ tab: ShareTab) -> [ShareLayout] { YoinShareCards.shared.layouts(content: content, tab: tab) }
}

/// A share asked for from Now Playing: the tab it opens on and what it focuses (Android ShareRequest).
struct ShareRequest: Equatable {
    var tab: ShareTab
    /// The long-pressed About row (its kind and key).
    var aboutFocus: (kind: String, key: String)? = nil
    var noteId: String? = nil

    static func == (a: ShareRequest, b: ShareRequest) -> Bool {
        a.tab == b.tab && a.noteId == b.noteId && a.aboutFocus?.kind == b.aboutFocus?.kind
            && a.aboutFocus?.key == b.aboutFocus?.key
    }
}

/// The one open request from About's long-press sheet or a note's edit card (Android ShareSheetHost): Now
/// Playing takes it, reads what it knows about the song and opens the sheet.
@MainActor
@Observable
final class ShareSheetHost {
    static let shared = ShareSheetHost()

    private(set) var request: ShareRequest?
    private(set) var serial = 0

    /// Asks Now Playing for the sheet; [after] lets a sheet that asked go down first.
    func open(_ request: ShareRequest, after delay: Duration = .zero) {
        Task { @MainActor in
            if delay > .zero { try? await Task.sleep(for: delay) }
            self.request = request
            self.serial += 1
        }
    }

    func take() -> ShareRequest? {
        defer { request = nil }
        return request
    }
}

/// One open sheet's state: the frozen render, the tab it opened on and what to run when the lyrics' image
/// was kept (Home's 「歌词一句」).
struct ShareSheetItem: Identifiable {
    let id = UUID()
    var render: ShareRender
    var initialTab: ShareTab
    var onKept: (@MainActor () -> Void)? = nil
}

// MARK: - Colours

/// One tone's colours on the playing scheme: the card, its text, its secondary text and the Story's backdrop.
struct ShareCardColors: Equatable {
    var card: Color
    var content: Color
    var secondary: Color
    var backdrop: Color
}

extension ShareTone {
    /// [self]'s colours for [role] on [scheme] (`YoinShareCards.toneColors`): scheme roles, Paper's white card,
    /// and the role re-toned through the shared HCT (Paper's ink T40 and backdrop T90).
    func colors(_ scheme: CoverScheme, role: ShareRole) -> ShareCardColors {
        let pairs = YoinShareCards.shared.toneColors(tone: self, role: role)
        let content = Self.resolve(pairs.content, scheme)
        return ShareCardColors(card: Self.resolve(pairs.card, scheme), content: content,
                               secondary: content.opacity(Double(YoinShareCards.shared.secondaryAlpha)),
                               backdrop: Self.resolve(pairs.backdrop, scheme))
    }

    static func resolve(_ ink: ShareInk, _ s: CoverScheme) -> Color {
        let base: Color = switch ink.source {
        case .primary: s.primary
        case .onprimary: s.onPrimary
        case .primarycontainer: s.primaryContainer
        case .onprimarycontainer: s.onPrimaryContainer
        case .tertiary: s.tertiary
        case .ontertiary: s.onTertiary
        case .tertiarycontainer: s.tertiaryContainer
        case .ontertiarycontainer: s.onTertiaryContainer
        default: .white
        }
        guard let tone = ink.tone?.int32Value else { return base }
        let argb = Int32(truncatingIfNeeded: CoverScheme.argb(of: base))
        return CoverScheme.color(Int(UInt32(bitPattern: YoinShareCards.shared.retone(argb: argb, tone: tone))))
    }

    /// The swatch's accessibility name (Android np_cd_tone_*).
    var label: String {
        switch self {
        case .soft: String(localized: "Soft colours")
        case .deep: String(localized: "Deep colours")
        case .vivid: String(localized: "Vivid colours")
        case .mist: String(localized: "Mist colours")
        default: String(localized: "Paper colours")
        }
    }
}

// MARK: - Layout memory

/// The layout each tab was last shared in, per device, outside sync (Android `yoin_share_layouts`):
/// `layout.storeValue` under `tab.storeKey`.
struct ShareLayoutStore {
    var defaults: UserDefaults = .standard

    static let prefix = "yoin_share_layouts."

    func layout(for tab: ShareTab) -> ShareLayout? {
        YoinShareCards.shared.layoutForStoreValue(tab: tab, value: defaults.string(forKey: Self.prefix + tab.storeKey))
    }

    func set(_ layout: ShareLayout, for tab: ShareTab) {
        guard layout.tab == tab else { return }
        defaults.set(layout.storeValue, forKey: Self.prefix + tab.storeKey)
    }
}

// MARK: - Strings

/// The sheet's and the images' words (Android np_share_*), from Localizable.
enum ShareStrings {
    /// The app's own language (what Localizable resolved to): the note's date sits among its words.
    static var appLocale: Locale { Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en") }

    static func tab(_ tab: ShareTab, bundle: Bundle = .main) -> String {
        switch tab {
        case .lyrics: bundle.localizedString(forKey: "Lyrics", value: nil, table: nil)
        case .about: bundle.localizedString(forKey: "About", value: nil, table: nil)
        default: bundle.localizedString(forKey: "Note", value: nil, table: nil)
        }
    }

    static func layout(_ layout: ShareLayout, bundle: Bundle = .main) -> String {
        let key: String = switch layout {
        case .lyricsclassic: "Classic"
        case .lyricsposter: "Poster"
        case .lyricsplayer: "Player"
        case .lyricsvertical: "Vertical"
        case .aboutreceipt: "Receipt"
        case .aboutquote: "Quote"
        case .aboutliner: "Liner notes"
        case .notebig: "Big type"
        case .noteclipping: "Clipping"
        default: "Timeline"
        }
        return bundle.localizedString(forKey: key, value: nil, table: nil)
    }

    /// About's labels in the app's language (the golden `labels` block).
    static func aboutLabels(bundle: Bundle = .main) -> ShareAboutLabels {
        func s(_ key: String) -> String { bundle.localizedString(forKey: key, value: nil, table: nil) }
        return ShareAboutLabels(lyricist: s("Lyricist"), composer: s("Composer"), arranger: s("Arranger"),
                                producer: s("Producer"), creationTime: s("Creation Time"),
                                creationLocation: s("Creation Location"), year: s("Year"), review: s("About"))
    }

    /// "By Gemini" / "由 Gemini 整理" / "由千问整理" (Android np_share_ai_sign): the name filled in, the space
    /// before or after a Chinese name dropped — album About's rule (`YoinAlbumAbout.aiCopy`), which the shared
    /// `YoinShareCards.aiSign` applies (PR #350).
    static func aiSign(_ name: String, bundle: Bundle = .main) -> String {
        aiSign(template: bundle.localizedString(forKey: "By %@", value: nil, table: nil), name: name)
    }

    /// [template] in Localizable's form ("由 %@ 整理") with [name] filled in by the shared rule.
    static func aiSign(template: String, name: String) -> String {
        YoinAlbumAbout.shared.aiCopy(template: template.replacingOccurrences(of: "%@", with: "%1$s"), name: name)
    }

    /// "Written July 18, 2026" / "写于 2026年7月18日" (Android np_share_note_written).
    static func written(_ date: String, bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: "Written %@", value: nil, table: nil), date)
    }

    /// "Gist: …" / "歌词大意：…" (Android np_share_lyrics_gist): the translations joined by the shared separator.
    static func gist(_ translations: [String], bundle: Bundle = .main) -> String {
        String(format: bundle.localizedString(forKey: "Gist: %@", value: nil, table: nil),
               translations.joined(separator: YoinShareCards.shared.verticalGistSeparator))
    }

    /// The note's date on an image: the long date ("2026年7月18日", "July 18, 2026"); a relative one would age.
    static func noteDate(_ epochMs: Int64, locale: Locale = appLocale, timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = timeZone
        f.dateStyle = .long
        f.timeStyle = .none
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
    }

    static let brand = "Yoin"
}

// MARK: - Content (Now Playing → shared)

/// Builds the sheet's content from what Now Playing knows (Android ShareSources.content).
@MainActor
enum ShareContentBuilder {
    /// One lyric line of the loaded lyrics as shared.
    static func line(text: String, startMs: Int64?, translation: String?) -> ShareLyricLine {
        ShareLyricLine(startMs: startMs.map { KotlinLong(value: $0) }, text: text, translation: translation)
    }

    /// The select-mode pick: the picked indices' lines in order, with their passages.
    static func pick(lines: [ShareLyricLine], selected: [Int], showTranslation: Bool) -> ShareLyrics? {
        let picked = Array(Set(selected.filter { $0 >= 0 && $0 < lines.count })).sorted()
        guard !picked.isEmpty else { return nil }
        let starts = YoinShareCards.shared.passageStarts(selected: selected.map { KotlinInt(value: Int32($0)) },
                                                          lineCount: Int32(lines.count))
        return ShareLyrics(lines: picked.map { lines[$0] }, passageStarts: starts, showTranslation: showTranslation)
    }

    /// About from the stored rows, the lyrics' credits, the server's composer and year, and the long-pressed row.
    static func about(entries: [SongAboutStore.Entry], credits: SongCredits?, spine: SpineSourceFacts?,
                      focus: (kind: String, key: String)?, labels: ShareAboutLabels = ShareStrings.aboutLabels())
        -> ShareAbout? {
        YoinShareCards.shared.aboutPick(
            entries: entries.map {
                ShareAboutEntry(kind: $0.kind, entryKey: $0.entryKey, promptText: $0.promptText,
                                titleText: $0.titleText, answerText: $0.answerText)
            },
            sources: ShareAboutSources(lyricist: credits?.lyricist, composer: credits?.composer,
                                       arranger: credits?.arranger, producer: credits?.producer,
                                       serverComposer: spine?.composer,
                                       year: spine?.year.map { KotlinInt(value: Int32($0)) }),
            labels: labels, focusKind: focus?.kind, focusKey: focus?.key)
    }

    static func note(_ n: SongNote) -> ShareNote {
        ShareNote(noteId: n.id, content: n.content, positionMs: n.positionMs.map { KotlinLong(value: $0) },
                  createdAt: n.createdAt)
    }

    /// The song's cover as an image: the server's, else the album's, else (no server: the mock library) the
    /// album's drawn mesh.
    static func cover(url: URL?, album: Album?) async -> UIImage? {
        for ref in [url.map(CoverRef.init(url:)), album?.palette.cover].compactMap({ $0 }) {
            await CoverArtwork.shared.load(ref)
            if let image = CoverArtwork.shared.image(ref) { return image }
        }
        guard let album, album.palette.cover == nil else { return nil }
        let r = ImageRenderer(content: CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
            .frame(width: 160, height: 160))
        r.scale = 2
        return r.uiImage
    }

    /// The note's mention covers, by link (a pill without one shows its kind's symbol).
    static func mentionCovers(_ note: ShareNote?) async -> [String: UIImage] {
        guard let note else { return [:] }
        var out: [String: UIImage] = [:]
        for line in YoinShareCards.shared.noteLines(note: note) {
            for run in line.runs {
                guard let m = run.mention, out[m.link] == nil else { continue }
                if let ref = NoteMentionArtwork.shared.cover(m) {
                    await CoverArtwork.shared.load(ref)
                    if let image = CoverArtwork.shared.image(ref) { out[m.link] = image; continue }
                }
                if let mock = NoteMentionArtwork.shared.mockImage(m) { out[m.link] = mock }
            }
        }
        return out
    }
}
