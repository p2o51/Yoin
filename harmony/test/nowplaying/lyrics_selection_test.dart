import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyric_tools.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/lyrics_selection.dart';
import 'package:yoin/nowplaying/nowplaying_strings.dart';

/// NowPlayingStrings as the player reads them under [lang].
Future<NowPlayingStrings> _stringsFor(WidgetTester tester, String lang) async {
  late NowPlayingStrings strings;
  await tester.pumpWidget(
    Localizations(
      locale: Locale(lang),
      delegates: const [DefaultWidgetsLocalizations.delegate],
      child: Builder(
        builder: (context) {
          strings = NowPlayingStrings.of(context);
          return const SizedBox();
        },
      ),
    ),
  );
  return strings;
}

final _rows = [for (var i = 0; i < 20; i++) LyricRow('line $i', startMs: i * 1000)];

void main() {
  group('nextLyricSelection', () {
    test('should_addLine_when_notPicked', () {
      final step = nextLyricSelection({1}, 4);
      expect(step.selected, {1, 4});
      expect(step.hitLimit, isFalse);
    });

    test('should_letGo_when_alreadyPicked', () {
      expect(nextLyricSelection({1, 4}, 4).selected, {1});
    });

    test('should_refuse_when_pickIsFull', () {
      final full = {for (var i = 0; i < maxSelectedLyricLines; i++) i};
      final step = nextLyricSelection(full, 18);
      expect(step.hitLimit, isTrue);
      expect(step.selected, full);
    });

    test('should_stillLetGo_when_pickIsFull', () {
      final full = {for (var i = 0; i < maxSelectedLyricLines; i++) i};
      expect(nextLyricSelection(full, 3).selected.length, maxSelectedLyricLines - 1);
    });
  });

  group('lyricSelectionRole', () {
    test('should_beIdle_when_nothingPicked', () => expect(lyricSelectionRole(2, {}), LyricSelectionRole.idle));
    test('should_bePicked_when_inPick', () => expect(lyricSelectionRole(2, {2}), LyricSelectionRole.picked));
    test('should_beOpen_when_roomLeft', () => expect(lyricSelectionRole(3, {2}), LyricSelectionRole.open));
    test('should_beOutside_when_full', () {
      final full = {for (var i = 0; i < maxSelectedLyricLines; i++) i};
      expect(lyricSelectionRole(17, full), LyricSelectionRole.outside);
    });
  });

  test('should_orderPickAndDropStaleIndices_when_readingSelectedLines', () {
    final lines = selectedLyricLines(_rows, {5, 2, 99, -1});
    expect(lines.map((l) => l.text), ['line 2', 'line 5']);
  });

  test('should_markEachSkip_when_pickJumps', () {
    expect(lyricPassageStarts(_rows, {2, 3, 7, 8, 12}), {2, 4});
    expect(lyricPassageStarts(_rows, {4, 5, 6}), isEmpty);
  });

  group('lyricsClipboardText', () {
    testWidgets('should_appendCredit_when_titleAndArtist', (tester) async {
      final en = await _stringsFor(tester, 'en');
      final text = lyricsClipboardText(
        selectedLyricLines(_rows, {1, 2}),
        includeTranslation: false,
        strings: en,
        songTitle: 'Song',
        artist: 'Artist',
      );
      expect(text, 'line 1\nline 2\n\n— Song · Artist');
    });

    testWidgets('should_creditTitleOnly_when_noArtist', (tester) async {
      final en = await _stringsFor(tester, 'en');
      final text = lyricsClipboardText([_rows[0]], includeTranslation: false, strings: en, songTitle: 'Song');
      expect(text, 'line 0\n\n— Song');
    });

    testWidgets('should_leaveBlankLineBetweenPassages_when_pickSkips', (tester) async {
      final en = await _stringsFor(tester, 'en');
      final picked = {1, 2, 6};
      final text = lyricsClipboardText(
        selectedLyricLines(_rows, picked),
        includeTranslation: false,
        strings: en,
        passageStarts: lyricPassageStarts(_rows, picked),
      );
      expect(text, 'line 1\nline 2\n\nline 6');
    });

    testWidgets('should_includeTranslation_when_shown', (tester) async {
      final en = await _stringsFor(tester, 'en');
      const rows = [LyricRow('hello', startMs: 0, translation: '你好'), LyricRow('bye', startMs: 1)];
      expect(lyricsClipboardText(rows, includeTranslation: true, strings: en), 'hello\n你好\nbye');
      expect(lyricsClipboardText(rows, includeTranslation: false, strings: en), 'hello\nbye');
    });
  });

  group('lyricsSelectionLabel', () {
    testWidgets('should_followAndroidStrings_when_counting', (tester) async {
      final en = await _stringsFor(tester, 'en');
      expect(lyricsSelectionLabel(0, en), 'Select lines');
      expect(lyricsSelectionLabel(3, en), '3 / 15');
      expect(lyricsSelectionLabel(15, en), '15 / 15');
      expect(lyricsSelectionLabel(4, en, limitNudge: true), '15 / 15');
      final zh = await _stringsFor(tester, 'zh');
      expect(lyricsSelectionLabel(0, zh), '选择歌词行');
    });
  });

  group('LyricsSelection', () {
    test('should_pickAndRefusePastCap_when_active', () {
      final s = LyricsSelection(maxLines: 2);
      expect(s.toggle(1), isFalse, reason: 'not in select mode');
      s.enter();
      expect(s.toggle(1), isTrue);
      expect(s.toggle(2), isTrue);
      expect(s.toggle(3), isFalse);
      expect(s.selected, {1, 2});
      s.exit();
      expect(s.active, isFalse);
      expect(s.selected, isEmpty);
    });
  });

  group('lyricToolsIdleEligible', () {
    bool eligible({
      bool playing = true,
      bool synced = true,
      bool follow = true,
      bool lyricsPage = true,
      bool onScreen = true,
      bool selecting = false,
    }) => lyricToolsIdleEligible(
      isPlaying: playing,
      hasSyncedLyrics: synced,
      autoScroll: follow,
      lyricsPageSelected: lyricsPage,
      toolsOnScreen: onScreen,
      selectingLines: selecting,
    );

    test('should_beEligible_when_playingSyncedLyricsFollow', () => expect(eligible(), isTrue));
    test('should_stay_when_paused', () => expect(eligible(playing: false), isFalse));
    test('should_stay_when_unsynced', () => expect(eligible(synced: false), isFalse));
    test('should_stay_when_scrolledAway', () => expect(eligible(follow: false), isFalse));
    test('should_stay_when_notePage', () => expect(eligible(lyricsPage: false), isFalse));
    test('should_stay_when_toolsOffScreen', () => expect(eligible(onScreen: false), isFalse));
    test('should_stay_when_selectingLines', () => expect(eligible(selecting: true), isFalse));
  });

  group('lyricsKeyWidths', () {
    test('should_keepTotal_when_middleKeyPressed', () {
      final w = lyricsKeyWidths(4, 52, (i) => i == 1 ? 1 : 0);
      expect(w[1], closeTo(52 * 1.15, 1e-9));
      expect(w[0], closeTo(52 - 52 * 0.15 / 2, 1e-9));
      expect(w[2], closeTo(52 - 52 * 0.15 / 2, 1e-9));
      expect(w.reduce((a, b) => a + b), closeTo(52 * 4, 1e-9));
    });

    test('should_takeFromOneNeighbour_when_endKeyPressed', () {
      final w = lyricsKeyWidths(3, 52, (i) => i == 0 ? 1 : 0);
      expect(w[1], closeTo(52 * 0.85, 1e-9));
      expect(w[2], 52);
    });

    test('should_neverGoNegative_when_overshooting', () {
      final w = lyricsKeyWidths(2, 52, (i) => 10);
      expect(w.every((x) => x >= 0), isTrue);
    });
  });

  test('should_useAndroidIdleDelay_when_toolsIdle', () {
    expect(LyricToolsMetrics.idleMs, 3000);
  });
}
