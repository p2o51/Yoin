import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/memories/memory_rate_sheet.dart';
import 'package:yoin/memories/palette/memory_palette.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

void main() {
  late InMemoryStore kv;
  late ProfileRepository repo;
  late Profile profile;
  final album = MediaId.subsonic('al');

  setUp(() async {
    kv = InMemoryStore();
    repo = ProfileRepository(kv, InMemoryStore());
    profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
  });

  Future<void> pumpSheet(WidgetTester tester) async {
    final source = LibraryFakeSource();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          profileRepositoryProvider.overrideWithValue(repo),
          activeSourceProvider.overrideWith((ref) async => source),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => showAlbumRateSheet(
                  context,
                  albumId: album,
                  albumName: 'Blue',
                  palette: MemoryPaletteSamples.m1,
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('should_saveReview_when_sheetClosed', (tester) async {
    await pumpSheet(tester);
    await tester.enterText(find.byType(TextField), 'Rain on the window.');
    await tester.pump();
    // Closing the sheet saves (there is no Save button).
    Navigator.of(tester.element(find.byType(AlbumRateSheet))).pop();
    await tester.pumpAndSettle();

    final row = await MemoryStore(kv, profileId: profile.id).albumRating(album);
    expect(row?.review, 'Rain on the window.');
  });

  testWidgets('should_keepReview_when_closedUntouched', (tester) async {
    await MemoryStore(kv, profileId: profile.id).setAlbumReview(album, 'Kept.', nowMs: 1);
    await pumpSheet(tester);
    expect(find.text('Kept.'), findsOneWidget);
    Navigator.of(tester.element(find.byType(AlbumRateSheet))).pop();
    await tester.pumpAndSettle();

    final row = await MemoryStore(kv, profileId: profile.id).albumRating(album);
    expect(row?.review, 'Kept.');
    expect(row?.reviewUpdatedAt, 1);
  });

  testWidgets('should_holdAndKeepAndroidMarkup_when_reviewClosedUntouched', (tester) async {
    const review = '# 天堂\n- 吉他像雾\n**后来**在 [@Andromeda](yoin:track/applemusic:1445555555?a=Weyes%20Blood) 里 _轻_';
    await MemoryStore(kv, profileId: profile.id).setAlbumReview(album, review, nowMs: 1);
    await pumpSheet(tester);
    // The field edits the stored text as written: the markup, not its drawing.
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, review);
    Navigator.of(tester.element(find.byType(AlbumRateSheet))).pop();
    await tester.pumpAndSettle();

    final row = await MemoryStore(kv, profileId: profile.id).albumRating(album);
    expect(row?.review, review);
    expect(row?.reviewUpdatedAt, 1);
  });

  testWidgets('should_storeScore_when_barDragged', (tester) async {
    await pumpSheet(tester);
    final bar = find.byWidgetPredicate((w) => w is Semantics && (w.properties.slider ?? false));
    final box = tester.getRect(bar);
    await tester.dragFrom(Offset(box.left + 4, box.center.dy), Offset(box.width * 0.85 - 4, 0));
    await tester.pumpAndSettle();

    final row = await MemoryStore(kv, profileId: profile.id).albumRating(album);
    expect(row?.rating, closeTo(8.5, 0.11));
    Navigator.of(tester.element(find.byType(AlbumRateSheet))).pop();
    await tester.pumpAndSettle();
  });
}
