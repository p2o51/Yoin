import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/highlight/lyric_highlight.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_playback_engine.dart';

/// A MusicSource for Home: every list Home reads, each able to fail.
class HomeFakeSource implements MusicSource, MusicLibrary, MusicPlayback {
  HomeFakeSource({
    this.playlists = const [],
    this.starred = const Starred(),
    this.recent = const [],
    this.random = const [],
    this.randomSongs = const [],
    this.albums = const {},
    this.hasRandomSongs = true,
  });

  List<Playlist> playlists;
  Starred starred;
  List<Album> recent;
  List<Album> random;
  List<Track> randomSongs;
  Map<String, Album> albums;
  bool hasRandomSongs;

  /// Method names that throw.
  final Set<String> failing = {};
  final List<String> calls = [];

  Future<T> _call<T>(String name, T value) async {
    calls.add(name);
    if (failing.contains(name)) throw const SourceNetworkException('offline');
    return value;
  }

  @override
  String get provider => MediaId.providerSubsonic;
  @override
  Set<Capability> get capabilities => {Capability.playlistsRead, if (hasRandomSongs) Capability.randomSongs};
  @override
  MusicLibrary get library => this;
  @override
  MusicMetadata get metadata => throw const UnsupportedSourceOperation('metadata');
  @override
  MusicWriteActions get writeActions => throw const UnsupportedSourceOperation('writeActions');
  @override
  MusicPlayback get playback => this;
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => 'https://covers.test/${ref.storageKey}?size=$size';
  @override
  void dispose() {}

  @override
  Future<PlaybackHandle> handleFor(Track track) async => DirectStream('https://stream.test/${track.id.rawId}');
  @override
  Future<void> reportNowPlaying(MediaId trackId) async {}
  @override
  Future<void> submitPlay(MediaId trackId, DateTime playedAt) async {}

  @override
  Future<bool> ping() async => true;
  @override
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0}) => switch (type) {
        AlbumListType.recent => _call('recent', recent.take(size).toList()),
        AlbumListType.random => _call('random', random.take(size).toList()),
        _ => _call('albumList', const <Album>[]),
      };
  @override
  Future<Album?> getAlbum(MediaId id) => _call('getAlbum', albums[id.rawId]);
  @override
  Future<List<ArtistIndex>> getArtists() async => const [];
  @override
  Future<ArtistDetail?> getArtist(MediaId id) async => null;
  @override
  Future<List<Playlist>> getPlaylists() => _call('playlists', playlists);
  @override
  Future<Playlist?> getPlaylist(MediaId id) async => null;
  @override
  Future<Starred> getStarred() => _call('starred', starred);
  @override
  Future<List<Track>> getRandomSongs({int size = 20}) => _call('randomSongs', randomSongs.take(size).toList());
  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async =>
      const SearchResults();
}

/// An ActivityLog in memory; [failReads] makes recent() throw.
class MemoryActivityLog implements ActivityLog {
  MemoryActivityLog([List<ActivityEvent> rows = const []]) : _rows = [...rows];

  final List<ActivityEvent> _rows;
  final _changes = StreamController<List<ActivityEvent>>.broadcast();
  bool failReads = false;

  @override
  Future<void> record(ActivityEvent event) async {
    _rows
      ..removeWhere((r) => r.entityKey == event.entityKey)
      ..insert(0, event);
    _changes.add(List.unmodifiable(_rows));
  }

  @override
  Future<List<ActivityEvent>> recent({int limit = ActivityLog.cap}) async {
    if (failReads) throw StateError('storage');
    return List.unmodifiable(_rows.take(limit));
  }

  @override
  Stream<List<ActivityEvent>> watchRecent({int limit = ActivityLog.cap}) async* {
    yield await recent(limit: limit);
    yield* _changes.stream.map((rows) => List.unmodifiable(rows.take(limit)));
  }
}

final DateTime homeTestNow = DateTime.utc(2026, 11, 13, 9, 30);

/// Everything Home needs, with one Subsonic account unless [withAccount] is false.
/// Lyric Cards' one-off intro is answered unless [lyricIntroSeen] is false.
Future<({ProviderContainer container, InMemoryStore store, FakePlaybackEngine engine})> homeContainer({
  HomeFakeSource? source,
  MemoryActivityLog? log,
  bool withAccount = true,
  bool addProfile = true,
  InMemoryStore? store,
  bool lyricIntroSeen = true,
}) async {
  final values = store ?? InMemoryStore();
  if (lyricIntroSeen) values.values[LyricHighlightStore.introSeenKey] = '1';
  final secrets = InMemoryStore();
  final engine = FakePlaybackEngine();
  final container = ProviderContainer(
    overrides: [
      keyValueStoreProvider.overrideWithValue(values),
      secretStoreProvider.overrideWithValue(secrets),
      playbackEngineProvider.overrideWithValue(engine),
      activeSourceProvider.overrideWith((ref) async => withAccount ? (source ?? HomeFakeSource()) : null),
      // An account switch builds and pings the target's source first.
      musicSourceFactoryProvider.overrideWithValue((_) => HomeFakeSource()),
      activityLogProvider.overrideWith((ref) async => withAccount ? (log ?? MemoryActivityLog()) : null),
      homeClockProvider.overrideWithValue(() => homeTestNow),
      homeActivityDebounceProvider.overrideWithValue(const Duration(milliseconds: 5)),
    ],
  );
  if (withAccount && addProfile) {
    await container.read(profileRepositoryProvider).add(
          provider: MediaId.providerSubsonic,
          displayName: 'Navidrome',
          credentials: const SubsonicCredentials(baseUrl: 'https://music.test', username: 'u', password: 'p'),
        );
  }
  return (container: container, store: values, engine: engine);
}

/// Pumps the event loop until [done] or a bound.
Future<void> settle(bool Function() done, {int rounds = 50}) async {
  for (var i = 0; i < rounds && !done(); i++) {
    await Future<void>.delayed(const Duration(milliseconds: 1));
  }
}

ActivityEvent albumEvent(String id, {int minutesAgo = 5, ActivityActionType action = ActivityActionType.played, String? title}) => ActivityEvent(
      entityType: ActivityEntityType.album,
      actionType: action,
      entityId: id,
      title: title ?? 'Album $id',
      subtitle: 'Artist $id',
      timestampMs: homeTestNow.millisecondsSinceEpoch - minutesAgo * 60000,
    );

ActivityEvent artistEvent(String id, {int minutesAgo = 5}) => ActivityEvent(
      entityType: ActivityEntityType.artist,
      actionType: ActivityActionType.visited,
      entityId: id,
      title: 'Artist $id',
      timestampMs: homeTestNow.millisecondsSinceEpoch - minutesAgo * 60000,
    );

ActivityEvent playlistEvent(String id, {int minutesAgo = 5}) => ActivityEvent(
      entityType: ActivityEntityType.playlist,
      actionType: ActivityActionType.played,
      entityId: id,
      title: 'Playlist $id',
      subtitle: 'owner',
      timestampMs: homeTestNow.millisecondsSinceEpoch - minutesAgo * 60000,
    );

ActivityEvent songEvent(String id, {int minutesAgo = 5}) => ActivityEvent(
      entityType: ActivityEntityType.song,
      actionType: ActivityActionType.played,
      entityId: id,
      songId: id,
      title: 'Song $id',
      timestampMs: homeTestNow.millisecondsSinceEpoch - minutesAgo * 60000,
    );

/// [starred]: when the user starred it (Recently Added sorts by it).
Album album(String id, {String? starred, CoverRef? cover}) => Album(
  id: MediaId.subsonic(id),
  name: 'Album $id',
  artist: 'Artist',
  coverArt: cover ?? CoverSourceRelative(id),
  isStarred: starred != null,
  starredAt: starred,
);

/// [starred]: when the user starred it (Recently Added sorts by it).
Track track(String id, {String? starred}) => Track(
  id: MediaId.subsonic(id),
  title: 'Song $id',
  artist: 'Artist',
  albumId: MediaId.subsonic('al-$id'),
  isStarred: starred != null,
  starredAt: starred,
);

Playlist playlist(String id, {int songs = 3}) =>
    Playlist(id: MediaId.subsonic(id), name: 'Playlist $id', owner: 'me', coverArt: CoverSourceRelative('pl-$id'), songCount: songs);
