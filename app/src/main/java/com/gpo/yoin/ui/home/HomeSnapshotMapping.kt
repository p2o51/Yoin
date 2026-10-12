package com.gpo.yoin.ui.home

import com.gpo.yoin.R
import com.gpo.yoin.data.home.HomeActivityDto
import com.gpo.yoin.data.home.HomeCardDto
import com.gpo.yoin.data.home.HomeCardTargetDto
import com.gpo.yoin.data.home.HomeFeedDto
import com.gpo.yoin.data.home.HomeMemoryPillDto
import com.gpo.yoin.data.home.HomeRediscoverDto
import com.gpo.yoin.data.home.credentialFreeCoverKey
import com.gpo.yoin.data.home.snapshotAlbum
import com.gpo.yoin.data.home.snapshotPlaylist
import com.gpo.yoin.data.home.snapshotTrack
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind

/*
 * Home's feed ⇄ its snapshot (P2 PR3). Out: every field the feed shows, each
 * cover as its storage key ([HomeWidgetCard.coverKey] and its twins) — the
 * resolved URL is never written. In: the same feed, each key resolved by the
 * caller ([coverUrl]: a URL key as itself, a source-relative one once the
 * account's source is up; until then the card shows its type icon and the
 * cover reveals when [withResolvedCovers] fills it in). An entry that doesn't
 * read back (an unknown kind, a malformed id) drops alone.
 */

/** This feed as its snapshot keeps it. */
internal fun HomeUiState.Content.toSnapshotFeed(): HomeFeedDto = HomeFeedDto(
    activities = activities.map(HomeActivityDto::from),
    activitiesFromRemote = activitiesFromRemote,
    heroFootnote = activityHeroFootnote,
    heroYear = activityHeroYear,
    heroSongCount = activityHeroSongCount,
    heroMinutes = activityHeroMinutes,
    widgetGrid = widgetGrid.map { card -> card.toSnapshotCard() },
    recentlyAddedTracks = recentlyAddedTracks.map(::snapshotTrack),
    recentlyAddedAlbums = recentlyAddedAlbums.map(::snapshotAlbum),
    memoryPill = memoryPill?.toSnapshotPill(),
    rediscover = rediscover.map { item -> item.toSnapshotItem() },
    recentlyPlayed = recentlyPlayed.map(::snapshotAlbum),
    playlists = playlists.map(::snapshotPlaylist)
)

/** The feed [this] snapshot holds, its covers resolved by [coverUrl] (null: the type icon for now). */
internal fun HomeFeedDto.toHomeContent(coverUrl: (key: String) -> String?): HomeUiState.Content {
    val resolve = { key: String? -> credentialFreeCoverKey(key)?.let(coverUrl) }
    return HomeUiState.Content(
        activities = activities.mapNotNull { activity -> readOrNull { activity.toDomain() } },
        activitiesFromRemote = activitiesFromRemote,
        activityHeroFootnote = heroFootnote,
        activityHeroYear = heroYear,
        activityHeroSongCount = heroSongCount,
        activityHeroMinutes = heroMinutes,
        widgetGrid = widgetGrid.mapNotNull { card -> readOrNull { card.toCard(resolve(card.coverKey)) } },
        recentlyAddedTracks = recentlyAddedTracks.mapNotNull { track -> readOrNull { track.toDomain() } },
        recentlyAddedAlbums = recentlyAddedAlbums.mapNotNull { album -> readOrNull { album.toDomain() } },
        memoryPill = memoryPill?.let { pill -> readOrNull { pill.toPill(resolve(pill.latest?.coverKey)) } },
        rediscover = rediscover.mapNotNull { item -> readOrNull { item.toItem(resolve(item.coverKey)) } },
        recentlyPlayed = recentlyPlayed.mapNotNull { album -> readOrNull { album.toDomain() } },
        playlists = playlists.mapNotNull { playlist -> readOrNull { playlist.toDomain() } }
    )
}

/**
 * [this] feed with each cover still unresolved — a URL-less card that has a
 * key — resolved by [coverUrl] now (the account's source came up after a
 * snapshot painted). The same instance when none resolves.
 */
internal fun HomeUiState.Content.withResolvedCovers(coverUrl: (key: String) -> String?): HomeUiState.Content {
    fun resolved(url: String?, key: String?): String? = url ?: key?.let(coverUrl)
    val grid = widgetGrid.map { card ->
        val url = resolved(card.coverArtUrl, card.coverKey)
        if (url == card.coverArtUrl) card else card.copy(coverArtUrl = url)
    }
    val shelf = rediscover.map { item ->
        val url = resolved(item.coverArtUrl, item.coverKey)
        if (url == item.coverArtUrl) item else item.copy(coverArtUrl = url)
    }
    val pill = memoryPill?.let { current ->
        val latest = current.latest ?: return@let current
        val url = resolved(latest.coverArtUrl, latest.coverKey)
        if (url == latest.coverArtUrl) current else current.copy(latest = latest.copy(coverArtUrl = url))
    }
    val changed = grid.indices.any { grid[it] !== widgetGrid[it] } ||
        shelf.indices.any { shelf[it] !== rediscover[it] } ||
        pill !== memoryPill
    return if (changed) copy(widgetGrid = grid, rediscover = shelf, memoryPill = pill) else this
}

private fun HomeWidgetCard.toSnapshotCard(): HomeCardDto {
    val snapshotTarget = when (val tap = target) {
        is HomeWidgetTarget.AlbumDetail -> HomeCardTargetDto(kind = HomeCardTargetDto.ALBUM, id = tap.albumId)
        is HomeWidgetTarget.PlaylistDetail ->
            HomeCardTargetDto(kind = HomeCardTargetDto.PLAYLIST, id = tap.playlistId)
        is HomeWidgetTarget.PlaySong -> HomeCardTargetDto(kind = HomeCardTargetDto.SONG, song = snapshotTrack(tap.song))
        is HomeWidgetTarget.MemoryFocus ->
            HomeCardTargetDto(kind = HomeCardTargetDto.MEMORY, sessionId = tap.sessionId)
    }
    return HomeCardDto(
        stableId = stableId,
        entityType = entityType.name,
        title = title,
        subtitle = subtitle,
        subtitleText = (subtitleText as? UiText.Raw)?.value,
        coverKey = credentialFreeCoverKey(coverKey),
        ratingText = ratingText,
        ratingBasis = ratingBasis,
        ratingBasisTracks = ratingBasisText.basisTrackCounts(),
        ratingBasisDateMillis = ratingBasisDateMillis,
        ratingUnavailable = ratingUnavailable,
        comment = comment,
        commentIsHeadline = commentIsHeadline,
        commentSerif = commentSerif,
        expanded = expanded,
        target = snapshotTarget
    )
}

/** The "Based on rated/total tracks" basis as its two counts; null for any other basis copy. */
private fun UiText?.basisTrackCounts(): List<Int>? {
    val text = this as? UiText.Res ?: return null
    if (text.id != R.string.home_widget_basis_tracks) return null
    val counts = text.args.map { arg -> arg as? Int ?: return null }
    return counts.takeIf { it.size == 2 }
}

private fun HomeCardDto.toCard(coverArtUrl: String?): HomeWidgetCard {
    val tap = when (target.kind) {
        HomeCardTargetDto.ALBUM -> HomeWidgetTarget.AlbumDetail(requireNotNull(target.id))
        HomeCardTargetDto.PLAYLIST -> HomeWidgetTarget.PlaylistDetail(requireNotNull(target.id))
        HomeCardTargetDto.SONG -> HomeWidgetTarget.PlaySong(requireNotNull(target.song).toDomain())
        HomeCardTargetDto.MEMORY -> HomeWidgetTarget.MemoryFocus(requireNotNull(target.sessionId))
        else -> throw IllegalArgumentException("Unknown card target ${target.kind}")
    }
    return HomeWidgetCard(
        stableId = stableId,
        entityType = MemoryEntityType.valueOf(entityType),
        title = title,
        subtitle = subtitle,
        coverArtUrl = coverArtUrl,
        ratingText = ratingText,
        ratingBasis = ratingBasis,
        comment = comment,
        commentIsHeadline = commentIsHeadline,
        commentSerif = commentSerif,
        expanded = expanded,
        target = tap,
        subtitleText = subtitleText?.let(UiText::Raw),
        ratingBasisText = ratingBasisTracks?.takeIf { counts -> counts.size == 2 }
            ?.let { counts -> UiText.Res(R.string.home_widget_basis_tracks, counts) },
        ratingBasisDateMillis = ratingBasisDateMillis,
        ratingUnavailable = ratingUnavailable,
        coverKey = coverKey
    )
}

private fun HomeRediscoverItem.toSnapshotItem(): HomeRediscoverDto = HomeRediscoverDto(
    albumId = albumId.toString(),
    albumName = albumName,
    artistName = artistName,
    coverKey = credentialFreeCoverKey(coverKey),
    score = score,
    scoreText = scoreText,
    scoreKind = scoreKind.name,
    lastPlayedAt = lastPlayedAt,
    firstPlayedAt = firstPlayedAt,
    playCount = playCount,
    hasReview = hasReview,
    noteCount = noteCount,
    ratedTrackCount = ratedTrackCount,
    song = song?.let(::snapshotTrack),
    noteSnippet = noteSnippet
)

private fun HomeRediscoverDto.toItem(coverArtUrl: String?): HomeRediscoverItem = HomeRediscoverItem(
    albumId = MediaId.parse(albumId),
    albumName = albumName,
    artistName = artistName,
    coverArtUrl = coverArtUrl,
    score = score,
    scoreText = scoreText,
    scoreKind = MemoryScoreKind.valueOf(scoreKind),
    lastPlayedAt = lastPlayedAt,
    firstPlayedAt = firstPlayedAt,
    playCount = playCount,
    hasReview = hasReview,
    noteCount = noteCount,
    ratedTrackCount = ratedTrackCount,
    song = song?.toDomain(),
    noteSnippet = noteSnippet,
    coverKey = coverKey
)

private fun HomeMemoryPill.toSnapshotPill(): HomeMemoryPillDto = HomeMemoryPillDto(
    latest = latest?.let { shown ->
        HomeMemoryPillDto.Latest(
            sessionId = shown.sessionId,
            albumId = shown.albumId.toString(),
            albumName = shown.albumName,
            artistName = shown.artistName,
            coverKey = credentialFreeCoverKey(shown.coverKey),
            scoreKind = shown.scoreKind.name,
            scoreText = shown.scoreText,
            writtenAtMillis = shown.writtenAtMillis,
            memoryTitle = shown.memoryTitle
        )
    },
    noteCount = noteCount,
    scope = scope,
    newsKey = newsKey
)

private fun HomeMemoryPillDto.toPill(coverArtUrl: String?): HomeMemoryPill = HomeMemoryPill(
    latest = latest?.let { shown ->
        HomeMemoryPill.Latest(
            sessionId = shown.sessionId,
            albumId = MediaId.parse(shown.albumId),
            albumName = shown.albumName,
            artistName = shown.artistName,
            coverArtUrl = coverArtUrl,
            scoreKind = MemoryScoreKind.valueOf(shown.scoreKind),
            scoreText = shown.scoreText,
            writtenAtMillis = shown.writtenAtMillis,
            memoryTitle = shown.memoryTitle,
            coverKey = shown.coverKey
        )
    },
    noteCount = noteCount,
    scope = scope,
    newsKey = newsKey
)

/** [read], or null when this entry doesn't read back (it drops; the rest of the feed stays). */
private inline fun <T> readOrNull(read: () -> T): T? = try {
    read()
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}
