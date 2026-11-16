import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/detail/album_hero_marks.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/library_fake_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

void main() {
  final album = MediaId.subsonic('al1');
  final now = DateTime(2026, 11, 17, 15);

  Future<(ProviderContainer, MemoryStore)> pump(
    WidgetTester tester, {
    VoidCallback? onEdit,
    bool chinese = false,
  }) async {
    final kv = InMemoryStore();
    final repo = ProfileRepository(kv, InMemoryStore());
    final profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
      ],
    );
    addTearDown(c.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          locale: chinese ? const Locale('zh') : null,
          supportedLocales: const [Locale('en'), Locale('zh')],
          localizationsDelegates: GlobalMaterialLocalizations.delegates,
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 200,
                child: AlbumHeroComment(albumId: album, onEdit: onEdit, now: now),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (c, MemoryStore.shared(kv, profile.id));
  }

  testWidgets('should_offerThePen_when_noReviewYet', (tester) async {
    final semantics = tester.ensureSemantics();
    var edits = 0;
    await pump(tester, onEdit: () => edits++);
    expect(find.byType(AlbumCommentPen), findsOneWidget);
    expect(find.bySemanticsLabel('Write a comment'), findsOneWidget);
    await tester.tap(find.byType(AlbumCommentPen));
    expect(edits, 1);
    semantics.dispose();
  });

  testWidgets('should_showTheReviewAndItsDay_when_written', (tester) async {
    final semantics = tester.ensureSemantics();
    var edits = 0;
    final (_, store) = await pump(tester, onEdit: () => edits++);
    const words = 'Rain on the window all through side two, and the bridge that never resolves.';
    await store.setAlbumReview(album, words, nowMs: now.subtract(const Duration(days: 1)).millisecondsSinceEpoch);
    await tester.pumpAndSettle();
    expect(find.byType(AlbumCommentPen), findsNothing);
    final text = tester.widget<Text>(find.text(words));
    expect(text.maxLines, 3);
    expect(text.overflow, TextOverflow.ellipsis);
    expect(find.text('Yesterday'), findsOneWidget);
    // Tapping the words edits them.
    expect(tester.getSemantics(find.byType(AlbumReviewText)).hintOverrides?.onTapHint, 'Edit comment');
    await tester.tap(find.text(words));
    expect(edits, 1);
    // Cleared: the pen comes back.
    await store.setAlbumReview(album, '', nowMs: now.millisecondsSinceEpoch);
    await tester.pumpAndSettle();
    expect(find.byType(AlbumCommentPen), findsOneWidget);
    expect(find.text(words), findsNothing);
    semantics.dispose();
  });

  testWidgets('should_dateInChinese_when_appIsChinese', (tester) async {
    final (_, store) = await pump(tester, chinese: true);
    await store.setAlbumReview(album, '雨', nowMs: DateTime(2026, 10, 3, 9).millisecondsSinceEpoch);
    await tester.pumpAndSettle();
    expect(find.text('10月3日'), findsOneWidget);
  });
}
