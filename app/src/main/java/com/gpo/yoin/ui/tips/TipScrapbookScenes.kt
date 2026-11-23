package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.album.AlbumScrapbookAlbumNote
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.notes.BacklinkSource
import com.gpo.yoin.data.notes.BacklinkTarget
import com.gpo.yoin.data.notes.BacklinkTrack
import com.gpo.yoin.data.notes.BacklinkWriting
import com.gpo.yoin.data.notes.MentionAccount
import com.gpo.yoin.data.notes.MentionResolution
import com.gpo.yoin.data.notes.NoteBacklink
import com.gpo.yoin.data.notes.findBacklinks
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.ui.detail.AlbumDetailUiState
import com.gpo.yoin.ui.detail.AlbumScrapbookPage
import com.gpo.yoin.ui.detail.AlbumScrapbookUiState
import com.gpo.yoin.ui.detail.AlbumSong
import com.gpo.yoin.ui.detail.ScrapEdit
import com.gpo.yoin.ui.detail.StickyEditing
import com.gpo.yoin.ui.detail.buildAlbumScrapbook
import com.gpo.yoin.ui.detail.rememberScrapbookColors
import com.gpo.yoin.ui.notes.LocalNoteMentions
import com.gpo.yoin.ui.notes.NoteDestination
import com.gpo.yoin.ui.notes.NoteMentionController
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The album scrapbook's notes (notes revamp phase B): the real scrapbook page (AlbumScrapbookPage) over
 * made-up albums, driven from outside through its stickies and its scroll.
 */

/**
 * Album notes: a tap on the scrapbook's bare paper pins a new sticky and opens it as the note editor; the
 * words go in and it stays on the page beside the one already there. The demo's sticky never takes the
 * focus, so no keyboard comes up over the tip; a sticky the user saves in the phone stays for the visit.
 */
@Composable
internal fun AlbumStickiesDemo(playing: Boolean) {
    val album = DemoAlbums[0]
    val cover = demoCoverUrl(album)
    val content = remember(cover) { demoScrapbookAlbum(0, cover, review = DemoStickyReview) }
    val notes = remember { mutableStateListOf(AlbumScrapbookAlbumNote("tip-an-0", DemoStickyFirst, 1L)) }
    val book = remember(content, notes.toList()) {
        buildAlbumScrapbook(content, AlbumScrapbookData(albumNotes = notes.toList()), nowMillis = DemoNow)
    }
    val editing = remember { StickyEditing(autoFocus = false) }
    val scroll = rememberScrollState()
    DemoPhone(playing = playing, script = {
        editing.close()
        notes.retainAll { it.id == "tip-an-0" }
        scroll.scrollTo(0)
        delay(900)
        // Bare paper under the page's pieces.
        moveTo("scrapPaper", fx = 0.5f, fy = 0.88f)
        tap { editing.startNew() }
        delay(700)
        val editor = editing.editor ?: return@DemoPhone
        lift()
        DemoStickySecond.forEach { c ->
            editor.fieldState.edit {
                append(c)
                placeCursorAtEnd()
            }
            delay(55)
        }
        delay(900)
        notes += AlbumScrapbookAlbumNote("tip-an-1", editor.text.trim(), 2L)
        editing.close()
        delay(2_400)
    }) {
        AlbumScrapbookPage(
            state = AlbumScrapbookUiState.Ready(book),
            content = content,
            colors = rememberScrapbookColors(MaterialTheme.colorScheme),
            currentTrackId = null,
            pageOffset = { 0f },
            onSongClick = {},
            onNoteMomentClick = { _, _ -> },
            onEditReview = {},
            onEdit = { edit -> notes.applyStickyEdit(edit) },
            stickyEditing = editing,
            scrollState = scroll,
            backlinks = emptyList(),
            modifier = Modifier.fillMaxSize().demoTarget("scrapPaper"),
        )
    }
}

/** A sticky saved, rewritten or cleared in the demo phone, as the page's data would take it. */
private fun MutableList<AlbumScrapbookAlbumNote>.applyStickyEdit(edit: ScrapEdit) {
    when (edit) {
        is ScrapEdit.NewAlbumNote -> add(AlbumScrapbookAlbumNote("tip-an-$size", edit.text, size + 1L))
        is ScrapEdit.AlbumNoteText -> {
            val i = indexOfFirst { it.id == edit.noteId }
            if (i >= 0) if (edit.text.isBlank()) removeAt(i) else set(i, get(i).copy(content = edit.text))
        }
        else -> Unit
    }
}

/**
 * 被提及: the scrapbook scrolls to its last block, where the notes and reviews that mention the album wait
 * as clippings with the mention marked; a tap on one opens where it was written — here, another album's
 * scrapbook with the sticky that names this one.
 */
@Composable
internal fun MentionedInDemo(playing: Boolean) {
    val covers = demoCoverUrls()
    val here = remember(covers) {
        demoScrapbookAlbum(1, covers[1], review = DemoMentionedReview, noted = true)
    }
    val there = remember(covers) { demoScrapbookAlbum(0, covers[0], review = DemoStickyReview) }
    val backlinks = remember(here) { demoBacklinks(here) }
    val hereBook = remember(here) {
        buildAlbumScrapbook(here, demoNotedData(), nowMillis = DemoNow)
    }
    val thereBook = remember(there, backlinks) {
        val note = backlinks.first { it.source is BacklinkSource.AlbumNote }
        val data = AlbumScrapbookData(albumNotes = listOf(AlbumScrapbookAlbumNote("tip-an-m", note.text, 1L)))
        buildAlbumScrapbook(there, data, nowMillis = DemoNow)
    }
    var opened by remember { mutableStateOf<NoteDestination?>(null) }
    val scope = rememberCoroutineScope()
    val controller = remember(scope, covers) {
        NoteMentionController(
            resolver = { m -> MentionResolution.Resolved(m, MediaId(m.provider, m.id), MentionResolution.Via.Id) },
            account = { MentionAccount("tip-demo", MediaId.PROVIDER_SUBSONIC) },
            scope = scope,
            trackAlbum = { null },
            navigate = { _, destination -> opened = destination },
            resolveCover = { key -> key?.toIntOrNull()?.let(covers::getOrNull) },
        )
    }
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The last clipping's middle: the page's foot padding, then half a one-line clipping.
    val lastClippingUp = with(density) { (MentionedPageFoot + navBottom + MentionedClippingHalf).toPx() }
    val tapped = backlinks.last().source
    DemoPhone(playing = playing, script = {
        opened = null
        scroll.scrollTo(0)
        delay(800)
        moveTo("scrapPage", fx = 0.5f, fy = 0.78f)
        coroutineScope {
            launch { scroll.animateScrollTo(scroll.maxValue, tween(1_400)) }
            drag(fx = 0.5f, fy = 0.3f, ms = 1_400) {}
        }
        delay(700)
        moveTo("scrapPage", fx = 0.5f, fy = 1f, dy = -lastClippingUp)
        delay(300)
        tap { controller.openSource(context, tapped) }
        lift()
        delay(2_600)
    }) {
        CompositionLocalProvider(LocalNoteMentions provides controller) {
            AnimatedContent(
                targetState = opened != null,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                label = "mentionedIn",
                modifier = Modifier.fillMaxSize(),
            ) { away ->
                val colors = rememberScrapbookColors(MaterialTheme.colorScheme)
                if (away) {
                    AlbumScrapbookPage(
                        state = AlbumScrapbookUiState.Ready(thereBook),
                        content = there,
                        colors = colors,
                        currentTrackId = null,
                        pageOffset = { 0f },
                        onSongClick = {},
                        onNoteMomentClick = { _, _ -> },
                        onEditReview = {},
                        backlinks = emptyList(),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AlbumScrapbookPage(
                        state = AlbumScrapbookUiState.Ready(hereBook),
                        content = here,
                        colors = colors,
                        currentTrackId = null,
                        pageOffset = { 0f },
                        onSongClick = {},
                        onNoteMomentClick = { _, _ -> },
                        onEditReview = {},
                        scrollState = scroll,
                        backlinks = backlinks,
                        modifier = Modifier.fillMaxSize().demoTarget("scrapPage"),
                    )
                }
            }
        }
    }
}

/** Album [index] of [DemoAlbums] as the album page has it: its first [tracks] songs, and the listener's review. */
private fun demoScrapbookAlbum(
    index: Int,
    cover: String,
    review: String,
    noted: Boolean = false,
    tracks: Int = 4,
): AlbumDetailUiState.Content {
    val album = DemoAlbums[index]
    val songs = List(tracks) { i ->
        AlbumSong(
            id = "${MediaId.PROVIDER_SUBSONIC}:tip-al$index-t$i",
            title = DemoTrackTitles[(index * 3 + i) % DemoTrackTitles.size],
            artist = album.artist,
            trackNumber = i + 1,
            duration = 200 + i * 17,
            isStarred = false,
        )
    }
    return AlbumDetailUiState.Content(
        albumId = "${MediaId.PROVIDER_SUBSONIC}:tip-al$index",
        albumName = album.name,
        artistName = album.artist,
        artistId = null,
        coverArtId = null,
        coverArtUrl = cover,
        year = 2024,
        songCount = songs.size,
        totalDuration = songs.sumOf { it.duration ?: 0 },
        songs = songs,
        userReview = review,
        averageTrackRating = if (noted) 8.5f else null,
        ratedTrackCount = if (noted) 2 else 0,
        ratedSongIds = if (noted) setOf(songs[0].id, songs[1].id) else emptySet(),
    )
}

/** The mentioned album's own page: two scored songs, one with a note, so there is paper to scroll past. */
private fun demoNotedData(): AlbumScrapbookData {
    fun id(t: Int) = MediaId(MediaId.PROVIDER_SUBSONIC, "tip-al1-t$t")
    return AlbumScrapbookData(
        ratings = mapOf(id(0) to 8.2f, id(1) to 8.8f),
        notes = mapOf(id(1) to listOf(AlbumScrapbookNote("tip-n-1", DemoMentionedNote, 64_000, 1L))),
        playCounts = mapOf(id(0) to 6, id(1) to 9),
    )
}

/**
 * What mentions album 1 elsewhere, read the way the page reads it ([findBacklinks]): an album note on
 * album 0 that names the album, and a song note on album 4 that names one of its songs. Covers by index.
 */
private fun demoBacklinks(target: AlbumDetailUiState.Content): List<NoteBacklink> {
    val albumMention = NoteMention(MentionKind.Album, MediaId.PROVIDER_SUBSONIC, "tip-al1", target.albumName, target.artistName)
    val song = target.songs[1]
    val songMention = NoteMention(
        MentionKind.Track,
        MediaId.PROVIDER_SUBSONIC,
        song.id.substringAfter(':'),
        song.title,
        song.artist,
    )
    val there = DemoAlbums[0]
    val other = DemoAlbums[4]
    val writings = listOf(
        BacklinkWriting(
            source = BacklinkSource.SongNote(
                noteId = "tip-bl-song",
                trackId = MediaId.subsonic("tip-al4-t0"),
                trackTitle = DemoTrackTitles[7],
                trackArtist = other.artist,
                positionMs = 95_000,
                albumId = MediaId.subsonic("tip-al4"),
                coverKey = "4",
            ),
            text = DemoBacklinkSongNote + NoteMarkup.serializeMention(songMention),
            at = DemoNow - 3 * DayMs,
        ),
        BacklinkWriting(
            source = BacklinkSource.AlbumNote(
                noteId = "tip-bl-album",
                albumId = MediaId.subsonic("tip-al0"),
                albumName = there.name,
                albumArtist = there.artist,
                coverKey = "0",
            ),
            text = DemoBacklinkAlbumNote + NoteMarkup.serializeMention(albumMention),
            at = DemoNow - 9 * DayMs,
        ),
    )
    val albumTarget = BacklinkTarget.Album(
        id = MediaId.subsonic("tip-al1"),
        title = target.albumName,
        artist = target.artistName,
        tracks = target.songs.map { BacklinkTrack(MediaId.subsonic(it.id.substringAfter(':')), it.title, it.artist) },
    )
    return findBacklinks(writings, albumTarget)
}

private const val DemoStickyReview = "Late summer, windows down." // i18n-allow: demo note words

private const val DemoStickyFirst = "Bought the vinyl at the harbour market" // i18n-allow: demo note words

private const val DemoStickySecond = "Side B is for 2 a.m." // i18n-allow: demo note words

private const val DemoMentionedReview = "Quiet, then all at once." // i18n-allow: demo note words

private const val DemoMentionedNote = "The bass walks in here" // i18n-allow: demo note words

private const val DemoBacklinkSongNote = "Same chords as " // i18n-allow: demo note words

private const val DemoBacklinkAlbumNote = "Play it right after " // i18n-allow: demo note words

/** A fixed "now" for the page's day labels, so the demo reads the same every time. */
private const val DemoNow = 1_791_000_000_000L
private const val DayMs = 86_400_000L

/** AlbumScrapbookPage's own space under its last block (before the window's navigation bar). */
private val MentionedPageFoot = 112.dp

/** Half a clipping whose words take one line. */
private val MentionedClippingHalf = 46.dp

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AlbumStickiesDemoPreview() {
    YoinTheme { Box(Modifier.padding(16.dp)) { AlbumStickiesDemo(playing = false) } }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MentionedInDemoPreview() {
    YoinTheme { Box(Modifier.padding(16.dp)) { MentionedInDemo(playing = false) } }
}
