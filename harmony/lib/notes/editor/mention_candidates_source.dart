/// Where the @ popup gets its rows (Android ui/notes/editor/MentionCandidates.kt
/// RepositoryMentionCandidateSource): an empty query offers the song playing,
/// its album and artist, then recent plays; a query searches the account's
/// service and puts matching recent plays first. A failed or slow search
/// falls back to the recent plays that match.
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/memory/memory_store.dart';
import '../../data/model/model.dart';
import '../../data/notes/mention_ranking.dart';
import '../../data/notes/note_markup.dart';
import '../../data/source/music_source.dart';
import '../../playback/playback_controller.dart';
import '../../theme/tokens.dart';

/// [query] blank: what's playing and recent plays; otherwise search results.
abstract interface class MentionCandidateSource {
  Future<MentionCandidates> candidates(String query, int limit);
}

/// No rows (previews, a field with no account).
final class NoMentionCandidates implements MentionCandidateSource {
  const NoMentionCandidates();

  @override
  Future<MentionCandidates> candidates(String query, int limit) async => MentionCandidates.empty;
}

/// The active account's rows.
final class AccountMentionCandidates implements MentionCandidateSource {
  AccountMentionCandidates({
    required this.source,
    required this.store,
    required this.nowPlaying,
    this.romanizer = PinyinMentionRomanizer.romanize,
  });

  final MusicSource? source;
  final MemoryStore? store;
  final Track? Function() nowPlaying;
  final MentionRomanizer romanizer;

  @override
  Future<MentionCandidates> candidates(String query, int limit) async {
    final q = query.trim();
    final recents = await _recentTracks();
    if (q.isEmpty) {
      final current = nowPlaying();
      final tracks = MentionCandidates(
        tracks: [?(current == null ? null : _track(current, MentionSource.current)), ...recents],
      ).merge(MentionCandidates.empty).tracks.take(limit).toList();
      final albumId = current?.albumId;
      final albumName = current?.album;
      final artistId = current?.artistId;
      final artistName = current?.artist;
      return MentionCandidates(
        tracks: tracks,
        albums: [
          if (albumId != null && albumName != null && albumName.trim().isNotEmpty)
            ?_candidate(
              MentionKind.album,
              albumId,
              albumName,
              current?.artist,
              null,
              current?.coverArt,
              MentionSource.current,
            ),
        ],
        artists: [
          if (artistId != null && artistName != null && artistName.trim().isNotEmpty)
            ?_candidate(MentionKind.artist, artistId, artistName, null, null, null, MentionSource.current),
        ],
      );
    }
    SearchResults? results;
    try {
      results = await source?.library
          .search(q, trackCount: limit * 2, albumCount: limit, artistCount: limit)
          .timeout(const Duration(milliseconds: NoteEditorTokens.searchTimeoutMs));
    } on Object {
      results = null;
    }
    final ranked = MentionRanking.rank(
      q,
      MentionCandidates(
        tracks: [...recents, for (final t in results?.tracks ?? const <Track>[]) ?_track(t, MentionSource.search)],
        albums: [for (final a in results?.albums ?? const <Album>[]) ?_album(a)],
        artists: [for (final a in results?.artists ?? const <Artist>[]) ?_artist(a)],
      ).merge(MentionCandidates.empty),
      romanizer,
    );
    return ranked.copyWith(
      tracks: ranked.tracks.take(limit).toList(),
      albums: ranked.albums.take(limit ~/ 2 + 1).toList(),
      artists: ranked.artists.take(limit ~/ 2 + 1).toList(),
    );
  }

  Future<List<MentionCandidate>> _recentTracks() async {
    try {
      final plays = [...await store?.trackPlays() ?? const <TrackPlays>[]]
        ..sort((a, b) => b.lastPlayedAt.compareTo(a.lastPlayedAt));
      return [
        for (final p in plays.take(NoteEditorTokens.recentLimit))
          if (p.title.trim().isNotEmpty)
            ?_candidate(
              MentionKind.track,
              p.trackId,
              p.title,
              p.artist,
              p.album,
              CoverRef.fromStorageKey(p.coverArtKey),
              MentionSource.recent,
            ),
      ];
    } on Object {
      return const [];
    }
  }

  MentionCandidate? _track(Track t, MentionSource source) =>
      _candidate(MentionKind.track, t.id, t.title ?? '', t.artist, t.album, t.coverArt, source);

  MentionCandidate? _album(Album a) =>
      _candidate(MentionKind.album, a.id, a.name, a.artist, null, a.coverArt, MentionSource.search);

  MentionCandidate? _artist(Artist a) =>
      _candidate(MentionKind.artist, a.id, a.name, null, null, a.coverArt, MentionSource.search);

  MentionCandidate? _candidate(
    MentionKind kind,
    MediaId id,
    String title,
    String? artist,
    String? album,
    CoverRef? cover,
    MentionSource from,
  ) {
    if (id.rawId.isEmpty || !NoteMention.isValidProvider(id.provider)) return null;
    final shownArtist = kind != MentionKind.artist && artist != null && artist.trim().isNotEmpty ? artist : null;
    final shownAlbum = kind == MentionKind.track && album != null && album.trim().isNotEmpty ? album : null;
    String? url;
    if (cover != null) {
      try {
        url = source?.resolveCoverUrl(cover, size: NoteEditorTokens.coverSize);
      } on Object {
        url = null;
      }
    }
    return MentionCandidate(
      mention: NoteMention(
        kind: kind,
        provider: id.provider,
        id: id.rawId,
        title: title.trim().isEmpty ? id.rawId : title,
        artist: shownArtist,
        album: shownAlbum,
      ),
      coverUrl: url,
      artist: shownArtist,
      album: shownAlbum,
      source: from,
    );
  }
}

/// The window's @ rows: the account in use, the song playing.
final mentionCandidatesProvider = Provider<MentionCandidateSource>((ref) {
  final source = ref.watch(activeSourceProvider).value;
  final store = ref.watch(memoryStoreProvider).value;
  return AccountMentionCandidates(
    source: source,
    store: store,
    nowPlaying: () => ref.read(playbackControllerProvider).current,
  );
});
