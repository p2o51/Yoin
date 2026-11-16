import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/source/music_source.dart' show Capability, MusicSource;
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/library/library_search_view.dart' show librarySearchRouteName;
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/memories/memories_reveal_scope.dart';
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

/// Home held in edit mode (no hint store behind it).
class _EditingHome extends HomeEditController {
  @override
  HomeEditState build() => const HomeEditState(editing: true);
}

final _album = AlbumPlaybackContext(albumId: MediaId.subsonic('al'), name: 'Album');

void main() {
  late NowPlayingFakeController controller;

  Future<void> pump(
    WidgetTester tester,
    NowPlayingState state, {
    Widget? home,
    List<Override> extra = const [],
    MusicSource? source,
  }) async {
    _phone(tester);
    controller = NowPlayingFakeController(state);
    final overrides = [...nowPlayingOverrides(controller: controller, source: source), ...extra];
    await tester.pumpWidget(home == null ? _app(overrides) : _app(overrides, home: home));
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

  testWidgets('should_switchToLibraryAndAskForSearch_when_libraryLongPressedInTheBar', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    final container = ProviderScope.containerOf(tester.element(find.byType(AppChrome)));
    final before = container.read(librarySearchRequestsProvider);
    await tester.longPress(find.bySemanticsLabel('Library'));
    await tester.pumpAndSettle();
    expect(container.read(shellSectionProvider), YoinSection.library);
    expect(container.read(librarySearchRequestsProvider), before + 1);
  });

  testWidgets('should_leaveFieldButKeepPage_when_escapePressedWhileTyping', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Center(child: TextField(autofocus: true)))),
    );
    await tester.pumpAndSettle();
    expect(FocusManager.instance.primaryFocus?.context?.findAncestorStateOfType<EditableTextState>(), isNotNull);
    await keys(tester, const [], LogicalKeyboardKey.escape);
    expect(find.byType(TextField), findsOneWidget, reason: 'the page stays');
    expect(FocusManager.instance.primaryFocus?.context?.findAncestorStateOfType<EditableTextState>(), isNull);
  });

  testWidgets('should_closeSearch_when_escapePressedInSearchField', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        settings: const RouteSettings(name: librarySearchRouteName),
        builder: (_) => const Scaffold(body: Center(child: TextField(autofocus: true))),
      ),
    );
    await tester.pumpAndSettle();
    await keys(tester, const [], LogicalKeyboardKey.escape);
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets('should_ringBackButton_when_focusedByKeyboard', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => Scaffold(appBar: AppBar(leading: const BackButton()))),
    );
    await tester.pumpAndSettle();
    await keys(tester, const [], LogicalKeyboardKey.tab);
    final ring = find.byWidgetPredicate((w) => w is CustomPaint && w.painter.runtimeType.toString() == '_RingPainter');
    expect(ring, paints..rrect(style: PaintingStyle.stroke));
  });

  ProviderContainer container(WidgetTester tester) =>
      ProviderScope.containerOf(tester.element(find.byType(AppChrome)));

  /// A Memories reveal for the handle (the shell registers its own; this harness has none).
  MemoriesRevealController reveal(WidgetTester tester) {
    final memories = MemoriesRevealController(vsync: const TestVSync());
    addTearDown(memories.dispose);
    container(tester).read(memoriesRevealHandleProvider).attach(memories);
    return memories;
  }

  testWidgets('should_toggleShuffle_when_ctrlAltSPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    expect(controller.state.shuffle, isFalse);
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.altLeft], LogicalKeyboardKey.keyS);
    expect(controller.state.shuffle, isTrue);
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.altLeft], LogicalKeyboardKey.keyS);
    expect(controller.state.shuffle, isFalse);
    expect(controller.calls.where((c) => c.startsWith('setMode')), hasLength(2));
  });

  testWidgets('should_notShuffle_when_ctrlSPressedWithoutAlt', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.keyS);
    expect(controller.calls.where((c) => c.startsWith('setMode')), isEmpty);
  });

  testWidgets('should_skipOnce_when_ctrlArrowHeld', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
    await tester.sendKeyDownEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyRepeatEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyRepeatEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
    await tester.pumpAndSettle();
    expect(controller.calls.where((c) => c == 'next'), hasLength(1));
  });

  testWidgets('should_switchSections_when_ctrlOneAndTwoPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit2);
    expect(container(tester).read(shellSectionProvider), YoinSection.library);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit1);
    expect(container(tester).read(shellSectionProvider), YoinSection.home);
  });

  testWidgets('should_returnToShellAndCloseFullPlayer_when_ctrlTwoPressedOverAPage', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Center(child: Text('pushed')))),
    );
    await tester.pumpAndSettle();
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.shiftLeft], LogicalKeyboardKey.keyP);
    expect(_player, findsOneWidget);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit2);
    expect(_player, findsNothing);
    expect(find.text('pushed'), findsNothing);
    expect(container(tester).read(shellSectionProvider), YoinSection.library);
  });

  testWidgets('should_closeMemories_when_ctrlOnePressedWithMemoriesOpen', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    final memories = reveal(tester);
    memories.open();
    await tester.pumpAndSettle();
    expect(memories.ownsBack, isTrue);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit1);
    expect(memories.ownsBack, isFalse);
    expect(container(tester).read(shellSectionProvider), YoinSection.home);
  });

  testWidgets('should_openAllMemoriesOverHome_when_ctrlThreePressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    final memories = reveal(tester);
    container(tester).read(shellSectionProvider.notifier).select(YoinSection.library);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit3);
    expect(container(tester).read(shellSectionProvider), YoinSection.home);
    expect(memories.ownsBack, isTrue);
  });

  testWidgets('should_notOpenAllMemories_when_homeIsBeingEdited', (tester) async {
    await pump(
      tester,
      playingState(nowPlayingTracks(2), context: _album),
      extra: [homeEditProvider.overrideWith(_EditingHome.new)],
    );
    final memories = reveal(tester);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit3);
    expect(memories.ownsBack, isFalse);
    expect(container(tester).read(homeEditProvider).editing, isTrue);
  });

  testWidgets('should_leaveEditMode_when_ctrlTwoPressed', (tester) async {
    await pump(
      tester,
      playingState(nowPlayingTracks(2), context: _album),
      extra: [homeEditProvider.overrideWith(_EditingHome.new)],
    );
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.digit2);
    expect(container(tester).read(homeEditProvider).editing, isFalse);
    expect(container(tester).read(shellSectionProvider), YoinSection.library);
  });

  testWidgets('should_popPushedPage_when_ctrlLeftBracketPressed', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Center(child: Text('pushed')))),
    );
    await tester.pumpAndSettle();
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.bracketLeft);
    expect(find.text('pushed'), findsNothing);
  });

  testWidgets('should_closeNowPlaying_when_ctrlLeftBracketPressedWithPlayerOpen', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await keys(tester, [LogicalKeyboardKey.controlLeft, LogicalKeyboardKey.shiftLeft], LogicalKeyboardKey.keyP);
    expect(_player, findsOneWidget);
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.bracketLeft);
    expect(_player, findsNothing);
  });

  testWidgets('should_stayInApp_when_ctrlLeftBracketPressedAtTheRoot', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    final calls = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      calls.add(call.method);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.bracketLeft);
    expect(calls, isNot(contains('SystemNavigator.pop')));
  });

  testWidgets('should_leaveHeartToTheWindow_when_serviceKeepsNoFavorites', (tester) async {
    await pump(
      tester,
      playingState(nowPlayingTracks(2), context: _album),
      source: _NoFavoritesSource(),
    );
    await keys(tester, [LogicalKeyboardKey.controlLeft], LogicalKeyboardKey.keyD);
    expect(controller.calls, isNot(contains('toggleFavorite')));
  });
}

class _NoFavoritesSource extends NowPlayingFakeSource {
  @override
  Set<Capability> get capabilities => const {Capability.lyrics};
}
