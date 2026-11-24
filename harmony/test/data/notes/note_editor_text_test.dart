// The editor's text rules, ported from Android's NoteEditorTextTest: the same
// cases, run on the editor's text (a mention is one U+FFFC) and compared, once
// decoded, with what Android stores.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/notes/note_editor_text.dart';
import 'package:yoin/data/notes/note_markup.dart';

void main() {
  final andromeda = NoteMention(
    kind: MentionKind.track,
    provider: 'subsonic',
    id: 't2',
    title: 'Andromeda',
    artist: 'Weyes Blood',
    album: 'Titanic Rising',
  );
  final link = NoteMarkup.serializeMention(andromeda);
  const o = objectReplacement;

  group('stored text ⇄ editor text', () {
    test('should_holdEachMentionAsOneCharacter_when_encoding', () {
      final e = NoteEditorText.encode('a $link b');
      expect(e.text, 'a $o b');
      expect(e.atoms.single.mention, andromeda);
      expect(NoteEditorText.decode(e.text, e.atoms), 'a $link b');
    });

    test('should_roundTripEveryGoldenText_byteForByte', () {
      final golden =
          jsonDecode(File('../shared/src/commonTest/resources/golden/note-markup.json').readAsStringSync())
              as Map<String, dynamic>;
      final texts = [
        for (final v in golden['parse'] as List) (v as Map)['text'] as String,
        '$o literal and $link',
      ];
      for (final text in texts) {
        final e = NoteEditorText.encode(text);
        expect(NoteEditorText.decode(e.text, e.atoms), text, reason: jsonEncode(text));
        expect(o.allMatches(e.text).length, e.atoms.length);
      }
    });

    test('should_mapAStoredOffsetPastALink_toTheChipsEnd', () {
      final stored = 'x $link y';
      expect(NoteEditorText.toEditorOffset(stored, 0), 0);
      expect(NoteEditorText.toEditorOffset(stored, 2 + link.length), 3);
      expect(NoteEditorText.toEditorOffset(stored, 5), 3);
      expect(NoteEditorText.toEditorOffset(stored, stored.length), 5);
    });
  });

  group('@ trigger', () {
    test('should_openTheTrigger_when_anAtStartsAWord', () {
      expect(NoteEditorText.trigger('@', 1), const MentionTrigger(0, 1, ''));
      expect(NoteEditorText.trigger('和那句 x @coc', 10), const MentionTrigger(6, 10, 'coc'));
      expect(NoteEditorText.trigger('副歌前那口气，和@他夏', 11)?.query, '他夏');
      expect(NoteEditorText.trigger('line\n＠cla', 9)?.query, 'cla');
    });

    test('should_stayShut_when_theAtIsAnAddressASpaceFollowsOrAChipIsBetween', () {
      expect(NoteEditorText.trigger('mail me at a@b', 14), isNull);
      expect(NoteEditorText.trigger('@two words', 10), isNull);
      expect(NoteEditorText.trigger('plain', 5), isNull);
      expect(NoteEditorText.trigger('@a${o}b', 4), isNull);
      expect(NoteEditorText.trigger('@${'a' * (NoteEditorText.maxQuery + 1)}', NoteEditorText.maxQuery + 2), isNull);
    });

    test('should_keepTheTriggerOpen_acrossSpacesTheKeyboardIsStillComposing', () {
      const text = '像 @niu yue';
      expect(
        NoteEditorText.trigger(text, text.length, composingStart: 3, composingEnd: text.length)?.query,
        'niu yue',
      );
      expect(NoteEditorText.trigger(text, text.length), isNull);
      expect(NoteEditorText.trigger('@a b', 4, composingStart: 3, composingEnd: 4), isNull);
    });

    test('should_openTheTrigger_onAFullWidthAt_followedByCommittedChinese', () {
      expect(NoteEditorText.trigger('＠灯塔', 3), const MentionTrigger(0, 3, '灯塔'));
      expect(NoteEditorText.trigger('这首像＠灯塔', 6)?.query, '灯塔');
      expect(NoteEditorText.trigger('这首像＠', 4)?.query, '');
      final fix = NoteEditorText.insertMention('这首像＠灯塔', const [], 6, 6, NoteEditorText.trigger('这首像＠灯塔', 6), andromeda);
      expect(NoteEditorText.decode(fix.edit.text, fix.atoms), '这首像$link ');
    });
  });

  group('picking a mention', () {
    test('should_replaceTheTriggerWithTheChipAndASpace_when_aMentionIsPicked', () {
      const text = '像 @andr 的';
      final trigger = NoteEditorText.trigger(text, 7)!;
      final edit = NoteEditorText.insertMention(text, const [], 7, 7, trigger, andromeda);
      expect(NoteEditorText.decode(edit.edit.text, edit.atoms), '像 $link 的');
      expect(edit.edit.text, '像 $o 的');
      expect(edit.edit.selectionStart, 4);
      final atCaret = NoteEditorText.insertMention('x', const [], 0, 0, null, andromeda);
      expect(NoteEditorText.decode(atCaret.edit.text, atCaret.atoms), '$link x');
    });

    test('should_keepOtherChipsInOrder_when_oneIsInsertedBetweenThem', () {
      final other = NoteMention(kind: MentionKind.album, provider: 'subsonic', id: 'al', title: 'Other');
      final e = NoteEditorText.encode('${NoteMarkup.serializeMention(other)} @an ${NoteMarkup.serializeMention(other)}');
      final trigger = NoteEditorText.trigger(e.text, 5)!;
      final fix = NoteEditorText.insertMention(e.text, e.atoms, 5, 5, trigger, andromeda);
      expect(fix.atoms.map((a) => a.mention?.title), ['Other', 'Andromeda', 'Other']);
    });

    test('should_turnTheNewlineIntoThePick_whateverShapeTheKeyboardDeliversItIn', () {
      const original = 'x @Ni y';
      final trigger = NoteEditorText.trigger(original, 5)!;
      expect(NoteEditorText.isEnterAtTrigger(original, 'x @Ni\n y', trigger), isTrue);
      expect(NoteEditorText.isEnterAtTrigger(original, 'x @Nice\n y', trigger), isTrue);
      expect(NoteEditorText.isEnterAtTrigger(original, 'x @\n y', trigger), isTrue);
      expect(NoteEditorText.isEnterAtTrigger(original, 'x @Ni \n y', trigger), isTrue);
      final before = NoteEditorText.trigger('@an\nnext line', 3)!;
      expect(NoteEditorText.isEnterAtTrigger('@an\nnext line', '@an\n\nnext line', before), isTrue);
    });

    test('should_leaveOtherEditsAndNewlinesOutsideTheToken_asTyped', () {
      final trigger = NoteEditorText.trigger('@an', 3)!;
      expect(NoteEditorText.isEnterAtTrigger('@an', '@and', trigger), isFalse);
      expect(NoteEditorText.isEnterAtTrigger('@an', '@a', trigger), isFalse);
      final second = NoteEditorText.trigger('a\n@an', 5)!;
      expect(NoteEditorText.isEnterAtTrigger('a\n@an', '\na\n@an', second), isFalse);
      expect(NoteEditorText.isEnterAtTrigger('@an b', '@an\n', NoteEditorText.trigger('@an b', 3)!), isFalse);
    });
  });

  group('atomic chips', () {
    test('should_dropTheMention_when_backspaceTakesItsCharacter', () {
      final e = NoteEditorText.encode('a $link b');
      final out = NoteEditorText.syncAtoms(
        oldText: e.text,
        oldAtoms: e.atoms,
        edited: const NoteEdit('a  b', 2),
      );
      expect(NoteEditorText.decode(out.edit.text, out.atoms), 'a  b');
      expect(out.atoms, isEmpty);
    });

    test('should_dropTheRightChip_when_twoSitSideBySide', () {
      final other = NoteMention(kind: MentionKind.album, provider: 'subsonic', id: 'al', title: 'Other');
      final e = NoteEditorText.encode('$link${NoteMarkup.serializeMention(other)}');
      // Backspace after the first chip: the caret lands at 0, the second chip stays
      final out = NoteEditorText.syncAtoms(oldText: e.text, oldAtoms: e.atoms, edited: const NoteEdit(o, 0));
      expect(out.atoms.single.mention, other);
    });

    test('should_dropAPastedPlaceholder_butKeepAPickedOne', () {
      final pasted = NoteEditorText.syncAtoms(oldText: 'ab', oldAtoms: const [], edited: const NoteEdit('a${o}b', 2));
      expect(pasted.edit, const NoteEdit('ab', 1));
      final picked = NoteEditorText.syncAtoms(
        oldText: 'ab',
        oldAtoms: const [],
        edited: const NoteEdit('a${o}b', 2),
        incoming: [EditorAtom.of(andromeda)],
      );
      expect(NoteEditorText.decode(picked.edit.text, picked.atoms), 'a${link}b');
    });

    test('should_moveTheAtomsAlong_when_typingBeforeAChip', () {
      final e = NoteEditorText.encode('$link.');
      final out = NoteEditorText.syncAtoms(oldText: e.text, oldAtoms: e.atoms, edited: NoteEdit('zz$o.', 2));
      expect(NoteEditorText.decode(out.edit.text, out.atoms), 'zz$link.');
    });
  });

  group('lists and shortcuts', () {
    test('should_continueTheList_when_enterEndsAnItem', () {
      expect(NoteEditorText.reconcile('- one', '- one\n'), const NoteEdit('- one\n- ', 8));
      expect(NoteEditorText.reconcile('- [x] done', '- [x] done\n'), const NoteEdit('- [x] done\n- [ ] ', 17));
      expect(NoteEditorText.reconcile('# Title', '# Title\n'), isNull);
    });

    test('should_leaveTheList_when_enterLandsOnAnEmptyItem', () {
      expect(NoteEditorText.reconcile('- one\n- ', '- one\n- \n'), const NoteEdit('- one\n', 6));
      expect(NoteEditorText.reconcile('- [ ] ', '- [ ] \n'), const NoteEdit('', 0));
    });

    test('should_makeTodosAndHeadings_when_aShortcutIsTypedAtALineStart', () {
      expect(NoteEditorText.reconcile('[]', '[] '), const NoteEdit('- [ ] ', 6));
      expect(NoteEditorText.reconcile('x\n【】', 'x\n【】 '), const NoteEdit('x\n- [ ] ', 8));
      expect(NoteEditorText.reconcile('[x]', '[x] '), const NoteEdit('- [x] ', 6));
      expect(NoteEditorText.reconcile('＃', '＃ '), const NoteEdit('# ', 2));
      expect(NoteEditorText.reconcile('a []', 'a [] '), isNull);
    });

    test('should_leaveEditsAlone_when_nothingApplies', () {
      expect(NoteEditorText.reconcile('hello $o', 'hello! $o'), isNull);
      expect(NoteEditorText.reconcile('a $o b', 'a  b'), isNull);
    });
  });

  group('the formatting row', () {
    test('should_wrapAndUnwrap_when_boldOrItalicIsTapped', () {
      final bold = NoteEditorText.apply(NoteTool.bold, 'the hook', 4, 8);
      expect(bold, const NoteEdit('the **hook**', 6, 10));
      expect(NoteEditorText.apply(NoteTool.bold, bold.text, 6, 10), const NoteEdit('the hook', 4, 8));
      expect(NoteEditorText.apply(NoteTool.italic, '副歌轻', 2, 3), const NoteEdit('副歌_轻_', 3, 4));
      expect(NoteEditorText.apply(NoteTool.italic, 'abc', 2, 3).text, 'ab*c*');
      expect(NoteEditorText.apply(NoteTool.bold, '', 0, 0), const NoteEdit('****', 2));
    });

    test('should_setAndClearBlocks_overEverySelectedLine', () {
      final listed = NoteEditorText.apply(NoteTool.bullet, 'one\ntwo', 0, 7);
      expect(listed.text, '- one\n- two');
      expect(NoteEditorText.apply(NoteTool.bullet, listed.text, 3, 9).text, 'one\ntwo');
      expect(NoteEditorText.apply(NoteTool.checklist, '- one', 3, 3).text, '- [ ] one');
      expect(NoteEditorText.apply(NoteTool.heading, 'one', 1, 1).text, '# one');
      expect(NoteEditorText.blockAt('# one', 3), NoteTool.heading);
    });

    test('should_insertAnAt_withASpaceAfterAnAsciiWord', () {
      expect(NoteEditorText.apply(NoteTool.mention, 'hi', 2, 2), const NoteEdit('hi @', 4));
      expect(NoteEditorText.apply(NoteTool.mention, '副歌', 2, 2), const NoteEdit('副歌@', 3));
    });

    test('should_reportBoldAndItalic_whereTheCaretSits', () {
      expect(NoteEditorText.inlineAt('a **bold** b', 5, 5), contains(NoteTool.bold));
      expect(NoteEditorText.inlineAt('a **bold** b', 0, 0), isEmpty);
    });

    test('should_formatAroundAChip_andKeepItsMention', () {
      final e = NoteEditorText.encode('x $link');
      final bold = NoteEditorText.apply(NoteTool.bold, e.text, 2, 3);
      expect(NoteEditorText.decode(bold.text, e.atoms), 'x **$link**');
      expect(NoteMarkup.parse(NoteEditorText.decode(bold.text, e.atoms)).mentions.single, andromeda);
    });
  });
}
