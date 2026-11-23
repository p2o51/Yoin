package com.gpo.yoin.shared.notes

import com.gpo.yoin.shared.transfer.TrackMatcher

/**
 * Whether a mention names an item, when its id can't say (owner 2026-11-22): a Spotify or Apple Music
 * mention read on a Subsonic account, or an id from another account. By id first; then by name: the
 * title, the artist when both sides know one, and for a track the album when both sides know one.
 * Names compare the way the playlist transfer compares them ([TrackMatcher]: case, width, accents,
 * Traditional/Simplified, "feat." credits and edition tags fall away; "Live" / "Remix" tags must agree).
 */
object MentionMatcher {
    fun matchesId(mention: NoteMention, provider: String, id: String): Boolean =
        mention.provider == provider && mention.id == id

    fun titlesMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val left = TrackMatcher.normalizeTitle(a)
        return left.isNotEmpty() && left == TrackMatcher.normalizeTitle(b) &&
            TrackMatcher.versionTags(a) == TrackMatcher.versionTags(b)
    }

    /** True when either side doesn't know the artist; otherwise they must share one ("A & B" vs "A"). */
    fun artistsMatch(a: String?, b: String?): Boolean =
        a.isNullOrBlank() || b.isNullOrBlank() || TrackMatcher.artistSimilarity(a, b) >= 1.0

    fun namesMatchTrack(mention: NoteMention, title: String?, artist: String?, album: String?): Boolean =
        mention.kind == MentionKind.Track &&
            titlesMatch(mention.title, title) &&
            artistsMatch(mention.artist, artist) &&
            (mention.album.isNullOrBlank() || album.isNullOrBlank() || titlesMatch(mention.album, album))

    fun namesMatchAlbum(mention: NoteMention, title: String?, artist: String?): Boolean =
        mention.kind == MentionKind.Album && titlesMatch(mention.title, title) && artistsMatch(mention.artist, artist)

    fun namesMatchArtist(mention: NoteMention, name: String?): Boolean {
        if (mention.kind != MentionKind.Artist || name.isNullOrBlank()) return false
        val left = TrackMatcher.normalizeArtist(mention.title)
        return left.isNotEmpty() && left == TrackMatcher.normalizeArtist(name)
    }

    /**
     * A track mention's album, by name: the mention says which album (`al`) and it is this one, by an
     * artist this album shares.
     */
    fun trackMentionOnAlbum(mention: NoteMention, albumTitle: String?, albumArtist: String?): Boolean =
        mention.kind == MentionKind.Track &&
            titlesMatch(mention.album, albumTitle) &&
            artistsMatch(mention.artist, albumArtist)

    /**
     * The start of a title for a SQL `LIKE prefix%` pre-filter (credits and edition tags dropped), so a
     * mention of "Song (feat. X)" still finds a stored "Song"; null when nothing is left.
     */
    fun searchPrefix(title: String): String? = TrackMatcher.stripDecorations(title).trim().takeIf(String::isNotEmpty)
}
