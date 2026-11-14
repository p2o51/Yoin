import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/memories/memories_reveal_scope.dart';
import 'package:yoin/memories/memories_screen.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/memories/memory_title_editor.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_playback_engine.dart';

MemoryEntry _memory(String id, {String? title}) => MemoryEntry(
  sessionId: id.hashCode,
  albumId: MediaId.subsonic(id),
  albumName: 'Glass Orchard $id',
  artistName: 'The Meridians',
  scoreKind: MemoryScoreKind.none,
  memoryTitle: title ?? 'Glass Orchard $id',
  memoryTitleKind: title == null ? MemoryTitleKind.album : MemoryTitleKind.user,
  proseLanguage: MemoryProseLanguage.en,
  yoinQuestion: 'What stays with you from this one?',
  excerptCandidates: const [
    MemoryExcerptCandidate(null, MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Nov 14')),
  ],
  excerptCandidatesMedium: const [
    MemoryExcerptCandidate(null, MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Nov 14')),
  ],
);

class _TitleDeck extends MemoriesViewModel {
  _TitleDeck(this.fixed);
  final MemoriesContent fixed;
  final saved = <String>[];
  var restored = 0;

  @override
  MemoriesUiState build() => fixed;

  @override
  Future<void> ensureLoaded({int? focus}) async {}

  @override
  Future<bool> saveTitle(MemoryEntry memory, String title) async {
    saved.add(title);
    return true;
  }

  @override
  Future<bool> restoreTitle(MemoryEntry memory) async {
    restored++;
    return true;
  }
}

void main() {
  group('rules', () {
    test('should_changeNothing_when_sameWordsSaved', () {
      expect(titleEditOutcome(' Mine ', 'Mine', userTitleInUse: true), isA<TitleUnchanged>());
    });

    test('should_restore_when_emptySavedOverUserTitle', () {
      expect(titleEditOutcome('  ', 'Mine', userTitleInUse: true), isA<TitleRestore>());
      expect(titleEditOutcome('', '', userTitleInUse: false), isA<TitleUnchanged>());
    });

    test('should_saveTrimmed_when_newWordsSaved', () {
      expect(titleEditOutcome('  The rain year ', '', userTitleInUse: false), const TitleSave('The rain year'));
    });

    test('should_openEmpty_when_albumNameStandsIn', () {
      expect(memoryTitleDraftSeed(_memory('a')), '');
      expect(memoryTitleDraftSeed(_memory('a', title: 'Mine')), 'Mine');
      expect(memoryTitlePlaceholder(_memory('a')), 'Glass Orchard a');
      expect(memoryTitleCanRestore(_memory('a', title: 'Mine')), isFalse);
    });

    test('should_flattenAndCap_when_pastedIntoField', () {
      const f = MemoryTitleInputFormatter();
      final pasted = f.formatEditUpdate(
        TextEditingValue.empty,
        const TextEditingValue(text: 'one\ntwo', selection: TextSelection.collapsed(offset: 7)),
      );
      expect(pasted.text, 'one two');
      final long = f.formatEditUpdate(
        TextEditingValue.empty,
        TextEditingValue(text: 'x' * 100, selection: const TextSelection.collapsed(offset: 100)),
      );
      expect(long.text.length, memoryTitleMaxLength);
      expect(long.selection.baseOffset, memoryTitleMaxLength);
    });

    test('should_rise_when_keyboardCoversTitleBlock', () {
      expect(memoryTitleLiftPx(blockBottom: 500, visibleBottom: 480, clearance: 24), 44);
      expect(memoryTitleLiftPx(blockBottom: 300, visibleBottom: 480, clearance: 24), 0);
    });
  });

  group('deck', () {
    late _TitleDeck deck;
    late ProviderContainer container;

    Future<void> pump(WidgetTester tester, {Size size = const Size(392, 850), String? title}) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      deck = _TitleDeck(MemoriesContent([_memory('a', title: title), _memory('b')]));
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            keyValueStoreProvider.overrideWithValue(InMemoryStore()),
            playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
            activeSourceProvider.overrideWith((ref) async => null),
            memoriesViewModelProvider.overrideWith(() => deck),
          ],
          child: MaterialApp(home: Scaffold(body: _Host())),
        ),
      );
      container = ProviderScope.containerOf(tester.element(find.byType(_Host)));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
    }

    Finder titleOf(String text) => find.descendant(of: find.byType(MemoryTitleSlot), matching: find.text(text));

    testWidgets('should_saveUserTitle_when_cardTitleEditedAndSaved', (tester) async {
      await pump(tester);
      await tester.tap(titleOf('Glass Orchard a').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.descendant(of: find.byType(MemoryTitleSlot), matching: find.byType(TextField)), findsOneWidget);
      expect(find.text('Save'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'The rain year');
      await tester.tap(find.text('Save'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(deck.saved, ['The rain year']);
      expect(find.descendant(of: find.byType(MemoryTitleSlot), matching: find.byType(TextField)), findsNothing);
      // The saved words show at once, before the deck's refresh lands.
      expect(titleOf('The rain year'), findsOneWidget);
    });

    testWidgets('should_restore_when_emptyTitleSaved', (tester) async {
      await pump(tester, title: 'Mine');
      await tester.tap(titleOf('Mine').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.enterText(find.byType(TextField), '');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(deck.restored, 1);
      expect(deck.saved, isEmpty);
    });

    testWidgets('should_cancelEditFirst_when_systemBack', (tester) async {
      await pump(tester);
      await tester.tap(titleOf('Glass Orchard a').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      final pageView = tester.widget<PageView>(find.byType(PageView));
      expect(pageView.physics, isA<NeverScrollableScrollPhysics>());

      container.read(memoriesRevealHandleProvider).back();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.descendant(of: find.byType(MemoryTitleSlot), matching: find.byType(TextField)), findsNothing);
      expect(deck.saved, isEmpty);
      expect(tester.widget<PageView>(find.byType(PageView)).physics, isNot(isA<NeverScrollableScrollPhysics>()));
    });

    testWidgets('should_editLeftPageTitle_when_spread', (tester) async {
      await pump(tester, size: const Size(1280, 800));
      await tester.tap(titleOf('Glass Orchard a').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.descendant(of: find.byType(MemoryTitleSlot), matching: find.byType(TextField)), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.descendant(of: find.byType(MemoryTitleSlot), matching: find.byType(TextField)), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });
}

class _Host extends StatefulWidget {
  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> with TickerProviderStateMixin {
  late final MemoriesRevealController _reveal = MemoriesRevealController(vsync: this)..snapTo(0);

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MemoriesLayer(reveal: _reveal);
}
