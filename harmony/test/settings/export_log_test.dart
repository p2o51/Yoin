import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/offline/offline_providers.dart';
import 'package:yoin/platform/share/file_share.dart';
import 'package:yoin/platform/storage/app_files.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/play_trace.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/settings/export_log.dart';
import 'package:yoin/settings/licenses.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/error_diagnostics.dart' show deviceLine;

import '../support/fake_music_source.dart';

/// Records what the share sheet was handed; [answer] is what it reports.
final class _RecordingFileShare implements FileShare {
  _RecordingFileShare({this.answer = true});
  final bool answer;
  final shared = <(String, SharedFileKind, String?)>[];

  @override
  Future<bool> shareFile(String path, {required SharedFileKind kind, String? title}) async {
    shared.add((path, kind, title));
    return answer;
  }
}

/// Settings › Help and about › Export log (Android ExportLogItem): the
/// playback log as a .txt, the build and the device on top, through the
/// share sheet.
void main() {
  late Directory temp;
  late PlayTrace trace;

  setUp(() {
    temp = Directory.systemTemp.createTempSync('yoin_export_log');
    trace = PlayTrace.memory(now: () => DateTime(2026, 11, 17, 21, 4, 5));
  });
  tearDown(() => temp.deleteSync(recursive: true));

  test('should_writeTheLogAndShareItAsText_when_exported', () async {
    trace.log('play subsonic s1 at=0/1 paused=false shuffled=false stream https://…/stream.view?id=s1');
    final share = _RecordingFileShare();
    final ok = await exportPlaybackLog(
      trace: trace,
      folder: () async => temp,
      deviceName: () async => 'HUAWEI Mate 70 Air',
      share: share,
    );
    expect(ok, isTrue);
    final (path, kind, title) = share.shared.single;
    expect(path, '${temp.path}/shared/yoin-log-20261117-210405.txt');
    expect(kind, SharedFileKind.text);
    expect(title, 'yoin-log-20261117-210405.txt');
    expect(File(path).readAsLinesSync(), [
      'Yoin $yoinVersionName ($yoinVersionCode)',
      '${deviceLine()} HUAWEI Mate 70 Air',
      '',
      '11-17 21:04:05.000 play subsonic s1 at=0/1 paused=false shuffled=false stream https://…/stream.view?id=s1',
    ]);
  });

  test('should_sayItCouldNot_when_thereIsNoCacheFolderOrNoShareSheet', () async {
    final share = _RecordingFileShare();
    expect(
      await exportPlaybackLog(trace: trace, folder: () async => null, deviceName: () async => 'X', share: share),
      isFalse,
    );
    expect(share.shared, isEmpty);
    expect(
      await exportPlaybackLog(
        trace: trace,
        folder: () async => temp,
        deviceName: () async => throw StateError('no name'),
        share: _RecordingFileShare(answer: false),
      ),
      isFalse,
    );
  });

  group('settings row', () {
    late _RecordingFileShare share;

    List<Override> overrides() => [
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
      playTraceProvider.overrideWithValue(trace),
      appFilesHostProvider.overrideWithValue(DirectoryAppFilesHost(temp)),
      fileShareProvider.overrideWithValue(share),
    ];

    Future<void> pump(WidgetTester tester) async {
      tester.view.physicalSize = const Size(400, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: MaterialApp(
            builder: (context, child) => YoinWindowScope.measured(child: child!),
            home: const SettingsScreen(),
          ),
        ),
      );
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
      await tester.pumpAndSettle();
    }

    testWidgets('should_sitAfterFeedback_when_helpAndFeedbackShows', (tester) async {
      share = _RecordingFileShare();
      await pump(tester);
      await tester.tap(find.text('Help and feedback'));
      await tester.pumpAndSettle();
      expect(find.text('Export log'), findsOneWidget);
      expect(find.text('Playback diagnostics'), findsOneWidget);
      final feedback = tester.getTopLeft(find.text('Send feedback')).dy;
      final export = tester.getTopLeft(find.text('Export log')).dy;
      expect(feedback < export, isTrue);
    });

    testWidgets('should_shareTheLog_when_exportLogTapped', (tester) async {
      share = _RecordingFileShare();
      await pump(tester);
      trace.log('pause LOCAL');
      await tester.tap(find.text('Help and feedback'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Export log'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Export log'));
      await _until(tester, () => share.shared.isNotEmpty);
      await tester.pumpAndSettle();
      expect(share.shared, hasLength(1));
      expect(File(share.shared.single.$1).readAsStringSync(), endsWith('pause LOCAL\n'));
    });

    testWidgets('should_sayItCouldNot_when_theShareSheetFails', (tester) async {
      share = _RecordingFileShare(answer: false);
      final messages = <String>[];
      await pump(tester);
      final c = ProviderScope.containerOf(tester.element(find.byType(SettingsScreen)));
      final sub = c.read(playbackControllerProvider.notifier).notices.listen((n) {
        if (n is TextNotice) messages.add(n.text);
      });
      addTearDown(sub.cancel);
      await tester.tap(find.text('Help and feedback'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Export log'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Export log'));
      await _until(tester, () => messages.isNotEmpty);
      await tester.pumpAndSettle();
      expect(messages, ["Couldn't export the log"]);
    });
  });
}

/// Lets the file writes (real I/O) and the frames that follow them run until
/// [done], for at most ten seconds of real time however loaded the machine is.
Future<void> _until(WidgetTester tester, bool Function() done) async {
  final deadline = DateTime.now().add(const Duration(seconds: 10));
  while (!done()) {
    if (DateTime.now().isAfter(deadline)) fail('timed out');
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 5)));
    await tester.pump();
  }
}
