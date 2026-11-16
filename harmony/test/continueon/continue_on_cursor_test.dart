import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/continueon/continue_on_cursor.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_context.dart';

/// Android ContinueOnCursorTest.
void main() {
  const cursor = ContinueOnCursor(
    provider: 'subsonic',
    accountKey: 'acct',
    trackId: 't2',
    title: 'Song',
    artist: 'Artist',
    album: 'Album',
    cover: 'al-1',
    queueIds: ['t1', 't2', 't3'],
    index: 1,
    positionMs: 61000,
    capturedAtMs: 1000,
    playing: true,
    repeatMode: 2,
    shuffle: true,
    context: ContinueOnContext(ContinueOnContextKind.album, 'subsonic:al-1', 'Album'),
    deviceName: 'HUAWEI Mate 70 Air',
    pause: ContinueOnPauseChannel(hosts: ['192.168.1.20'], port: 40123, token: 'tok'),
  );

  Map<String, Object?> entriesWith(Map<String, Object?> change) => {...cursor.toEntries(), ...change};

  test('should_readBackTheSameCursor_when_flattenedAndRead', () {
    expect(ContinueOnCursor.fromEntries(cursor.toEntries()), cursor);
  });

  test('should_useOnlyWantSafeValues_when_flattened', () {
    for (final value in cursor.toEntries().values) {
      expect(value is String || value is int || value is bool || value is List<String>, isTrue, reason: '$value');
    }
    expect(cursor.toEntries().keys.every(ContinueOnCursor.keys.contains), isTrue);
  });

  test('should_readTheSameCursor_when_numbersArriveAsDoubles', () {
    // A platform may hand whole numbers back as doubles and lists untyped.
    final entries = {
      for (final e in cursor.toEntries().entries)
        e.key: switch (e.value) {
          final int n => n.toDouble(),
          final List<String> l => List<Object?>.of(l),
          final other => other,
        },
    };
    expect(ContinueOnCursor.fromEntries(entries), cursor);
  });

  test('should_readNothing_when_versionIsUnknown', () {
    expect(ContinueOnCursor.fromEntries(entriesWith({ContinueOnCursor.keyVersion: 99})), isNull);
  });

  test('should_readNothing_when_noCursorIsThere', () {
    expect(ContinueOnCursor.fromEntries(const {}), isNull);
  });

  test('should_fallBackToTheTrackAlone_when_queueDoesNotHoldItAtIndex', () {
    final read = ContinueOnCursor.fromEntries(entriesWith({ContinueOnCursor.keyIndex: 0}))!;
    expect(read.queueIds, ['t2']);
    expect(read.index, 0);
  });

  test('should_dropThePauseChannel_when_itHasNoToken', () {
    final entries = cursor.toEntries()..remove(ContinueOnCursor.keyPauseToken);
    expect(ContinueOnCursor.fromEntries(entries)!.pause, isNull);
  });

  test('should_dropThePauseChannel_when_itsTokenCouldAddALine', () {
    for (final token in ['tok\nGET / HTTP/1.1', 'tok en', 'tok\r', '']) {
      expect(ContinueOnCursor.fromEntries(entriesWith({ContinueOnCursor.keyPauseToken: token}))!.pause, isNull);
    }
  });

  test('should_keepOnlyWrittenOutAddresses_when_pauseHostsAreRead', () {
    final hosts = [
      '192.168.1.20',
      'example.com',
      'fd00::1a2b',
      '300.1.1.1',
      'localhost',
      '10.0.0.7 x',
      // 192.168.1.20 in Arabic-Indic digits: not an address, a name.
      '١٩٢.١٦٨.١.٢٠',
    ];
    final read = ContinueOnCursor.fromEntries(entriesWith({ContinueOnCursor.keyPauseHosts: hosts}))!;
    expect(read.pause?.hosts, ['192.168.1.20', 'fd00::1a2b']);
  });

  test('should_dropThePauseChannel_when_noHostIsAnAddress', () {
    final read = ContinueOnCursor.fromEntries(
      entriesWith({
        ContinueOnCursor.keyPauseHosts: ['example.com'],
      }),
    )!;
    expect(read.pause, isNull);
  });

  test('should_dropThePauseChannel_when_portIsOutOfRange', () {
    expect(ContinueOnCursor.fromEntries(entriesWith({ContinueOnCursor.keyPausePort: 70000}))!.pause, isNull);
  });

  test('should_catchUpThePosition_when_itWasPlaying', () {
    expect(cursor.positionAt(6000), 66000);
    expect(cursor.copyWith(playing: false).positionAt(6000), 61000);
  });

  test('should_stopShortOfTheEnd_when_catchingUpPastIt', () {
    expect(cursor.positionAt(60000, durationMs: 90000), 87000);
  });

  test('should_startWhereTheCardShowsIt_when_theCursorIsTooOldToCatchUp', () {
    // Read a quarter of an hour ago: the song has long ended there.
    final now = cursor.capturedAtMs + 15 * 60000;
    expect(cursor.positionAt(now, durationMs: 240000), 61000);
    expect(cursor.positionAt(now), 61000);
  });

  test('should_giveTheQueueFromTheSongTheDeviceIsOnNow_when_itHasMovedOn', () {
    final read = cursor.copyWith(queueIds: ['t3', 't1', 't2', 't3', 't4'], index: 2);
    // Its own song, at its own place.
    expect(read.queueFrom('t2'), ['t2', 't3', 't4']);
    // A later one: the place after the cursor's, not the same song queued earlier.
    expect(read.queueFrom('t3'), ['t3', 't4']);
    // Gone back to an earlier one.
    expect(read.queueFrom('t1'), ['t1', 't2', 't3', 't4']);
    // One the queue it was read with doesn't hold: alone.
    expect(read.queueFrom('t9'), ['t9']);
  });

  test('should_keepTheTrackAndItsSurroundings_when_queueIsLong', () {
    final queue = [for (var i = 0; i < 1000; i++) 'id$i'];
    final (window, index) = ContinueOnCursor.queueWindow(queue, 500);
    expect(window, hasLength(ContinueOnCursor.maxQueue));
    expect(window[index], 'id500');
    expect(index, ContinueOnCursor.maxQueue ~/ 4);
  });

  test('should_takeMoreBefore_when_theTailIsShort', () {
    final queue = [for (var i = 0; i < 1000; i++) 'id$i'];
    final (window, index) = ContinueOnCursor.queueWindow(queue, 998);
    expect(window, hasLength(ContinueOnCursor.maxQueue));
    expect(window[index], 'id998');
    expect(window.last, 'id999');
  });

  test('should_trimTheWindow_when_idsAreTooLongForTheExtras', () {
    final queue = [for (var i = 0; i < 200; i++) '${'x' * 400}$i'];
    final (window, index) = ContinueOnCursor.queueWindow(queue, 10);
    expect(
      window.fold<int>(0, (sum, id) => sum + id.length * 2 + 8),
      lessThanOrEqualTo(ContinueOnCursor.maxExtrasBytes ~/ 2),
    );
    expect(window[index], queue[10]);
  });

  test('should_capLongTexts_when_flattened', () {
    final named = ContinueOnCursor(
      provider: 'subsonic',
      accountKey: null,
      trackId: 't',
      title: '🎵' * 150,
      artist: null,
      album: null,
      cover: null,
      queueIds: const ['t'],
      index: 0,
      positionMs: 0,
      capturedAtMs: 0,
      playing: false,
      repeatMode: 0,
      shuffle: false,
      context: null,
      deviceName: null,
      pause: null,
    );
    final title = named.toEntries()[ContinueOnCursor.keyTitle]! as String;
    expect(title.length, lessThanOrEqualTo(200));
    expect(title, '🎵' * 100);
  });

  test('should_restoreTheContextAsThePlayerKeepsIt_when_idIsRaw', () {
    const context = ContinueOnContext(ContinueOnContextKind.album, 'al-1', 'Album');
    final restored = playbackContextOf(context, 'subsonic') as AlbumPlaybackContext;
    expect(restored.albumId, MediaId.subsonic('al-1'));
    expect(restored.name, 'Album');
    const full = ContinueOnContext(ContinueOnContextKind.album, 'subsonic:al-1', 'Album');
    expect((playbackContextOf(full, 'subsonic') as AlbumPlaybackContext).albumId, MediaId.subsonic('al-1'));
    expect(
      playbackContextOf(const ContinueOnContext(ContinueOnContextKind.likedSongs, null, null), 'subsonic'),
      isA<FavoritesPlaybackContext>(),
    );
    expect(playbackContextOf(null, 'subsonic'), isA<NoPlaybackContext>());
  });

  test('should_carryWhereThePlayStarted_when_flattened', () {
    expect(
      cursorContextOf(PlaylistPlaybackContext(playlistId: MediaId.subsonic('pl-1'), name: 'Mix')),
      const ContinueOnContext(ContinueOnContextKind.playlist, 'pl-1', 'Mix'),
    );
    expect(
      cursorContextOf(const FavoritesPlaybackContext()),
      const ContinueOnContext(ContinueOnContextKind.likedSongs, null, null),
    );
    expect(cursorContextOf(const NoPlaybackContext()), isNull);
  });

  test('should_keepTheRepeatMode_when_carriedAsAndroidCodes', () {
    for (final mode in RepeatMode.values) {
      expect(repeatModeOf(repeatModeCode(mode)), mode);
    }
    expect(repeatModeCode(RepeatMode.all), 2);
    expect(repeatModeOf(9), RepeatMode.off);
  });
}
