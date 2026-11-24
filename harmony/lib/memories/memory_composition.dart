/// One album Memory's card, built from its candidate and the album's own
/// rows (Android AlbumMemoryComposition + MemoriesDeckCoordinator
/// .resolveAlbumMemory). Pure: the clock, zone and app language come in.
///
/// The title is the user's own ([userTitle], MemoryStore titles), else Yoin's
/// AI title ([aiTitle], memory_ai.dart), else the album name. The narration
/// is the AI's when one is passed ([AlbumMemoryDraft.entry]), else the local
/// template, as on Android without a key.
library;

import '../data/ai/ai_service.dart' show MemoryNarrationText;
import '../data/memory/album_memory_candidate.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../data/notes/note_markup.dart';
import '../playback/local_ratings.dart';
import 'copy/memory_copy.dart';
import 'copy/memory_voice.dart';
import 'memories_ui_state.dart';
import 'memory_ai.dart' show MemoryTitleWriting;

/// The deck holds at most this many cards (Android MEMORY_DECK_SIZE).
const int memoryDeckSize = 6;

MemoryEntry composeAlbumMemory({
  required AlbumMemoryCandidate candidate,
  required Map<MediaId, LocalRating> trackRatings,
  required AlbumRatingRow? ratingRow,
  required List<SongNote> notes,
  required int nowMs,
  MemoryDayOf dayOf = localMemoryDay,
  MemoryProseLanguage appLanguage = MemoryProseLanguage.en,
  String? aiTitle,
  String? userTitle,
  MemoryNarrationText? aiNarration,
  MemoryOtherProfile? otherProfile,
  CoverRef? Function(CoverRef? cover)? coverFor,
}) => draftAlbumMemory(
  candidate: candidate,
  trackRatings: trackRatings,
  ratingRow: ratingRow,
  notes: notes,
  nowMs: nowMs,
  dayOf: dayOf,
  appLanguage: appLanguage,
  aiTitle: aiTitle,
  userTitle: userTitle,
  otherProfile: otherProfile,
  coverFor: coverFor,
).entry(aiNarration: aiNarration);

/// The words an album's AI title is made from (the deck's titleOccupant):
/// the review, else the newest note; null when the user wrote nothing.
MemoryTitleWriting? memoryTitleWriting(AlbumRatingRow? ratingRow, List<SongNote> notes) {
  if (ratingRow?.hasReview ?? false) return MemoryTitleWriting(kind: 'album review', text: ratingRow!.review!);
  final written = [
    for (final n in notes)
      if (!n.isBlank) n,
  ]..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
  return written.isEmpty ? null : MemoryTitleWriting(kind: 'note', text: written.first.content);
}

/// One album Memory before Yoin's narration is chosen: its card with the
/// local template ([entry]), and the facts the AI may narrate from
/// ([narrationBrief]; null with a review or no listening signal).
final class AlbumMemoryDraft {
  AlbumMemoryDraft._(this._build, this.narrationBrief);

  final MemoryEntry Function(MemoryNarrationText? aiNarration) _build;
  final MemoryNarrationBrief? narrationBrief;

  /// The card; [aiNarration] replaces the template's narration and question.
  MemoryEntry entry({MemoryNarrationText? aiNarration}) => _build(aiNarration);
}

AlbumMemoryDraft draftAlbumMemory({
  required AlbumMemoryCandidate candidate,
  required Map<MediaId, LocalRating> trackRatings,
  required AlbumRatingRow? ratingRow,
  required List<SongNote> notes,
  required int nowMs,
  MemoryDayOf dayOf = localMemoryDay,
  MemoryProseLanguage appLanguage = MemoryProseLanguage.en,
  String? aiTitle,
  String? userTitle,
  MemoryOtherProfile? otherProfile,
  CoverRef? Function(CoverRef? cover)? coverFor,
}) {
  final album = candidate.album;
  final songs = album?.tracks ?? const <Track>[];
  final tracks = [
    for (var i = 0; i < songs.length; i++)
      MemoryTrack(
        trackId: songs[i].id,
        title: songs[i].title ?? '',
        artist: songs[i].artist ?? '',
        number: songs[i].trackNumber ?? (i + 1),
        playbackIndex: i,
        durationSec: songs[i].durationSec,
        rating: trackRatings[songs[i].id]?.rating,
      ),
  ];
  final rated = [
    for (final t in tracks)
      if (t.rating != null) t.rating!,
  ];
  final average = rated.isEmpty ? candidate.averageSongRating : rated.reduce((a, b) => a + b) / rated.length;
  final albumScore = (ratingRow?.hasScore ?? false) ? ratingRow!.rating : candidate.albumRating;
  final scoreKind = albumScore != null
      ? MemoryScoreKind.albumRating
      : (average != null ? MemoryScoreKind.averageTrackRating : MemoryScoreKind.none);

  final review = (ratingRow?.hasReview ?? false)
      ? MemoryWriting(
          kind: MemoryWritingKind.review,
          text: ratingRow!.review!,
          writtenAt: ratingRow.reviewUpdatedAt ?? ratingRow.updatedAt,
        )
      : null;
  final titleById = {for (final s in songs) s.id: s.title};
  final writings = [
    for (final n in notes)
      if (!n.isBlank)
        MemoryWriting(
          kind: MemoryWritingKind.songNote,
          text: n.content,
          writtenAt: n.updatedAt,
          trackTitle: titleById[n.trackId] ?? n.title,
          positionMs: n.positionMs,
          trackId: n.trackId,
          noteId: n.id,
        ),
  ]..sort((a, b) => b.writtenAt.compareTo(a.writtenAt));

  final plays = candidate.plays;
  final listening = plays == null || plays.playCount <= 0
      ? null
      : MemoryListening(
          plays: plays.playCount,
          firstHeard: dayOf(plays.firstPlayedAt < plays.lastPlayedAt ? plays.firstPlayedAt : plays.lastPlayedAt),
          lastHeard: dayOf(plays.lastPlayedAt),
        );

  final copyTracks = [for (final t in tracks) MemoryCopyTrack(number: t.number, title: t.title, rating: t.rating)];
  final copyTrackById = <MediaId, MemoryCopyTrack>{};
  for (var i = 0; i < tracks.length; i++) {
    copyTrackById.putIfAbsent(tracks[i].trackId, () => copyTracks[i]);
  }
  final oldestFirst = [...writings]..sort((a, b) => a.writtenAt.compareTo(b.writtenAt));
  final input = MemoryCopyInput(
    albumName: album?.name ?? candidate.albumName,
    aiTitle: aiTitle,
    // Yoin's copy (card excerpts, the voice) reads the words alone: marks
    // dropped, mentions as their names, so a card never quotes a raw link.
    review: review == null ? null : NoteMarkup.plainText(review.text),
    reviewWrittenOn: review == null ? null : dayOf(review.writtenAt),
    notes: [
      for (final w in oldestFirst)
        MemoryCopyNote(
          text: NoteMarkup.plainText(w.text),
          writtenOn: dayOf(w.writtenAt),
          track: copyTrackById[w.trackId] ?? MemoryCopyTrack(number: 1 << 31, title: w.trackTitle ?? ''),
          positionMs: w.positionMs,
        ),
    ],
    tracks: copyTracks,
    ratedTracks: songs.isNotEmpty ? rated.length : candidate.ratedTrackCount,
    totalTracks: songs.isNotEmpty ? songs.length : candidate.totalTracks,
    albumScore: albumScore,
    listening: listening,
  );
  final today = dayOf(nowMs);
  final voice = MemoryVoice.compose(input, today, appLanguage: appLanguage);

  return AlbumMemoryDraft._(
    (ai) => MemoryEntry(
      sessionId: candidate.sessionId,
      albumId: candidate.albumId,
      albumName: album?.name ?? candidate.albumName,
      artistName: candidate.artistName,
      year: album?.year ?? candidate.year,
      // Another account's cover id means that account's server: [coverFor] makes it a URL.
      coverArt: coverFor == null
          ? (album?.coverArt ?? candidate.coverArt)
          : coverFor(album?.coverArt ?? candidate.coverArt),
      scoreKind: scoreKind,
      score: albumScore ?? average,
      albumRating: albumScore,
      // The user's own title over Yoin's (Android resolveAlbumMemoryTitle: user > AI > album name).
      memoryTitle: _written(userTitle) ?? voice.title,
      memoryTitleKind: _written(userTitle) != null ? MemoryTitleKind.user : voice.titleKind,
      yoinTitle: voice.titleKind == MemoryTitleKind.ai ? voice.title : null,
      proseLanguage: voice.language,
      review: review,
      writings: writings,
      yoinNarration: ai?.narration ?? voice.narration,
      yoinQuestion: ai?.question ?? voice.question,
      excerptCandidates: MemoryExcerpt.candidates(
        input,
        capped: false,
        today: today,
        zhDates: appLanguage == MemoryProseLanguage.zh,
      ),
      excerptCandidatesMedium: MemoryExcerpt.candidates(
        input,
        capped: true,
        today: today,
        zhDates: appLanguage == MemoryProseLanguage.zh,
      ),
      tracks: tracks,
      diaryTracks: diaryTracks(tracks, writings),
      playbackSongs: songs,
      noteCount: writings.length,
      ratedTrackCount: input.ratedTracks,
      totalTrackCount: input.totalTracks,
      playsInYoin: listening?.plays,
      firstHeardAt: listening == null ? null : plays!.firstPlayedAt,
      lastHeardAt: listening == null ? null : plays!.lastPlayedAt,
      otherProfile: otherProfile,
    ),
    MemoryVoice.narrationBrief(input, voice, today),
  );
}

String? _written(String? title) {
  final words = title?.trim();
  return words == null || words.isEmpty ? null : words;
}

/// Option A (owner default): only rated or noted songs, album order. Notes
/// group under their song: anchored by position, unanchored after them,
/// oldest first. A song listed twice keeps its notes under the first row.
List<MemoryDiaryTrack> diaryTracks(List<MemoryTrack> tracks, List<MemoryWriting> writings) {
  final byTrack = <MediaId, List<MemoryWriting>>{};
  for (final w in writings) {
    if (w.kind == MemoryWritingKind.songNote && w.trackId != null) (byTrack[w.trackId!] ??= []).add(w);
  }
  final placed = <MediaId>{};
  final out = <MemoryDiaryTrack>[];
  for (final t in tracks) {
    final notes = placed.add(t.trackId) ? [...?byTrack[t.trackId]] : <MemoryWriting>[];
    notes.sort((a, b) {
      final c = (a.positionMs ?? (1 << 62)).compareTo(b.positionMs ?? (1 << 62));
      return c != 0 ? c : a.writtenAt.compareTo(b.writtenAt);
    });
    if (t.rating != null || notes.isNotEmpty) out.add(MemoryDiaryTrack(t, notes));
  }
  return out;
}
