package com.gpo.yoin.data.model

/**
 * The artist pages the album header's artist name opens, in the service's order: one for a lone artist, one per
 * credited artist with a page ("JAY-Z & Kanye West", OpenSubsonic's `artists`). Empty when no page is known.
 *
 * - [Album.artistCredits] with ids, and [Album.artistId] (first) when the credits leave it out;
 * - else the page the album's own tracks give its artist: the id the tracks credited by the album artist's name
 *   agree on, or the one id every track shares. Apple Music can answer an album without its `artists`
 *   relationship (a library album the catalogue doesn't match, a storefront that left it out), and a Subsonic
 *   server can leave `artistId` off an album whose songs carry it.
 *
 * A single link takes the header's own name ([Album.artist]); several keep their own names for the chooser.
 */
fun albumArtistLinks(album: Album): List<ArtistCredit> {
    val credited = album.artistCredits
        .filter { it.id != null && it.name.isNotBlank() }
        .distinctBy { it.id }
    val links = buildList {
        val own = album.artistId
        val ownName = album.artist?.let(::sameArtistKey)
        // Left out by the credits, and not under another id by the same name (a server's album artist).
        if (own != null && credited.none { it.id == own || sameArtistKey(it.name) == ownName }) {
            add(ArtistCredit(album.artist?.takeIf(String::isNotBlank) ?: credited.firstOrNull()?.name.orEmpty(), own))
        }
        addAll(credited)
    }
    if (links.size > 1) return links
    val single = links.firstOrNull()?.id ?: tracksArtistId(album) ?: return emptyList()
    return listOf(ArtistCredit(album.artist?.takeIf(String::isNotBlank) ?: links.firstOrNull()?.name.orEmpty(), single))
}

private fun tracksArtistId(album: Album): MediaId? {
    val tracks = album.tracks
    if (tracks.isEmpty()) return null
    val name = album.artist?.let(::sameArtistKey)?.takeIf(String::isNotEmpty)
    if (name != null) {
        val byName = tracks.flatMap { track -> track.artistCredits }
            .filter { credit -> credit.id != null && sameArtistKey(credit.name) == name }
            .groupingBy { it.id!! }
            .eachCount()
        byName.maxByOrNull { it.value }?.let { return it.key }
    }
    val ids = tracks.map { it.artistId }
    return ids.first()?.takeIf { first -> ids.all { it == first } }
}

private fun sameArtistKey(name: String): String = name.trim().lowercase()
