import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Contrast, Hct;
import 'package:yoin/continueon/continue_on_card.dart';
import 'package:yoin/continueon/continue_on_controller.dart';
import 'package:yoin/platform/continueon/continue_on_host.dart';
import 'package:yoin/theme/color_scheme.dart';
import 'package:yoin/theme/theme.dart' show yoinSeed;
import 'package:yoin/ui/adaptive/edge_split.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/yoin_loading_indicator.dart';

/// The Continue On card (Android ContinueOnCard.kt): what it says in each
/// stage, which buttons it offers and that they reach the controller.
void main() {
  Widget app(Widget child, {Locale locale = const Locale('en')}) => MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('zh')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(body: child),
  );

  ContinueOnPrompt sample({
    ContinueOnStage stage = ContinueOnStage.asking,
    ContinueOnAccount account = const ContinueOnAccountFound('p', 'Home'),
  }) => sampleContinueOnPrompt(
    title: 'Weightless',
    artist: 'Marconi Union',
    album: 'Ambient',
    stage: stage,
    account: account,
  );

  /// Lets the springs run (a ticker's first frame only starts its clock).
  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 40; i++) {
      await tester.pump(const Duration(milliseconds: 32));
    }
  }

  Future<void> show(
    WidgetTester tester,
    ContinueOnPrompt? prompt, {
    VoidCallback? onContinue,
    VoidCallback? onDismiss,
    Locale locale = const Locale('en'),
  }) async {
    await tester.pumpWidget(
      app(
        ContinueOnCard(prompt: prompt, onContinue: onContinue ?? () {}, onDismiss: onDismiss ?? () {}),
        locale: locale,
      ),
    );
    await settle(tester);
  }

  testWidgets('should_showTheSongAndWhereTheOtherDeviceIs_when_aHandOffArrives', (tester) async {
    var continued = 0;
    var dismissed = 0;
    await show(tester, sample(), onContinue: () => continued++, onDismiss: () => dismissed++);
    expect(find.text('From HUAWEI Mate 70 Air'), findsOneWidget);
    expect(find.text('Weightless'), findsOneWidget);
    expect(find.text('Playing'), findsOneWidget);
    expect(find.text('1:23'), findsOneWidget);
    expect(find.text('Not now'), findsOneWidget);
    await tester.tap(find.text('Continue here'));
    expect(continued, 1);
    await tester.tap(find.text('Not now'));
    expect(dismissed, 1);
  });

  testWidgets('should_holdContinueHere_when_theAccountIsStillBeingMatched', (tester) async {
    var continued = 0;
    await show(tester, sample(account: const ContinueOnAccountLooking()), onContinue: () => continued++);
    final button = tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Continue here'));
    expect(button.onPressed, isNull);
    await tester.tap(find.text('Continue here'), warnIfMissed: false);
    expect(continued, 0);
  });

  testWidgets('should_sayTheAccountIsMissing_when_noAccountMatches', (tester) async {
    await show(tester, sample(account: const ContinueOnAccountMissing()));
    expect(find.textContaining("This account isn't on this device yet"), findsOneWidget);
    expect(find.text('OK'), findsOneWidget);
    expect(find.text('Continue here'), findsNothing);
  });

  testWidgets('should_showProgressOnly_when_starting', (tester) async {
    await show(tester, sample(stage: ContinueOnStage.starting));
    expect(find.byType(YoinLoadingIndicator), findsOneWidget);
    expect(find.text('Continue here'), findsNothing);
    expect(find.text('Not now'), findsNothing);
  });

  testWidgets('should_sayTheOtherDeviceMayStillPlay_when_itDidNotAnswer', (tester) async {
    await show(tester, sample(stage: ContinueOnStage.otherStillPlaying));
    expect(find.textContaining("didn't answer"), findsOneWidget);
    expect(find.text('OK'), findsOneWidget);
  });

  testWidgets('should_sayTheSongCannotBeRead_when_takingOverFailed', (tester) async {
    await show(tester, sample(stage: ContinueOnStage.failed));
    expect(find.text("Couldn't load this song here."), findsOneWidget);
  });

  testWidgets('should_speakChinese_when_theLocaleIsChinese', (tester) async {
    await show(tester, sample(), locale: const Locale('zh'));
    expect(find.text('来自 HUAWEI Mate 70 Air'), findsOneWidget);
    expect(find.text('在这里继续'), findsOneWidget);
    expect(find.text('不用了'), findsOneWidget);
    expect(find.text('正在播放'), findsOneWidget);
  });

  testWidgets('should_leaveOnItsSpring_when_thePromptGoes', (tester) async {
    await show(tester, sample());
    expect(find.text('Weightless'), findsOneWidget);
    await tester.pumpWidget(app(ContinueOnCard(prompt: null, onContinue: () {}, onDismiss: () {})));
    // Still drawn while it sinks out.
    await tester.pump(const Duration(milliseconds: 16));
    expect(find.text('Weightless'), findsOneWidget);
    await settle(tester);
    expect(find.text('Weightless'), findsNothing);
  });

  testWidgets('should_answerTheController_when_theHostCardIsTapped', (tester) async {
    final container = ProviderContainer(
      overrides: [
        continueOnHostProvider.overrideWithValue(const NoContinueOnHost()),
        continueOnAccountsProvider.overrideWithValue(_NoAccounts()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: app(const ContinueOnCardHost())));
    expect(find.text('OK'), findsNothing);
    container.read(continueOnProvider.notifier).receive(sample().cursor.toEntries());
    await settle(tester);
    // No account here to take it (none configured): the card says so and offers OK.
    expect(find.textContaining("This account isn't on this device yet"), findsOneWidget);
    await tester.tap(find.text('OK'));
    await tester.pump();
    expect(container.read(continueOnProvider), isNull);
    await settle(tester);
    expect(find.text('OK'), findsNothing);
  });

  test('should_writeTimesAsMinutesAndSeconds_when_formatted', () {
    expect(formatContinueOnPosition(83000), '1:23');
    expect(formatContinueOnPosition(5000), '0:05');
    expect(formatContinueOnPosition(3723000), '1:02:03');
    expect(formatContinueOnPosition(-1), '0:00');
  });

  // Android FlatFloaterContrastTest: no shadow, so the colours alone set the card apart.
  test('should_standOutFromThePage_when_theCardHasNoShadow', () {
    const seeds = [
      yoinSeed,
      Color(0xFFD32F2F),
      Color(0xFFFFEB3B),
      Color(0xFF00BCD4),
      Color(0xFF43A047),
      Color(0xFF8D8A90),
    ];
    double ratio(Color a, Color b) =>
        Contrast.ratioOfTones(Hct.fromInt(a.toARGB32()).tone, Hct.fromInt(b.toARGB32()).tone);
    for (final seed in seeds) {
      for (final brightness in Brightness.values) {
        final page = yoinExpressiveScheme(seed, brightness);
        final card = continueOnCardScheme(page);
        final where = '$seed $brightness';
        expect(ratio(card.surface, page.surface), greaterThanOrEqualTo(3.0), reason: 'card on page, $where');
        expect(
          ratio(card.surface, page.surfaceContainerHigh),
          greaterThanOrEqualTo(3.0),
          reason: 'card on container, $where',
        );
        expect(ratio(card.onSurface, card.surface), greaterThanOrEqualTo(4.5), reason: 'title, $where');
        expect(ratio(card.onSurfaceVariant, card.surface), greaterThanOrEqualTo(4.5), reason: 'quiet line, $where');
        expect(ratio(card.primary, card.surface), greaterThanOrEqualTo(4.5), reason: 'accent, $where');
        expect(ratio(card.onPrimary, card.primary), greaterThanOrEqualTo(4.5), reason: 'button text, $where');
        // Library's search hint (yoin_bottom_bar.dart LibrarySearchHint), flat the same way.
        expect(ratio(page.inverseSurface, page.surface), greaterThanOrEqualTo(3.0), reason: 'hint on page, $where');
        expect(ratio(page.inversePrimary, page.inverseSurface), greaterThanOrEqualTo(3.0), reason: 'hint icon, $where');
      }
    }
  });

  testWidgets('should_drawFlatOnTheInverseSurface_when_shown', (tester) async {
    await show(tester, sample());
    final page = Theme.of(tester.element(find.byType(ContinueOnCard))).colorScheme;
    final surface = tester.widget<Material>(
      find.ancestor(of: find.text('Weightless'), matching: find.byType(Material)).first,
    );
    expect(surface.color, page.inverseSurface);
    expect(surface.elevation, 0);
    final title = tester.widget<Text>(find.text('Weightless'));
    expect(title.style!.color, page.surface);
  });

  testWidgets('should_sitAboveTheBar_when_theHostShowsTheCard', (tester) async {
    final container = ProviderContainer(
      overrides: [
        continueOnHostProvider.overrideWithValue(const NoContinueOnHost()),
        continueOnAccountsProvider.overrideWithValue(_NoAccounts()),
      ],
    );
    addTearDown(container.dispose);
    // A portrait phone: the floating bar's reserve, navigation inset included.
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: YoinWindowScope(info: const YoinWindowInfo(400, 800), child: app(const ContinueOnCardHost())),
      ),
    );
    final padding = tester.widget<Padding>(
      find.descendant(of: find.byType(ContinueOnCardHost), matching: find.byType(Padding)).first,
    );
    expect(
      padding.padding,
      resolveShellChromeInsets(window: const YoinWindowInfo(400, 800), viewPadding: EdgeInsets.zero),
    );
    expect((padding.padding as EdgeInsets).bottom, greaterThan(0));
  });
}

final class _NoAccounts implements ContinueOnAccounts {
  @override
  Future<String?> accountKey(profile) async => null;
  @override
  Future<String?> activeProfileId() async => null;
  @override
  Future<Never> activeSource() async => throw UnimplementedError();
  @override
  Future<String?> coverUrl(String profileId, cover, int sizePx) async => null;
  @override
  Future<List<Never>> profiles() async => const [];
  @override
  Future<void> switchTo(String profileId) async {}
}
