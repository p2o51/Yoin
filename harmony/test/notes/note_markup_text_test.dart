import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/notes/mention_resolver.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/detail/scrapbook_focus.dart';
import 'package:yoin/notes/note_markup_text.dart';
import 'package:yoin/notes/note_mentions.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/library_fake_source.dart';

/// The span holding exactly [text] (a mention's name), first in reading order.
TextSpan? _spanOf(InlineSpan root, String text) {
  TextSpan? found;
  root.visitChildren((span) {
    if (span is TextSpan && span.text == text) {
      found = span;
      return false;
    }
    return true;
  });
  return found;
}

/// The span whose children hold the span reading exactly [text].
TextSpan? _parentOf(InlineSpan root, String text) {
  if (root is! TextSpan) return null;
  for (final child in root.children ?? const <InlineSpan>[]) {
    if (child is TextSpan && child.text == text) return root;
    final deeper = _parentOf(child, text);
    if (deeper != null) return deeper;
  }
  return null;
}

InlineSpan _drawn(WidgetTester tester) =>
    tester.widget<RichText>(find.descendant(of: find.byType(NoteMarkupText), matching: find.byType(RichText))).text;

Widget _app(Widget child, {List overrides = const []}) => ProviderScope(
  overrides: [...overrides],
  child: MaterialApp(
    theme: yoinTheme(Brightness.light),
    home: Scaffold(body: child),
  ),
);

const _link = '[@Blue](yoin:album/subsonic:al1?a=Joni)';

/// Android NoteMarkupText: marks drawn as glyphs and styles, mentions as their names — in the mention colour and
/// tappable when they resolve, plain words otherwise.
void main() {
  test('should_drawMarksAsGlyphsAndStyles_when_buildingTheSpan', () {
    final base = gsf(16);
    final span = buildNoteSpan(
      NoteMarkup.parse('# Side A\n- one\n- [x] **two** _three_\n- [ ] four'),
      base: base,
      mentionColor: Colors.red,
      glyphColor: Colors.blue,
    );
    expect(span.toPlainText(), 'Side A\n•  one\n☑  two three\n☐  four');
    // Bold is the font's weight and its variable `wght` axis (the theme's text is Google Sans Flex).
    final parentOfBold = _parentOf(span, 'two')!.style!;
    expect(parentOfBold.fontWeight, FontWeight.w700);
    expect(parentOfBold.fontVariations, contains(const FontVariation('wght', 700)));
    expect(_spanOf(span, '☑  ')!.style!.color, Colors.blue);
  });

  testWidgets('should_drawUnresolvedMentionAsPlainWords_when_nothingAnswers', (tester) async {
    await tester.pumpWidget(
      _app(NoteMarkupText('see [@Hannah Diamond](yoin:artist/spotify:hd) live', isMentionResolved: (_) => false)),
    );
    final drawn = _drawn(tester);
    expect(drawn.toPlainText(), 'see Hannah Diamond live');
    final name = _spanOf(drawn, 'Hannah Diamond')!;
    expect(name.style, isNull, reason: "the text's own colour, not grey, not the mention colour");
    expect(name.recognizer, isNull);
  });

  testWidgets('should_tintAndOpen_when_mentionResolves', (tester) async {
    NoteMention? opened;
    await tester.pumpWidget(
      _app(NoteMarkupText('**像** $_link 一样', isMentionResolved: (_) => true, onMentionTap: (m) => opened = m)),
    );
    final drawn = _drawn(tester);
    expect(drawn.toPlainText(), '像 Blue 一样');
    final name = _spanOf(drawn, 'Blue')!;
    final scheme = Theme.of(tester.element(find.byType(NoteMarkupText))).colorScheme;
    expect(name.style!.color, scheme.primary);
    expect(name.style!.fontWeight, FontWeight.w600);
    (name.recognizer! as TapGestureRecognizer).onTap!();
    expect(opened?.mediaId, 'subsonic:al1');
  });

  testWidgets('should_resolveForTheAccountInUse_when_noOverride', (tester) async {
    final store = MemoryStore(InMemoryStore(), profileId: 'p');
    await tester.runAsync(
      () => store.recordTrackPlay(LibraryFixtures.track('t9', artist: 'Weyes Blood', album: 'Titanic Rising'), atMs: 1),
    );
    const text = '$_link [@Song t9](yoin:track/spotify:sp9?a=Weyes%20Blood) [@Weyes Blood](yoin:artist/spotify:wb)';
    await tester.pumpWidget(
      _app(
        const NoteMarkupText(text),
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
          memoryStoreProvider.overrideWith((ref) async => store),
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
        ],
      ),
    );
    // The source lands, then the Spotify song's answer (by name, in the background).
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 10));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 10)));
    }
    await tester.pump();
    final drawn = _drawn(tester);
    expect(drawn.toPlainText(), 'Blue Song t9 Weyes Blood');
    expect(_spanOf(drawn, 'Blue')!.recognizer, isNotNull, reason: 'same provider: by id');
    expect(_spanOf(drawn, 'Song t9')!.recognizer, isNotNull, reason: 'a Spotify song known here by name');
    expect(_spanOf(drawn, 'Weyes Blood')!.style, isNull, reason: "an artist can't be matched by name");
  });

  testWidgets('should_openTheSongsAlbumScrapbookOnIt_when_trackMentionTapped', (tester) async {
    final store = MemoryStore(InMemoryStore(), profileId: 'p');
    await tester.runAsync(
      () => store.recordTrackPlay(LibraryFixtures.track('t1', albumId: 'al1', album: 'Blue', artist: 'Joni'), atMs: 1),
    );
    final container = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
        memoryStoreProvider.overrideWith((ref) async => store),
        keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      ],
    );
    addTearDown(container.dispose);
    final left = <void Function(BuildContext)>[];
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: Scaffold(
            body: NoteMentionLeave(leave: left.add, child: const NoteMarkupText('[@Song t1](yoin:track/subsonic:t1)')),
          ),
        ),
      ),
    );
    await tester.runAsync(() => container.read(activeSourceProvider.future));
    await tester.pump();
    final recognizer = _spanOf(_drawn(tester), 'Song t1')!.recognizer! as TapGestureRecognizer;
    await tester.runAsync(() async {
      recognizer.onTap!();
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    expect(left, hasLength(1), reason: 'the surface gets out of the way first (Now Playing closes)');

    final notes = container.read(noteMentionsProvider.notifier);
    final song = NoteMarkup.mentions('[@Song t1](yoin:track/subsonic:t1)').single;
    final target = await tester.runAsync(
      () => notes.destinationOf(MentionResolved(song, MediaId.subsonic('t1'), MentionVia.id)),
    );
    expect(target, isA<NoteAlbumScrapbook>());
    target as NoteAlbumScrapbook;
    expect(target.albumId, MediaId.subsonic('al1'));
    expect(target.focusTrack, MediaId.subsonic('t1'));
    final artist = await tester.runAsync(
      () => notes.destinationOf(
        MentionResolved(
          NoteMarkup.mentions('[@Joni](yoin:artist/subsonic:ar1)').single,
          MediaId.subsonic('ar1'),
          MentionVia.id,
        ),
      ),
    );
    expect((artist as NoteArtist).artistId, MediaId.subsonic('ar1'));
    expect(container.read(scrapbookFocusRequestProvider), isNull, reason: 'asked only when the page opens');
  });
}
