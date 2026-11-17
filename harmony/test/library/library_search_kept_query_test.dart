import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/library/library_search.dart';
import 'package:yoin/library/library_search_view.dart';

import '../support/library_fake_source.dart';

/// QA r2_22: a query kept after a result opened in the detail column comes
/// back selected, so typing replaces it instead of appending ("HarGla").
void main() {
  testWidgets('should_selectKeptQuery_when_searchReopens', (tester) async {
    final source = LibraryFakeSource(
      searchResults: SearchResults(artists: [LibraryFixtures.artist('a', name: 'Harbor')]),
    );
    final c = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => source)]);
    addTearDown(c.dispose);
    c.listen(librarySearchProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    c.read(librarySearchProvider.notifier).search('Har');

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => openLibrarySearch(context, const Rect.fromLTWH(16, 8, 300, 56)),
                child: const Text('pill'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('pill'));
    await tester.pumpAndSettle();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.controller!.text, 'Har');
    expect(field.controller!.selection, const TextSelection(baseOffset: 0, extentOffset: 3));

    await tester.enterText(find.byType(TextField), 'Gla');
    expect(c.read(librarySearchProvider).query, 'Gla', reason: 'typing replaces the kept query');
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(c.read(librarySearchProvider).query, isEmpty, reason: 'an ordinary close still clears');
  });
}
