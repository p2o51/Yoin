import YoinShared

/// The Explicit "E" and the release kinds through :shared's `YoinReleaseMarks` (Android PR #343): each service's
/// rating and kind, the " - EP" / " - Single" suffix, Library's kind filter and the badge's size and fit, so iOS
/// answers as Android and HarmonyOS do (shared/src/commonTest/resources/golden/release-marks.json). The Swift
/// models keep their own enums ([ExplicitRating], [ReleaseKind]); these convert at the edge.
enum ReleaseMarks {
    private static var marks: YoinReleaseMarks { YoinReleaseMarks.shared }

    // MARK: Ratings

    static func rating(_ r: YoinShared.ContentRating?) -> ExplicitRating? {
        guard let r else { return nil }
        return r == YoinShared.ContentRating.explicit_ ? .explicit : .clean
    }

    /// Apple Music `attributes.contentRating`.
    static func appleMusic(_ value: String?) -> ExplicitRating? { rating(marks.contentRatingFromAppleMusic(value: value)) }

    /// Spotify's track `explicit`.
    static func explicitFlag(_ explicit: Bool?) -> ExplicitRating? {
        rating(marks.contentRatingFromExplicitFlag(explicit: explicit.map { KotlinBoolean(bool: $0) }))
    }

    /// A release from its loaded tracks: explicit as soon as one is, clean only when [complete] and every one is.
    static func fromTracks(_ ratings: [ExplicitRating?], complete: Bool) -> ExplicitRating? {
        rating(marks.albumContentRating(explicitTracks: Int32(ratings.filter { $0 == .explicit }.count),
                                        cleanTracks: Int32(ratings.filter { $0 == .clean }.count),
                                        loadedTracks: Int32(ratings.count), complete: complete))
    }

    /// Whether [loaded] tracks are the whole tracklist (Spotify `total_tracks`, else the page's `total`).
    static func tracklistComplete(loaded: Int, totalTracks: Int?, pageTotal: Int?) -> Bool {
        marks.tracklistComplete(loadedTracks: Int32(loaded), totalTracks: totalTracks.map { KotlinInt(int: Int32($0)) },
                                pageTotal: pageTotal.map { KotlinInt(int: Int32($0)) })
    }

    // MARK: Kinds

    static func kind(_ t: YoinShared.ReleaseType?) -> ReleaseKind? {
        switch t {
        case YoinShared.ReleaseType.album: .album
        case YoinShared.ReleaseType.ep: .ep
        case YoinShared.ReleaseType.single: .single
        case YoinShared.ReleaseType.compilation: .compilation
        default: nil
        }
    }

    static func shared(_ k: ReleaseKind?) -> YoinShared.ReleaseType? {
        switch k {
        case .album: YoinShared.ReleaseType.album
        case .ep: YoinShared.ReleaseType.ep
        case .single: YoinShared.ReleaseType.single
        case .compilation: YoinShared.ReleaseType.compilation
        case nil: nil
        }
    }

    static func spotifyKind(albumType: String?, totalTracks: Int?) -> ReleaseKind? {
        kind(marks.spotifyReleaseType(albumType: albumType, totalTracks: totalTracks.map { KotlinInt(int: Int32($0)) }))
    }

    /// The shared Subsonic models' lower-case `releaseType` ("ep") read back.
    static func subsonicKind(_ releaseType: String?) -> ReleaseKind? {
        kind(marks.subsonicReleaseType(releaseTypes: releaseType.map { [$0] } ?? []))
    }

    static func appleMusicKind(name: String?, isCompilation: Bool?, isSingle: Bool?) -> ReleaseKind? {
        kind(marks.appleMusicReleaseType(name: name, isCompilation: isCompilation.map { KotlinBoolean(bool: $0) },
                                         isSingle: isSingle.map { KotlinBoolean(bool: $0) }))
    }

    /// [name] without Apple Music's " - EP" / " - Single" for an EP / a single (shown as a kind instead).
    static func nameWithoutKindSuffix(_ name: String, _ kind: ReleaseKind?) -> String {
        marks.nameWithoutKindSuffix(name: name, type: shared(kind))
    }

    /// An EP or a single wears its kind as a tag (an album is the default).
    static func showsKindTag(_ kind: ReleaseKind?) -> Bool { marks.showsKindTag(type: shared(kind)) }

    /// The kind the album page's header names: the real one, Album when unknown.
    static func headerKind(_ kind: ReleaseKind?) -> ReleaseKind { self.kind(marks.headerKind(type: shared(kind))) ?? .album }

    // MARK: Library's kind filter

    enum Filter: String, CaseIterable, Hashable, Sendable {
        case all, albums, singlesAndEps, compilations

        var shared: ReleaseKindFilter {
            switch self {
            case .all: .all
            case .albums: .albums
            case .singlesAndEps: .singlesandeps
            case .compilations: .compilations
            }
        }

        init(_ f: ReleaseKindFilter) {
            switch f {
            case .albums: self = .albums
            case .singlesandeps: self = .singlesAndEps
            case .compilations: self = .compilations
            default: self = .all
            }
        }

        /// The chip's words, the artist page's (Android detail_artist_filter_*).
        var label: String {
            switch self {
            case .all: String(localized: "All")
            case .albums: String(localized: "Albums")
            case .singlesAndEps: String(localized: "Singles & EPs")
            case .compilations: String(localized: "Compilations")
            }
        }

        func accepts(_ kind: ReleaseKind?) -> Bool {
            YoinReleaseMarks.shared.filterAccepts(filter: shared, type: ReleaseMarks.shared(kind))
        }
    }

    /// The filters worth showing for a shelf of these kinds: none unless it holds two or more.
    static func offeredFilters(_ kinds: [ReleaseKind?]) -> [Filter] {
        marks.offeredFilters(types: kinds.compactMap(shared)).map(Filter.init)
    }

    // MARK: The E

    /// The square's side for a title of [fontSize] pt: its cap height (min 9); [hero] (Now Playing): its
    /// x-height, at most 18.
    static func badgeSide(fontSize: CGFloat, hero: Bool) -> CGFloat {
        CGFloat(marks.badgeSide(fontSize: Float(fontSize), hero: hero, minSide: marks.minSideDp,
                                heroMaxSide: marks.heroMaxSideDp))
    }

    /// The space between the title and the badge.
    static func badgeGap(fontSize: CGFloat, hero: Bool) -> CGFloat {
        CGFloat(marks.badgeGap(fontSize: Float(fontSize), hero: hero, minSide: marks.minSideDp,
                               heroMaxSide: marks.heroMaxSideDp, minGap: marks.gapDp))
    }

    /// The title keeps at least half the box, or all of itself if shorter; else the badge is left out.
    static func badgeFits(maxWidth: CGFloat, titleRoom: CGFloat, naturalTitle: CGFloat) -> Bool {
        marks.explicitBadgeFits(maxWidth: Int32(maxWidth.rounded(.down)), titleRoom: Int32(titleRoom.rounded(.down)),
                                naturalTitle: Int32(naturalTitle.rounded(.up)))
    }

    static var letterShare: CGFloat { CGFloat(marks.letterShare) }
    static var cornerShare: CGFloat { CGFloat(marks.cornerShare) }
    static var capHeightEm: CGFloat { CGFloat(marks.capHeightEm) }
}
