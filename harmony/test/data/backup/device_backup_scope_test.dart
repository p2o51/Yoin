import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/continueon/continue_on_cursor.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/applemusic/apple_music_name_region.dart';
import 'package:yoin/data/backup/device_backup_scope.dart';
import 'package:yoin/data/highlight/lyric_highlight.dart';
import 'package:yoin/data/lyrics/lyrics_cache.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/memory/track_album_links.dart';
import 'package:yoin/data/neodb/neodb_config.dart';
import 'package:yoin/data/offline/offline_downloads.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/sync/auth/google_oauth.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_made_for_you.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/landing/landing_store.dart';
import 'package:yoin/memories/other_profiles.dart';
import 'package:yoin/playback/keep_screen_on.dart';
import 'package:yoin/playback/local_ratings.dart';
import 'package:yoin/playback/replay_gain.dart';
import 'package:yoin/playback/scrobble.dart';
import 'package:yoin/playback/spatial_audio.dart';
import 'package:yoin/settings/stream_quality.dart';
import 'package:yoin/settings/tips.dart';
import 'package:yoin/ui/component/seam/seam_style.dart';

const _ohosMain = 'ohos/entry/src/main';

/// One `string[]` constant of BackupScope.ets, entry by entry.
List<String> _etsList(String source, String name) {
  final body = RegExp('$name: string\\[\\] = \\[([^\\]]*)\\];').firstMatch(source)?.group(1);
  if (body == null) throw StateError('$name not found in BackupScope.ets');
  return [for (final m in RegExp(r"'([^']*)'").allMatches(body)) m.group(1)!];
}

String _etsString(String source, String name) =>
    RegExp("$name(?:: string)? = '([^']*)'").firstMatch(source)?.group(1) ?? (throw StateError('$name not found'));

void main() {
  group('the ArkTS backup extension carries what Dart says', () {
    final scope = File('$_ohosMain/ets/yoin/backup/BackupScope.ets').readAsStringSync();

    test('should_listTheSameKeys_when_dartAndArkTsCompared', () {
      expect(_etsList(scope, 'CARRIED_PREF_KEYS').toSet(), DeviceBackupScope.prefKeys);
      expect(_etsList(scope, 'CARRIED_PREF_PREFIXES'), DeviceBackupScope.prefPrefixes);
      expect(_etsList(scope, 'CARRIED_CACHE_PREFIXES'), DeviceBackupScope.cachePrefixes);
    });

    test('should_readTheStoragePluginsFiles_when_preferencesNamed', () {
      final plugin = File('$_ohosMain/ets/yoin/storage/StoragePlugin.ets').readAsStringSync();
      expect(_etsString(scope, 'PREFS_FILE'), _etsString(plugin, 'PREFS_NAME'));
      expect(_etsString(scope, 'CACHE_FILE'), _etsString(plugin, 'CACHE_PREFS_NAME'));
    });

    test('should_copyThePayloadAndThePlaylistBackupsOnly_when_backupConfigRead', () {
      final config = jsonDecode(File('$_ohosMain/resources/base/profile/backup_config.json').readAsStringSync()) as Map;
      expect(config['allowToBackupRestore'], isTrue);
      expect(config['fullBackupOnly'], isFalse);
      const files = '/data/storage/el2/base/files';
      expect((config['includes'] as List).toSet(), {
        '$files/${_etsString(scope, 'PAYLOAD_DIR')}/',
        for (final dir in DeviceBackupScope.fileDirs) '$files/$dir/',
      });
    });

    test('should_declareTheBackupExtension_when_moduleRead', () {
      final module = File('$_ohosMain/module.json5').readAsStringSync();
      final block = RegExp(
        r'\{[^{}]*"type": "backup"[^{}]*"metadata": \[\s*\{[^{}]*\}\s*\][^{}]*\}',
      ).firstMatch(module);
      expect(block, isNotNull);
      final text = block!.group(0)!;
      expect(text, contains('"name": "ohos.extension.backup"'));
      expect(text, contains(r'"resource": "$profile:backup_config"'));
      expect(text, contains('"exported": false'));
      final src = RegExp(r'"srcEntry": "\./([^"]+)"').firstMatch(text)!.group(1)!;
      expect(File('$_ohosMain/$src').existsSync(), isTrue, reason: src);
    });
  });

  group('DeviceBackupScope', () {
    const profile = 'p1';
    final album = MemoryStore.albumRatingsKey(profile);

    test('should_carryEveryRoomTableEquivalent_when_storeKeysChecked', () {
      final carried = [
        ProfileRepository.profilesKey,
        ProfileRepository.activeKey,
        SongAboutStore.key,
        AiSettingsStore.prefsKey,
        NeoDbConfigStore.instanceKey,
        NeoDbMappings.storeKey,
        LocalRatingStore.keyFor(profile),
        album,
        MemoryStore.songNotesKey(profile),
        MemoryStore.albumPlaysKey(profile),
        MemoryStore.trackPlaysKey(profile),
        MemoryStore.titlesKey(profile),
        StoredActivityLog.keyFor(profile),
        HomeLayoutNotifier.keyFor('subsonic', profile),
        HomeLayoutNotifier.updatedAtKeyFor('subsonic', profile),
        MemoryCopyCache.keyFor(profile),
        TrackAlbumLinkStore.keyFor(profile),
        LyricHighlightStore.keyFor('subsonic', profile),
        LyricsCacheStore.legacyUserKey('song'),
      ];
      for (final key in carried) {
        expect(DeviceBackupScope.carriesPref(key), isTrue, reason: key);
      }
    });

    test('should_carryLyricsPicksAndPaidTranslations_when_cacheKeysChecked', () {
      final translation = LyricsCacheStore.translationKey('song', 'gemini', 'zh');
      for (final key in [
        LyricsCacheStore.rowKey('song'),
        'yoin.lyrics.cache.index',
        translation,
        LyricsCacheStore.paidSlotKey(translation, 'hash'),
        LyricsCacheStore.paidIndexKey,
      ]) {
        expect(DeviceBackupScope.carriesCache(key), isTrue, reason: key);
      }
      expect(DeviceBackupScope.carriesCache(MadeForYouNotifier.keyFor(profile)), isFalse);
    });

    test('should_leaveSecretsBehind_when_secretKeysChecked', () {
      for (final key in [
        ProfileRepository.credentialsKey(profile),
        NeoDbConfigStore.tokenKey,
        AiSettingsStore.geminiKeyKey,
        AiSettingsStore.searchExaKeyKey,
        GoogleOAuth.refreshTokenKey,
      ]) {
        expect(DeviceBackupScope.carriesPref(key), isFalse, reason: key);
        expect(DeviceBackupScope.carriesCache(key), isFalse, reason: key);
      }
    });

    test('should_leaveThisDevicesSettingsAndStateBehind_when_androidKeepsThemOutOfItsBackup', () {
      for (final key in [
        TipReadStore.key,
        KeyValueLandingStore.key,
        SeamTopStylePreference.key,
        KeepScreenOnPreference.storeKey,
        ReplayGainStore.key,
        SpatialAudioStore.key,
        StreamQualityStore.key(profile, StreamNetwork.wifi),
        DownloadSettingsStore.qualityKey,
        DownloadSettingsStore.mobileDataKey,
        AppleMusicNameRegionStore.keyFor(profile),
        LyricHighlightStore.introSeenKey,
        OtherProfilesNoticeStore.key,
        HomeGridPoolStore.keyFor(profile, 'subsonic'),
        PendingScrobbles.storeKey,
        ContinueOnCursor.keyQueue,
        ContinueOnCursor.keyPauseToken,
      ]) {
        expect(DeviceBackupScope.carriesPref(key), isFalse, reason: key);
      }
    });
  });
}
