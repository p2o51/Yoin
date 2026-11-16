/// Matches a playlist's tracks on another profile's service and writes them
/// there (Android data/transfer/PlaylistTransfer.kt; docs/playlist-transfer.md
/// §3): [PlaylistTransfer.resolve] each track on the target (ISRC first where
/// the target can look one up, then a title search scored by [TrackMatcher]),
/// then [PlaylistTransfer.writeTracks] a new playlist holding the chosen
/// tracks in order. Ambiguous and unmatched tracks are reported, never added.
library;

import 'package:flutter/foundation.dart';

import '../model/model.dart';
import '../source/music_source.dart';
import 'track_matcher.dart';

/// A track to find on the target, with its ISRC when the backup kept one.
@immutable
final class MatchableTrack {
  const MatchableTrack(this.track, {this.isrc});
  final Track track;
  final String? isrc;
}

/// One source track at [position] and what became of it on the target.
@immutable
final class TrackResolution {
  const TrackResolution({required this.position, required this.source, required this.decision});

  final int position;
  final Track source;
  final MatchDecision decision;

  TrackResolution withPosition(int value) => TrackResolution(position: value, source: source, decision: decision);
}

/// What [PlaylistTransfer.writeTracks] made: [created] (null when nothing
/// was), how many of the [requested] tracks it holds, and what stopped the
/// write, if anything (a [PartialPlaylistCreate] still has [created]).
@immutable
final class PlaylistWrite {
  const PlaylistWrite({required this.created, required this.added, required this.requested, this.failure});

  final Playlist? created;
  final int added;
  final int requested;
  final Object? failure;
}

/// Where a new playlist goes: the target source's playlist writes.
typedef PlaylistCreator = Future<Playlist> Function(String name, List<MediaId> tracks);

class PlaylistTransfer {
  const PlaylistTransfer();

  /// Each of [tracks] (in order) resolved on [target]. [onProgress] is told
  /// how many were looked at, of how many. A search that fails leaves its
  /// track unmatched rather than failing the whole match.
  Future<List<TrackResolution>> resolve(
    List<MatchableTrack> tracks,
    MusicSource target, {
    void Function(int done, int total)? onProgress,
    void Function(TrackResolution resolution)? onResolved,
  }) async {
    final codes = target.trackCodes;
    final isrcs = {for (final t in tracks) ?t.isrc}.toList();
    var byIsrc = const <String, List<Track>>{};
    if (codes != null && isrcs.isNotEmpty) {
      try {
        byIsrc = await codes.tracksByIsrc(isrcs);
      } catch (_) {
        byIsrc = const {};
      }
    }
    final searches = <String, List<Track>>{};
    Future<List<Track>> searched(String query) async => searches[query] ??= await _search(target, query);
    final result = <TrackResolution>[];
    for (final (index, item) in tracks.indexed) {
      final track = item.track;
      final isrcHits = item.isrc == null ? null : byIsrc[item.isrc];
      final isrcDecision = isrcHits == null ? null : TrackMatcher.chooseByIsrc(track, isrcHits);
      MatchDecision decision;
      if (isrcDecision is MatchMatched) {
        decision = isrcDecision;
      } else {
        final query = TrackMatcher.searchQuery(track);
        if (query == null) {
          decision = const MatchNotFound();
        } else {
          decision = TrackMatcher.choose(track, await searched(query));
          // The artist as the target spells it can sink "title artist" (a romanised name against the
          // original script): the title alone, then.
          final titleOnly = TrackMatcher.titleQuery(track);
          if (decision is MatchNotFound && titleOnly != null && titleOnly != query) {
            decision = TrackMatcher.choose(track, await searched(titleOnly));
          }
        }
      }
      final resolution = TrackResolution(position: index, source: track, decision: decision);
      onResolved?.call(resolution);
      onProgress?.call(index + 1, tracks.length);
      result.add(resolution);
    }
    return result;
  }

  /// A playlist named [name] on [target] holding [trackIds] in order (a
  /// restore writes what its review left checked), described by
  /// [description] where given. None: no empty playlist left behind on the
  /// target, nothing made.
  Future<PlaylistWrite> writeTracks(
    String name,
    List<MediaId> trackIds,
    MusicSource target, {
    String? description,
  }) async {
    if (trackIds.isEmpty) return const PlaylistWrite(created: null, added: 0, requested: 0);
    final writes = target.playlistWrites;
    if (writes == null) {
      return PlaylistWrite(
        created: null,
        added: 0,
        requested: trackIds.length,
        failure: const UnsupportedSourceOperation('createPlaylist'),
      );
    }
    try {
      final playlist = await writes.createPlaylist(name, tracks: trackIds, description: description);
      return PlaylistWrite(created: playlist, added: trackIds.length, requested: trackIds.length);
    } on PartialPlaylistCreate catch (partial) {
      return PlaylistWrite(
        created: partial.playlist,
        added: partial.added,
        requested: trackIds.length,
        failure: partial,
      );
    } catch (failure) {
      return PlaylistWrite(created: null, added: 0, requested: trackIds.length, failure: failure);
    }
  }

  static Future<List<Track>> _search(MusicSource target, String query) async {
    try {
      return (await target.library.search(query)).tracks;
    } catch (_) {
      return const [];
    }
  }
}
