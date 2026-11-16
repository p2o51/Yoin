import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/landing/landing_models.dart';
import 'package:yoin/landing/landing_stage.dart';
import 'package:yoin/theme/tokens.dart';

void main() {
  group('steps', () {
    test('should_listOnlyHelloPickAboutReady_when_nothingPickedAndNoAccount', () {
      expect(landingSteps(const {}, hasAccount: false), [
        LandingStep.hello,
        LandingStep.pick,
        LandingStep.about,
        LandingStep.ready,
      ]);
    });

    test('should_addConnectStepsInCatalogueOrder_when_servicesPicked', () {
      expect(landingSteps({LandingService.appleMusic, LandingService.subsonic}, hasAccount: false), [
        LandingStep.hello,
        LandingStep.pick,
        LandingStep.about,
        LandingStep.subsonic,
        LandingStep.appleMusic,
        LandingStep.ready,
      ]);
    });

    test('should_addHomeStep_when_anAccountExists', () {
      expect(landingSteps({LandingService.subsonic}, hasAccount: true), [
        LandingStep.hello,
        LandingStep.pick,
        LandingStep.about,
        LandingStep.subsonic,
        LandingStep.home,
        LandingStep.ready,
      ]);
    });

    test('should_stayOnTheSameStep_when_rebuiltWithAnAccount', () {
      final state = LandingUiState.initial(LandingMode.firstRun).copyWith(picked: {LandingService.subsonic});
      final onConnect = state.rebuilt(hasAccount: false).copyWith(index: 3);
      expect(onConnect.step, LandingStep.subsonic);
      final after = onConnect.rebuilt(hasAccount: true);
      expect(after.step, LandingStep.subsonic);
      expect(after.steps, contains(LandingStep.home));
    });

    test('should_countDotsWithoutHello_when_onAStep', () {
      final state = LandingUiState.initial(LandingMode.firstRun).copyWith(index: 2);
      expect(state.dotCount, 3);
      expect(state.dotIndex, 1);
    });
  });

  group('primary button', () {
    LandingUiState at(
      LandingStep step, {
      Set<LandingService> picked = const {},
      bool account = false,
      Set<LandingService> connected = const {},
    }) {
      final base = LandingUiState.initial(
        LandingMode.firstRun,
      ).copyWith(picked: picked, connected: connected).rebuilt(hasAccount: account);
      return base.copyWith(index: base.steps.indexOf(step));
    }

    test('should_sayGetStartedWithArrow_when_hello', () {
      expect(
        landingPrimaryFor(at(LandingStep.hello)),
        const LandingPrimary(LandingPrimaryLabel.getStarted, LandingPrimaryStyle.filled, arrow: true),
      );
    });

    test('should_beUnavailableAndRefuse_when_pickEmptyOnFirstRun', () {
      final state = at(LandingStep.pick);
      expect(landingPrimaryFor(state).style, LandingPrimaryStyle.unavailable);
      expect(landingPrimaryAction(state), LandingPrimaryAction.refuse);
    });

    test('should_offerSkip_when_pickEmptyButAnAccountExists', () {
      final state = at(LandingStep.pick, account: true);
      expect(landingPrimaryFor(state), const LandingPrimary(LandingPrimaryLabel.skip, LandingPrimaryStyle.tonal));
      expect(landingPrimaryAction(state), LandingPrimaryAction.next);
    });

    test('should_skipUntilConnected_when_onAConnectStep', () {
      final picked = {LandingService.subsonic};
      expect(landingPrimaryFor(at(LandingStep.subsonic, picked: picked)).label, LandingPrimaryLabel.skip);
      expect(
        landingPrimaryFor(at(LandingStep.subsonic, picked: picked, connected: picked)),
        const LandingPrimary(LandingPrimaryLabel.next, LandingPrimaryStyle.filled),
      );
    });

    test('should_openYoinAndFinish_when_ready', () {
      final state = at(LandingStep.ready);
      expect(landingPrimaryFor(state).label, LandingPrimaryLabel.openYoin);
      expect(landingPrimaryFor(state).arrow, isTrue);
      expect(landingPrimaryAction(state), LandingPrimaryAction.finish);
    });
  });

  group('lines', () {
    test('should_sayNoAccountYet_when_readyWithoutAccounts', () {
      expect(landingLineFor(LandingStep.ready, noAccounts: true), LandingLine.readyNone);
      expect(landingLineFor(LandingStep.ready, noAccounts: false), LandingLine.ready);
    });
  });

  group('frame', () {
    test('should_spanThePhoneLikeTheBar_when_portraitPhone', () {
      final f = landingFrame(
        width: 400,
        height: 800,
        topInset: 30,
        bottomInset: 20,
        centered: false,
        twoColumns: false,
      );
      expect(f.colW, 368);
      expect(f.colX, 16);
      expect(f.pillH, BarTokens.height);
      expect(f.pillTop, 800 - 20 - BarTokens.portraitVerticalMargin - BarTokens.height);
      expect(f.helloPillW, 368);
      expect(f.barW, 368);
      expect(f.wide, isFalse);
      expect(f.medium, isFalse);
    });

    test('should_centreACappedColumn_when_mediumWindow', () {
      final f = landingFrame(width: 800, height: 1200, topInset: 0, bottomInset: 0, centered: true, twoColumns: false);
      expect(f.medium, isTrue);
      expect(f.colW, LandingTokens.columnMax);
      expect(f.colX, (800 - LandingTokens.columnMax) / 2);
      expect(f.pillH, BarTokens.centeredHeight);
      expect(f.helloPillW, LandingTokens.helloPillMax);
      expect(f.barW, BarTokens.centeredMaxWidth);
    });

    test('should_putTheWindowInTheRightColumn_when_wide', () {
      final f = landingFrame(
        width: 1280,
        height: 800,
        topInset: 0,
        bottomInset: 0,
        centered: true,
        twoColumns: true,
        wideWindow: true,
      );
      expect(f.wide, isTrue);
      expect(f.colW, LandingTokens.twoColumnMax);
      expect(f.colX, 1280 - LandingTokens.twoColumnMax - 1280 * LandingTokens.twoColumnEndFraction);
      expect(f.pillTop, 800 - BarTokens.centeredBottomMarginWide - BarTokens.centeredHeight);
      expect(f.pillX(LandingStep.hello), (1280 - LandingTokens.helloPillMax) / 2);
      expect(f.pillW(LandingStep.pick), f.colW);
    });
  });
}
