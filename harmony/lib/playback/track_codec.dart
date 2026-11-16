/// Track ⇄ JSON for [EngineItem.extra]: the native host echoes it back so a
/// re-created UI (ability re-created while the process played on) rebuilds
/// the queue with full tracks (Android keeps the Track in the player item's
/// extras for the same reason). Only the playback chain reads it.
library;

import 'dart:convert';

import '../data/model/model.dart';
import '../platform/playback/playback_engine.dart';
import 'playback_context.dart';

abstract final class TrackCodec {
  static String encode(Track t) => jsonEncode({
        'id': t.id.toString(),
        if (t.title != null) 'title': t.title,
        if (t.artist != null) 'artist': t.artist,
        if (t.artistId != null) 'artistId': t.artistId.toString(),
        if (t.album != null) 'album': t.album,
        if (t.albumId != null) 'albumId': t.albumId.toString(),
        if (t.coverArt != null) 'cover': t.coverArt!.storageKey,
        if (t.durationSec != null) 'dur': t.durationSec,
        if (t.trackNumber != null) 'no': t.trackNumber,
        if (t.discNumber != null) 'disc': t.discNumber,
        if (t.year != null) 'year': t.year,
        if (t.genre != null) 'genre': t.genre,
        if (t.userRating != null) 'rating': t.userRating,
        if (t.isStarred) 'starred': true,
        if (t.starredAt != null) 'starredAt': t.starredAt,
        if (t.addedAt != null) 'added': t.addedAt,
        if (t.audioFormat != null) 'fmt': _formatToJson(t.audioFormat!),
        if (t.replayGain != null) 'rg': t.replayGain!.toJson(),
      });

  /// null when [raw] isn't a track this codec wrote.
  static Track? decode(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    try {
      final m = jsonDecode(raw);
      if (m is! Map) return null;
      final id = MediaId.tryParse(m['id'] as String?);
      if (id == null) return null;
      return Track(
        id: id,
        title: m['title'] as String?,
        artist: m['artist'] as String?,
        artistId: MediaId.tryParse(m['artistId'] as String?),
        album: m['album'] as String?,
        albumId: MediaId.tryParse(m['albumId'] as String?),
        coverArt: CoverRef.fromStorageKey(m['cover'] as String?),
        durationSec: m['dur'] as int?,
        trackNumber: m['no'] as int?,
        discNumber: m['disc'] as int?,
        year: m['year'] as int?,
        genre: m['genre'] as String?,
        userRating: m['rating'] as int?,
        isStarred: m['starred'] == true,
        starredAt: m['starredAt'] as String?,
        addedAt: m['added'] as String?,
        audioFormat: _formatFromJson(m['fmt']),
        replayGain: ReplayGain.fromJson(m['rg']),
      );
    } on Object {
      return null;
    }
  }

  static Map<String, Object> _formatToJson(AudioFormat f) => {
        'sfx': ?f.suffix,
        'ct': ?f.contentType,
        'kbps': ?f.bitRateKbps,
        'hz': ?f.sampleRateHz,
        'depth': ?f.bitDepth,
        'ch': ?f.channels,
      };

  static AudioFormat? _formatFromJson(Object? json) {
    if (json is! Map) return null;
    return AudioFormat(
      suffix: json['sfx'] as String?,
      contentType: json['ct'] as String?,
      bitRateKbps: json['kbps'] as int?,
      sampleRateHz: json['hz'] as int?,
      bitDepth: json['depth'] as int?,
      channels: json['ch'] as int?,
    );
  }

  /// The best Track for a native item: its extra, else a slim one from the
  /// item's metadata; null when the id isn't a MediaId.
  static Track? fromItem(EngineItem item) {
    final full = decode(item.extra);
    if (full != null) return full;
    final id = MediaId.tryParse(item.id);
    if (id == null) return null;
    final ms = item.durationMs;
    return Track(
      id: id,
      title: item.title.isEmpty ? null : item.title,
      artist: item.artist,
      album: item.album,
      durationSec: ms == null ? null : ms ~/ 1000,
    );
  }
}

/// [PlaybackContext] ⇄ JSON, carried as `ctx` inside every item's extra so a
/// rehydrated queue keeps its "PLAYING FROM …" label.
abstract final class PlaybackContextCodec {
  /// [extra] (a [TrackCodec] JSON) with [context] added; unchanged when
  /// there is no context or [extra] isn't ours.
  static String? attach(String? extra, PlaybackContext context) {
    final ctx = _encode(context);
    if (extra == null || ctx == null) return extra;
    try {
      final m = jsonDecode(extra);
      if (m is! Map) return extra;
      return jsonEncode({...m, 'ctx': ctx});
    } on Object {
      return extra;
    }
  }

  /// The context stored in [extra]; [NoPlaybackContext] when none.
  static PlaybackContext read(String? extra) {
    if (extra == null || extra.isEmpty) return const NoPlaybackContext();
    try {
      final m = jsonDecode(extra);
      final c = m is Map ? m['ctx'] : null;
      if (c is! Map) return const NoPlaybackContext();
      final name = c['name'] as String?;
      final cover = CoverRef.fromStorageKey(c['cover'] as String?);
      switch (c['kind']) {
        case 'album':
          final id = MediaId.tryParse(c['id'] as String?);
          if (id == null || name == null) break;
          return AlbumPlaybackContext(
            albumId: id,
            name: name,
            artist: c['artist'] as String?,
            artistId: MediaId.tryParse(c['artistId'] as String?),
            cover: cover,
          );
        case 'artist':
          final id = MediaId.tryParse(c['id'] as String?);
          if (id == null || name == null) break;
          return ArtistPlaybackContext(artistId: id, name: name, cover: cover);
        case 'playlist':
          final id = MediaId.tryParse(c['id'] as String?);
          if (id == null || name == null) break;
          return PlaylistPlaybackContext(playlistId: id, name: name, owner: c['owner'] as String?, cover: cover);
        case 'favorites':
          return const FavoritesPlaybackContext();
      }
    } on Object {
      // Not ours: no label.
    }
    return const NoPlaybackContext();
  }

  static Map<String, Object?>? _encode(PlaybackContext c) => switch (c) {
        NoPlaybackContext() => null,
        AlbumPlaybackContext() => {
            'kind': 'album',
            'id': c.albumId.toString(),
            'name': c.name,
            if (c.artist != null) 'artist': c.artist,
            if (c.artistId != null) 'artistId': c.artistId.toString(),
            if (c.cover != null) 'cover': c.cover!.storageKey,
          },
        ArtistPlaybackContext() => {
            'kind': 'artist',
            'id': c.artistId.toString(),
            'name': c.name,
            if (c.cover != null) 'cover': c.cover!.storageKey,
          },
        PlaylistPlaybackContext() => {
            'kind': 'playlist',
            'id': c.playlistId.toString(),
            'name': c.name,
            if (c.owner != null) 'owner': c.owner,
            if (c.cover != null) 'cover': c.cover!.storageKey,
          },
        FavoritesPlaybackContext() => {'kind': 'favorites'},
      };
}
