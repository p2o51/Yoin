import Observation
import SwiftUI
import UniformTypeIdentifiers

// The landing's "Using Spotoolfy?" step (Android ui/landing/LandingSpotoolfyStep.kt): before Spotify's
// connect step. The export is read and kept in memory here (nothing reaches an account yet; the recap
// shows first); it goes into the Spotify account the moment that account connects.

enum SpotoolfyChoice: Sendable, Equatable { case using, notUsing }

enum SpotoolfyFailure: Sendable, Equatable {
    case notSpotoolfy, corrupt, tooLarge, empty, alreadyImported, unreadable

    var message: String {
        switch self {
        case .notSpotoolfy: String(localized: "That isn't a Spotoolfy export.")
        case .corrupt: String(localized: "The file is damaged. Try exporting it again.")
        case .tooLarge: String(localized: "The file is over 32 MB.")
        case .empty: String(localized: "There are no notes or ratings in this file.")
        case .alreadyImported: String(localized: "This file is already in Yoin.")
        case .unreadable: String(localized: "Couldn't read the file.")
        }
    }
}

/// Where the step's file is.
enum SpotoolfyFile: Sendable, Equatable {
    case none
    case reading
    /// Read, recap ready, waiting for the Spotify account.
    case received(noteCount: Int, ratingCount: Int)
    case failed(SpotoolfyFailure)
    /// In [accountName]'s account.
    case written(noteCount: Int, ratingCount: Int, accountName: String)
    /// The account already held the user's own data, so nothing was written.
    case notWritten
}

struct SpotoolfyStepState: Sendable, Equatable {
    var choice: SpotoolfyChoice?
    var file: SpotoolfyFile = .none

    /// A file read but not in any account yet.
    var unwritten: Bool {
        if choice == .using, case .received = file { return true }
        return false
    }
}

/// The pill's main button on this step (Android `spotoolfyPrimary`; iOS has no "Open Spotoolfy": the
/// export comes from the Files picker).
func spotoolfyPrimary(_ state: SpotoolfyStepState) -> LandingPrimary {
    let next = String(localized: "Next")
    if state.choice == nil { return LandingPrimary(label: next, style: .unavailable) }
    if state.choice == .notUsing { return LandingPrimary(label: next, style: .filled) }
    switch state.file {
    case .reading: return LandingPrimary(label: next, style: .unavailable)
    case .received, .written, .notWritten: return LandingPrimary(label: next, style: .filled)
    case .none, .failed: return LandingPrimary(label: String(localized: "Choose the export file"), style: .filled)
    }
}

/// The step's state machine over `SpotoolfyImporter`.
@MainActor
@Observable
final class SpotoolfyStepModel {
    private(set) var state = SpotoolfyStepState()
    /// The recap of the file on hand (while it is received or written).
    private(set) var recap: SpotoolfyRecap?
    /// The recap sheet is open.
    var recapShown = false
    /// A file that couldn't be used: the mascot shakes.
    private(set) var refusals = 0

    @ObservationIgnored private let importer: SpotoolfyImporter
    @ObservationIgnored private var staged: SpotoolfyImporter.Staged?
    @ObservationIgnored private var receipt: SpotoolfyImporter.Receipt?
    @ObservationIgnored private var generation = 0

    init(importer: SpotoolfyImporter = SpotoolfyImporter()) { self.importer = importer }

    func choose(_ choice: SpotoolfyChoice) {
        state.choice = choice
        // "No" after a file was read: it must not reach the account, or comes back out of it.
        if choice == .notUsing { dropFile() }
    }

    /// "Use another file": the current file goes, from the account too if it was already written.
    func replace() {
        state.choice = .using
        dropFile()
    }

    private func dropFile() {
        generation += 1
        if let receipt { importer.discard(receipt) }
        receipt = nil
        staged = nil
        recap = nil
        recapShown = false
        state.file = .none
    }

    /// The Files picker's answer: read like a shared file, then show the recap.
    func pick(_ result: Result<URL, Error>) async {
        guard case .success(let url) = result else {
            // Cancelling the picker is not an error; the system reports it as one only for real failures.
            if case .failure(let error) = result, (error as? CocoaError)?.code != .userCancelled { fail(.unreadable) }
            return
        }
        generation += 1
        let mine = generation
        state.choice = .using
        state.file = .reading
        let data: Data? = await Task.detached { Self.read(url) }.value
        guard mine == generation else { return }
        guard let data else { return fail(.unreadable) }
        if data.count > SpotoolfyExportParser.maxBytes { return fail(.tooLarge) }
        let sha = SpotoolfyImporter.sha256(data)
        let analysed = await Task.detached { SpotoolfyImporter.analyze(data) }.value
        guard mine == generation else { return }
        switch analysed {
        case .staged(let s):
            // The very file already written in this landing: nothing changes.
            if let receipt, receipt.fileSha256 == sha, case .written = state.file { return }
            if importer.wasImported(sha256: sha) { return fail(.alreadyImported) }
            staged = s
            recap = s.recap
            state.file = .received(noteCount: s.recap.noteCount, ratingCount: s.recap.ratedTrackCount)
            recapShown = true
        case .alreadyImported: fail(.alreadyImported)
        case .notSpotoolfy: fail(.notSpotoolfy)
        case .corrupt: fail(.corrupt)
        case .tooLarge: fail(.tooLarge)
        case .empty: fail(.empty)
        }
    }

    /// Reads the picked file (security scoped). A file over the size limit comes back as a buffer one byte
    /// over it, so the caller's size check reports it without loading it all.
    nonisolated private static func read(_ url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > SpotoolfyExportParser.maxBytes {
            return Data(count: SpotoolfyExportParser.maxBytes + 1)
        }
        return try? Data(contentsOf: url)
    }

    func reopenRecap() { if recap != nil { recapShown = true } }

    /// Spotify connected as [profile]: the file goes into that account, if it holds nothing yet.
    func commit(to profile: Profile) {
        guard state.unwritten, let staged else { return }
        guard let userId = profile.spotifyUserId else {
            state.file = .failed(.unreadable)
            return
        }
        let scope = AccountScope.spotifyScope(userId: userId)
        switch importer.commit(staged, scope: scope, unknownTitle: String(localized: "Unknown track")) {
        case .committed(let notes, let ratings, let receipt):
            self.receipt = receipt
            state.file = .written(noteCount: notes, ratingCount: ratings, accountName: profile.title)
        case .notEligible: state.file = .notWritten
        case .alreadyInYoin: state.file = .failed(.alreadyImported)
        case .failed: state.file = .failed(.unreadable)
        }
    }

    /// The landing ends without an account for the file: it goes.
    func discardUnwritten() {
        guard state.unwritten else { return }
        dropFile()
    }

    private func fail(_ failure: SpotoolfyFailure) {
        state.file = .failed(failure)
        refusals += 1
    }

    /// The line the Spotify step shows once the file is in its account.
    var writtenLine: String? {
        guard case .written(let notes, let ratings, let name) = state.file else { return nil }
        return Self.writtenText(notes: notes, ratings: ratings, account: name)
    }

    static func writtenText(notes: Int, ratings: Int, account: String) -> String {
        if notes > 0 {
            return notes == 1 ? String(localized: "1 note is now in \(account)") : String(localized: "\(notes) notes are now in \(account)")
        }
        return ratings == 1 ? String(localized: "1 rating is now in \(account)") : String(localized: "\(ratings) ratings are now in \(account)")
    }

    static func receivedText(notes: Int, ratings: Int) -> String {
        if notes > 0 { return notes == 1 ? String(localized: "1 note received") : String(localized: "\(notes) notes received") }
        return ratings == 1 ? String(localized: "1 rating received") : String(localized: "\(ratings) ratings received")
    }
}

// MARK: Scene

struct LandingSpotoolfyScene: View {
    var model: SpotoolfyStepModel
    var scheme: CoverScheme

    var body: some View {
        let state = model.state
        LandingSceneColumn {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "Spotoolfy")
                    .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                Text("Your notes and ratings can come along.")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .padding(.horizontal, 6)
            .padding(.bottom, 16)
            VStack(spacing: 2) {
                option(title: String(localized: "Yes, bring my data"),
                       body: String(localized: "Notes and ratings go into your new Spotify account"),
                       selected: state.choice == .using, top: true) { model.choose(.using) }
                option(title: String(localized: "No"), body: String(localized: "Start fresh"),
                       selected: state.choice == .notUsing, top: false) { model.choose(.notUsing) }
            }
            if state.choice == .using, state.file != .none {
                status(state.file).padding(.top, 12)
            }
        }
        .animation(Yoin.spatial, value: state)
    }

    private func option(title: String, body: String, selected: Bool, top: Bool, action: @escaping () -> Void) -> some View {
        let big: CGFloat = 20, small: CGFloat = 4
        return Button(action: action) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                        .foregroundStyle(selected ? scheme.onPrimaryContainer : scheme.onSurface)
                    Text(body)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle((selected ? scheme.onPrimaryContainer : scheme.onSurface).opacity(0.78))
                }
                Spacer(minLength: 8)
                ZStack {
                    Circle().fill(selected ? scheme.primary : .clear)
                    Circle().strokeBorder(selected ? scheme.primary : scheme.outline, lineWidth: 2)
                    YoinIcon("check", size: 18).foregroundStyle(scheme.onPrimary).scaleEffect(selected ? 1 : 0.01)
                }
                .frame(width: 26, height: 26)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? scheme.primaryContainer : scheme.surfaceBright,
                        in: UnevenRoundedRectangle(topLeadingRadius: top ? big : small, bottomLeadingRadius: top ? small : big,
                                                   bottomTrailingRadius: top ? small : big, topTrailingRadius: top ? big : small))
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .animation(Yoin.effects, value: selected)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private func status(_ file: SpotoolfyFile) -> some View {
        switch file {
        case .none: EmptyView()
        case .reading:
            HStack(spacing: 12) {
                ProgressView()
                Text("Reading the file…")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(scheme.onSurface)
            }
            .padding(.leading, 8)
            .padding(.top, 4)
        case .received(let notes, let ratings):
            card(title: SpotoolfyStepModel.receivedText(notes: notes, ratings: ratings),
                 body: String(localized: "They go into your Yoin once Spotify is connected."))
        case .written(let notes, let ratings, let name):
            card(title: SpotoolfyStepModel.writtenText(notes: notes, ratings: ratings, account: name), body: nil)
        case .notWritten:
            Text("This account already has notes or ratings, so Spotoolfy's data stayed out.")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .padding(.horizontal, 8)
        case .failed(let failure):
            ErrorLine(text: failure.message, scheme: scheme)
        }
    }

    private func card(title: String, body: String?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                YoinIcon("check", size: 20).foregroundStyle(scheme.primary)
                Text(title)
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
            }
            if let body {
                Text(body)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.leading, 30)
                    .padding(.top, 2)
            }
            HStack(spacing: 4) {
                Button { model.reopenRecap() } label: {
                    Text("See the recap again")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.onSecondaryContainer)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .background(scheme.secondaryContainer, in: .capsule)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                Button { model.replace() } label: {
                    Text("Use another file")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 40)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
            .padding(.leading, 22)
            .padding(.top, 8)
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .padding(.top, 14)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.surfaceBright, in: .rect(cornerRadius: 22))
    }
}

// MARK: Recap

/// What the import found, in one page (Android's two-page SpotoolfyRecapActivity, reduced to the same
/// facts: counts, the span of time, the words written most, a lyric kept, or the notes themselves when
/// there are too few for words).
struct SpotoolfyRecapView: View {
    var recap: SpotoolfyRecap
    var scheme: CoverScheme
    var onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text("Spotoolfy recap")
                        .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                        .foregroundStyle(scheme.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button(action: onClose) {
                        Text("Close")
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(scheme.primary)
                            .padding(.horizontal, 8)
                            .frame(minHeight: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("In Spotoolfy you wrote")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.onSurfaceVariant)
                    Text(Self.headline(recap))
                        .font(YoinFont.rounded(40, semibold: true))
                        .foregroundStyle(scheme.primary)
                    Text(Self.summary(recap))
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(scheme.onSurface)
                    if let range = Self.range(recap) {
                        Text(range)
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(scheme.onSurfaceVariant)
                    }
                }
                if !recap.keywords.isEmpty {
                    section(String(localized: "Words you wrote most"))
                    FlowLayout(spacing: 8) {
                        ForEach(recap.keywords.prefix(18), id: \.text) { word in
                            Text(verbatim: "\(word.text) \(word.count)")
                                .yoinRole(Tokens.type.labelLarge)
                                .foregroundStyle(scheme.onSecondaryContainer)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(scheme.secondaryContainer, in: .capsule)
                        }
                    }
                } else if !recap.fallbackNotes.isEmpty {
                    section(String(localized: "What you wrote"))
                    ForEach(Array(recap.fallbackNotes.enumerated()), id: \.offset) { _, note in noteCard(note) }
                }
                if !recap.lyricSnippets.isEmpty {
                    section(String(localized: "What you kept"))
                    ForEach(Array(recap.lyricSnippets.enumerated()), id: \.offset) { _, lyric in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(verbatim: "「\(lyric.lyric)」").yoinRole(Tokens.type.titleMedium).foregroundStyle(scheme.onSurface)
                            if !lyric.note.isEmpty {
                                Text(verbatim: lyric.note).yoinRole(Tokens.type.bodyMedium).foregroundStyle(scheme.onSurfaceVariant)
                            }
                            if let by = Self.songBy(lyric.trackTitle, lyric.artist) {
                                Text(verbatim: by).yoinRole(Tokens.type.labelMedium).foregroundStyle(scheme.onSurfaceVariant)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(scheme.surfaceBright, in: .rect(cornerRadius: 20))
                    }
                }
            }
            .padding(20)
        }
        .background(scheme.surfaceContainer)
    }

    private func section(_ title: String) -> some View {
        Text(title).yoinRole(Tokens.type.labelLarge).foregroundStyle(scheme.primary).accessibilityAddTraits(.isHeader)
    }

    private func noteCard(_ note: RecapNote) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: note.text).yoinRole(Tokens.type.bodyLarge).foregroundStyle(scheme.onSurface)
            if let by = Self.songBy(note.trackTitle, note.artist) {
                Text(verbatim: by).yoinRole(Tokens.type.labelMedium).foregroundStyle(scheme.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(scheme.surfaceBright, in: .rect(cornerRadius: 20))
    }

    /// "12 notes", or the ratings when there are no notes.
    static func headline(_ r: SpotoolfyRecap) -> String {
        if r.noteCount > 0 { return r.noteCount == 1 ? String(localized: "1 note") : String(localized: "\(r.noteCount) notes") }
        return r.ratedTrackCount == 1 ? String(localized: "1 rating") : String(localized: "\(r.ratedTrackCount) ratings")
    }

    /// "for 32 songs by 10 artists, 4 of them rated".
    static func summary(_ r: SpotoolfyRecap) -> String {
        let songs = r.trackCount == 1 ? String(localized: "1 song") : String(localized: "\(r.trackCount) songs")
        let artists = r.artistCount == 1 ? String(localized: "1 artist") : String(localized: "\(r.artistCount) artists")
        let base = r.artistCount > 0 ? String(localized: "for \(songs) by \(artists)") : String(localized: "for \(songs)")
        if r.noteCount == 0 || r.ratedTrackCount == 0 { return base }
        if r.ratedTrackCount >= r.trackCount { return base + String(localized: ", every one of them rated") }
        return base + String(localized: ", \(r.ratedTrackCount) of them rated")
    }

    static func range(_ r: SpotoolfyRecap) -> String? {
        guard r.firstRecordAt > 0 else { return nil }
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        let a = f.string(from: Date(timeIntervalSince1970: Double(r.firstRecordAt) / 1000))
        let b = f.string(from: Date(timeIntervalSince1970: Double(r.lastRecordAt) / 1000))
        return a == b ? a : String(localized: "\(a) to \(b)")
    }

    static func songBy(_ title: String?, _ artist: String?) -> String? {
        guard let title, !title.isEmpty else { return nil }
        if let artist, !artist.isEmpty { return String(localized: "“\(title)” by \(artist)") }
        return "“\(title)”"
    }
}

/// Words flow left to right and wrap.
private struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(proposal.width ?? 320, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let laid = arrange(bounds.width, subviews)
        for (i, origin) in laid.origins.enumerated() {
            subviews[i].place(at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y), proposal: .unspecified)
        }
    }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (origins: [CGPoint], size: CGSize) {
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        var origins: [CGPoint] = []
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width { x = 0; y += rowH + spacing; rowH = 0 }
            origins.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowH = max(rowH, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (origins, CGSize(width: maxX, height: y + rowH))
    }
}
