/// The user's own listening on the detail pages (Android getAlbumLastPlayed /
/// getArtistListening): when an album was last played, and an artist's most
/// played songs, both from the account's plays in Yoin ([MemoryStore]), so
/// they exist for every provider. Live: a play made anywhere shows at once.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import 'artist_detail_view_model.dart';
import 'detail_strings.dart';

/// An artist's listening (Android ArtistListening): every play of theirs and
/// the most played songs.
@immutable
final class ArtistListening {
  const ArtistListening({required this.playCount, required this.mostPlayed});

  static const empty = ArtistListening(playCount: 0, mostPlayed: []);

  final int playCount;

  /// Most played first, then most recently played.
  final List<TrackPlays> mostPlayed;

  /// The rows as a play queue, in row order (getMostPlayedTracks).
  List<Track> tracks({MediaId? artistId, String? artistName}) => [
    for (final row in mostPlayed)
      Track(
        id: row.trackId,
        title: row.title,
        artist: artistName ?? row.artist,
        artistId: artistId,
        album: row.album,
        albumId: row.albumId,
        coverArt: CoverRef.fromStorageKey(row.coverArtKey),
        durationSec: row.durationSec != null && row.durationSec! > 0 ? row.durationSec : null,
      ),
  ];

  @override
  bool operator ==(Object other) =>
      other is ArtistListening && other.playCount == playCount && listEquals(other.mostPlayed, mostPlayed);

  @override
  int get hashCode => Object.hash(playCount, Object.hashAll(mostPlayed));
}

/// How many Most Played rows the artist page lists (getArtistListening topLimit).
const artistMostPlayedLimit = 5;

/// PlayHistoryDao.getArtistTopSongs + getArtistPlayStats over [rows]: a play
/// is the artist's when it is on the artist's provider and came from one of
/// [albumIds] OR carries their exact [artistName] (features, releases
/// outside the fetched discography).
ArtistListening artistListeningFrom(
  Iterable<TrackPlays> rows, {
  required MediaId artistId,
  required String artistName,
  required Iterable<MediaId> albumIds,
  int limit = artistMostPlayedLimit,
}) {
  final albums = {
    for (final id in albumIds)
      if (id.provider == artistId.provider) id,
  };
  final mine = [
    for (final r in rows)
      if (r.trackId.provider == artistId.provider && (albums.contains(r.albumId) || r.artist == artistName)) r,
  ];
  final total = mine.fold(0, (sum, r) => sum + r.playCount);
  if (total == 0) return ArtistListening.empty;
  mine.sort((a, b) {
    final byCount = b.playCount.compareTo(a.playCount);
    return byCount != 0 ? byCount : b.lastPlayedAt.compareTo(a.lastPlayedAt);
  });
  return ArtistListening(playCount: total, mostPlayed: List.unmodifiable(mine.take(limit)));
}

/// Base for the two listeners: reloads on every write of plays to the store.
abstract class _PlaysNotifier<T> extends Notifier<T> {
  StreamSubscription<MemoryStoreChange>? _changes;
  int _generation = 0;

  T get initial;

  Future<T> read(MemoryStore store);

  @override
  T build() {
    final store = ref.watch(memoryStoreProvider).value;
    watchInputs();
    _changes?.cancel();
    _changes = store?.changes.listen((c) {
      if (c == MemoryStoreChange.plays) _load(store);
    });
    ref.onDispose(() => _changes?.cancel());
    final previous = stateOrNull;
    scheduleMicrotask(() => _load(store));
    return previous ?? initial;
  }

  /// Watches whatever else the value depends on (the artist page's state).
  void watchInputs() {}

  Future<void> _load(MemoryStore? store) async {
    final generation = ++_generation;
    if (store == null) return;
    try {
      final next = await read(store);
      if (!ref.mounted || generation != _generation) return;
      state = next;
    } catch (_) {
      // Best-effort: a failed read leaves the page as it was.
    }
  }
}

/// When the album was last played in Yoin (MAX(play_history.playedAt) for the
/// album); null = never.
class AlbumLastPlayedNotifier extends _PlaysNotifier<int?> {
  AlbumLastPlayedNotifier(this.albumId);

  final MediaId albumId;

  @override
  int? get initial => null;

  @override
  Future<int?> read(MemoryStore store) async => (await store.playsOf(albumId))?.lastPlayedAt;
}

final albumLastPlayedProvider = NotifierProvider.autoDispose.family<AlbumLastPlayedNotifier, int?, MediaId>(
  AlbumLastPlayedNotifier.new,
);

/// The artist's listening once the page has loaded its discography.
class ArtistListeningNotifier extends _PlaysNotifier<ArtistListening> {
  ArtistListeningNotifier(this.artistId);

  final MediaId artistId;
  ArtistDetailContent? _content;

  @override
  ArtistListening get initial => ArtistListening.empty;

  @override
  void watchInputs() {
    final s = ref.watch(artistDetailProvider(artistId));
    _content = s is ArtistDetailContent ? s : null;
  }

  @override
  Future<ArtistListening> read(MemoryStore store) async {
    final content = _content;
    if (content == null) return ArtistListening.empty;
    return artistListeningFrom(
      await store.trackPlays(),
      artistId: content.artist.id,
      artistName: content.artist.name,
      albumIds: content.releases.map((r) => r.id),
    );
  }
}

final artistListeningProvider = NotifierProvider.autoDispose.family<ArtistListeningNotifier, ArtistListening, MediaId>(
  ArtistListeningNotifier.new,
);

/// Album-level "last play" → (day, time), e.g. ("Yesterday", "16:04")
/// (Android albumLastPlayLabels): Today / Yesterday / N days ago under a
/// week, else the month and day; the time is 24-hour local.
(String, String) lastPlayLabels(int epochMs, DetailStrings s, {DateTime? now}) {
  final moment = DateTime.fromMillisecondsSinceEpoch(epochMs);
  final today = now ?? DateTime.now();
  // Calendar days, counted on UTC dates so a DST change never shortens one.
  final days = DateTime.utc(
    today.year,
    today.month,
    today.day,
  ).difference(DateTime.utc(moment.year, moment.month, moment.day)).inDays;
  final day = switch (days) {
    <= 0 => s.relativeToday,
    1 => s.relativeYesterday,
    < 7 => s.relativeDaysAgo(days),
    _ => s.monthDay(moment.month, moment.day),
  };
  String two(int v) => v.toString().padLeft(2, '0');
  return (day, '${two(moment.hour)}:${two(moment.minute)}');
}
