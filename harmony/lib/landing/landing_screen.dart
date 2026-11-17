import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../nowplaying/haptics.dart';
import '../settings/appearance/appearance_strings.dart';
import '../settings/apple_music_setup_view_model.dart';
import '../settings/settings_view_model.dart';
import '../settings/subsonic_setup_view_model.dart';
import '../shell/chrome_back.dart';
import '../shell/desktop_shortcuts.dart' show isTypingIntoTextField;
import '../shell/shell_state.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'landing_bubble.dart';
import 'landing_connect.dart';
import 'landing_edge_step.dart';
import 'landing_home_step.dart';
import 'landing_mascot.dart';
import 'landing_models.dart';
import 'landing_motion.dart';
import 'landing_pill.dart';
import 'landing_scenes.dart';
import 'landing_stage.dart';
import 'landing_strings.dart';
import 'landing_view_model.dart';

/// The landing on screen (LandingScreen.kt): the backdrop, the stage (floating
/// window, mascot, bubble, pill) and the choreography around the view model.
///
/// Back (a stepped flow): system back and the pill's back button both step
/// back; on a re-run's first step back closes the landing; on a first run's
/// first step it is left to the system (root back is never consumed).
/// HarmonyOS back carries no progress, so it commits at once, through the
/// same path as the button.
class LandingScreen extends ConsumerStatefulWidget {
  const LandingScreen({
    super.key,
    required this.session,
    required this.fadeIn,
    required this.onShellWanted,
    required this.onClosed,
  });

  final LandingSession session;

  /// Over a visible shell (a slow first launch, a re-run) it fades in; over a held shell it is simply there.
  final bool fadeIn;

  /// The shell may compose beneath (from the last step on), so Home is ready for the hand-off.
  final VoidCallback onShellWanted;

  /// The hand-off (or a re-run's close) is done: remove the landing.
  final VoidCallback onClosed;

  @override
  ConsumerState<LandingScreen> createState() => _LandingScreenState();
}

class _LandingScreenState extends ConsumerState<LandingScreen>
    with TickerProviderStateMixin
    implements LandingReactions {
  late final LandingMotion _motion = LandingMotion(this);
  late final BubbleTailState _tail = BubbleTailState();
  final LandingWindowSpec _windowSpec = LandingWindowSpec();
  final LandingForms _forms = LandingForms();
  late final OverlayEntry _entry = OverlayEntry(builder: _buildLanding);
  LandingMascotState? _mascot;
  bool _reduced = false;

  /// The keyboard's anchor while the landing shows (the shell is out of focus
  /// beneath it): Esc steps back like system back.
  final FocusNode _keys = FocusNode(debugLabel: 'Landing', skipTraversal: true);

  /// The mascot's slow breath while it waits, and the backdrop's drift.
  late final AnimationController _breath = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 3400),
  );
  late final AnimationController _drift = AnimationController(vsync: this, duration: const Duration(seconds: 24));

  late final ChromeBackEntry _back = ChromeBackEntry(
    () => scheduleMicrotask(_onBack),
    owns: () => _wantsBack,
    priority: ChromeBackPriority.landing,
  );

  late final _handoffContent = _DerivedAnimation(_motion.handoff.animation, (v) => (1 - v * 2.2).clamp(0.0, 1.0));

  bool _introStarted = false;
  bool _closing = false;
  LandingStep? _placedStep;

  LandingProvider get _provider => landingViewModelProvider(widget.session);
  LandingViewModel get _vm => ref.read(_provider.notifier);

  bool get _wantsBack {
    final state = ref.read(_provider);
    return !state.finishing && !_closing && (state.canGoBack || widget.session.mode == LandingMode.rerun);
  }

  @override
  void initState() {
    super.initState();
    _motion.reveal.snapTo(widget.fadeIn ? 0 : 1);
    _motion.addListener(_syncBreath);
    _motion.handoff.animation.addListener(_syncBreath);
    FocusManager.instance.addListener(_holdKeys);
  }

  /// No primary focus = no key events: hold it on the landing until a field takes it.
  void _holdKeys() {
    if (!mounted) return;
    final primary = FocusManager.instance.primaryFocus;
    if (primary == null || primary == FocusManager.instance.rootScope) _keys.requestFocus();
  }

  void _onEscape() {
    if (isTypingIntoTextField()) {
      // Out of the field first, as everywhere else.
      FocusManager.instance.primaryFocus?.unfocus();
      _keys.requestFocus();
      return;
    }
    if (_wantsBack) _onBack();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (_mascot == null || reduced != _reduced) {
      _reduced = reduced;
      _mascot?.dispose();
      _mascot = LandingMascotState(this, reduced: reduced);
      _syncDrift();
      _syncBreath();
      _entry.markNeedsBuild();
    }
    if (!_introStarted) {
      _introStarted = true;
      _start();
    }
  }

  void _start() {
    final step = ref.read(_provider).step;
    _motion.snap(step);
    _placedStep = step;
    _motion.onBubbleChanged = () => _motion.popBubble(LandingSprings.fastSpatial);
    if (_motion.reveal.value < 1) _motion.reveal.animateTo(1, LandingSprings.effects);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncBack());
    if (_reduced) {
      _mascot!.intro();
      return;
    }
    _motion.pillIn.snapTo(0);
    _motion.bubblePop.snapTo(0);
    unawaited(
      Future<void>.delayed(const Duration(milliseconds: _introDelayMs), () {
        if (!mounted) return;
        _mascot!.intro(
          onLanded: () {
            if (!mounted) return;
            _motion.pillIn.animateTo(1, LandingSprings.spatial);
            unawaited(
              Future<void>.delayed(const Duration(milliseconds: _bubbleAfterLandMs), () {
                if (!mounted) return;
                _motion.bubblePop.snapTo(0.2);
                _motion.bubblePop.animateTo(1, LandingSprings.fastSpatial);
              }),
            );
          },
        );
      }),
    );
  }

  void _syncDrift() {
    if (_reduced) {
      _drift.stop();
      _drift.value = 0.5;
    } else if (!_drift.isAnimating) {
      _drift.repeat(reverse: true);
    }
  }

  /// The breath runs only while nothing else moves.
  void _syncBreath() {
    final moving = !_motion.settled || _motion.handoff.isAnimating || _motion.handoff.value > 0;
    if (_reduced || moving) {
      if (_breath.isAnimating) _breath.stop();
    } else if (!_breath.isAnimating) {
      _breath.repeat();
    }
  }

  /// Back belongs to the landing exactly while it can step back (or close a re-run).
  void _syncBack() {
    if (!mounted) return;
    final navigator = appNavigatorKey.currentState;
    if (_wantsBack && navigator != null) {
      _back.attach(navigator);
    } else if (!_wantsBack && _back.isAttached) {
      scheduleMicrotask(() {
        if (!_wantsBack) _back.detach();
      });
    }
  }

  void _onBack() {
    if (!mounted) return;
    final state = ref.read(_provider);
    if (state.finishing || _closing) return;
    if (state.canGoBack) {
      _vm.back();
    } else if (widget.session.mode == LandingMode.rerun) {
      _close();
    }
  }

  @override
  void didUpdateWidget(LandingScreen old) {
    super.didUpdateWidget(old);
    _entry.markNeedsBuild();
  }

  @override
  void dispose() {
    FocusManager.instance.removeListener(_holdKeys);
    _keys.dispose();
    _back.dispose();
    _motion.removeListener(_syncBreath);
    _motion.handoff.animation.removeListener(_syncBreath);
    _motion.dispose();
    _mascot?.dispose();
    _breath.dispose();
    _drift.dispose();
    _tail.dispose();
    _forms.dispose();
    // _entry lives and dies with this screen's own Overlay (already unmounted here).
    super.dispose();
  }

  // ── Reactions ─────────────────────────────────────────────────────────

  @override
  void refused() {
    YoinHaptics.reject();
    _mascot?.shake();
  }

  @override
  void connected(LandingService service) {
    YoinHaptics.confirm();
    _vm.onConnected(service);
    _mascot?.cheer();
  }

  void _toggleService(LandingService service) {
    if (!ref.read(_provider).picked.contains(service)) _mascot?.nod();
    _vm.toggleService(service);
  }

  void _onPrimary() {
    final state = ref.read(_provider);
    if (state.finishing || _closing) return;
    switch (landingPrimaryAction(state)) {
      case LandingPrimaryAction.next:
        _vm.next();
      case LandingPrimaryAction.refuse:
        refused();
      case LandingPrimaryAction.finish:
        _finish();
    }
  }

  /// "Open Yoin": the layout is written, then the window folds into the pill,
  /// the pill becomes the bar, Yoin waves and flies to the corner, and the
  /// landing fades off Home.
  Future<void> _finish() async {
    YoinHaptics.confirm();
    FocusManager.instance.primaryFocus?.unfocus();
    widget.onShellWanted();
    final ok = await _vm.finish();
    if (!ok || !mounted) return;
    _syncBack();
    if (_reduced) {
      _motion.reveal.snapTo(0);
      widget.onClosed();
      return;
    }
    _mascot?.wave(times: 1, ticks: false);
    _motion.handoff.animateTo(1, LandingSprings.spatial);
    unawaited(
      Future<void>.delayed(const Duration(milliseconds: _flyAfterMs), () {
        if (mounted) _motion.fly.animateTo(1, LandingSprings.slowSpatial);
      }),
    );
    await Future<void>.delayed(const Duration(milliseconds: _revealAfterMs));
    if (!mounted) return;
    await _motion.reveal.animateTo(0, LandingSprings.slowEffects);
    if (mounted) widget.onClosed();
  }

  /// A re-run closed from its first step: fade back to Home.
  Future<void> _close() async {
    if (_closing) return;
    _closing = true;
    _syncBack();
    widget.onShellWanted();
    if (_reduced) {
      _motion.reveal.snapTo(0);
    } else {
      await _motion.reveal.animateTo(0, LandingSprings.slowEffects);
    }
    if (mounted) widget.onClosed();
  }

  void _onState(LandingUiState? previous, LandingUiState next) {
    if (next.step != _placedStep) {
      _placedStep = next.step;
      if (_reduced) {
        _motion.snap(next.step);
      } else {
        _motion.go(next.step, LandingSprings.spatial);
      }
    }
    // The shell warms up beneath from the scroll-edge step on, so Home is ready when the hand-off reveals it.
    final edge = next.steps.indexOf(LandingStep.edge);
    if (next.finishing || (edge >= 0 && next.index >= edge) || next.step == LandingStep.ready) widget.onShellWanted();
    _syncBack();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(_provider, _onState);
    // The connect steps' view models live as long as the landing (Android
    // keeps them in its own store): stepping away mid-connect neither drops
    // the save nor misses the account it adds, and the mascot cheers for it
    // whichever step is showing.
    ref.listen(subsonicSetupViewModelProvider, (prev, next) {
      if (next.connected && !(prev?.connected ?? false)) connected(LandingService.subsonic);
    });
    ref.listen(appleMusicSetupViewModelProvider, (prev, next) {
      if (next.connected && !(prev?.connected ?? false)) connected(LandingService.appleMusic);
    });
    // The landing's own overlay: its text fields need one for their handles and toolbar.
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape, includeRepeats: false): _onEscape},
      child: Focus(
        focusNode: _keys,
        autofocus: true,
        includeSemantics: false,
        child: Overlay(initialEntries: [_entry]),
      ),
    );
  }

  Widget _buildLanding(BuildContext context) => Consumer(
    builder: (context, ref, _) {
      final state = ref.watch(_provider);
      final settings = ref.watch(settingsViewModelProvider);
      final accounts = settings is SettingsContent ? settings.accounts : const <AccountCard>[];
      return _buildStage(context, state, accounts);
    },
  );

  Widget _buildStage(BuildContext context, LandingUiState state, List<AccountCard> accounts) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dark = theme.brightness == Brightness.dark;
    final strings = LandingStrings.of(context);
    final window = YoinWindowInfo.of(context);
    final media = MediaQuery.of(context);
    final twoColumns = window.layoutMode == LayoutMode.wide || window.isCompactHeight;
    final centered = window.chromeForm == ShellChromeForm.centeredBar;
    final mascot = _mascot!;
    return Material(
      type: MaterialType.transparency,
      child: FadeTransition(
        opacity: _ClampedAnimation(_motion.reveal.animation),
        child: Stack(
          fit: StackFit.expand,
          children: [
            CustomPaint(
              painter: _BackdropPainter(dark: dark, base: colors.surface, drift: _drift),
            ),
            LandingStage(
              motion: _motion,
              windowSpec: _windowSpec,
              tail: _tail,
              topInset: media.padding.top,
              bottomInset: media.viewPadding.bottom,
              imeBottom: media.viewInsets.bottom,
              centered: centered,
              twoColumns: twoColumns,
              wideWindow: window.layoutMode == LayoutMode.wide,
              window: ListenableBuilder(
                listenable: _motion,
                builder: (context, _) {
                  final steps = _motion.settled ? [_motion.to] : [_motion.from, _motion.to];
                  return LandingWindow(
                    motion: _motion,
                    spec: _windowSpec,
                    color: colors.surfaceContainer,
                    leaving: steps.length == 2,
                    children: [
                      for (final step in steps) KeyedSubtree(key: ValueKey(step), child: _scene(step, state, accounts)),
                    ],
                  );
                },
              ),
              mascot: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () {
                  YoinHaptics.tick();
                  mascot.wave(times: 1, ticks: false);
                },
                child: LandingMascot(state: mascot, breath: _reduced ? null : _breath),
              ),
              bubble: ListenableBuilder(
                listenable: _motion,
                builder: (context, _) {
                  final (title, body) = _lines(
                    strings,
                    landingLineFor(_motion.bubbleStep, noAccounts: accounts.isEmpty),
                  );
                  return Semantics(
                    liveRegion: true,
                    child: LandingBubble(
                      title: title,
                      body: body,
                      tail: _tail,
                      background: dark ? colors.primaryFixed : colors.surfaceBright,
                      content: dark ? colors.onPrimaryFixed : colors.onSurface,
                    ),
                  );
                },
              ),
              pill: LandingPill(
                hello: state.step == LandingStep.hello,
                primary: landingPrimaryFor(state),
                dotCount: state.dotCount,
                dotIndex: state.dotIndex,
                centered: centered,
                onBack: _onBack,
                onPrimary: _onPrimary,
                contentAlpha: _handoffContent,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _scene(LandingStep step, LandingUiState state, List<AccountCard> accounts) => switch (step) {
    LandingStep.hello => const SizedBox.shrink(),
    LandingStep.pick => PickScene(picked: state.picked, onToggle: _toggleService),
    LandingStep.about => AboutScene(picked: state.picked),
    LandingStep.subsonic => SubsonicConnectScene(
      forms: _forms,
      connected: state.connected.contains(LandingService.subsonic),
      reactions: this,
    ),
    LandingStep.appleMusic => AppleConnectScene(
      forms: _forms,
      connected: state.connected.contains(LandingService.appleMusic),
      reactions: this,
    ),
    LandingStep.home => HomeSectionsScene(
      sections: landingShownSections(state.layout),
      onEnabled: _vm.setSectionEnabled,
      onMove: _vm.moveShownSection,
    ),
    LandingStep.edge => ScrollEdgeScene(playing: _motion.to == LandingStep.edge, onSelect: _vm.setEdge),
    LandingStep.corners => const ScreenCornersScene(),
    LandingStep.ready => ReadyScene(accounts: accounts),
  };

  (String, String?) _lines(LandingStrings strings, LandingLine line) => switch (line) {
    LandingLine.hello => (strings.helloTitle, strings.helloBody),
    LandingLine.pick => (strings.pickTitle, null),
    LandingLine.about => (strings.aboutTitle, null),
    LandingLine.subsonic => (strings.subsonicTitle, null),
    LandingLine.appleMusic => (strings.appleTitle, strings.appleBody),
    LandingLine.home => (strings.homeTitle, null),
    LandingLine.edge => (AppearanceStrings.of(context).landingEdgeTitle, null),
    LandingLine.corners => (AppearanceStrings.of(context).landingCornersTitle, null),
    LandingLine.ready => (strings.readyTitle, null),
    LandingLine.readyNone => (strings.readyNoneTitle, strings.readyNoneBody),
  };

  static const _introDelayMs = 220;
  static const _bubbleAfterLandMs = 150;
  static const _flyAfterMs = 350;
  static const _revealAfterMs = 520;
}

typedef LandingProvider = NotifierProvider<LandingViewModel, LandingUiState>;

/// [parent] mapped through [map] (the pill's content fading with the hand-off).
class _DerivedAnimation extends Animation<double> with AnimationWithParentMixin<double> {
  _DerivedAnimation(this.parent, this.map);

  @override
  final Animation<double> parent;
  final double Function(double) map;

  @override
  double get value => map(parent.value);
}

/// An unbounded spring read as an opacity (0..1).
class _ClampedAnimation extends Animation<double> with AnimationWithParentMixin<double> {
  _ClampedAnimation(this.parent);

  @override
  final Animation<double> parent;

  @override
  double get value => parent.value.clamp(0.0, 1.0);
}

/// The landing's ground (LandingBackdrop): the page colour with the launcher
/// icon's navy glow and two slow brand blooms (violet, pink). The drift runs
/// only when motion is allowed.
class _BackdropPainter extends CustomPainter {
  _BackdropPainter({required this.dark, required this.base, required this.drift}) : super(repaint: drift);

  final bool dark;
  final Color base;
  final Animation<double> drift;

  // The launcher mark's colours (artwork, not theme roles).
  static const _violet = Color(0xFF9113FF);
  static const _pink = Color(0xFFFF59CD);

  @override
  void paint(Canvas canvas, Size size) {
    final p = Curves.linear.transform(drift.value.clamp(0.0, 1.0));
    final w = size.width;
    final h = size.height;
    canvas.drawRect(Offset.zero & size, Paint()..color = base);
    void bloom(Color color, double cx, double cy, double r) {
      if (r <= 0) return;
      final center = Offset(cx, cy);
      canvas.drawCircle(
        center,
        r,
        Paint()
          ..shader = RadialGradient(
            colors: [color, color.withValues(alpha: 0)],
          ).createShader(Rect.fromCircle(center: center, radius: r)),
      );
    }

    final glow = dark ? const Color(0xF2092163) : const Color(0x1A192396);
    bloom(glow, w * (0.5 + 0.04 * p), h * (0.46 + 0.05 * p), math.max(w, h) * 0.62);
    bloom(_violet.withValues(alpha: dark ? 0.30 : 0.16), w * (0.05 + 0.18 * p), h * (0.12 + 0.1 * p), w * 0.62);
    bloom(_pink.withValues(alpha: dark ? 0.17 : 0.15), w * (1.0 - 0.14 * p), h * (0.5 + 0.16 * p), w * 0.55);
  }

  @override
  bool shouldRepaint(_BackdropPainter old) => old.dark != dark || old.base != base || old.drift != drift;
}
