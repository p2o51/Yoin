/// Home's feed ⇄ its snapshot (Android ui/home/HomeSnapshotMapping.kt). Out:
/// every field the feed shows, each cover as its storage key — a key that
/// carries credentials is never written. In: the same feed; covers resolve
/// through the account's source as it comes up (CoverArt watches it), so
/// until then a card shows its placeholder. An entry that doesn't read back
/// (an unknown kind, a malformed id) drops alone.
library;

import '../data/activity/activity.dart';
import '../data/cache/detail_cache_json.dart';
import '../data/home/home_snapshot_store.dart';
import '../data/model/model.dart';
import 'home_ui_state.dart';

/// This feed as its snapshot keeps it.
Map<String, Object?> homeSnapshotFeed(HomeContent c) => {
  'activities': [for (final e in c.activities) _event(e)],
  'hero': ?_footnote(c.heroFootnote),
  'grid': [for (final card in c.grid) ?_card(card)],
  HomeFeedKeys.recentlyAddedTracks: [for (final t in c.recentlyAddedTracks) _track(t)],
  HomeFeedKeys.recentlyAddedAlbums: [for (final a in c.recentlyAddedAlbums) _album(a)],
  HomeFeedKeys.recentlyPlayed: [for (final a in c.recentlyPlayedAll) _album(a)],
  HomeFeedKeys.playlists: [for (final p in c.playlists) _playlist(p)],
};

/// The feed a snapshot holds, as [scopeKey]'s Content (time-ago against [nowMs]).
HomeContent homeContentFromSnapshot(
  Map<String, Object?> feed, {
  required String scopeKey,
  required String provider,
  String? accountTitle,
  required int nowMs,
}) => HomeContent(
  scopeKey: scopeKey,
  provider: provider,
  accountTitle: accountTitle,
  activities: _list(feed['activities'], _eventFrom),
  activitiesAtMs: nowMs,
  heroFootnote: _footnoteFrom(feed['hero']),
  grid: _list(feed['grid'], _cardFrom),
  recentlyAddedTracks: _list(feed[HomeFeedKeys.recentlyAddedTracks], DetailCacheJson.trackFrom),
  recentlyAddedAlbums: _list(feed[HomeFeedKeys.recentlyAddedAlbums], DetailCacheJson.albumFrom),
  recentlyPlayedAll: _list(feed[HomeFeedKeys.recentlyPlayed], DetailCacheJson.albumFrom),
  playlists: _list(feed[HomeFeedKeys.playlists], DetailCacheJson.playlistFrom),
);

// ---------------------------------------------------------------- out

String? _cover(CoverRef? c) => credentialFreeCoverKey(c?.storageKey);

Map<String, Object?> _track(Track t) {
  final json = DetailCacheJson.track(t);
  final cover = _cover(t.coverArt);
  return cover == null ? (json..remove('c')) : (json..['c'] = cover);
}

/// An album as Home shows it: no track list.
Map<String, Object?> _album(Album a) {
  final json = DetailCacheJson.album(
    Album(
      id: a.id,
      name: a.name,
      artist: a.artist,
      artistId: a.artistId,
      coverArt: a.coverArt,
      songCount: a.songCount,
      durationSec: a.durationSec,
      year: a.year,
      genre: a.genre,
      isStarred: a.isStarred,
      starredAt: a.starredAt,
      addedAt: a.addedAt,
      releaseType: a.releaseType,
      version: a.version,
      contentRating: a.contentRating,
    ),
  );
  final cover = _cover(a.coverArt);
  return cover == null ? (json..remove('c')) : (json..['c'] = cover);
}

/// A playlist as Home shows it: no track list.
Map<String, Object?> _playlist(Playlist p) {
  final json = DetailCacheJson.playlist(
    Playlist(
      id: p.id,
      name: p.name,
      owner: p.owner,
      coverArt: p.coverArt,
      songCount: p.songCount,
      durationSec: p.durationSec,
      canWrite: p.canWrite,
      comment: p.comment,
    ),
  );
  final cover = _cover(p.coverArt);
  return cover == null ? (json..remove('c')) : (json..['c'] = cover);
}

Map<String, Object?> _event(ActivityEvent e) {
  final json = e.toJson();
  json['c'] = credentialFreeCoverKey(e.coverArtKey);
  return json;
}

Map<String, Object?>? _footnote(HeroFootnote? f) =>
    f == null ? null : {'y': ?f.year, 'sc': ?f.songCount, 'm': ?f.minutes, 'v': ?f.version};

Map<String, Object?>? _card(HomeWidgetCard c) {
  final target = switch (c.target) {
    HomeOpenAlbum(:final album) => {'k': 'album', 'album': _album(album)},
    HomeOpenPlaylist(:final playlist) => {'k': 'playlist', 'playlist': _playlist(playlist)},
    HomeOpenArtist(:final id, :final name) => {'k': 'artist', 'id': id.toString(), 'n': name},
    HomePlaySong(:final track) => {'k': 'song', 'song': _track(track)},
    HomeOpenMemory(:final sessionId) => {'k': 'memory', 'session': sessionId},
  };
  final s = c.signal;
  return {
    'id': c.stableId,
    'kind': c.kind.name,
    'ti': c.title,
    'st': c.subtitle,
    'c': ?_cover(c.cover),
    'v': ?c.version,
    't': target,
    if (s != null)
      'sig': {
        'score': ?s.scoreText,
        'date': ?s.basisDateMs,
        if (s.basisTracks != null) 'tracks': [s.basisTracks!.$1, s.basisTracks!.$2],
        'cm': ?s.comment,
        if (s.commentIsHeadline) 'hl': true,
      },
  };
}

// ---------------------------------------------------------------- in

List<T> _list<T>(Object? json, T? Function(Object?) read) => [
  if (json is List)
    for (final e in json) ?_orNull(() => read(e)),
];

T? _orNull<T>(T? Function() read) {
  try {
    return read();
  } on Object {
    return null;
  }
}

ActivityEvent? _eventFrom(Object? json) => json is Map ? ActivityEvent.fromJson(json.cast<String, Object?>()) : null;

HeroFootnote? _footnoteFrom(Object? json) {
  if (json is! Map) return null;
  final f = HeroFootnote(
    year: json['y'] as int?,
    songCount: json['sc'] as int?,
    minutes: json['m'] as int?,
    version: json['v'] as String?,
  );
  return f.isEmpty ? null : f;
}

HomeWidgetCard? _cardFrom(Object? json) {
  if (json is! Map) return null;
  final id = json['id'], title = json['ti'], subtitle = json['st'], t = json['t'];
  final kind = HomeCardKind.values.where((k) => k.name == json['kind']).firstOrNull;
  if (id is! String || title is! String || subtitle is! String || t is! Map || kind == null) return null;
  final HomeTarget? target = switch (t['k']) {
    'album' => switch (DetailCacheJson.albumFrom(t['album'])) {
      final Album a => HomeOpenAlbum(a),
      null => null,
    },
    'playlist' => switch (DetailCacheJson.playlistFrom(t['playlist'])) {
      final Playlist p => HomeOpenPlaylist(p),
      null => null,
    },
    'artist' => switch (MediaId.tryParse(t['id'] as String?)) {
      final MediaId a => HomeOpenArtist(a, (t['n'] as String?) ?? ''),
      null => null,
    },
    'song' => switch (DetailCacheJson.trackFrom(t['song'])) {
      final Track s => HomePlaySong(s),
      null => null,
    },
    'memory' => t['session'] is int ? HomeOpenMemory(t['session'] as int) : null,
    _ => null,
  };
  if (target == null) return null;
  final sig = json['sig'];
  JbiSignal? signal;
  if (sig is Map) {
    final tracks = sig['tracks'];
    signal = JbiSignal(
      scoreText: sig['score'] as String?,
      basisDateMs: sig['date'] as int?,
      basisTracks: tracks is List && tracks.length == 2 && tracks[0] is int && tracks[1] is int
          ? (tracks[0] as int, tracks[1] as int)
          : null,
      comment: sig['cm'] as String?,
      commentIsHeadline: sig['hl'] == true,
    );
  }
  return HomeWidgetCard(
    stableId: id,
    kind: kind,
    title: title,
    subtitle: subtitle,
    target: target,
    cover: CoverRef.fromStorageKey(json['c'] as String?),
    version: json['v'] as String?,
    signal: signal,
  );
}
