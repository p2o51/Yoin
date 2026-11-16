import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/about_widgets.dart';
import 'package:yoin/nowplaying/ask_bar.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/nowplaying/stage_tabs.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/yoin_loading_indicator.dart';

import '../support/fake_ai_client.dart';
import '../support/nowplaying_fakes.dart';

const _about = '''
[CREATION_TIME]1971[/CREATION_TIME]
[CREATION_LOCATION]N/A[/CREATION_LOCATION]
[LYRICIST]Joni Mitchell[/LYRICIST]
[COMPOSER]N/A[/COMPOSER]
[PRODUCER]Henry Lewy[/PRODUCER]
[REVIEW]A quiet record.[/REVIEW]''';

/// Now Playing's About tab (Android SongInfoDisplay, AboutFullscreenPane,
/// AskGeminiBar, AboutEntrySheet) on a phone.
void main() {
  late InMemoryStore kv;
  late FakeAiClient client;
  late NowPlayingFakeController playback;
  final blue = Track(
    id: MediaId.subsonic('t0'),
    title: 'Blue',
    artist: 'Joni',
    album: 'Blue',
    durationSec: 200,
    genre: 'Folk',
  );

  Future<NowPlayingOverlayController> pump(
    WidgetTester tester, {
    bool ready = true,
    Size size = const Size(412, 892),
    List<Track>? queue,
    FakeAiClient? ai,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    kv = InMemoryStore();
    final secrets = InMemoryStore();
    if (ready) {
      await AiSettingsStore(kv, secrets).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
    }
    client = ai ?? FakeAiClient(reply: _about);
    final tracks = queue ?? [blue];
    playback = NowPlayingFakeController(playingState(tracks));
    final controller = NowPlayingOverlayController(vsync: tester);
    addTearDown(controller.dispose);
    controller.window = YoinWindowInfo(size.width, size.height);
    controller.height = size.height;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          ...nowPlayingOverrides(controller: playback),
          upNextPrefetchDelayProvider.overrideWithValue(Duration.zero),
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(secrets),
          aiClientProvider.overrideWithValue(client),
        ],
        child: MaterialApp(
          home: Material(
            child: NowPlayingOverlayScope(
              controller: controller,
              child: NowPlayingScreen(controller: controller),
            ),
          ),
        ),
      ),
    );
    controller.openPlayer();
    await tester.pumpAndSettle();
    return controller;
  }

  testWidgets('should_offerAboutTabBetweenLyricsAndNote_when_playerOpen', (tester) async {
    await pump(tester);
    final lyrics = tester.getCenter(find.text('Lyrics'));
    final about = tester.getCenter(find.text('About'));
    final note = tester.getCenter(find.text('Note'));
    expect(lyrics.dx < about.dx && about.dx < note.dx, isTrue);
  });

  testWidgets('should_fetchOnceAndShowReceipt_when_aboutOpened', (tester) async {
    final controller = await pump(tester);
    await tester.tap(find.text('About'));
    await tester.pumpAndSettle();
    expect(client.prompts, hasLength(1));
    expect(find.byType(SongInfoDisplay), findsOneWidget);
    // The carousel's first page: three receipt lines (the AI's credits first).
    expect(find.text('Lyricist'), findsWidgets);
    expect(find.text('Joni Mitchell'), findsWidgets);

    controller.expandStage();
    await tester.pumpAndSettle();
    expect(find.byType(AboutPane), findsOneWidget);
    expect(find.byType(AskGeminiBar), findsOneWidget);
    expect(find.text('Henry Lewy'), findsWidgets);
    expect(find.text('Genre'), findsWidgets);
    expect(find.text('A quiet record.'), findsWidgets);
    // Opening it again asks nothing new.
    await tester.tap(find.text('Lyrics').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('About').last);
    await tester.pumpAndSettle();
    expect(client.prompts, hasLength(1));
    final rows = await SongAboutStore.shared(kv).all();
    expect(rows.where((r) => r.isCanonical), hasLength(6));
  });

  testWidgets('should_loadTheNewSongsAbout_when_theSongChangesWhileOnAbout', (tester) async {
    // Android 11-16 (5145074a9): staying on About across a song change loads the new song's About.
    final river = Track(
      id: MediaId.subsonic('t1'),
      title: 'River',
      artist: 'Joni',
      album: 'Blue',
      durationSec: 240,
      genre: 'Folk',
    );
    final tracks = [blue, river];
    await pump(tester, queue: tracks);
    await tester.tap(find.text('About'));
    await tester.pumpAndSettle();
    expect(client.prompts, hasLength(1));

    client.reply = _about.replaceAll('Henry Lewy', 'Joni Mitchell');
    playback.emit(playingState(tracks, index: 1));
    await tester.pumpAndSettle();
    expect(client.prompts, hasLength(2));
    expect(client.prompts.last, contains('River'));
    expect(find.text('Load song info'), findsNothing);
  });

  testWidgets('should_showTheBusyIndicatorInTheTabRow_when_theAiLoadsOverServerFacts', (tester) async {
    // Android 11-16 (5145074a9): the server's facts show at once; the wait sits between About and Note.
    final gated = _GatedAiClient(reply: _about);
    await pump(tester, ai: gated);
    await tester.tap(find.text('About'));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    final busy = find.descendant(of: find.byType(StageTabs), matching: find.byType(YoinLoadingIndicator));
    expect(busy, findsOneWidget);
    final x = tester.getCenter(busy).dx;
    expect(x, greaterThan(tester.getCenter(find.text('About')).dx));
    expect(x, lessThan(tester.getCenter(find.text('Note')).dx));
    expect(find.text('Folk'), findsWidgets);
    expect(find.text('Searching for song info…'), findsNothing);

    gated.gate.complete();
    await tester.pumpAndSettle();
    expect(busy, findsNothing);
    expect(find.text('Joni Mitchell'), findsWidgets);
  });

  testWidgets('should_notFetch_when_theSongChangesOnAnotherPage', (tester) async {
    final river = Track(id: MediaId.subsonic('t1'), title: 'River', artist: 'Joni', album: 'Blue', durationSec: 240);
    final tracks = [blue, river];
    await pump(tester, queue: tracks);
    playback.emit(playingState(tracks, index: 1));
    await tester.pumpAndSettle();
    expect(client.prompts, isEmpty);
  });

  testWidgets('should_showNoKeyAndSettings_when_noAiService', (tester) async {
    await pump(tester, ready: false);
    await tester.tap(find.text('About'));
    await tester.pumpAndSettle();
    expect(find.text('No Gemini API key'), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);
    expect(client.prompts, isEmpty);
  });

  testWidgets('should_askAndListTheAnswer_when_quickPromptSent', (tester) async {
    final controller = await pump(tester);
    await tester.tap(find.text('About'));
    await tester.pumpAndSettle();
    controller.expandStage();
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ask Gemini'));
    await tester.pumpAndSettle();
    expect(find.byType(QuickPrompts), findsOneWidget);
    await tester.tap(find.text('What does this song sample?'));
    await tester.pumpAndSettle();
    expect(find.byType(QuickPrompts), findsNothing, reason: 'the field is no longer empty');

    client.reply = '[TITLE]Writing Blue[/TITLE][ANSWER]At a piano in Matala.[/ANSWER]';
    await tester.testTextInput.receiveAction(TextInputAction.send);
    await tester.pumpAndSettle();
    expect(find.text('Writing Blue'), findsWidgets);
    expect(find.text('At a piano in Matala.'), findsOneWidget);
    final ask = (await SongAboutStore.shared(kv).all()).where((r) => r.kind == SongAboutKinds.ask).single;
    expect(ask.promptText, 'What does this song sample?');
    // One Send asks once: one answer call and one heading call, nothing doubled.
    expect(client.prompts.where((p) => p.startsWith('Answer the following question')), hasLength(1));
    expect(client.prompts.where((p) => p.startsWith('Write a concise visual heading')), hasLength(1));
    // Back at rest, and the prompt already asked is not offered again.
    expect(find.text('Ask Gemini'), findsOneWidget);
    await tester.tap(find.text('Ask Gemini'));
    await tester.pumpAndSettle();
    expect(find.text('What does this song sample?'), findsNothing);
  });

  testWidgets('should_openRowSheetWithRegenerate_when_receiptRowLongPressed', (tester) async {
    final controller = await pump(tester);
    await tester.tap(find.text('About'));
    await tester.pumpAndSettle();
    controller.expandStage();
    await tester.pumpAndSettle();
    await tester.longPress(find.text('Henry Lewy').last);
    await tester.pumpAndSettle();
    expect(find.text('Regenerate with Gemini'), findsOneWidget);
    // The song has no album page here: nothing to manage.
    expect(find.text('Manage in Scrapbook'), findsNothing);

    client.reply = _about.replaceAll('Henry Lewy', 'H. Lewy');
    await tester.tap(find.text('Regenerate with Gemini'));
    await tester.pumpAndSettle();
    expect(client.prompts, hasLength(2));
    expect(find.text('H. Lewy'), findsWidgets);
  });

  testWidgets('should_showPaneAndAskBarInRightColumn_when_dualPane', (tester) async {
    final controller = await pump(tester, size: const Size(1280, 800));
    if (!controller.fullscreen) {
      controller.toggleFullscreen();
      await tester.pumpAndSettle();
    }
    await tester.tap(find.text('About').last);
    await tester.pumpAndSettle();
    expect(find.byType(AboutPane), findsOneWidget);
    expect(find.byType(AskGeminiBar), findsOneWidget);
    expect(find.text('Henry Lewy'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_showAboutInLandscape_when_handsetTurned', (tester) async {
    final details = <FlutterErrorDetails>[];
    final previous = FlutterError.onError;
    FlutterError.onError = details.add;
    await pump(tester, size: const Size(892, 412));
    FlutterError.onError = previous;
    expect(details, isEmpty, reason: 'before About');
    FlutterError.onError = details.add;
    await tester.tap(find.text('About').last);
    await tester.pumpAndSettle();
    FlutterError.onError = previous;
    expect(find.byType(SongInfoDisplay).evaluate().length + find.byType(AboutPane).evaluate().length, 1);
    expect(details, isEmpty);
  });
}

/// Holds the AI's answers until [gate] completes: About stays loading.
class _GatedAiClient extends FakeAiClient {
  _GatedAiClient({super.reply});

  final gate = Completer<void>();

  @override
  Future<String> generate(
    AiSettings ai,
    String prompt, {
    required double temperature,
    required bool useSearch,
    Duration? timeout,
  }) async {
    await gate.future;
    return super.generate(ai, prompt, temperature: temperature, useSearch: useSearch, timeout: timeout);
  }
}
