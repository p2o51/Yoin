import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../library/playlist_writes.dart';
import 'detail_cache.dart';

/// One playlist row: a stable per-entry key so the same song twice is two rows.
final class PlaylistEntry {
  const PlaylistEntry(this.rowKey, this.track);
  final String rowKey;
  final Track track;
}

sealed class PlaylistDetailState {
  const PlaylistDetailState();
}

final class PlaylistDetailLoading extends PlaylistDetailState {
  const PlaylistDetailLoading();
}

final class PlaylistDetailError extends PlaylistDetailState {
  const PlaylistDetailError({required this.notFound});
  final bool notFound;
}

final class PlaylistDetailContent extends PlaylistDetailState {
  PlaylistDetailContent(this.playlist)
    : entries = [for (final (i, t) in playlist.tracks.indexed) PlaylistEntry('row-$i', t)];

  final Playlist playlist;
  final List<PlaylistEntry> entries;

  List<Track> get tracks => playlist.tracks;

  /// songCount, else the rows.
  int get trackCount => playlist.songCount ?? tracks.length;
  int get totalDurationSec => playlist.durationSec ?? tracks.fold(0, (sum, t) => sum + (t.durationSec ?? 0));

  /// The hero cover: the playlist's own cover id (Subsonic maps it to the
  /// playlist id), else the first track's cover or album id; none (the
  /// placeholder) for an empty playlist.
  CoverRef? get heroCover => playlistHeroCover(playlist);

  /// The cover the play context carries.
  CoverRef? get contextCover => heroCover;
}

/// The cover a playlist shows everywhere (Library row and cell, detail hero).
/// A playlist known to be empty has none: the server's art for it is a blank
/// (black) square, so it gets the placeholder with the playlist symbol, as
/// Android's artwork does when there is nothing to show.
CoverRef? playlistHeroCover(Playlist playlist) {
  if (playlist.songCount == 0 && playlist.tracks.isEmpty) return null;
  if (playlist.coverArt case final cover?) return cover;
  final first = playlist.tracks.firstOrNull;
  if (first == null) return null;
  return first.coverArt ?? (first.albumId == null ? null : CoverSourceRelative(first.albumId!.rawId));
}

/// Up to two covers to fan behind the hero (Android playlistSleeveCovers):
/// the rows' own album covers in playlist order, distinct, not the hero's.
List<CoverRef> playlistSleeveCovers(Playlist playlist, {int max = 2}) {
  final hero = playlistHeroCover(playlist);
  final out = <CoverRef>[];
  for (final t in playlist.tracks) {
    final cover = t.coverArt ?? (t.albumId == null ? null : CoverSourceRelative(t.albumId!.rawId));
    if (cover == null || cover == hero || out.contains(cover)) continue;
    out.add(cover);
    if (out.length == max) break;
  }
  return out;
}

class PlaylistDetailNotifier extends Notifier<PlaylistDetailState> {
  PlaylistDetailNotifier(this.playlistId);

  final MediaId playlistId;
  int _generation = 0;

  @override
  PlaylistDetailState build() {
    // A write anywhere (Add to playlist from a row's sheet, a rename here): the page re-reads in place.
    ref.listen(playlistsRevisionProvider, (_, _) {
      if (state is PlaylistDetailContent) _load(ref.read(detailCacheProvider));
    });
    _load(ref.watch(detailCacheProvider));
    return const PlaylistDetailLoading();
  }

  PlaylistWrites get _writes => ref.read(playlistWritesProvider);

  /// Renames the playlist; false when the server refused.
  Future<bool> rename(String name) async {
    try {
      await _writes.rename(playlistId, name);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Deletes the playlist; false when the server refused (the page stays).
  Future<bool> delete() async {
    try {
      await _writes.delete(playlistId);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Takes the song at [position] out: gone from the page at once, back if
  /// the server refuses (returns false then).
  Future<bool> removeAt(int position) async {
    final content = state;
    if (content is! PlaylistDetailContent || position < 0 || position >= content.tracks.length) return false;
    final tracks = [...content.tracks]..removeAt(position);
    state = PlaylistDetailContent(_withTracks(content.playlist, tracks));
    try {
      await _writes.remove(playlistId, [position]);
      return true;
    } catch (_) {
      if (ref.mounted) state = content;
      return false;
    }
  }

  /// Moves the song at [from] to [to]: the page shows the new order at once,
  /// the old one again if the server refuses (returns false then).
  Future<bool> move(int from, int to) async {
    final content = state;
    if (content is! PlaylistDetailContent) return false;
    final tracks = [...content.tracks];
    if (from == to || from < 0 || to < 0 || from >= tracks.length || to >= tracks.length) return false;
    tracks.insert(to, tracks.removeAt(from));
    state = PlaylistDetailContent(_withTracks(content.playlist, tracks));
    try {
      await _writes.reorder(playlistId, tracks);
      return true;
    } catch (_) {
      if (ref.mounted) state = content;
      return false;
    }
  }

  static Playlist _withTracks(Playlist p, List<Track> tracks) => Playlist(
    id: p.id,
    name: p.name,
    owner: p.owner,
    coverArt: p.coverArt,
    songCount: tracks.length,
    durationSec: tracks.fold<int>(0, (sum, t) => sum + (t.durationSec ?? 0)),
    tracks: tracks,
    canWrite: p.canWrite,
    comment: p.comment,
    libraryAddedAt: p.libraryAddedAt,
  );

  Future<void> retry() async {
    state = const PlaylistDetailLoading();
    await _load(ref.read(detailCacheProvider));
  }

  Future<void> _load(DetailCache cache) async {
    final gen = ++_generation;
    try {
      final playlist = await cache.playlist(playlistId);
      if (!ref.mounted || gen != _generation) return;
      state = playlist == null ? const PlaylistDetailError(notFound: true) : PlaylistDetailContent(playlist);
    } catch (_) {
      if (!ref.mounted || gen != _generation) return;
      state = const PlaylistDetailError(notFound: false);
    }
  }
}

final playlistDetailProvider = NotifierProvider.autoDispose
    .family<PlaylistDetailNotifier, PlaylistDetailState, MediaId>(PlaylistDetailNotifier.new);
