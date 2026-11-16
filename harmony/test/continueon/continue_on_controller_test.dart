import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/continueon/continue_on_controller.dart';
import 'package:yoin/continueon/continue_on_cursor.dart';
import 'package:yoin/continueon/continue_on_pause_channel.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/continueon/continue_on_host.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/play_mode.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/playhead.dart';

/// Android ContinueOnTakeOverTest, with the player, the accounts and the
/// platform faked: what the card says about a received hand-off, what
/// "Continue here" starts, and what this device hands over.
void main() {
  const home = Profile(id: 'p-home', provider: MediaId.providerSubsonic, displayName: 'Home');
  const work = Profile(id: 'p-work', provider: MediaId.providerSubsonic, displayName: 'Work');
  const pauseChannel = ContinueOnPauseChannel(hosts: ['192.168.1.20'], port: 40123, token: 'tok');

  late _FakeHost host;
  late _FakePlayer player;
  late _FakeAccounts accounts;
  late List<String> pauseRequests;
  late PauseAnswer pauseAnswer;
  late _FakeSource homeSource;
  late _FakeSource workSource;
  late ProviderContainer container;
  late bool offered;

  ContinueOnController controller() => container.read(continueOnProvider.notifier);
  ContinueOnPrompt? prompt() => container.read(continueOnProvider);

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  ContinueOnCursor cursor({
    String provider = MediaId.providerSubsonic,
    String? accountKey = 'k-home',
    required bool playing,
    ContinueOnPauseChannel? pause,
    ContinueOnContext? context,
  }) => ContinueOnCursor(
    provider: provider,
    accountKey: accountKey,
    trackId: 't2',
    title: 'Song',
    artist: 'Artist',
    album: 'Album',
    cover: 'al-1',
    queueIds: const ['t1', 't2', 't3'],
    index: 1,
    positionMs: 61000,
    capturedAtMs: 0,
    playing: playing,
    repeatMode: 0,
    shuffle: false,
    context: context,
    deviceName: 'HUAWEI Mate 70 Air',
    pause: pause,
  );

  setUp(() {
    host = _FakeHost();
    homeSource = _FakeSource('home');
    workSource = _FakeSource('work');
    player = _FakePlayer();
    accounts = _FakeAccounts(
      profiles: const [home, work],
      keys: const {'p-home': 'k-home', 'p-work': 'k-work'},
      sources: {'p-home': homeSource, 'p-work': workSource},
      active: 'p-home',
    );
    pauseRequests = [];
    pauseAnswer = const PauseAnswerUnreachable();
    offered = false;
    container = ProviderContainer(
      overrides: [
        continueOnHostProvider.overrideWithValue(host),
        continueOnPlayerProvider.overrideWithValue(player),
        continueOnAccountsProvider.overrideWithValue(accounts),
        continueOnAskToPauseProvider.overrideWithValue((channel, trackId) async {
          pauseRequests.add(trackId);
          return pauseAnswer;
        }),
        continueOnOfferedProvider.overrideWith((ref) => offered),
        continueOnHandoffShapeProvider.overrideWithValue(0),
        continueOnActiveSourceProvider.overrideWithValue(null),
        playbackClockProvider.overrideWithValue(_FixedClock(0)),
        continueOnStillPlayingNoteProvider.overrideWithValue(const Duration(milliseconds: 50)),
      ],
    );
    container.listen(continueOnProvider, (_, _) {});
  });

  tearDown(() => container.dispose());

  group('taking over', () {
    test('should_loadThePausedSongWhereItStopped_when_continueHereIsTapped', () async {
      homeSource.has(['t2', 't3']);
      host.receive(cursor(playing: false).toEntries());
      await settle();
      expect(prompt()?.account, const ContinueOnAccountFound('p-home', 'Home'));
      expect(prompt()?.coverUrl, 'https://covers.test/home/al-1?size=300');

      controller().confirm();
      expect(prompt()?.stage, ContinueOnStage.starting);
      await settle();

      // The song and what came after it, read again here; nothing plays by itself.
      final start = player.starts.single;
      expect(start.ids, ['t2', 't3']);
      expect(start.startIndex, 0);
      expect(start.context, isA<NoPlaybackContext>());
      expect(start.startPositionMs, 61000);
      expect(start.startPaused, isTrue);
      expect(player.modes, [PlayMode.repeatOff]);
      expect(accounts.switches, isEmpty);
      expect(pauseRequests, isEmpty);
      expect(player.nowPlayingOpened, 1);
      expect(prompt(), isNull);
    });

    test('should_switchToTheAccountTheSongCameFrom_when_anotherOneIsInUse', () async {
      workSource.has(['t2', 't3']);
      host.receive(cursor(accountKey: 'k-work', playing: false).toEntries());
      await settle();
      expect(prompt()?.account, const ContinueOnAccountFound('p-work', 'Work'));
      controller().confirm();
      await settle();
      expect(accounts.switches, ['p-work']);
      expect(player.starts.single.ids, ['t2', 't3']);
      expect(player.starts.single.startPositionMs, 61000);
    });

    test('should_startNothing_when_theAccountChangesWhileTheSongsAreRead', () async {
      // The person switches accounts while this one's songs are still being read.
      homeSource.has(['t2', 't3']);
      homeSource.onRead = () => accounts.active = 'p-work';
      host.receive(cursor(playing: false).toEntries());
      await settle();
      controller().confirm();
      await settle();
      expect(player.starts, isEmpty);
      expect(prompt()?.stage, ContinueOnStage.failed);
      expect(player.nowPlayingOpened, 0);
    });

    test('should_sayTheOtherDeviceMayStillPlay_when_itLeftNoWayToPauseIt', () async {
      homeSource.has(['t2', 't3']);
      // Playing there, and no pause channel in the cursor (the other device had no local address).
      host.receive(cursor(playing: true).toEntries());
      await settle();
      controller().confirm();
      await settle();
      expect(player.starts.single.startPaused, isFalse);
      expect(prompt()?.stage, ContinueOnStage.otherStillPlaying);
      // The note leaves by itself.
      await Future<void>.delayed(const Duration(milliseconds: 120));
      expect(prompt(), isNull);
    });

    test('should_startTheSongTheOtherDeviceIsOnByNow_when_itHasMovedOnSinceTheCursorWasRead', () async {
      homeSource.has(['t2', 't3']);
      // The cursor named t2; by the tap the other device plays t3.
      pauseAnswer = const PauseAnswerPaused(positionMs: 12000, trackId: 't3');
      host.receive(cursor(playing: true, pause: pauseChannel).toEntries());
      await settle();
      controller().confirm();
      await settle();
      expect(pauseRequests, ['t2']);
      final start = player.starts.single;
      expect(start.ids, ['t3']);
      expect(start.startPositionMs, 12000);
      expect(start.startPaused, isFalse);
      // The other device paused: nothing more to say.
      expect(prompt(), isNull);
    });

    test('should_findTheSongInItsAlbum_when_theOtherDeviceHasMovedOnInIt', () async {
      homeSource.albums['al-1'] = Album(
        id: MediaId.subsonic('al-1'),
        name: 'Album',
        artist: 'Artist',
        coverArt: const CoverSourceRelative('al-1'),
        tracks: [
          for (final id in ['t1', 't2', 't3', 't4']) _track(id),
        ],
      );
      pauseAnswer = const PauseAnswerPaused(positionMs: 5000, trackId: 't4');
      final fromAlbum = cursor(
        playing: true,
        pause: pauseChannel,
        context: const ContinueOnContext(ContinueOnContextKind.album, 'al-1', 'Album'),
      );
      host.receive(fromAlbum.toEntries());
      await settle();
      controller().confirm();
      await settle();
      final start = player.starts.single;
      expect(start.ids, ['t1', 't2', 't3', 't4']);
      expect(start.startIndex, 3);
      expect(start.startPositionMs, 5000);
      final context = start.context as AlbumPlaybackContext;
      expect(context.albumId, MediaId.subsonic('al-1'));
      expect(context.name, 'Album');
      expect(context.artist, 'Artist');
      expect(homeSource.trackReads, isEmpty);
    });

    test('should_startTheCursorsSong_when_theOneTheOtherDeviceIsOnCannotBeReadHere', () async {
      homeSource.has(['t2', 't3']);
      pauseAnswer = const PauseAnswerPaused(positionMs: 12000, trackId: 'gone');
      host.receive(cursor(playing: true, pause: pauseChannel).toEntries());
      await settle();
      controller().confirm();
      await settle();
      // Not the other song's position: the cursor's song, where the card showed it.
      final start = player.starts.single;
      expect(start.ids, ['t2', 't3']);
      expect(start.startPositionMs, 61000);
      expect(start.startPaused, isFalse);
    });

    test('should_leaveTheOtherDevicePlaying_when_theAccountCannotBeSwitchedTo', () async {
      workSource.has(['t2', 't3']);
      accounts.switchFails = true;
      host.receive(cursor(accountKey: 'k-work', playing: true, pause: pauseChannel).toEntries());
      await settle();
      controller().confirm();
      await settle();
      expect(prompt()?.stage, ContinueOnStage.failed);
      // Nothing was taken over, so the other device was never asked to stop.
      expect(pauseRequests, isEmpty);
      expect(player.starts, isEmpty);
    });

    test('should_sayTheAccountIsMissing_when_noAccountOfThatServiceIsHere', () async {
      host.receive(cursor(provider: MediaId.providerAppleMusic, playing: false).toEntries());
      await settle();
      expect(prompt()?.account, const ContinueOnAccountMissing());
      controller().confirm();
      await settle();
      expect(player.starts, isEmpty);
    });

    test('should_matchTheOnlyAccountOfThatService_when_theKeyIsUnknownOnOneSide', () async {
      // The other device had no key for the account (not syncing): the only Subsonic account here without one.
      accounts.keys = const {'p-home': 'k-home'};
      host.receive(cursor(accountKey: null, playing: false).toEntries());
      await settle();
      // Two Subsonic accounts here and no key to tell them apart: the one in use.
      expect(prompt()?.account, const ContinueOnAccountFound('p-home', 'Home'));

      host.receive(cursor(accountKey: 'k-elsewhere', playing: false).toEntries());
      await settle();
      // A key no account here has: the only account without a key.
      expect(prompt()?.account, const ContinueOnAccountFound('p-work', 'Work'));
    });

    test('should_showNoCard_when_theHandOffHoldsNoCursor', () async {
      expect(controller().receive(const {'unrelated': 1}), isFalse);
      expect(prompt(), isNull);
    });

    test('should_dropTheCard_when_notNowIsTapped', () async {
      host.receive(cursor(playing: false).toEntries());
      await settle();
      controller().dismiss();
      expect(prompt(), isNull);
      controller().confirm();
      await settle();
      expect(player.starts, isEmpty);
    });

    test('should_showTheNewerCard_when_anotherHandOffArrives', () async {
      host.receive(cursor(playing: false).toEntries());
      host.receive(cursor(accountKey: 'k-work', playing: false).toEntries());
      await settle();
      expect(prompt()?.account, const ContinueOnAccountFound('p-work', 'Work'));
    });
  });

  group('handing off', () {
    test('should_handOverTheSongItsQueueAndTheAccount_when_theSystemAsks', () async {
      offered = true;
      container.invalidate(continueOnOfferedProvider);
      final queue = [_track('t1'), _track('t2'), _track('t3')];
      player.state = NowPlayingState(queue: queue, index: 1, repeat: RepeatMode.one, shuffle: true);
      player.position = 42000;
      await settle();

      final entries = await host.ask();
      final handedOver = ContinueOnCursor.fromEntries(entries!)!;
      expect(handedOver.provider, MediaId.providerSubsonic);
      expect(handedOver.accountKey, 'k-home');
      expect(handedOver.trackId, 't2');
      expect(handedOver.queueIds, ['t1', 't2', 't3']);
      expect(handedOver.index, 1);
      expect(handedOver.positionMs, 42000);
      expect(handedOver.repeatMode, 1);
      expect(handedOver.shuffle, isTrue);
      expect(handedOver.deviceName, 'HUAWEI MatePad');
      // Nothing is playing: no port is opened for a pause.
      expect(handedOver.pause, isNull);
    });

    test('should_openAPauseChannel_when_theSongPlays', () async {
      offered = true;
      container.invalidate(continueOnOfferedProvider);
      host.addresses = ['fe80::1%wlan0', '127.0.0.1', 'fd00::5', '192.168.1.20'];
      player.state = NowPlayingState(queue: [_track('t1')], index: 0, playWhenReady: true);
      await settle();

      final handedOver = ContinueOnCursor.fromEntries((await host.ask())!)!;
      expect(handedOver.playing, isTrue);
      expect(handedOver.pause?.hosts, ['192.168.1.20', 'fd00::5']);
      expect(handedOver.pause?.port, isPositive);

      // The other device asks it to pause: this device pauses and says where.
      final answer = await requestContinueOnPause(
        ContinueOnPauseChannel(
          hosts: const ['127.0.0.1'],
          port: handedOver.pause!.port,
          token: handedOver.pause!.token,
        ),
        't1',
      );
      expect(answer, const PauseAnswerPaused(positionMs: 0, trackId: 't1'));
      expect(player.pauses, 1);
    });

    test('should_handOverNothing_when_noSongIsLoaded', () async {
      expect(await host.ask(), isNull);
    });

    test('should_offerTheHandOff_when_aSongIsLoaded', () async {
      await settle();
      expect(host.offers.last.offered, isFalse);
      offered = true;
      player.state = NowPlayingState(queue: [_track('t1')], index: 0);
      container.invalidate(continueOnOfferedProvider);
      await settle();
      final last = host.offers.last;
      expect(last.offered, isTrue);
      // A snapshot for a request Dart can't answer in time: never a pause channel.
      final snapshot = ContinueOnCursor.fromEntries(last.snapshot!)!;
      expect(snapshot.trackId, 't1');
      expect(snapshot.pause, isNull);

      offered = false;
      container.invalidate(continueOnOfferedProvider);
      await settle();
      expect(host.offers.last.offered, isFalse);
      expect(host.readyCalls, 1);
    });
  });

  group('localPauseHosts', () {
    test('should_keepOnlyRoutableWrittenOutAddresses_when_listed', () {
      expect(localPauseHosts(['fe80::1%wlan0', '::1', '127.0.0.1', '169.254.3.4', 'fd00::5%2', '10.0.0.2', 'host']), [
        '10.0.0.2',
        'fd00::5',
      ]);
      expect(localPauseHosts(['10.0.0.1', '10.0.0.2', '10.0.0.3', '10.0.0.4']), hasLength(3));
    });
  });
}

Track _track(String rawId) =>
    Track(id: MediaId.subsonic(rawId), title: 'Track $rawId', artist: 'Artist', album: 'Album', durationSec: 180);

final class _FixedClock implements PlaybackClock {
  _FixedClock(this.now);
  int now;
  @override
  int wallMs() => now;
  @override
  int monotonicMs() => now;
}

final class _FakeHost implements ContinueOnHost {
  final _received = StreamController<Map<String, Object?>>.broadcast(sync: true);
  HandoffAnswer? _answer;
  final List<({bool offered, Map<String, Object?>? snapshot})> offers = [];
  List<String> addresses = const [];
  int readyCalls = 0;

  void receive(Map<String, Object?> entries) => _received.add(entries);

  Future<Map<String, Object?>?> ask() async => _answer == null ? null : await _answer!();

  @override
  bool get supported => true;

  @override
  Stream<Map<String, Object?>> get received => _received.stream;

  @override
  set handoffRequests(HandoffAnswer? answer) => _answer = answer;

  @override
  Future<void> ready() async => readyCalls++;

  @override
  Future<void> setOffered(bool offered, {Map<String, Object?>? snapshot}) async =>
      offers.add((offered: offered, snapshot: snapshot));

  @override
  Future<List<String>> localAddresses() async => addresses;

  @override
  Future<String?> deviceName() async => 'HUAWEI MatePad';
}

final class _Start {
  _Start(this.tracks, this.startIndex, this.context, this.startPositionMs, this.startPaused);
  final List<Track> tracks;
  final int startIndex;
  final PlaybackContext context;
  final int startPositionMs;
  final bool startPaused;
  List<String> get ids => [for (final t in tracks) t.id.rawId];
}

final class _FakePlayer implements ContinueOnPlayer {
  @override
  NowPlayingState state = NowPlayingState.idle;
  int position = 0;
  int pauses = 0;
  int nowPlayingOpened = 0;
  final List<_Start> starts = [];
  final List<PlayMode> modes = [];

  @override
  int livePositionMs() => position;

  @override
  Future<void> pause() async => pauses++;

  @override
  Future<void> start(
    List<Track> tracks, {
    required int startIndex,
    required PlaybackContext context,
    required int startPositionMs,
    required bool startPaused,
  }) async => starts.add(_Start(tracks, startIndex, context, startPositionMs, startPaused));

  @override
  Future<void> setMode(PlayMode mode) async => modes.add(mode);

  @override
  void openNowPlaying() => nowPlayingOpened++;
}

final class _FakeAccounts implements ContinueOnAccounts {
  _FakeAccounts({required List<Profile> profiles, required this.keys, required this.sources, required this.active})
    : _profiles = profiles;

  final List<Profile> _profiles;
  Map<String, String> keys;
  final Map<String, MusicSource> sources;
  String? active;
  bool switchFails = false;
  final List<String> switches = [];

  @override
  Future<List<Profile>> profiles() async => _profiles;

  @override
  Future<String?> activeProfileId() async => active;

  @override
  Future<String?> accountKey(Profile profile) async => keys[profile.id];

  @override
  Future<void> switchTo(String profileId) async {
    switches.add(profileId);
    if (switchFails) throw StateError('could not save');
    active = profileId;
  }

  @override
  Future<MusicSource?> activeSource() async => sources[active];

  @override
  Future<String?> coverUrl(String profileId, CoverRef cover, int sizePx) async =>
      sources[profileId]?.resolveCoverUrl(cover, size: sizePx);
}

final class _FakeSource implements MusicSource, MusicLibrary, MusicTrackLookup {
  _FakeSource(this.name);
  final String name;
  final Map<String, Track> tracks = {};
  final Map<String, Album> albums = {};
  final List<String> trackReads = [];
  void Function()? onRead;

  void has(List<String> rawIds) {
    for (final id in rawIds) {
      tracks[id] = _track(id);
    }
  }

  @override
  Future<Track?> getTrack(MediaId id) async {
    trackReads.add(id.rawId);
    onRead?.call();
    return tracks[id.rawId];
  }

  @override
  Future<Album?> getAlbum(MediaId id) async => albums[id.rawId];

  @override
  String get provider => MediaId.providerSubsonic;
  @override
  Set<Capability> get capabilities => const {};
  @override
  MusicLibrary get library => this;
  @override
  MusicMetadata get metadata => throw UnimplementedError();
  @override
  MusicWriteActions get writeActions => throw UnimplementedError();
  @override
  MusicPlayback get playback => throw UnimplementedError();
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => 'https://covers.test/$name/${ref.storageKey}?size=$size';
  @override
  void dispose() {}
  @override
  Future<bool> ping() async => true;
  @override
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0}) async => const [];
  @override
  Future<List<ArtistIndex>> getArtists() async => const [];
  @override
  Future<ArtistDetail?> getArtist(MediaId id) async => null;
  @override
  Future<List<Playlist>> getPlaylists() async => const [];
  @override
  Future<Playlist?> getPlaylist(MediaId id) async => null;
  @override
  Future<Starred> getStarred() async => const Starred();
  @override
  Future<List<Track>> getRandomSongs({int size = 20}) async => const [];
  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async =>
      const SearchResults();
}
