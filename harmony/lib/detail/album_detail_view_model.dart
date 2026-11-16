import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../ui/component/error_diagnostics.dart';
import 'detail_cache.dart';

/// Album page state (Android AlbumDetailUiState).
sealed class AlbumDetailState {
  const AlbumDetailState();
}

final class AlbumDetailLoading extends AlbumDetailState {
  const AlbumDetailLoading();
}

final class AlbumDetailError extends AlbumDetailState {
  const AlbumDetailError({required this.notFound, this.cause, this.diagnostics});

  /// The server has no such album (vs. a failed read).
  final bool notFound;

  /// What the failed read threw: the page says its line (`userMessageText`).
  final Object? cause;

  /// The scrubbed report Copy details copies ([diagnosticReport]), taken when it failed.
  final String? diagnostics;
}

final class AlbumDetailContent extends AlbumDetailState {
  const AlbumDetailContent(this.album);

  /// With tracks in disc / track order (the mapper's discTrackOrder): rows,
  /// disc headings, flowing titles and the queue all read this order.
  final Album album;

  List<Track> get tracks => album.tracks;

  /// songs.size when loaded, else the server's songCount.
  int get trackCount => tracks.isNotEmpty ? tracks.length : (album.songCount ?? 0);
  int get totalDurationSec => tracks.fold(0, (sum, t) => sum + (t.durationSec ?? 0));

  /// More than one distinct disc number.
  bool get isMultiDisc => tracks.map((t) => t.discNumber).whereType<int>().toSet().length > 1;

  /// The " (feat. X)" credit for [track]: its artist when non-blank and not
  /// the album artist (case-insensitive), else null.
  String? featArtistOf(Track track) {
    final artist = track.artist?.trim();
    if (artist == null || artist.isEmpty) return null;
    final albumArtist = album.artist?.trim().toLowerCase();
    return artist.toLowerCase() == albumArtist ? null : artist;
  }
}

/// Loads one album through the detail cache (single-flight, joins a
/// tap-time prefetch) and records the visit for Home's activity.
class AlbumDetailNotifier extends Notifier<AlbumDetailState> {
  AlbumDetailNotifier(this.albumId);

  final MediaId albumId;
  int _generation = 0;

  @override
  AlbumDetailState build() {
    final cache = ref.watch(detailCacheProvider);
    final cached = cache.cachedAlbum(albumId);
    if (cached != null && cached.tracks.isNotEmpty) {
      _recordVisit(cached);
      return AlbumDetailContent(cached);
    }
    _load(cache);
    return const AlbumDetailLoading();
  }

  Future<void> retry() async {
    state = const AlbumDetailLoading();
    await _load(ref.read(detailCacheProvider));
  }

  Future<void> _load(DetailCache cache) async {
    final gen = ++_generation;
    try {
      final album = await cache.album(albumId);
      if (!ref.mounted || gen != _generation) return;
      if (album == null) {
        state = const AlbumDetailError(notFound: true);
        return;
      }
      state = AlbumDetailContent(album);
      _recordVisit(album);
    } catch (e) {
      if (!ref.mounted || gen != _generation) return;
      state = AlbumDetailError(notFound: false, cause: e, diagnostics: diagnosticReport(e));
    }
  }

  /// ALBUM / VISITED (home spec item 18), off the critical path.
  void _recordVisit(Album album) {
    unawaited(() async {
      try {
        final log = await ref.read(activityLogProvider.future);
        await log?.record(
          ActivityEvent(
            entityType: ActivityEntityType.album,
            actionType: ActivityActionType.visited,
            entityId: album.id.rawId,
            title: album.name,
            subtitle: album.artist ?? '',
            coverArtKey: album.coverArt?.storageKey ?? album.id.rawId,
            albumId: album.id.rawId,
            artistId: album.artistId?.rawId,
            timestampMs: DateTime.now().millisecondsSinceEpoch,
          ),
        );
      } catch (_) {
        // Logging never affects the page.
      }
    }());
  }
}

final albumDetailProvider = NotifierProvider.autoDispose.family<AlbumDetailNotifier, AlbumDetailState, MediaId>(
  AlbumDetailNotifier.new,
);
