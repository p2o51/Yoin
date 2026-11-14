/// The Memories deck's state (Android ui/memories MemoriesUiState): one
/// [MemoryEntry] per album card, everything the card, the diary and the
/// emblem paint.
library;

import 'package:flutter/foundation.dart';

import '../data/model/model.dart';
import 'copy/memory_copy.dart';
import 'copy/memory_voice.dart';

sealed class MemoriesUiState {
  const MemoriesUiState();
}

final class MemoriesLoading extends MemoriesUiState {
  const MemoriesLoading();
}

final class MemoriesEmpty extends MemoriesUiState {
  const MemoriesEmpty();
}

final class MemoriesError extends MemoriesUiState {
  const MemoriesError();
}

final class MemoriesContent extends MemoriesUiState {
  const MemoriesContent(this.memories, {this.currentPage = 0});
  final List<MemoryEntry> memories;

  /// The page the deck rests on (kept across openings in one session).
  final int currentPage;

  MemoriesContent copyWith({List<MemoryEntry>? memories, int? currentPage}) =>
      MemoriesContent(memories ?? this.memories, currentPage: currentPage ?? this.currentPage);
}

/// Which score the card shows: the album score first, else the average of
/// the rated tracks.
enum MemoryScoreKind { albumRating, averageTrackRating, none }

/// Something the user wrote on this album.
@immutable
final class MemoryWriting {
  const MemoryWriting({
    required this.kind,
    required this.text,
    required this.writtenAt,
    this.trackTitle,
    this.positionMs,
    this.trackId,
    this.noteId,
  });

  final MemoryWritingKind kind;
  final String text;
  final int writtenAt;

  /// Song notes only: the song's title, the moment it is pinned to, its id.
  final String? trackTitle;
  final int? positionMs;
  final MediaId? trackId;

  /// The note's id; null for the review.
  final String? noteId;
}

enum MemoryWritingKind { review, songNote }

/// One song of the album as the diary lists it.
@immutable
final class MemoryTrack {
  const MemoryTrack({
    required this.trackId,
    required this.title,
    required this.artist,
    required this.number,
    required this.playbackIndex,
    this.durationSec,
    this.rating,
  });

  final MediaId trackId;
  final String title;
  final String artist;

  /// The track number, else the 1-based position.
  final int number;

  /// Index into [MemoryEntry.playbackSongs].
  final int playbackIndex;
  final int? durationSec;

  /// 0.0–10.0, null when unrated.
  final double? rating;
}

/// A diary track row and the notes under it, lyrics-style: anchored notes
/// by position, unanchored ones after them (oldest first).
@immutable
final class MemoryDiaryTrack {
  const MemoryDiaryTrack(this.track, this.notes);
  final MemoryTrack track;
  final List<MemoryWriting> notes;
}

@immutable
final class MemoryEntry {
  const MemoryEntry({
    required this.sessionId,
    required this.albumId,
    required this.albumName,
    this.artistName,
    this.year,
    this.coverArt,
    required this.scoreKind,
    this.score,
    required this.memoryTitle,
    required this.memoryTitleKind,
    this.yoinTitle,
    required this.proseLanguage,
    this.review,
    this.writings = const [],
    this.yoinNarration,
    this.yoinQuestion,
    this.excerptCandidates = const [],
    this.excerptCandidatesMedium = const [],
    this.tracks = const [],
    this.diaryTracks = const [],
    this.playbackSongs = const [],
    this.noteCount = 0,
    this.ratedTrackCount = 0,
    this.totalTrackCount = 0,
    this.playsInYoin,
    this.firstHeardAt,
    this.lastHeardAt,
    this.albumRating,
  });

  /// Stable per (account, provider, album): the deck's identity of a card.
  final int sessionId;
  final MediaId albumId;
  final String albumName;
  final String? artistName;
  final int? year;
  final CoverRef? coverArt;

  final MemoryScoreKind scoreKind;

  /// The score shown (album score or track average); null when unrated.
  final double? score;

  /// The user's album score, when set.
  final double? albumRating;

  /// The title over the card: the user's own, else Yoin's AI title, else
  /// the album name.
  final String memoryTitle;
  final MemoryTitleKind memoryTitleKind;

  /// Yoin's own (AI) title under the user's, when there is one: what a
  /// restore brings back. Null = the album name stands in.
  final String? yoinTitle;

  /// Yoin's prose follows the language the user writes in.
  final MemoryProseLanguage proseLanguage;

  final MemoryWriting? review;

  /// Song notes, newest first.
  final List<MemoryWriting> writings;

  /// Yoin's narration and question, only while there is no review.
  final String? yoinNarration;
  final String? yoinQuestion;

  /// Card excerpt candidates, best first: phone (capped by the slot) and
  /// Medium (the teaser cap).
  final List<MemoryExcerptCandidate> excerptCandidates;
  final List<MemoryExcerptCandidate> excerptCandidatesMedium;

  /// Every song, album order (the emblem's rings read them).
  final List<MemoryTrack> tracks;

  /// Option A: only rated or noted songs, each with its notes.
  final List<MemoryDiaryTrack> diaryTracks;
  final List<Track> playbackSongs;

  final int noteCount;
  final int ratedTrackCount;
  final int totalTrackCount;

  /// Plays in Yoin only; all three null for an album never played here (the
  /// diary footer and "Last heard" are then hidden).
  final int? playsInYoin;
  final int? firstHeardAt;
  final int? lastHeardAt;

  /// The score the card shows, with the shared rounding ("8.5"); null when unrated.
  String? get scoreText => score == null ? null : MemoryScores.text(score!);

  bool get isTitleSerif => memoryTitleKind != MemoryTitleKind.album;

  /// The user's own title is in use.
  bool get hasUserTitle => memoryTitleKind == MemoryTitleKind.user;
}
