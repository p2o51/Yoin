/// Settings › Help and about › Export log (Android SettingsScreen
/// ExportLogItem, iOS's row): the playback diagnostics log ([PlayTrace]) as a
/// .txt through the system share sheet; nothing leaves the device unless the
/// user picks where it goes.
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/sync/cloud_sync_providers.dart';
import '../offline/offline_providers.dart';
import '../platform/share/file_share.dart';
import '../playback/play_trace.dart';
import '../shell/app_messages.dart';
import '../ui/component/error_diagnostics.dart' show deviceLine;
import 'licenses.dart' show yoinVersionCode, yoinVersionName;
import 'settings_rows.dart';
import 'settings_strings.dart';

/// Writes the log (cacheDir/shared/yoin-log-<time>.txt, the app's version
/// and the device on top) and opens the share sheet on it. False when it
/// couldn't: no cache folder, a failed write, or no share sheet.
Future<bool> exportPlaybackLog({
  required PlayTrace trace,
  required Future<Directory?> Function() folder,
  required Future<String> Function() deviceName,
  required FileShare share,
}) async {
  try {
    final dir = await folder();
    if (dir == null) return false;
    String model;
    try {
      model = (await deviceName()).trim();
    } catch (_) {
      model = '';
    }
    final file = await trace.writeExport(
      dir,
      header: playTraceHeader(device: model.isEmpty ? deviceLine() : '${deviceLine()} $model'),
    );
    return await share.shareFile(file.path, kind: SharedFileKind.text, title: file.uri.pathSegments.last);
  } catch (_) {
    return false;
  }
}

/// The export's first lines (Android PlayTrace.writeExport): the build, the
/// system and device, then a blank line.
List<String> playTraceHeader({required String device}) => ['Yoin $yoinVersionName ($yoinVersionCode)', device, ''];

/// The Help and about row.
class ExportLogItem extends ConsumerWidget {
  const ExportLogItem({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    return SettingsItem(
      icon: YoinSymbols.share,
      title: strings.aboutExportLog,
      summary: strings.aboutExportLogSummary,
      onTap: () async {
        final failed = strings.aboutExportLogFailed;
        final shared = await exportPlaybackLog(
          trace: ref.read(playTraceProvider),
          folder: ref.read(appFilesHostProvider).cacheDirectory,
          deviceName: ref.read(cloudSyncHostProvider).deviceName,
          share: ref.read(fileShareProvider),
        );
        if (!shared && context.mounted) showAppMessage(context, failed);
      },
    );
  }
}
