/// The first-run landing's state (Android ui/landing/LandingModels.kt,
/// docs/design.md › 首次引导): one surface over the shell with an in-page
/// state machine — these steps. Back steps through them; the first step leaves
/// back to the system (first run) or closes the landing (re-run from Settings).
library;

import 'package:flutter/foundation.dart';

import '../data/model/media_id.dart';
import '../home/home_section.dart';

/// The services the landing can connect, in the catalogue's order. Android
/// also has Spotify (and the Spotoolfy question before it); the HarmonyOS
/// port has no Spotify source, so it is not offered here.
enum LandingService {
  subsonic(MediaId.providerSubsonic),
  appleMusic(MediaId.providerAppleMusic);

  const LandingService(this.provider);

  /// The [MediaId] provider token of the accounts this service adds.
  final String provider;
}

/// The landing's steps. Android's Spotoolfy and Spotify steps (no Spotify on
/// HarmonyOS), its scroll-edge step (HarmonyOS has one scroll edge, no
/// choice) and its screen-corner step (Android only) are not here.
enum LandingStep {
  hello,
  pick,
  about,
  subsonic,
  appleMusic,
  home,
  ready;

  /// The service a connect step adds, else null.
  LandingService? get service => switch (this) {
    subsonic => LandingService.subsonic,
    appleMusic => LandingService.appleMusic,
    _ => null,
  };

  static LandingStep connectStep(LandingService service) => switch (service) {
    LandingService.subsonic => subsonic,
    LandingService.appleMusic => appleMusic,
  };
}

/// First run (no account yet) or re-run from Settings › Help and about (accounts may exist).
enum LandingMode { firstRun, rerun }

/// The step list for a selection. One connect step per picked service, in the
/// catalogue's order; Home only once an account exists to own the layout.
List<LandingStep> landingSteps(Set<LandingService> picked, {required bool hasAccount}) => [
  LandingStep.hello,
  LandingStep.pick,
  LandingStep.about,
  for (final service in LandingService.values)
    if (picked.contains(service)) LandingStep.connectStep(service),
  if (hasAccount) LandingStep.home,
  LandingStep.ready,
];

@immutable
final class LandingUiState {
  const LandingUiState({
    required this.mode,
    required this.steps,
    required this.index,
    this.picked = const {},
    this.connected = const {},
    this.hasAccount = false,
    required this.layout,
    this.finishing = false,
  });

  factory LandingUiState.initial(LandingMode mode) => LandingUiState(
    mode: mode,
    steps: landingSteps(const {}, hasAccount: false),
    index: 0,
    layout: HomeLayout.defaultLayout,
  );

  final LandingMode mode;
  final List<LandingStep> steps;
  final int index;
  final Set<LandingService> picked;

  /// Services an account was added for during this landing.
  final Set<LandingService> connected;
  final bool hasAccount;

  /// Home's sections as the Home step edits them.
  final HomeLayout layout;

  /// "Open Yoin" was pressed: the hand-off into the shell is running.
  final bool finishing;

  LandingStep get step => steps[index.clamp(0, steps.length - 1)];
  bool get canGoBack => index > 0;

  /// The pill's dots: every step but hello, the current one long.
  int get dotCount => steps.length - 1;
  int get dotIndex => (index - 1).clamp(0, steps.length);

  LandingUiState copyWith({
    List<LandingStep>? steps,
    int? index,
    Set<LandingService>? picked,
    Set<LandingService>? connected,
    bool? hasAccount,
    HomeLayout? layout,
    bool? finishing,
  }) => LandingUiState(
    mode: mode,
    steps: steps ?? this.steps,
    index: index ?? this.index,
    picked: picked ?? this.picked,
    connected: connected ?? this.connected,
    hasAccount: hasAccount ?? this.hasAccount,
    layout: layout ?? this.layout,
    finishing: finishing ?? this.finishing,
  );

  /// The step list rebuilt for the selection and [hasAccount], staying on the
  /// current step when it is still in the list.
  LandingUiState rebuilt({required bool hasAccount}) {
    final next = landingSteps(picked, hasAccount: hasAccount);
    final keep = next.indexOf(step);
    return copyWith(steps: next, index: keep >= 0 ? keep : index.clamp(0, next.length - 1), hasAccount: hasAccount);
  }
}

/// The pill's main button: its label, its weight, and whether it carries an arrow.
enum LandingPrimaryLabel { getStarted, next, skip, openYoin }

enum LandingPrimaryStyle { filled, tonal, unavailable }

@immutable
final class LandingPrimary {
  const LandingPrimary(this.label, this.style, {this.arrow = false});

  final LandingPrimaryLabel label;
  final LandingPrimaryStyle style;
  final bool arrow;

  @override
  bool operator ==(Object other) =>
      other is LandingPrimary && other.label == label && other.style == style && other.arrow == arrow;

  @override
  int get hashCode => Object.hash(label, style, arrow);

  @override
  String toString() => 'LandingPrimary($label, $style${arrow ? ', arrow' : ''})';
}

/// The primary button for the state's step (LandingScreen.kt primaryFor).
LandingPrimary landingPrimaryFor(LandingUiState state) {
  final step = state.step;
  final service = step.service;
  if (step == LandingStep.hello) {
    return const LandingPrimary(LandingPrimaryLabel.getStarted, LandingPrimaryStyle.filled, arrow: true);
  }
  if (step == LandingStep.pick) {
    if (state.picked.isNotEmpty) return const LandingPrimary(LandingPrimaryLabel.next, LandingPrimaryStyle.filled);
    // An account already exists (a re-run): nothing new to add is fine, Home is still ahead.
    if (state.hasAccount) return const LandingPrimary(LandingPrimaryLabel.skip, LandingPrimaryStyle.tonal);
    return const LandingPrimary(LandingPrimaryLabel.next, LandingPrimaryStyle.unavailable);
  }
  if (service != null) {
    return state.connected.contains(service)
        ? const LandingPrimary(LandingPrimaryLabel.next, LandingPrimaryStyle.filled)
        : const LandingPrimary(LandingPrimaryLabel.skip, LandingPrimaryStyle.tonal);
  }
  if (step == LandingStep.ready) {
    return const LandingPrimary(LandingPrimaryLabel.openYoin, LandingPrimaryStyle.filled, arrow: true);
  }
  return const LandingPrimary(LandingPrimaryLabel.next, LandingPrimaryStyle.filled);
}

/// What the primary button does on [state]'s step.
enum LandingPrimaryAction { next, refuse, finish }

LandingPrimaryAction landingPrimaryAction(LandingUiState state) {
  if (state.step == LandingStep.ready) return LandingPrimaryAction.finish;
  if (state.step == LandingStep.pick && state.picked.isEmpty && !state.hasAccount) return LandingPrimaryAction.refuse;
  return LandingPrimaryAction.next;
}

/// Which of the mascot's lines a step shows (LandingScreen.kt bubbleLines).
enum LandingLine { hello, pick, about, subsonic, appleMusic, home, ready, readyNone }

LandingLine landingLineFor(LandingStep step, {required bool noAccounts}) => switch (step) {
  LandingStep.hello => LandingLine.hello,
  LandingStep.pick => LandingLine.pick,
  LandingStep.about => LandingLine.about,
  LandingStep.subsonic => LandingLine.subsonic,
  LandingStep.appleMusic => LandingLine.appleMusic,
  LandingStep.home => LandingLine.home,
  LandingStep.ready => noAccounts ? LandingLine.readyNone : LandingLine.ready,
};
