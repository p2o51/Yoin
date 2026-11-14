/// One album's score as every surface shows it (Android ScoreEmblem's spec /
/// AlbumMemoryFacts): the album score when the user gave one, else the
/// average of the rated tracks, and which tracks are rated (the emblem's
/// rings). Live: a score, a review or a track rating written anywhere shows
/// at once.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../detail/detail_cache.dart';
import '../playback/local_ratings.dart';
import 'memories_view_model.dart';

@immutable
final class AlbumScore {
  const AlbumScore({this.albumRating, this.review, this.average, this.ratedTracks = const []});

  /// The user's album score (> 0), or null.
  final double? albumRating;
  final String? review;

  /// Average of the rated tracks, or null.
  final double? average;

  /// Album order: whether each track is rated.
  final List<bool> ratedTracks;

  int get ratedCount => ratedTracks.where((r) => r).length;
  int get trackCount => ratedTracks.length;

  /// The score the emblem shows.
  double? get shown => albumRating ?? average;
}

class AlbumScoreNotifier extends Notifier<AlbumScore> {
  AlbumScoreNotifier(this.albumId);

  final MediaId albumId;
  StreamSubscription<MemoryStoreChange>? _changes;
  int _generation = 0;

  @override
  AlbumScore build() {
    final ratings = ref.watch(localRatingsProvider);
    final store = ref.watch(memoryStoreProvider).value;
    _changes?.cancel();
    _changes = store?.changes.listen((c) {
      if (c == MemoryStoreChange.albumRatings) _load(store, ref.read(localRatingsProvider));
    });
    ref.onDispose(() => _changes?.cancel());
    final previous = stateOrNull;
    scheduleMicrotask(() => _load(store, ratings));
    return previous ?? const AlbumScore();
  }

  Future<void> _load(MemoryStore? store, Map<MediaId, LocalRating> ratings) async {
    final generation = ++_generation;
    final row = await store?.albumRating(albumId);
    final album = ref.read(detailCacheProvider).cachedAlbum(albumId);
    if (!ref.mounted || generation != _generation) return;
    final tracks = album?.tracks ?? const <Track>[];
    final rated = [for (final t in tracks) ratings[t.id]?.rating];
    final scores = rated.whereType<double>().toList();
    state = AlbumScore(
      albumRating: (row?.hasScore ?? false) ? row!.rating : null,
      review: row?.review,
      average: scores.isEmpty ? null : scores.reduce((a, b) => a + b) / scores.length,
      ratedTracks: [for (final r in rated) r != null],
    );
  }

  /// The album score, 0.0–10.0; 0 clears it (an album's 0 is its clear).
  Future<void> setRating(double rating) async {
    final store = await ref.read(memoryStoreProvider.future);
    await store?.setAlbumRating(albumId, rating <= 0 ? null : rating, nowMs: ref.read(memoriesClockProvider)());
  }

  Future<void> setReview(String review) async {
    final store = await ref.read(memoryStoreProvider.future);
    await store?.setAlbumReview(albumId, review, nowMs: ref.read(memoriesClockProvider)());
  }
}

final albumScoreProvider = NotifierProvider.autoDispose.family<AlbumScoreNotifier, AlbumScore, MediaId>(
  AlbumScoreNotifier.new,
);
