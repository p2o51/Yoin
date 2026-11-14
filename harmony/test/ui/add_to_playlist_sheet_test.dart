import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/ui/component/add_to_playlist_sheet.dart';

void main() {
  late List<String> picks;

  Future<void> pump(WidgetTester tester, {bool canCreate = true}) async {
    picks = [];
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () => showAddToPlaylistSheet(
                    context,
                    loadWritablePlaylists: () async => [
                      Playlist(id: MediaId.subsonic('pl1'), name: 'Road', songCount: 3, canWrite: true),
                    ],
                    onAddToExisting: (p) => picks.add('add:${p.id.rawId}'),
                    onCreateAndAdd: canCreate ? (name) => picks.add('create:$name') : null,
                  ),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('should_addAndClose_when_playlistPicked', (tester) async {
    await pump(tester);
    expect(find.text('3 songs'), findsOneWidget);
    await tester.tap(find.text('Road'));
    await tester.pumpAndSettle();
    expect(picks, ['add:pl1']);
    expect(find.text('Add to playlist'), findsNothing);
  });

  testWidgets('should_createWithTypedName_when_dialogConfirmed', (tester) async {
    await pump(tester);
    await tester.tap(find.text('Create new playlist…'));
    await tester.pumpAndSettle();
    final create = find.widgetWithText(TextButton, 'Create');
    expect(tester.widget<TextButton>(create).onPressed, isNull, reason: 'a blank name can not be created');
    await tester.enterText(find.byType(EditableText), '  Mix ');
    await tester.pump();
    await tester.tap(create);
    await tester.pumpAndSettle();
    expect(picks, ['create:Mix']);
    expect(find.text('Add to playlist'), findsNothing);
  });

  testWidgets('should_hideCreateRow_when_accountCannotCreate', (tester) async {
    await pump(tester, canCreate: false);
    expect(find.text('Create new playlist…'), findsNothing);
    expect(find.text('Road'), findsOneWidget);
  });
}
