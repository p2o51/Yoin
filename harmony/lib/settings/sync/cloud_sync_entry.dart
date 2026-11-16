/// Settings › Sync and storage's first row: "Cloud sync" and where it stands
/// ("Off", then "Synced 2 min ago" under "On") (Android CloudSyncEntry.kt).
/// Opens [CloudSyncScreen].
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../settings_rows.dart';
import 'cloud_sync_screen.dart';
import 'cloud_sync_strings.dart';
import 'cloud_sync_texts.dart';
import 'cloud_sync_view_model.dart';

class CloudSyncEntryRow extends ConsumerWidget {
  const CloudSyncEntryRow({super.key, required this.onTap, this.showChevron = false, this.now});

  final VoidCallback onTap;

  /// List-detail: rows that open a page on the right carry a chevron.
  final bool showChevron;

  /// Fixed time (tests).
  final int? now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = CloudSyncStrings.of(context);
    final sync = ref.watch(cloudSyncViewModelProvider.select((p) => p.sync));
    return SyncClock(
      now: now,
      builder: (context, nowMs) {
        final text = cloudSyncEntrySummary(sync, nowMs, strings, date: localizedSyncDate(context));
        return SettingsItem(
          icon: YoinSymbols.devices,
          title: strings.title,
          summary: text.status,
          summaryDetail: text.detail,
          onTap: onTap,
          trailing: showChevron
              ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
              : null,
        );
      },
    );
  }
}
