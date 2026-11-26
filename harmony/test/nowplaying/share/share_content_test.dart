// What Now Playing hands the share sheet (Android ShareSources.content): the
// pick or the current line, About from the lyrics' credits, the server's
// composer and year and the AI rows (focused on the long-pressed one), one
// note, the AI service, frozen when the sheet opens.
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/nowplaying_strings.dart';
import 'package:yoin/nowplaying/share_content.dart';

SongAboutEntry _row(String kind, String key, String answer, {String? title}) => SongAboutEntry(
  titleKey: 't',
  artistKey: 'a',
  albumKey: '',
  titleDisplay: 'T',
  artistDisplay: 'A',
  albumDisplay: '',
  kind: kind,
  entryKey: key,
  titleText: title,
  answerText: answer,
  createdAt: 1,
  updatedAt: 1,
);

SongNote _note(String id, String content, int? at, int created) => SongNote(
  id: id,
  trackId: MediaId.subsonic('s1'),
  content: content,
  createdAt: created,
  updatedAt: created,
  title: 'Song',
  artist: 'Artist',
  positionMs: at,
);

void main() {
  late NowPlayingStrings strings;

  testWidgets('should_freezeEveryTab_when_built', (tester) async {
    await tester.pumpWidget(
      Localizations(
        locale: const Locale('zh'),
        delegates: const [DefaultWidgetsLocalizations.delegate],
        child: Builder(
          builder: (context) {
            strings = NowPlayingStrings.of(context);
            return const SizedBox();
          },
        ),
      ),
    );
    final track = Track(id: MediaId.subsonic('s1'), title: 'Song', artist: 'Artist', album: 'LP', year: 2009);
    final ask = _row('ask', 'who', 'An answer.', title: 'Who plays');
    ShareContent build({SongAboutEntry? focus, String? noteId, ShareLyrics? lyrics}) => nowPlayingShareContent(
      track: track,
      coverUrl: 'https://example.com/c.jpg',
      durationMs: 200000,
      lyricRows: const [LyricRow('one', startMs: 1000), LyricRow('two', startMs: 5000)],
      showTranslation: false,
      positionMs: 6000,
      aboutEntries: [_row('canonical', 'review', 'The review.'), _row('canonical', 'producer', 'P'), ask],
      credits: const LyricCredits([LyricCredit('作词', 'L', headline: LyricCreditRole.lyricist)]),
      notes: [_note('a', 'first', 1000, 1), _note('b', 'later', 9000, 2), _note('c', '  ', 2000, 3)],
      strings: strings,
      aiProvider: AiProvider.qwen,
      aboutFocus: focus,
      noteId: noteId,
      lyrics: lyrics,
    );

    final content = build();
    expect(content.song.coverArtUrl, 'https://example.com/c.jpg');
    expect(content.song.durationMs, 200000);
    // No pick: the line the playhead is in.
    expect(content.lyrics!.lines.single.text, 'two');
    expect(
      [for (final c in content.about!.credits) '${c.label}=${c.value}${c.ai ? '*' : ''}${c.fact ? '!' : ''}'],
      ['作词=L', '制作人=P*', '年份=2009!'],
    );
    expect(content.about!.heading, '关于');
    expect(content.about!.paragraph, 'The review.');
    // The note the playhead is in (blank ones never).
    expect(content.note!.noteId, 'a');
    expect(content.aiProvider, AiProvider.qwen);
    expect(content.tabs, ShareTab.values);

    final focused = build(focus: ask, noteId: 'b');
    expect(focused.about!.heading, 'Who plays');
    expect(focused.about!.paragraph, 'An answer.');
    expect(focused.note!.noteId, 'b');

    const pick = ShareLyrics(lines: [ShareLyricLine(startMs: 1000, text: 'one')]);
    expect(build(lyrics: pick).lyrics, pick);
  });

  test('should_readNothing_when_theStoreHoldsAnotherTabsLayout', () async {
    final store = ShareLayoutStore.inMemory();
    await store.load();
    store.setLayout(ShareTab.lyrics, ShareLayout.noteBig);
    expect(store.layoutFor(ShareTab.lyrics), isNull);
    store.setLayout(ShareTab.lyrics, ShareLayout.lyricsPlayer);
    expect(store.layoutFor(ShareTab.lyrics), ShareLayout.lyricsPlayer);
  });

  test('should_writeFullDates_when_signingANote', () {
    final at = DateTime(2026, 7, 18, 9).millisecondsSinceEpoch;
    expect(shareNoteDate(at, const Locale('zh')), '2026年7月18日');
    expect(shareNoteDate(at, const Locale('en')), 'July 18, 2026');
  });
}
