import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/offline/offline_downloads.dart';
import 'package:yoin/data/offline/offline_model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/album_detail_screen.dart';
import 'package:yoin/detail/album_detail_view_model.dart';
import 'package:yoin/detail/playlist_detail_screen.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';
import 'package:yoin/offline/downloads_screen.dart';
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/offline/offline_strings.dart';
import 'package:yoin/offline/offline_toggle.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/ui/component/meta_line.dart';
import 'package:yoin/ui/component/track_menu_sheet.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../support/nowplaying_fakes.dart';
import 'offline_fakes.dart';

final _song = Track(
  id: MediaId.subsonic('s1'),
  title: 'Lantern',
  artist: 'Mono',
  albumId: MediaId.subsonic('al1'),
  coverArt: const CoverSourceRelative('c1'),
);

final _albumModel = Album(id: MediaId.subsonic('al1'), name: 'Hymn', artist: 'Mono', tracks: [_song]);
final _album = OfflineCollection.album(_albumModel);
final _playlistModel = Playlist(id: MediaId.subsonic('pl1'), name: 'Night', owner: 'me', tracks: [_song]);

void main() {
  late FakeOfflineDownloads downloads;
  late NowPlayingFakeController controller;

  List<Override> overrides({MusicSource? source}) => [
    ...nowPlayingOverrides(controller: controller, source: source ?? DownloadingFakeSource()),
    offlineDownloadsProvider.overrideWithValue(downloads),
  ];

  setUp(() {
    downloads = FakeOfflineDownloads();
    controller = NowPlayingFakeController(playingState(nowPlayingTracks(2)));
  });

  group('song sheet', () {
    late List<String> messages;

    Future<void> openSheet(WidgetTester tester, {MusicSource? source}) async {
      messages = [];
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(source: source),
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => showTrackMenuSheet(context, track: _song, onMessage: messages.add),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
    }

    testWidgets('should_downloadTheSongAlone_when_downloadTapped', (tester) async {
      await openSheet(tester);
      await tester.tap(find.text('Download'));
      await tester.pumpAndSettle();
      expect(downloads.calls, ['download song:${_song.id} s1']);
      expect(messages, ['Downloading 1 songs']);
      expect(find.text('Lantern'), findsNothing, reason: 'the sheet goes down');
    });

    testWidgets('should_sayItWaitsForWifi_when_onMobileData', (tester) async {
      downloads.waitsOnWifi = true;
      await openSheet(tester);
      await tester.tap(find.text('Download'));
      await tester.pumpAndSettle();
      expect(messages, ['1 songs will download on Wi‑Fi']);
    });

    testWidgets('should_offerRemove_when_theSongWasDownloadedAlone', (tester) async {
      downloads = FakeOfflineDownloads(
        songs: [
          offlineSong(_song, [OfflineCollection.song(_song)]),
        ],
      );
      await openSheet(tester);
      expect(find.text('Remove download'), findsOneWidget);
      expect(find.byIcon(YoinSymbols.downloadDone), findsOneWidget);
      await tester.tap(find.text('Remove download'));
      await tester.pumpAndSettle();
      expect(downloads.calls, ['remove song:${_song.id}']);
      expect(messages, ['Download removed']);
    });

    testWidgets('should_leaveTheRowOut_when_theSongIsKeptByItsAlbum', (tester) async {
      downloads = FakeOfflineDownloads(
        songs: [
          offlineSong(_song, [_album]),
        ],
      );
      await openSheet(tester);
      expect(find.text('Download'), findsNothing);
      expect(find.text('Remove download'), findsNothing);
    });

    testWidgets('should_leaveTheRowOut_when_theAccountCannotDownload', (tester) async {
      await openSheet(tester, source: NowPlayingFakeSource());
      expect(find.text('Download'), findsNothing);
    });

    testWidgets('should_leaveTheRowOut_when_thisBuildKeepsNoFiles', (tester) async {
      downloads.fake = OfflineState(activeProfileId: 'p1', ready: true, available: false);
      await openSheet(tester);
      expect(find.text('Download'), findsNothing);
    });
  });

  group('page menus', () {
    Future<List<DetailMenuAction>> menuOf(
      WidgetTester tester,
      List<DetailMenuAction> Function(BuildContext context, WidgetRef ref) items,
    ) async {
      late List<DetailMenuAction> shown;
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: MaterialApp(
            home: Consumer(
              builder: (context, ref, _) {
                shown = items(context, ref);
                return Column(children: [for (final a in shown) Text(a.label)]);
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      return shown;
    }

    testWidgets('should_downloadTheAlbum_when_itsMenuRowTapped', (tester) async {
      final items = await menuOf(tester, (c, r) => albumMenuItems(c, r, AlbumDetailContent(_albumModel)));
      expect(items.map((a) => a.label), containsAllInOrder(['Play next', 'Add to queue', 'Download']));
      items.firstWhere((a) => a.label == 'Download').onTap();
      await tester.pumpAndSettle();
      expect(downloads.calls, ['download ${_album.key} s1']);
      // The row follows the download.
      expect(find.text('Downloading 0 / 1'), findsOneWidget);
    });

    testWidgets('should_offerDownloadFirst_when_thePlaylistMenuOpens', (tester) async {
      final items = await menuOf(tester, (c, r) => playlistMenuItems(c, r, PlaylistDetailContent(_playlistModel)));
      expect(items.first.label, 'Download');
      items.first.onTap();
      await tester.pumpAndSettle();
      expect(downloads.calls, ['download playlist:${_playlistModel.id} s1']);
    });
  });

  group('the row', () {
    test('should_nameEachStatus_when_shown', () {
      const strings = OfflineStrings.forLanguage(chinese: false);
      expect(offlineToggleFace(const OfflineNone(), strings), ('Download', YoinSymbols.download));
      expect(offlineToggleFace(const OfflineDownloading(2, 5), strings), ('Downloading 2 / 5', YoinSymbols.close));
      expect(offlineToggleFace(const OfflineDownloaded(), strings), ('Remove download', YoinSymbols.downloadDone));
      expect(offlineToggleFace(const OfflineFailed(), strings), ('Retry download', YoinSymbols.download));
      const zh = OfflineStrings.forLanguage(chinese: true);
      expect(offlineToggleFace(const OfflineDownloaded(), zh).$1, '移除下载');
    });

    test('should_removeWhatCameSoFar_when_tappedWhileDownloading', () async {
      final d = FakeOfflineDownloads();
      final said = <String>[];
      await runOfflineToggle(
        downloads: d,
        status: const OfflineDownloading(1, 3),
        collection: _album,
        tracks: () async => [_song],
        strings: const OfflineStrings.forLanguage(chinese: false),
        onMessage: said.add,
      );
      expect(d.calls, ['remove ${_album.key}']);
      expect(said, ['Download removed']);
      d.dispose();
    });

    test('should_sizeLikeSettings_when_bytesFormatted', () {
      const s = OfflineStrings.forLanguage(chinese: false);
      expect(s.bytes(0), 'Empty');
      expect(s.bytes(512), '512 B');
      expect(s.bytes(1536), '1.5 KB');
      expect(s.bytes(5 * 1048576), '5.0 MB');
      expect(s.bytes(3 * 1073741824), '3.00 GB');
    });
  });

  group('Downloads page', () {
    Future<void> pumpPage(WidgetTester tester) async {
      // Tall enough for the whole page.
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: const MaterialApp(home: DownloadsScreen()),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('should_sayNothingIsDownloaded_when_empty', (tester) async {
      await pumpPage(tester);
      expect(find.text('Download quality'), findsOneWidget);
      expect(find.text('The file as it is on your server'), findsOneWidget);
      expect(find.text('Off: downloads wait for Wi‑Fi'), findsOneWidget);
      expect(find.text('Nothing downloaded yet'), findsOneWidget);
    });

    testWidgets('should_changeQualityAndMobileData_when_tapped', (tester) async {
      await pumpPage(tester);
      await tester.tap(find.text('Original'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Download using mobile data'));
      await tester.pumpAndSettle();
      expect(downloads.calls, ['quality original', 'mobile true']);
      expect(find.text('Off: downloads wait for Wi‑Fi'), findsNothing);
    });

    testWidgets('should_listWhatIsDownloadedAndRemoveIt_when_closeTapped', (tester) async {
      downloads = FakeOfflineDownloads(
        songs: [
          offlineSong(_song, [_album], bytes: 2048),
          offlineSong(
            Track(id: MediaId.subsonic('s2')),
            [OfflineCollection.playlist(_playlistModel)],
            state: OfflineSongState.downloading,
            order: 1,
          ),
        ],
      );
      await pumpPage(tester);
      // The artist on one line, the count and size as a MetaLine below: no dots.
      expect(find.text('Mono'), findsOneWidget);
      final meta = find.byType(MetaLine);
      expect(meta, findsOneWidget);
      expect(find.descendant(of: meta, matching: find.text('1')), findsOneWidget);
      expect(find.descendant(of: meta, matching: find.text('song')), findsOneWidget);
      expect(find.descendant(of: meta, matching: find.text('2.0 KB')), findsOneWidget);
      expect(tester.getTopLeft(find.text('1')).dy, greaterThan(tester.getTopLeft(find.text('Mono')).dy));
      // Progress in place of the count, on its own line.
      expect(find.text('me'), findsOneWidget);
      expect(find.text('Downloading 0 of 1'), findsOneWidget);
      expect(find.textContaining('·'), findsNothing);
      await tester.tap(find.byTooltip('Remove download').last);
      await tester.pumpAndSettle();
      expect(downloads.calls, ['remove ${_album.key}']);
    });

    testWidgets('should_removeEveryAccountsDownloads_when_confirmed', (tester) async {
      downloads = FakeOfflineDownloads(
        songs: [
          offlineSong(_song, [_album]),
        ],
      );
      await pumpPage(tester);
      await tester.tap(find.text('Remove all downloads'));
      await tester.pumpAndSettle();
      expect(
        find.text(
          'The songs downloaded for every account on this device will be deleted. You can download them again.',
        ),
        findsOneWidget,
      );
      await tester.tap(find.text('Remove'));
      await tester.pumpAndSettle();
      expect(downloads.calls, ['removeAll']);
    });

    testWidgets('should_summariseQualityAndSize_when_theAccountRowShows', (tester) async {
      downloads = FakeOfflineDownloads(
        songs: [
          offlineSong(_song, [_album], bytes: 3 * 1048576),
        ],
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: const MaterialApp(
            home: Material(child: DownloadsRow(profileId: 'p1')),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Downloads'), findsOneWidget);
      // The quality, then what the downloads take on a line of its own.
      expect(find.text('320 kbps'), findsOneWidget);
      expect(find.text('3.0 MB on this device'), findsOneWidget);
      // 2dp below it, as every line of a settings row (SettingsItem's spacedBy(2.dp)).
      expect(
        tester.getTopLeft(find.text('3.0 MB on this device')).dy - tester.getBottomLeft(find.text('320 kbps')).dy,
        closeTo(2, 0.01),
      );
    });

    testWidgets('should_sayNothingIsDownloadedOnTheSecondLine_when_theAccountRowIsEmpty', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: const MaterialApp(
            home: Material(child: DownloadsRow(profileId: 'p1')),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('320 kbps'), findsOneWidget);
      expect(find.text('Nothing downloaded yet'), findsOneWidget);
      expect(find.textContaining('·'), findsNothing);
    });

    test('should_sayTheSizeOnThisDevice_when_inEitherLanguage', () {
      const en = OfflineStrings.forLanguage(chinese: false);
      const zh = OfflineStrings.forLanguage(chinese: true);
      expect(downloadsSummaryDetail(en, 0), 'Nothing downloaded yet');
      expect(downloadsSummaryDetail(zh, 0), '还没有下载');
      expect(downloadsSummaryDetail(en, 1536), '1.5 KB on this device');
      expect(downloadsSummaryDetail(zh, 1536), '本机已存 1.5 KB');
      expect(en.unitSong(1), 'song');
      expect(en.unitSong(2), 'songs');
      expect(zh.unitSong(2), '首');
    });
  });

  test('should_followTheDownloads_when_theyChange', () {
    final d = FakeOfflineDownloads();
    final container = ProviderContainer(overrides: [offlineDownloadsProvider.overrideWithValue(d)]);
    addTearDown(container.dispose);
    expect(container.read(offlineLibraryProvider).entries, isEmpty);
    d.show(
      OfflineState(
        songs: [
          offlineSong(_song, [_album]),
        ],
        activeProfileId: 'p1',
      ),
    );
    expect(container.read(offlineLibraryProvider).statusOf(_album.key), const OfflineDownloaded());
    // Another account's downloads aren't listed.
    d.show(
      OfflineState(
        songs: [
          offlineSong(_song, [_album]),
        ],
        activeProfileId: 'p2',
      ),
    );
    expect(container.read(offlineLibraryProvider).entries, isEmpty);
  });
}
