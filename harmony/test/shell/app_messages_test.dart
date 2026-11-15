import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/library/new_playlist_fab.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/app_messages.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/yoin_dialog.dart';

import '../support/nowplaying_fakes.dart';

void main() {
  late NowPlayingFakeController controller;

  Future<void> pumpApp(WidgetTester tester, {Widget home = const Scaffold(body: SizedBox.expand())}) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 2.625;
    addTearDown(tester.view.reset);
    controller = NowPlayingFakeController(NowPlayingState.idle);
    await tester.pumpWidget(
      ProviderScope(
        overrides: nowPlayingOverrides(controller: controller),
        child: MaterialApp(
          navigatorKey: appNavigatorKey,
          builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
          home: home,
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  group('dialogs', () {
    testWidgets('should_sendBarAway_when_yoinDialogIsUp', (tester) async {
      await pumpApp(tester);
      expect(find.text('Library'), findsOneWidget);

      showYoinDialog<void>(
        context: appNavigatorKey.currentContext!,
        builder: (_) => const AlertDialog(title: Text('New playlist')),
      );
      await tester.pumpAndSettle();
      expect(find.text('Library'), findsNothing, reason: 'the bar must not sit bright above the scrim');

      appNavigatorKey.currentState!.pop();
      await tester.pumpAndSettle();
      expect(find.text('Library'), findsOneWidget);
    });

    test('should_useYoinDialogAndShellSnackbar_when_featureCodeShowsOne', () {
      // Every dialog goes through showYoinDialog (bar away), every message
      // through the shell's snackbar (showAppMessage / showMessage).
      final offenders = <String>[];
      for (final file in Directory('lib').listSync(recursive: true).whereType<File>()) {
        if (!file.path.endsWith('.dart') || file.path.endsWith('yoin_dialog.dart')) continue;
        final text = file.readAsStringSync();
        if (RegExp(r'\bshow(General)?Dialog<').hasMatch(text) || RegExp(r'\bshowDialog\(').hasMatch(text)) {
          offenders.add('${file.path}: raw showDialog');
        }
        if (text.contains('ScaffoldMessenger')) {
          offenders.add('${file.path}: ScaffoldMessenger');
        }
      }
      expect(offenders, isEmpty);
    });
  });

  group('snackbar', () {
    testWidgets('should_showOnShellSnackbar_when_appMessageSent', (tester) async {
      await pumpApp(tester);
      showAppMessage(appNavigatorKey.currentContext!, 'Created "QA list"');
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('Created "QA list"'), findsOneWidget);
      expect(find.ancestor(of: find.text('Created "QA list"'), matching: find.byType(YoinSnackbar)), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing);

      await tester.pump(snackbarShortDuration);
      await tester.pumpAndSettle();
      expect(find.text('Created "QA list"'), findsNothing);
    });

    testWidgets('should_publishLiftThenDropIt_when_snackbarShowsAndLeaves', (tester) async {
      await pumpApp(tester);
      final container = ProviderScope.containerOf(appNavigatorKey.currentContext!);
      expect(container.read(snackbarLiftProvider), 0);

      showAppMessage(appNavigatorKey.currentContext!, 'Renamed');
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      final lift = container.read(snackbarLiftProvider);
      final pill = tester.getSize(find.byType(YoinSnackbar));
      expect(lift, moreOrLessEquals(pill.height, epsilon: 0.5));
      expect(lift, greaterThan(SnackbarTokens.minHeight));

      await tester.pump(snackbarShortDuration);
      await tester.pumpAndSettle();
      expect(container.read(snackbarLiftProvider), 0);
    });

    testWidgets('should_riseOverSnackbar_when_newPlaylistFabAndMessageShows', (tester) async {
      final scroll = ScrollController();
      addTearDown(scroll.dispose);
      await pumpApp(
        tester,
        home: Scaffold(
          body: Stack(
            children: [
              ListView(controller: scroll, children: const [SizedBox(height: 10)]),
              Positioned(right: 16, bottom: 120, child: NewPlaylistFab(controller: scroll)),
            ],
          ),
        ),
      );
      final fab = find.descendant(of: find.byType(NewPlaylistFab), matching: find.byType(Material)).first;
      final rest = tester.getRect(fab).bottom;

      showAppMessage(appNavigatorKey.currentContext!, 'Deleted');
      await tester.pumpAndSettle(const Duration(milliseconds: 16));
      final lift = ProviderScope.containerOf(appNavigatorKey.currentContext!).read(snackbarLiftProvider);
      expect(tester.getRect(fab).bottom, moreOrLessEquals(rest - lift, epsilon: 0.5));

      await tester.pump(snackbarShortDuration);
      await tester.pumpAndSettle();
      expect(tester.getRect(fab).bottom, moreOrLessEquals(rest, epsilon: 0.5));
    });
  });

  group('new playlist fab', () {
    Future<Finder> pumpFab(WidgetTester tester) async {
      final scroll = ScrollController();
      addTearDown(scroll.dispose);
      await pumpApp(
        tester,
        home: Scaffold(
          body: Stack(
            children: [
              ListView(controller: scroll, children: const [SizedBox(height: 10)]),
              Positioned(right: 16, bottom: 120, child: NewPlaylistFab(controller: scroll)),
            ],
          ),
        ),
      );
      return find.descendant(of: find.byType(NewPlaylistFab), matching: find.byType(Opacity)).first;
    }

    testWidgets('should_stepAside_when_itsNameDialogIsUp', (tester) async {
      final fade = await pumpFab(tester);
      expect(tester.widget<Opacity>(fade).opacity, moreOrLessEquals(1, epsilon: 0.001));
      await tester.tap(find.byType(NewPlaylistFab));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsOneWidget);
      expect(tester.widget<Opacity>(fade).opacity, moreOrLessEquals(0, epsilon: 0.001), reason: 'not riding up with the keyboard behind the dialog');

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(tester.widget<Opacity>(fade).opacity, moreOrLessEquals(1, epsilon: 0.001));
    });

    testWidgets('should_stepAside_when_keyboardIsUp', (tester) async {
      final fade = await pumpFab(tester);
      tester.view.viewInsets = const FakeViewPadding(bottom: 800);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpAndSettle();
      expect(tester.widget<Opacity>(fade).opacity, moreOrLessEquals(0, epsilon: 0.001));
      tester.view.resetViewInsets();
      await tester.pumpAndSettle();
      expect(tester.widget<Opacity>(fade).opacity, moreOrLessEquals(1, epsilon: 0.001));
    });
  });
}
