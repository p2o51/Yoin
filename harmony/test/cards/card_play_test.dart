import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/cards/memory_card.dart';
import 'package:yoin/cards/play_card.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/cards/cards_channel.dart';
import 'package:yoin/platform/playback/playback_engine.dart' show PlaybackPhase;
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_context.dart';

import '../support/fake_music_source.dart';

final _albumId = MediaId.subsonic('al-1');
final _playlistId = MediaId.subsonic('pl-1');
final _track = Track(id: MediaId.subsonic('t-1'), title: 'One');

class _CardSource extends FakeMusicSource {
  _CardSource({this.album, this.playlist, this.readError});

  final Album? album;
  final Playlist? playlist;
  final Object? readError;

  @override
  Future<Album?> getAlbum(MediaId id) async {
    if (readError != null) throw readError!;
    return album?.id == id ? album : null;
  }

  @override
  Future<Playlist?> getPlaylist(MediaId id) async {
    if (readError != null) throw readError!;
    return playlist?.id == id ? playlist : null;
  }
}

/// A playback that answers the card's wait from [states] (the controller's states, in order).
final class _Playback {
  _Playback(this.states);

  final List<NowPlayingState> states;
  final List<({List<Track> tracks, PlaybackContext context})> plays = [];

  Future<void> play(List<Track> tracks, PlaybackContext context) async => plays.add((tracks: tracks, context: context));

  Future<NowPlayingState?> awaitState(bool Function(NowPlayingState state) test, Duration timeout) async {
    for (final state in states) {
      if (test(state)) return state;
    }
    return null;
  }
}

/// The card host: keeps the tap handler and the pages asked for.
final class _Host implements CardsHost {
  void Function(CardAction action)? handler;
  final List<String> opened = [];

  @override
  Future<void> listen(void Function(CardAction action) handler) async => this.handler = handler;

  @override
  Future<void> openDetail(String entity, String id) async => opened.add('$entity $id');

  @override
  Future<void> publishMemory(List<MemoryCardItem> items) async {}

  @override
  Future<void> publishPlay(
    List<PlayCardItem> items, {
    required Uint8List glyphLight,
    required Uint8List glyphDark,
  }) async {}
}

/// The Play card's publisher with no account, listening to [host].
ProviderContainer _publisherWithoutAccount(_Host host) {
  final container = ProviderContainer(
    overrides: [
      cardsHostProvider.overrideWithValue(host),
      activeSourceProvider.overrideWith((ref) async => null),
      activityLogProvider.overrideWith((ref) async => null),
      memoryCardPublisherProvider.overrideWith((ref) => MemoryCardPublisher(ref, host)),
    ],
  );
  container.read(playCardPublisherProvider);
  return container;
}

Future<bool> _start(
  String entity,
  MediaId id, {
  MusicSource? source,
  Future<MusicSource?> Function()? activeSource,
  required _Playback playback,
}) => startCardPlay(
  entity: entity,
  id: id,
  activeSource: activeSource ?? () async => source,
  play: playback.play,
  awaitState: playback.awaitState,
  sourceTimeout: const Duration(milliseconds: 50),
);

NowPlayingState _albumState({bool playing = false, PlaybackPhase phase = PlaybackPhase.loading, MediaId? albumId}) =>
    NowPlayingState(
      queue: [_track],
      index: 0,
      isPlaying: playing,
      phase: phase,
      context: AlbumPlaybackContext(albumId: albumId ?? _albumId, name: 'A'),
    );

void main() {
  final album = Album(id: _albumId, name: 'A', tracks: [_track]);
  final playlist = Playlist(id: _playlistId, name: 'P', tracks: [_track]);

  test('should_reportStarted_when_theAlbumsSongPlays', () async {
    final playback = _Playback([_albumState(), _albumState(playing: true, phase: PlaybackPhase.ready)]);
    final started = await _start(
      'album',
      _albumId,
      source: _CardSource(album: album),
      playback: playback,
    );
    expect(started, isTrue);
    expect(playback.plays.single.context, isA<AlbumPlaybackContext>());
    expect(playback.plays.single.tracks, [_track]);
  });

  test('should_playThePlaylist_when_theCardHoldsAPlaylist', () async {
    final playback = _Playback([
      NowPlayingState(
        queue: [_track],
        index: 0,
        isPlaying: true,
        context: PlaylistPlaybackContext(playlistId: _playlistId, name: 'P'),
      ),
    ]);
    final started = await _start(
      'playlist',
      _playlistId,
      source: _CardSource(playlist: playlist),
      playback: playback,
    );
    expect(started, isTrue);
    expect(playback.plays.single.context, isA<PlaylistPlaybackContext>());
  });

  test('should_fail_when_noAccountIsActive', () async {
    final playback = _Playback(const []);
    expect(await _start('album', _albumId, playback: playback), isFalse);
    expect(playback.plays, isEmpty);
  });

  test('should_fail_when_theAccountNeverLoads', () async {
    final playback = _Playback(const []);
    final never = Completer<MusicSource?>();
    expect(await _start('album', _albumId, activeSource: () => never.future, playback: playback), isFalse);
    expect(playback.plays, isEmpty);
  });

  test('should_fail_when_theCardIsAnotherServicesAlbum', () async {
    final playback = _Playback(const []);
    final appleId = MediaId('applemusic', 'al-1');
    expect(
      await _start(
        'album',
        appleId,
        source: _CardSource(album: album),
        playback: playback,
      ),
      isFalse,
    );
    expect(playback.plays, isEmpty);
  });

  test('should_fail_when_theAlbumCantBeRead', () async {
    final playback = _Playback(const []);
    expect(await _start('album', _albumId, source: _CardSource(), playback: playback), isFalse);
    expect(
      await _start(
        'album',
        _albumId,
        source: _CardSource(readError: StateError('offline')),
        playback: playback,
      ),
      isFalse,
    );
    expect(playback.plays, isEmpty);
  });

  test('should_fail_when_theAlbumHasNoSongs', () async {
    final playback = _Playback(const []);
    final empty = Album(id: _albumId, name: 'A');
    expect(
      await _start(
        'album',
        _albumId,
        source: _CardSource(album: empty),
        playback: playback,
      ),
      isFalse,
    );
    expect(playback.plays, isEmpty);
  });

  test('should_fail_when_thePlayEndsInAnError', () async {
    final playback = _Playback([_albumState(phase: PlaybackPhase.error)]);
    expect(
      await _start(
        'album',
        _albumId,
        source: _CardSource(album: album),
        playback: playback,
      ),
      isFalse,
    );
  });

  test('should_fail_when_onlySomethingElseIsPlaying', () async {
    final other = MediaId.subsonic('al-2');
    final playback = _Playback([_albumState(playing: true, albumId: other)]);
    expect(
      await _start(
        'album',
        _albumId,
        source: _CardSource(album: album),
        playback: playback,
      ),
      isFalse,
    );
  });

  test('should_failWithoutPlaying_when_theCardHoldsAnArtist', () async {
    final playback = _Playback(const []);
    final artistId = MediaId.subsonic('ar-1');
    expect(await _start('artist', artistId, source: _CardSource(), playback: playback), isFalse);
    expect(playback.plays, isEmpty);
  });

  group('publisher', () {
    test('should_openTheAlbumsPage_when_aCardPlayCantStart', () async {
      final host = _Host();
      final container = _publisherWithoutAccount(host);
      addTearDown(container.dispose);
      await pumpEventQueue();
      host.handler!(const CardPlay('album', 'subsonic:al-1'));
      await pumpEventQueue();
      expect(host.opened, ['album subsonic:al-1']);
    });

    test('should_openTheArtistsPage_when_theCardPlaysAnArtist', () async {
      final host = _Host();
      final container = _publisherWithoutAccount(host);
      addTearDown(container.dispose);
      await pumpEventQueue();
      host.handler!(const CardPlay('artist', 'subsonic:ar-1'));
      await pumpEventQueue();
      expect(host.opened, ['artist subsonic:ar-1']);
    });

    test('should_openNothing_when_theCardsIdIsUnreadable', () async {
      final host = _Host();
      final container = _publisherWithoutAccount(host);
      addTearDown(container.dispose);
      await pumpEventQueue();
      host.handler!(const CardPlay('album', 'not-an-id'));
      await pumpEventQueue();
      expect(host.opened, isEmpty);
    });
  });

  test('should_sendEntityAndId_when_openDetailIsCalled', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel('yoin/cards');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null),
    );
    await const ChannelCardsHost().openDetail('playlist', 'subsonic:pl-1');
    expect(calls.single.method, 'openDetail');
    expect(calls.single.arguments, {'entity': 'playlist', 'id': 'subsonic:pl-1'});
  });
}
