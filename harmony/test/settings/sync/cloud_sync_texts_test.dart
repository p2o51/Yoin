import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/cloud_sync_state.dart';
import 'package:yoin/settings/sync/cloud_sync_strings.dart';
import 'package:yoin/settings/sync/cloud_sync_texts.dart';

void main() {
  const en = CloudSyncStrings.forLanguage(chinese: false);
  const zh = CloudSyncStrings.forLanguage(chinese: true);
  final now = DateTime(2026, 11, 16, 12).millisecondsSinceEpoch;
  const minute = 60 * 1000;
  const day = 24 * 60 * minute;

  group('formatRelativeTime', () {
    test('should_countUp_when_timePasses', () {
      expect(formatRelativeTime(now - 10 * 1000, now, en), 'just now');
      expect(formatRelativeTime(now + 5000, now, en), 'just now');
      expect(formatRelativeTime(now - 2 * minute, now, en), '2 min ago');
      expect(formatRelativeTime(now - 3 * 60 * minute, now, en), '3 hr ago');
      expect(formatRelativeTime(now - day - minute, now, en), 'yesterday');
      expect(formatRelativeTime(now - 4 * day, now, en), '4 days ago');
      expect(formatRelativeTime(now - 4 * day, now, zh), '4 天前');
    });

    test('should_showDate_when_aWeekOrMoreAgo', () {
      final sameYear = DateTime(2026, 9, 12, 8).millisecondsSinceEpoch;
      final lastYear = DateTime(2025, 9, 12, 8).millisecondsSinceEpoch;
      expect(formatRelativeTime(sameYear, now, en), 'Sep 12');
      expect(formatRelativeTime(lastYear, now, en), 'Sep 12, 2025');
      expect(formatRelativeTime(lastYear, now, zh), '2025年9月12日');
    });
  });

  group('cloudSyncEntrySummary', () {
    test('should_sayWhereSyncStands_when_settingsRowShown', () {
      expect(cloudSyncEntrySummary(const CloudSyncState(), now, en), const CloudSyncEntryText('Off'));
      expect(
        cloudSyncEntrySummary(CloudSyncState(phase: const SyncUpToDate(), lastSyncAt: now - 2 * minute), now, en),
        const CloudSyncEntryText('On', 'Synced 2 min ago'),
      );
      expect(
        cloudSyncEntrySummary(const CloudSyncState(phase: SyncUpToDate(), pendingChanges: true), now, en),
        const CloudSyncEntryText('Changes waiting to upload'),
      );
      expect(
        cloudSyncEntrySummary(const CloudSyncState(phase: SyncNeedsReauth()), now, en),
        const CloudSyncEntryText('Paused', 'Reconnect Google'),
      );
      expect(
        cloudSyncEntrySummary(const CloudSyncState(phase: SyncNeedsReview('Home', 3)), now, en),
        const CloudSyncEntryText('Needs review', '3 notes removed'),
      );
      expect(
        cloudSyncEntrySummary(const CloudSyncState(phase: SyncResetElsewhere('Pixel 9')), now, zh),
        const CloudSyncEntryText('已在 Pixel 9 上关闭'),
      );
      expect(
        cloudSyncEntrySummary(const CloudSyncState(phase: SyncMisconfiguredPhase('com.gpo.yoin')), now, en),
        const CloudSyncEntryText('Not available in this build'),
      );
    });
  });

  group('cloudSyncStatusText', () {
    test('should_sayNotSyncedYet_when_upToDateWithoutHistory', () {
      final text = cloudSyncStatusText(const CloudSyncState(phase: SyncUpToDate()), now, en);
      expect(text.title, 'Up to date');
      expect(text.lines, ['Not synced yet']);
    });

    test('should_showMessageAndLastSync_when_error', () {
      final text = cloudSyncStatusText(
        CloudSyncState(phase: const SyncErrorPhase('Google Drive error (500).'), lastSyncAt: now - 5 * minute),
        now,
        en,
      );
      expect(text.title, "Couldn't sync");
      expect(text.lines, ['Google Drive error (500).', 'Last synced 5 min ago']);
    });

    test('should_nameDevice_when_updateRequired', () {
      final text = cloudSyncStatusText(const CloudSyncState(phase: SyncUpdateRequired('Pixel 9')), now, en);
      expect(text.title, 'Update Yoin');
      expect(text.lines, ['Pixel 9']);
    });
  });
}
