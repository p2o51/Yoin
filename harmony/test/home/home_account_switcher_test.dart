import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/home/home_account_switcher.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/settings/settings_view_model.dart';
import 'package:yoin/shell/bar_suppression.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  test('should_listOnlyOtherAccounts_when_quickSwitching', () {
    AccountCard card(String id, {bool active = false}) =>
        AccountCard(id: id, provider: MediaId.providerSubsonic, displayName: id, title: id, host: null, isActive: active, avatarShape: 0);
    expect(otherAccounts([card('a', active: true), card('b'), card('c')]).map((c) => c.id), ['b', 'c']);
  });

  Future<(ProviderContainer, String, String)> twoAccounts(WidgetTester tester) async {
    tester.view.physicalSize = const Size(400, 860);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final h = await homeContainer();
    final repo = h.container.read(profileRepositoryProvider);
    final first = (await repo.activeProfile())!.id;
    final second = (await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'Den',
      credentials: const SubsonicCredentials(baseUrl: 'https://den.test', username: 'den', password: 'p'),
    ))
        .id;
    await repo.setActive(first);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: h.container,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          builder: (context, child) => YoinWindowScope.measured(child: child!),
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    return (h.container, first, second);
  }

  Future<void> settleFrames(WidgetTester tester, [int n = 30]) async {
    for (var i = 0; i < n; i++) {
      await tester.pump(const Duration(milliseconds: 32));
    }
  }

  Future<void> done(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  testWidgets('should_nameTheAccountUser_when_avatarIsRead', (tester) async {
    final semantics = tester.ensureSemantics();
    final (c, _, _) = await twoAccounts(tester);
    // Android home_account_cd with card.title: the username, not the server host.
    expect(find.bySemanticsLabel('Account: u'), findsOneWidget);
    semantics.dispose();
    await done(tester, c);
  });

  testWidgets('should_openCardAndSwitch_when_anotherAccountIsPicked', (tester) async {
    final (c, _, second) = await twoAccounts(tester);
    await tester.tap(find.byType(HomeAccountButton));
    await settleFrames(tester);
    expect(find.text('Manage accounts'), findsOneWidget);
    expect(find.text('den'), findsOneWidget);
    await tester.tap(find.text('den'));
    await settleFrames(tester);
    expect(find.text('Manage accounts'), findsNothing);
    expect((await c.read(profileRepositoryProvider).activeProfile())!.id, second);
    await done(tester, c);
  });

  testWidgets('should_closeCardOnly_when_systemBack', (tester) async {
    final (c, first, _) = await twoAccounts(tester);
    await tester.tap(find.byType(HomeAccountButton));
    await settleFrames(tester);
    expect(find.text('Manage accounts'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await settleFrames(tester);
    expect(find.text('Manage accounts'), findsNothing);
    expect(find.byType(HomeScreen), findsOneWidget);
    expect((await c.read(profileRepositoryProvider).activeProfile())!.id, first);
    await done(tester, c);
  });

  testWidgets('should_sendTheBarAwayWhileTheCardIsUp_when_avatarTapped', (tester) async {
    final (c, _, _) = await twoAccounts(tester);
    expect(c.read(barSuppressedProvider), isFalse);
    await tester.tap(find.byType(HomeAccountButton));
    await settleFrames(tester);
    expect(c.read(barSuppressedProvider), isTrue);
    await tester.binding.handlePopRoute();
    await settleFrames(tester);
    expect(find.text('Manage accounts'), findsNothing);
    expect(c.read(barSuppressedProvider), isFalse);
    await done(tester, c);
  });

  testWidgets('should_switchOnRelease_when_holdSlidesOntoAnAccount', (tester) async {
    final (c, _, second) = await twoAccounts(tester);
    final avatar = tester.getRect(find.byType(HomeAccountButton));
    final gesture = await tester.startGesture(avatar.center);
    await tester.pump(const Duration(milliseconds: 600));
    await settleFrames(tester, 10);
    expect(find.text('den'), findsOneWidget);
    await gesture.moveTo(Offset(avatar.center.dx, fanItemCenterY(avatar, 0)));
    await tester.pump();
    await gesture.up();
    await settleFrames(tester);
    expect((await c.read(profileRepositoryProvider).activeProfile())!.id, second);
    expect(find.text('den'), findsNothing);
    await done(tester, c);
  });

  testWidgets('should_keepAccount_when_holdIsReleasedElsewhere', (tester) async {
    final (c, first, _) = await twoAccounts(tester);
    final avatar = tester.getRect(find.byType(HomeAccountButton));
    final gesture = await tester.startGesture(avatar.center);
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.up();
    await settleFrames(tester);
    expect((await c.read(profileRepositoryProvider).activeProfile())!.id, first);
    await done(tester, c);
  });
}
