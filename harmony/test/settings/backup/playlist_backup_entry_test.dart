import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/backup/playlist_backup_models.dart';
import 'package:yoin/data/backup/playlist_backup_providers.dart';
import 'package:yoin/data/backup/playlist_backup_store.dart';
import 'package:yoin/detail/playlist_backup_action.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/backup/playlist_backup_strings.dart';
import 'package:yoin/settings/backup/playlist_backups_screen.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/sync/cloud_sync_view_model.dart';

import '../../support/fake_music_source.dart';
import '../sync/cloud_sync_screen_test.dart' show FakeCloudSync;

PlaylistBackupRecord _record({int tracks = 3, int unexported = 0}) => PlaylistBackupRecord(
  id: 'b',
  name: 'n',
  sourceProvider: 'subsonic',
  sourceProfileLabel: 'Home',
  sourcePlaylistId: 'subsonic:p',
  sourceProfileId: null,
  playlistCreatedAt: null,
  firstBackedUpAt: 0,
  lastBackedUpAt: 0,
  lastChangedAt: 0,
  sourceGoneAt: null,
  content: PlaylistBackupContent(
    tracks: [for (var i = 0; i < tracks; i++) BackupTrack(title: 't$i', sourceTrackId: 'subsonic:$i')],
    unexported: [for (var i = 0; i < unexported; i++) BackupUnexported(position: i, reason: 'local_file')],
  ),
  restores: const [],
  updatedAt: 0,
);

void main() {
  test('should_sayHowMuchCameAlong_when_aPlaylistIsBackedUp', () {
    const en = PlaylistBackupStrings.forLanguage(chinese: false);
    const zh = PlaylistBackupStrings.forLanguage(chinese: true);
    expect(backupMessage(en, _record()), 'Backed up 3 songs');
    expect(backupMessage(en, _record(tracks: 1)), 'Backed up 1 song');
    expect(backupMessage(en, _record(unexported: 2)), 'Backed up 3 of 5 songs');
    expect(backupMessage(zh, _record(unexported: 2)), '已备份 3 / 5 首');
  });

  test('should_readServiceDates_when_theyComeInEitherShape', () {
    expect(parseServiceDate('2024-03-01T10:00:00Z'), DateTime.utc(2024, 3, 1, 10).millisecondsSinceEpoch);
    expect(parseServiceDate('2024-03-01'), DateTime.utc(2024, 3, 1).millisecondsSinceEpoch);
    expect(parseServiceDate('soon'), isNull);
    expect(parseServiceDate(null), isNull);
  });

  testWidgets('should_listPlaylistBackupsUnderSyncAndStorage_when_settingsOpen', (tester) async {
    tester.view.physicalSize = const Size(800, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final kv = InMemoryStore();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(kv),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          cloudSyncControllerProvider.overrideWithValue(FakeCloudSync()),
          playlistBackupStoreProvider.overrideWithValue(PlaylistBackupStore(InMemoryPlaylistBackupStorage())),
        ],
        child: const MaterialApp(home: SettingsScreen()),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Sync and backups'), findsOneWidget);
    await tester.tap(find.text('Sync and backups'));
    await tester.pumpAndSettle();
    expect(find.text('Playlist cloud backup'), findsOneWidget);
    await tester.tap(find.text('Playlist cloud backup'));
    await tester.pumpAndSettle();
    expect(find.byType(PlaylistBackupsScreen), findsOneWidget);
    expect(find.text('No backups'), findsOneWidget);
  });
}
