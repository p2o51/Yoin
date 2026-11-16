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
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

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

  Future<NowPlayingOverlayController> pump(WidgetTester tester, {bool ready = true}) async {
    tester.view.physicalSize = const Size(412, 892);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    kv = InMemoryStore();
    final secrets = InMemoryStore();
    if (ready) {
      await AiSettingsStore(kv, secrets).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
    }
    client = FakeAiClient(reply: _about);
    final tracks = [
      Track(id: MediaId.subsonic('t0'), title: 'Blue', artist: 'Joni', album: 'Blue', durationSec: 200, genre: 'Folk'),
    ];
    final controller = NowPlayingOverlayController(vsync: tester);
    addTearDown(controller.dispose);
    controller.window = const YoinWindowInfo(412, 892);
    controller.height = 892;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          ...nowPlayingOverrides(controller: NowPlayingFakeController(playingState(tracks))),
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
}
