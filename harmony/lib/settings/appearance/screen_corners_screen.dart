import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/services.dart' show SystemUiOverlayStyle;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../detail/detail_page.dart' show DetailBackButton;
import '../../nowplaying/haptics.dart';
import '../../shell/bar_suppression.dart';
import '../../theme/motion.dart';
import '../../theme/system_bars.dart';
import '../../theme/tokens.dart';
import '../../ui/component/corner_mask.dart';
import '../../ui/component/spring_cross_fade.dart';
import '../settings_metrics.dart';
import 'appearance_strings.dart';
import 'screen_corner_control.dart';
import 'screen_corners.dart';

/// Settings › Appearance › Screen corners (ScreenCornersPage.kt, owner
/// 2026-11-08), a PushPage. The whole page is one flat rounded rectangle at
/// the chosen corner over black (a light primary page in both themes,
/// [screenCornersPageScheme]), so where it meets the glass is the preview
/// itself: slide until no black shows in the corners. In the middle a phone
/// plays, in turn, the places that take this corner; at the bottom, the
/// corner bar.
///
/// "Match screen" follows the display's own radius where HarmonyOS reports
/// one (display.getRoundedCorner, API 23+); a panel that reports none falls
/// back to 28 and the bar sets it by hand. Per device, never synced.
///
/// In the wide window's right pane ([inPane]) the same page fills the pane,
/// without its back button (the list stays beside it).
class ScreenCornersScreen extends ConsumerStatefulWidget {
  const ScreenCornersScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  ConsumerState<ScreenCornersScreen> createState() => _ScreenCornersScreenState();
}

class _ScreenCornersScreenState extends ConsumerState<ScreenCornersScreen> {
  @override
  void initState() {
    super.initState();
    // The display may have changed since the value was read (another screen, a fold).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) ref.read(screenCornersProvider.notifier).refreshDevice();
    });
  }

  @override
  Widget build(BuildContext context) {
    final corners = ref.watch(screenCornersProvider);
    final notifier = ref.read(screenCornersProvider.notifier);
    final page = ScreenCornersPageContent(
      value: corners.radius,
      following: corners.following,
      reported: corners.device,
      showBack: !widget.inPane,
      onValueChange: (v) => notifier.select(v, persist: false),
      onInteractionChange: (active) {
        if (!active) notifier.commit();
      },
      onFollow: () => notifier.select(null),
    );
    // The page is light in both themes: the status bar's icons go dark while it shows (in the
    // pane too, as Android's split Activity sets them for its window).
    final region = AnnotatedRegion<SystemUiOverlayStyle>(
      value: yoinStatusBarStyle(Brightness.light),
      child: widget.inPane ? page : Scaffold(backgroundColor: Colors.black, body: page),
    );
    if (widget.inPane) return region;
    return ChromeBarSuppression(child: region);
  }
}

/// [ScreenCornersScreen] without its state: the Tips demo can play it with a value of its own.
class ScreenCornersPageContent extends StatelessWidget {
  const ScreenCornersPageContent({
    super.key,
    required this.value,
    required this.following,
    required this.reported,
    required this.onValueChange,
    required this.onInteractionChange,
    required this.onFollow,
    this.showBack = true,
    this.examplesPlaying = true,
    this.onBack,
  });

  final double value;
  final bool following;
  final double? reported;
  final ValueChanged<double> onValueChange;
  final ValueChanged<bool> onInteractionChange;
  final VoidCallback onFollow;
  final bool showBack;
  final bool examplesPlaying;

  /// The back button's tap in place of popping (the Tips demo's).
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    final base = Theme.of(context);
    final scheme = screenCornersPageScheme(base.colorScheme);
    final strings = AppearanceStrings.of(context);
    return Theme(
      data: base.copyWith(colorScheme: scheme),
      child: Builder(
        builder: (context) {
          final text = Theme.of(context).textTheme;
          return ColoredBox(
            color: Colors.black,
            // The page's corners are painted black over it (not clipped): the preview is the glass itself.
            child: CornerMask(
              radius: value,
              topStart: true,
              topEnd: true,
              bottomStart: true,
              bottomEnd: true,
              fill: Colors.black,
              child: ColoredBox(
                color: scheme.primaryContainer,
                child: SafeArea(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(
                        height: ScreenCornerTokens.headerHeight,
                        child: Row(
                          children: [
                            if (showBack)
                              Padding(
                                padding: const EdgeInsetsDirectional.only(
                                  start: SettingsMetrics.backStart,
                                  end: SettingsMetrics.backEnd,
                                ),
                                child: DetailBackButton(
                                  container: scheme.surfaceContainerHighest,
                                  content: scheme.onSurface,
                                  onBack: onBack,
                                ),
                              )
                            else
                              const SizedBox(width: ScreenCornerTokens.hintPaddingH),
                            Expanded(
                              child: Semantics(
                                header: true,
                                child: Text(
                                  strings.screenCorners,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: text.titleLarge!.copyWith(color: scheme.onSecondaryContainer),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: ScreenCornerTokens.hintPaddingH,
                          vertical: ScreenCornerTokens.hintPaddingV,
                        ),
                        child: Text(
                          strings.screenCornersPageHint,
                          style: text.bodyLarge!.copyWith(
                            color: scheme.onSecondaryContainer.withValues(alpha: ScreenCornerTokens.hintAlpha),
                          ),
                        ),
                      ),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: ScreenCornerTokens.examplesPaddingV),
                          child: CornerExamples(radius: value, playing: examplesPlaying),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(
                          ScreenCornerTokens.controlsPaddingH,
                          0,
                          ScreenCornerTokens.controlsPaddingH,
                          ScreenCornerTokens.controlsPaddingBottom,
                        ),
                        child: ScreenCornerSlider(
                          value: value,
                          following: following,
                          reported: reported,
                          onValueChange: onValueChange,
                          onInteractionChange: onInteractionChange,
                          onFollow: onFollow,
                          showHint: false,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// The page's colours: one light, clearly coloured page in both themes
/// (owner 2026-11-09: in dark theme a dark page read as plain black on an
/// OLED screen, so the black corners had nothing to stand against). Light
/// theme keeps primaryContainer; dark theme takes the same tone-90 primary
/// (its onPrimaryContainer) and re-inks what sits on it, so the bar, its
/// fill and "Match screen" stay one colour family and readable.
ColorScheme screenCornersPageScheme(ColorScheme base) {
  if (base.brightness == Brightness.light) return base.copyWith(onSecondaryContainer: base.onPrimaryContainer);
  final page = base.onPrimaryContainer;
  final ink = base.primaryContainer;
  return base.copyWith(
    primaryContainer: page,
    onPrimaryContainer: ink,
    onSecondaryContainer: ink,
    onSurface: ink,
    onSurfaceVariant: ink.withValues(alpha: 0.75),
    surfaceContainerHighest: Color.alphaBlend(ink.withValues(alpha: 0.10), page),
    primary: base.inversePrimary,
    onPrimary: page,
  );
}

/// The places that take the screen corner, as the middle phone plays them.
enum CornerExample { nowPlaying, memories, back }

/// A phone shaped like this window, its own corner the chosen one (scaled to
/// its size), playing one example at a time and moving on by itself; a tap
/// moves on too. Each moving page wears the corner while it moves, as the
/// real one does. Reduced motion: each example holds mid-way.
class CornerExamples extends StatefulWidget {
  const CornerExamples({super.key, required this.radius, required this.playing});

  final double radius;
  final bool playing;

  @override
  State<CornerExamples> createState() => _CornerExamplesState();
}

class _CornerExamplesState extends State<CornerExamples> with SingleTickerProviderStateMixin {
  late final AnimationController _progress = AnimationController.unbounded(vsync: this);
  int _index = 0;
  int _turn = 0;
  bool _reduced = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (reduced != _reduced || _turn == 0) {
      _reduced = reduced;
      _run();
    }
  }

  @override
  void didUpdateWidget(CornerExamples old) {
    super.didUpdateWidget(old);
    if (old.playing != widget.playing) _run();
  }

  @override
  void dispose() {
    _turn++;
    _progress.dispose();
    super.dispose();
  }

  /// One example: wait, move in, hold, move out, rest, the next one.
  Future<void> _run() async {
    final turn = ++_turn;
    _progress.stop();
    if (_reduced || !widget.playing) {
      _progress.value = _reduced ? ScreenCornerTokens.exampleReducedHold : 0;
      return;
    }
    bool live() => mounted && turn == _turn;
    while (live()) {
      _progress.value = 0;
      await Future<void>.delayed(ScreenCornerTokens.exampleLead);
      if (!live() || !await _move(1)) return;
      await Future<void>.delayed(ScreenCornerTokens.exampleHold);
      if (!live() || !await _move(0)) return;
      await Future<void>.delayed(ScreenCornerTokens.exampleRest);
      if (!live()) return;
      setState(() => _index = (_index + 1) % CornerExample.values.length);
    }
  }

  Future<bool> _move(double to) async {
    try {
      await _progress
          .animateWith(SpringSimulation(YoinSprings.slowSpatial, _progress.value, to, _progress.velocity))
          .orCancel;
      return true;
    } on TickerCanceled {
      return false;
    }
  }

  void _next() {
    YoinHaptics.click();
    setState(() => _index = (_index + 1) % CornerExample.values.length);
    _run();
  }

  String _label(AppearanceStrings strings, CornerExample e) => switch (e) {
    CornerExample.nowPlaying => strings.exampleNowPlaying,
    CornerExample.memories => strings.exampleMemories,
    CornerExample.back => strings.exampleBack,
  };

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final strings = AppearanceStrings.of(context);
    final window = MediaQuery.sizeOf(context);
    final example = CornerExample.values[_index];
    return Column(
      children: [
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final aspect = (window.width / math.max(1, window.height)).clamp(0.3, 1.6);
              final h = math.min(constraints.maxHeight, ScreenCornerTokens.phoneMaxHeight);
              final w = math.min(h * aspect, constraints.maxWidth);
              final scale = w / math.max(1, window.width);
              final corner = widget.radius * scale;
              return Center(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _next,
                  child: SizedBox(
                    width: w,
                    height: w / aspect,
                    child: _Phone(example: example, progress: _progress, corner: corner),
                  ),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: ScreenCornerTokens.examplesGap),
        SpringCrossFade(
          childKey: example,
          fit: StackFit.loose,
          child: Text(
            _label(strings, example),
            style: Theme.of(context).textTheme.titleSmall!.copyWith(color: scheme.onSecondaryContainer),
          ),
        ),
        const SizedBox(height: ScreenCornerTokens.examplesGap),
        _ExampleDots(index: _index, color: scheme.onSecondaryContainer),
      ],
    );
  }
}

/// The phone: a quiet list page, the moving page on it, its own corners
/// painted in the page colour and outlined.
class _Phone extends StatelessWidget {
  const _Phone({required this.example, required this.progress, required this.corner});

  final CornerExample example;
  final Animation<double> progress;
  final double corner;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final radius = BorderRadius.circular(corner);
    return Stack(
      fit: StackFit.expand,
      children: [
        CornerMask(
          radius: corner,
          topStart: true,
          topEnd: true,
          bottomStart: true,
          bottomEnd: true,
          fill: scheme.primaryContainer,
          // A square clip only: the page sliding in from below stays inside the phone.
          child: ClipRect(
            child: ColoredBox(
              color: scheme.surface,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  const _ListPage(),
                  AnimatedBuilder(
                    animation: progress,
                    builder: (context, _) => _movingPage(context, progress.value, radius),
                  ),
                ],
              ),
            ),
          ),
        ),
        IgnorePointer(
          child: DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: radius,
              border: Border.all(
                color: scheme.onSecondaryContainer.withValues(alpha: ScreenCornerTokens.phoneBorderAlpha),
                width: ScreenCornerTokens.phoneBorder,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _movingPage(BuildContext context, double p, BorderRadius radius) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, c) => switch (example) {
        // Now Playing rises from the bottom: a full page whose corners meet the glass.
        CornerExample.nowPlaying => Transform.translate(
          offset: Offset(0, (1 - p) * c.maxHeight),
          child: _Page(
            color: scheme.primary,
            radius: radius,
            child: Center(
              child: FractionallySizedBox(
                widthFactor: 0.62,
                child: Container(
                  height: 64,
                  decoration: BoxDecoration(
                    color: scheme.onPrimary.withValues(alpha: 0.85),
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
              ),
            ),
          ),
        ),
        // Memories retreats upward, shrinking, its corners the screen's.
        CornerExample.memories => Opacity(
          opacity: (1 - ScreenCornerTokens.memoriesFade * p).clamp(0.0, 1.0),
          child: Transform.translate(
            offset: Offset(0, -ScreenCornerTokens.memoriesRise * c.maxHeight * p),
            child: Transform.scale(
              scale: 1 - ScreenCornerTokens.memoriesShrink * p,
              alignment: Alignment.topCenter,
              child: _Page(
                color: scheme.tertiary,
                radius: radius,
                child: _PagePlaceholder(ink: scheme.onTertiary),
              ),
            ),
          ),
        ),
        // The back gesture: the page shrinks toward the edge it came from, the one under it shows.
        CornerExample.back => Transform.translate(
          offset: Offset(ScreenCornerTokens.backShift * c.maxWidth * p, 0),
          child: Transform.scale(
            scale: 1 - ScreenCornerTokens.backShrink * p,
            alignment: Alignment.centerRight,
            child: _Page(
              color: scheme.secondary,
              radius: radius,
              child: _PagePlaceholder(ink: scheme.onSecondary),
            ),
          ),
        ),
      },
    );
  }
}

/// A moving page: its shape painted (not clipped), its content inset clear of the corners.
class _Page extends StatelessWidget {
  const _Page({required this.color, required this.radius, required this.child});

  final Color color;
  final BorderRadius radius;
  final Widget child;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(color: color, borderRadius: radius),
    child: child,
  );
}

/// The page underneath: a few rows, as a list reads at a glance.
class _ListPage extends StatelessWidget {
  const _ListPage();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ColoredBox(
      color: scheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 10),
            _Bar(width: 56, height: 10, color: scheme.onSurface.withValues(alpha: 0.5)),
            for (var i = 0; i < 4; i++) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  _Box(size: 28, radius: 8, color: scheme.surfaceContainerHighest),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FractionallySizedBox(
                      alignment: AlignmentDirectional.centerStart,
                      widthFactor: 0.7,
                      child: _Bar(height: 8, color: scheme.surfaceContainerHighest),
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// A few rows, so a moving page reads as a page and not a block of colour.
class _PagePlaceholder extends StatelessWidget {
  const _PagePlaceholder({required this.ink});

  final Color ink;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 10),
        _Bar(width: 48, height: 9, color: ink.withValues(alpha: 0.55)),
        for (var i = 0; i < 5; i++) ...[
          const SizedBox(height: 9),
          Row(
            children: [
              _Box(size: 24, radius: 7, color: ink.withValues(alpha: 0.22)),
              const SizedBox(width: 8),
              Expanded(
                child: FractionallySizedBox(
                  alignment: AlignmentDirectional.centerStart,
                  widthFactor: 0.6,
                  child: _Bar(height: 7, color: ink.withValues(alpha: 0.22)),
                ),
              ),
            ],
          ),
        ],
      ],
    ),
  );
}

class _Bar extends StatelessWidget {
  const _Bar({this.width, required this.height, required this.color});

  final double? width;
  final double height;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    width: width,
    height: height,
    decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(height / 2)),
  );
}

class _Box extends StatelessWidget {
  const _Box({required this.size, required this.radius, required this.color});

  final double size;
  final double radius;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(radius)),
  );
}

/// One dot per example, the current one long.
class _ExampleDots extends StatelessWidget {
  const _ExampleDots({required this.index, required this.color});

  final int index;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      for (var i = 0; i < CornerExample.values.length; i++) ...[
        if (i > 0) const SizedBox(width: ScreenCornerTokens.dotGap),
        _ExampleDot(active: i == index, color: color),
      ],
    ],
  );
}

class _ExampleDot extends StatefulWidget {
  const _ExampleDot({required this.active, required this.color});

  final bool active;
  final Color color;

  @override
  State<_ExampleDot> createState() => _ExampleDotState();
}

class _ExampleDotState extends State<_ExampleDot> with SingleTickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.active ? 1 : 0,
    spring: YoinSprings.fastSpatial,
  );

  @override
  void didUpdateWidget(_ExampleDot old) {
    super.didUpdateWidget(old);
    if (old.active == widget.active) return;
    final target = widget.active ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _on.snapTo(target);
    } else {
      _on.animateTo(target);
    }
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _on.animation,
    builder: (context, _) {
      final on = _on.value.clamp(0.0, 1.0);
      return Container(
        width: ScreenCornerTokens.dot + (ScreenCornerTokens.dotActiveWidth - ScreenCornerTokens.dot) * on,
        height: ScreenCornerTokens.dot,
        decoration: BoxDecoration(
          color: widget.color.withValues(
            alpha: ScreenCornerTokens.dotAlpha + (ScreenCornerTokens.dotActiveAlpha - ScreenCornerTokens.dotAlpha) * on,
          ),
          borderRadius: BorderRadius.circular(ScreenCornerTokens.dot / 2),
        ),
      );
    },
  );
}
