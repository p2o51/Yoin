// The @ popup never covers the line being written (Android MentionPopupPositionTest).
// Here the caret rect is already in the overlay's coordinates.
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';
import 'package:yoin/notes/editor/mention_popup.dart';
import 'package:yoin/notes/editor/note_markup_field.dart';
import 'package:yoin/theme/tokens.dart';

void main() {
  const window = Size(1080, 2400);
  const popup = Size(820, 900);
  const margin = 21.0;

  test('should_sitAboveTheWholeField_whenThereIsRoom', () {
    const field = Rect.fromLTRB(120, 1300, 1000, 1420);
    const caret = Rect.fromLTRB(420, 1360, 422, 1420);
    final at = mentionPopupOffset(field: field, caret: caret, window: window, popup: popup, margin: margin);
    expect(at.dy, 1300 - 900 - margin);
    expect(at.dy + popup.height, lessThanOrEqualTo(field.top));
  });

  test('should_sitAboveTheCaretsLine_whenTheFieldIsTooTallForAboveIt', () {
    const field = Rect.fromLTRB(48, 200, 1032, 2000);
    const caret = Rect.fromLTRB(448, 1300, 450, 1360);
    final at = mentionPopupOffset(field: field, caret: caret, window: window, popup: popup, margin: margin);
    expect(at.dy, 1300 - 900 - margin);
  });

  test('should_dropBelowTheCaretsLine_whenNothingFitsAbove', () {
    const field = Rect.fromLTRB(48, 100, 1032, 1500);
    const caret = Rect.fromLTRB(448, 140, 450, 200);
    final at = mentionPopupOffset(field: field, caret: caret, window: window, popup: popup, margin: margin);
    expect(at.dy, 200 + margin);
  });

  test('should_stayInsideTheWindowSideways', () {
    const field = Rect.fromLTRB(0, 1300, 1080, 1420);
    const caret = Rect.fromLTRB(1070, 1300, 1072, 1360);
    final at = mentionPopupOffset(field: field, caret: caret, window: window, popup: popup, margin: margin);
    expect(at.dx, 1080 - 820 - margin);
  });

  test('should_neverReachUnderTheKeyboard_andShrinkItsRowsToTheRoomierSide', () {
    const imeTop = 1450.0;
    const statusBar = 60.0;
    const field = Rect.fromLTRB(140, 700, 940, 900);
    const caret = Rect.fromLTRB(200, 700, 202, 760);
    final listMax = mentionPopupListMax(caretTop: 700, caretBottom: 760, topLimit: statusBar, bottomLimit: imeTop);
    expect(listMax, NoteEditorTokens.popupMaxListHeight);
    final shrunk = Size(NoteEditorTokens.popupWidth, listMax + NoteEditorTokens.popupChrome);
    final at = mentionPopupOffset(
      field: field,
      caret: caret,
      window: window,
      popup: shrunk,
      margin: margin,
      topLimit: statusBar,
      bottomLimit: imeTop,
    );
    expect(at.dy + shrunk.height, lessThanOrEqualTo(imeTop));
    expect(at.dy, greaterThanOrEqualTo(statusBar));
    expect(at.dy + shrunk.height <= 700 || at.dy >= 760, isTrue);
  });

  test('should_holdTheRowsToTheirMinimum_inATinyWindow', () {
    expect(
      mentionPopupListMax(caretTop: 100, caretBottom: 140, topLimit: 60, bottomLimit: 200),
      NoteEditorTokens.popupMinListHeight,
    );
  });

  testWidgets('should_readTheKeyboardFromTheWindow_evenWhenTheOverlaySitsLower', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final key = GlobalKey();
    // An overlay that starts 100 below the window's top (a nested navigator under a header).
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: Column(
          children: [
            const SizedBox(height: 100),
            Expanded(child: SizedBox.expand(key: key)),
          ],
        ),
      ),
    );
    final overlay = key.currentContext!.findRenderObject()! as RenderBox;
    const window = MediaQueryData(
      size: Size(400, 800),
      padding: EdgeInsets.only(top: 24),
      viewInsets: EdgeInsets.only(bottom: 300),
    );
    final (top, bottom) = mentionPopupLimits(window, overlay);
    expect(top, 0, reason: 'the status bar is above this overlay');
    expect(bottom, 400, reason: 'the keyboard starts 500 down the window, 400 down the overlay');
  });
}
