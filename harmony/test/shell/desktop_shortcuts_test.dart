import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/desktop_shortcuts.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

Widget _app(List<Override> overrides, {Widget home = const Scaffold(body: SizedBox.expand())}) => ProviderScope(
  overrides: overrides,
  child: MaterialApp(
    navigatorKey: appNavigatorKey,
    builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
    home: home,
  ),
);

/// A phone-sized window: Now Playing opens full screen (no side panel).
void _phone(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 2.625;
  addTearDown(tester.view.reset);
}

Finder get _player => find.text('PLAYING FROM ALBUM');

final _album = AlbumPlaybackContext(albumId: MediaId.subsonic('al'), name: 'Album');

void main() {
  late NowPlayingFakeController controller;

  Future<void> pump(WidgetTester tester, NowPlayingState state, {Widget? home}) async {
    _phone(tester);
    controller = NowPlayingFakeController(state);
    await tester.pumpWidget(
      home == null
          ? _app(nowPlayingOverrides(controller: controller))
          : _app(nowPlayingOverrides(controller: controller), home: home),
    );
    await tester.pumpAndSettle();
  }

  Future<void> keys(WidgetTester tester, List<LogicalKeyboardKey> held, LogicalKeyboardKey key) async {
    for (final k in held) {
      await tester.sendKeyDownEvent(k);
    }
    await tester.sendKeyEvent(key);
    for (final k in held.reversed) {
      await tester.sendKeyUpEvent(k);
    }
    await tester.pumpAndSettle();
  }

  testWidgets('should_pause_when_spacePressedWhilePlaying', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, const [], LogicalKeyboardKey.space);
    expect(controller.calls, contains('pause'));
  });

  testWidgets('should_skip_when_ctrlArrowsPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.arrowRight);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.arrowLeft);
    expect(controller.calls, containsAllInOrder(['next', 'previous']));
  });

  testWidgets('should_toggleFavorite_when_ctrlDPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.keyD);
    expect(controller.calls, contains('toggleFavorite'));
  });

  testWidgets('should_openAndCloseNowPlaying_when_ctrlShiftPPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    expect(_player, findsNothing);
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.shiftLeft], LogicalKeyboardKey.keyP);
    expect(_player, findsOneWidget);
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.shiftLeft], LogicalKeyboardKey.keyP);
    expect(_player, findsNothing);
  });

  testWidgets('should_seekFiveSeconds_when_arrowPressedWithPlayerOpen', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, const [], LogicalKeyboardKey.arrowRight);
    expect(controller.calls.where((c) => c.startsWith('seekTo')), isEmpty, reason: 'no seek with the player closed');
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.shiftLeft], LogicalKeyboardKey.keyP);
    await keys(tester, const [], LogicalKeyboardKey.arrowRight);
    expect(controller.calls.where((c) => c.startsWith('seekTo')), hasLength(1));
  });

  testWidgets('should_leaveSpaceToTheField_when_typing', (tester) async {
    final field = TextEditingController();
    addTearDown(field.dispose);
    await pump(
      tester,
      playingState(nowPlayingTracks(2), context: _album),
      home: Scaffold(body: Center(child: TextField(controller: field, autofocus: true))),
    );
    await tester.enterText(find.byType(TextField), 'a');
    await keys(tester, const [], LogicalKeyboardKey.space);
    expect(controller.calls, isNot(contains('pause')));
  });

  testWidgets('should_popPushedPage_when_escapePressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Center(child: Text('pushed')))),
    );
    await tester.pumpAndSettle();
    expect(find.text('pushed'), findsOneWidget);
    await keys(tester, const [], LogicalKeyboardKey.escape);
    expect(find.text('pushed'), findsNothing);
  });

  testWidgets('should_switchToLibraryAndAskForSearch_when_ctrlKPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    final container = ProviderScope.containerOf(tester.element(find.byType(AppChrome)));
    final before = container.read(librarySearchRequestsProvider);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.keyK);
    expect(container.read(shellSectionProvider), YoinSection.library);
    expect(container.read(librarySearchRequestsProvider), before + 1);
  });
}
