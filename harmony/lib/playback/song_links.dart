/// A song's album and artist for one that came without them (Android
/// SongLinks / YoinRepository.songLinks / MusicMetadata.songLinks).
///
/// Home's cards rebuild songs from local history (noted songs, Lyric Cards,
/// Jump Back In's cached pools, activity songs) and a queue handed back from
/// the native host can be slim too: they carry no artist and sometimes no
/// album, so Now Playing's title and artist, and the song menu's Go to album /
/// Go to artist, would open nothing. One read of the song from its service
/// fills them (Android 140e8a944: Subsonic and Spotify answer with getTrack,
/// Apple Music with the song's albums / artists relationships, which its
/// getTrack here already includes).
library;

import 'dart:async';
import 'dart:collection';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';

/// The album and artist a song belongs to; either is null when there is none to open.
@immutable
final class SongLinks {
  const SongLinks({this.albumId, this.artistId});

  /// [track]'s own.
  SongLinks.of(Track track) : this(albumId: track.albumId, artistId: track.artistId);

  final MediaId? albumId;
  final MediaId? artistId;

  bool get complete => albumId != null && artistId != null;

  /// Each of these that is missing taken from [other].
  SongLinks orElse(SongLinks? other) =>
      SongLinks(albumId: albumId ?? other?.albumId, artistId: artistId ?? other?.artistId);

  /// [track] with what it lacks filled from these; [track] itself when nothing changes.
  Track applyTo(Track track) {
    final album = track.albumId == null ? albumId : null;
    final artist = track.artistId == null ? artistId : null;
    if (album == null && artist == null) return track;
    return track.copyWith(albumId: album, artistId: artist);
  }

  @override
  bool operator ==(Object other) => other is SongLinks && other.albumId == albumId && other.artistId == artistId;

  @override
  int get hashCode => Object.hash(albumId, artistId);

  @override
  String toString() => 'SongLinks($albumId, $artistId)';
}

/// Asks the account in use for the links of songs that lack them, once per
/// song (answers are kept; a failed read is asked again next time).
class SongLinksResolver {
  SongLinksResolver(this._source, {this.capacity = 200});

  final MusicSource? _source;

  /// Songs whose answer is kept.
  final int capacity;

  /// Reads under way, and the answers in (oldest first; a song the service doesn't know is a null answer).
  final Map<MediaId, Future<SongLinks?>> _asked = {};
  final LinkedHashMap<MediaId, SongLinks?> _answers = LinkedHashMap();

  /// [track]'s links: its own, completed by its service where it lacks one
  /// (YoinRepository.songLinks). Only the account in use's service is asked,
  /// and only about its own songs.
  Future<SongLinks> linksFor(Track track) async {
    final own = SongLinks.of(track);
    if (own.complete) return own;
    final source = _source;
    if (source == null || source.provider != track.id.provider || track.id.rawId.trim().isEmpty) return own;
    final MusicTrackLookup? lookup;
    try {
      lookup = source.trackLookup;
    } on Object {
      return own; // A source without a library (test fakes, a signed-out one).
    }
    if (lookup == null) return own;
    if (_answers.containsKey(track.id)) return own.orElse(_answers[track.id]);
    final pending = _asked[track.id] ??= _ask(lookup, track.id);
    return own.orElse(await pending);
  }

  Future<SongLinks?> _ask(MusicTrackLookup lookup, MediaId id) async {
    try {
      final song = await lookup.getTrack(id);
      final links = song == null ? null : SongLinks.of(song);
      _answers[id] = links;
      while (_answers.length > capacity) {
        _answers.remove(_answers.keys.first);
      }
      return links;
    } catch (e) {
      debugPrint('song links not read: ${e.runtimeType}');
      return null;
    } finally {
      unawaited(Future<void>.microtask(() => _asked.remove(id)));
    }
  }
}

/// The account in use's resolver; a new account starts a fresh one.
final songLinksResolverProvider = Provider<SongLinksResolver>(
  (ref) => SongLinksResolver(ref.watch(activeSourceProvider).value),
);
