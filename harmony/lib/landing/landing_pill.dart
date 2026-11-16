import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/expressive/expressive_shapes.dart';
import '../ui/component/expressive/expressive_surface_button.dart';
import 'landing_models.dart';
import 'landing_strings.dart';

/// The landing's floating navigation (LandingPill) — a twin of the shell's
/// bar (same surface, height and corners), so the hand-off can turn it into
/// the bar. [hello]: the whole pill is the primary button. Otherwise back,
/// the step dots and the primary button.
class LandingPill extends StatefulWidget {
  const LandingPill({
    super.key,
    required this.hello,
    required this.primary,
    required this.dotCount,
    required this.dotIndex,
    required this.centered,
    required this.onBack,
    required this.onPrimary,
    required this.contentAlpha,
  });

  final bool hello;
  final LandingPrimary primary;
  final int dotCount;
  final int dotIndex;

  /// The centred bar's 44 buttons in a 60 pill (else 48 in 68).
  final bool centered;
  final VoidCallback onBack;
  final VoidCallback onPrimary;

  /// The content's alpha (it fades out as the pill becomes the bar).
  final Animation<double> contentAlpha;

  @override
  State<LandingPill> createState() => _LandingPillState();
}

class _LandingPillState extends State<LandingPill> with TickerProviderStateMixin {
  /// 1 = back and dots shown (every step but hello).
  late final SpringValue _chrome = SpringValue(
    vsync: this,
    initial: widget.hello ? 0 : 1,
    spring: YoinSprings.standardDefaultSpatial,
  );

  @override
  void didUpdateWidget(LandingPill old) {
    super.didUpdateWidget(old);
    final target = widget.hello ? 0.0 : 1.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _chrome.snapTo(target);
    } else {
      _chrome.animateTo(target);
    }
  }

  @override
  void dispose() {
    _chrome.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = LandingStrings.of(context);
    final height = widget.centered ? BarTokens.centeredHeight : BarTokens.height;
    final buttonHeight = widget.centered ? BarTokens.centeredButtonHeight : BarTokens.buttonHeight;
    final padding = (height - buttonHeight) / 2;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceContainerHigh,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(BarTokens.corner)),
      ),
      child: FadeTransition(
        opacity: widget.contentAlpha,
        child: Padding(
          padding: EdgeInsets.all(padding),
          child: LayoutBuilder(
            builder: (context, constraints) => AnimatedBuilder(
              animation: _chrome.animation,
              builder: (context, _) {
                final c = _chrome.value.clamp(0.0, 1.0);
                // Back: fades, scales from nothing and opens its slot (expandHorizontally).
                final backSlot = (buttonHeight + BarTokens.itemGap) * c;
                return Row(
                  children: [
                    if (backSlot > 0.5)
                      SizedBox(
                        width: backSlot,
                        child: ClipRect(
                          child: OverflowBox(
                            alignment: AlignmentDirectional.centerStart,
                            minWidth: buttonHeight,
                            maxWidth: buttonHeight,
                            child: Opacity(
                              opacity: c,
                              child: Transform.scale(
                                scale: c,
                                child: _BackButton(size: buttonHeight, label: strings.back, onTap: widget.onBack),
                              ),
                            ),
                          ),
                        ),
                      ),
                    // The dots keep their size while the slot opens (the
                    // primary lets go of the row): clipped, never squeezed.
                    Expanded(
                      child: ClipRect(
                        child: OverflowBox(
                          minWidth: 0,
                          maxWidth: double.infinity,
                          child: Opacity(
                            opacity: widget.dotCount > 1 ? c : 0,
                            child: c <= 0.01 || widget.dotCount <= 1
                                ? const SizedBox.shrink()
                                : _LandingDots(count: widget.dotCount, index: widget.dotIndex),
                          ),
                        ),
                      ),
                    ),
                    _PrimaryButton(
                      primary: widget.primary,
                      height: buttonHeight,
                      // Hello: the whole pill is the button; it lets go of the width as the chrome comes in.
                      fill: 1 - c,
                      maxWidth: math.max(0.0, constraints.maxWidth - backSlot),
                      onTap: widget.onPrimary,
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}

class _BackButton extends StatelessWidget {
  const _BackButton({required this.size, required this.label, required this.onTap});

  final double size;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox.square(
      dimension: size,
      child: ExpressiveSurfaceButton(
        shapes: m3eIconButtonShapes(height: size),
        height: size,
        color: colors.surfaceBright,
        contentColor: colors.onSurface,
        semanticLabel: label,
        onTap: onTap,
        child: Icon(YoinSymbols.back, size: LandingTokens.backGlyph, color: colors.onSurface),
      ),
    );
  }
}

/// The primary button: its colours spring between filled / tonal /
/// unavailable, its label slides in from below, the arrow opens beside it.
class _PrimaryButton extends StatefulWidget {
  const _PrimaryButton({
    required this.primary,
    required this.height,
    required this.fill,
    required this.maxWidth,
    required this.onTap,
  });

  final LandingPrimary primary;
  final double height;

  /// 1 = it takes the whole row (hello), 0 = its own width.
  final double fill;

  /// The room the row leaves it.
  final double maxWidth;
  final VoidCallback onTap;

  @override
  State<_PrimaryButton> createState() => _PrimaryButtonState();
}

class _PrimaryButtonState extends State<_PrimaryButton> with TickerProviderStateMixin {
  late LandingPrimaryStyle _from = widget.primary.style;
  late LandingPrimaryStyle _to = widget.primary.style;
  late final SpringValue _style = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  late final SpringValue _arrow = SpringValue(
    vsync: this,
    initial: widget.primary.arrow ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );

  /// The label slide: 0 = the old label, 1 = the new one in place.
  late final SpringValue _label = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardFastSpatial);
  late LandingPrimaryLabel _oldLabel = widget.primary.label;

  /// The label row's width, springing as the label changes (animateContentSize).
  late final SpringValue _width = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  double? _widthTarget;

  /// The spring holds the shown width (after the first frame's snap).
  bool _widthLive = false;

  /// Springs the width toward [target] after this frame (never from inside build).
  void _followWidth(double target, {required bool reduce}) {
    if (_widthTarget == target) return;
    final first = _widthTarget == null;
    _widthTarget = target;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _widthTarget != target) return;
      (first || reduce) ? _width.snapTo(target) : _width.animateTo(target);
      _widthLive = true;
    });
  }

  @override
  void didUpdateWidget(_PrimaryButton old) {
    super.didUpdateWidget(old);
    final reduce = MediaQuery.disableAnimationsOf(context);
    if (old.primary.style != widget.primary.style) {
      final current = _style.value.clamp(0.0, 1.0);
      // Retarget from what is on screen: the colour so far becomes the new start.
      _from = current >= 0.5 ? _to : _from;
      _to = widget.primary.style;
      _style.snapTo(0);
      reduce ? _style.snapTo(1) : _style.animateTo(1);
    }
    if (old.primary.label != widget.primary.label) {
      _oldLabel = old.primary.label;
      _label.snapTo(0);
      reduce ? _label.snapTo(1) : _label.animateTo(1);
    }
    final arrow = widget.primary.arrow ? 1.0 : 0.0;
    reduce ? _arrow.snapTo(arrow) : _arrow.animateTo(arrow);
  }

  @override
  void dispose() {
    _style.dispose();
    _arrow.dispose();
    _label.dispose();
    _width.dispose();
    super.dispose();
  }

  (Color, Color) _colors(ColorScheme colors, LandingPrimaryStyle style) => switch (style) {
    LandingPrimaryStyle.filled => (colors.primary, colors.onPrimary),
    LandingPrimaryStyle.tonal => (colors.secondaryContainer, colors.onSecondaryContainer),
    LandingPrimaryStyle.unavailable => (colors.surfaceContainerHighest, colors.onSurfaceVariant),
  };

  String _text(LandingStrings strings, LandingPrimaryLabel label) => switch (label) {
    LandingPrimaryLabel.getStarted => strings.getStarted,
    LandingPrimaryLabel.next => strings.next,
    LandingPrimaryLabel.skip => strings.skip,
    LandingPrimaryLabel.openYoin => strings.openYoin,
  };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = LandingStrings.of(context);
    final style = theme.textTheme.labelLarge!.copyWith(fontSize: theme.textTheme.titleSmall!.fontSize);
    final reduce = MediaQuery.disableAnimationsOf(context);
    return AnimatedBuilder(
      animation: Listenable.merge([_style.animation, _arrow.animation, _label.animation, _width.animation]),
      builder: (context, _) {
        final s = _style.value.clamp(0.0, 1.0);
        final (fromBg, fromFg) = _colors(colors, _from);
        final (toBg, toFg) = _colors(colors, _to);
        final bg = Color.lerp(fromBg, toBg, s)!;
        final fg = Color.lerp(fromFg, toFg, s)!;
        final arrow = _arrow.value.clamp(0.0, 1.0);
        final newText = _text(strings, widget.primary.label);
        final oldText = _text(strings, _oldLabel);
        final measured = _textWidth(newText, style, context);
        final contentW =
            measured +
            (LandingTokens.primaryArrowGap + LandingTokens.primaryArrow) * arrow +
            2 * LandingTokens.primaryPadding;
        final shownW = _widthLive ? _width.value : contentW;
        _followWidth(contentW, reduce: reduce);
        final own = math.min(widget.maxWidth, math.max(M3eButtonTokens.minWidth, shownW));
        final width = own + (widget.maxWidth - own) * widget.fill.clamp(0.0, 1.0);
        final l = _label.value;
        return SizedBox(
          width: width,
          height: widget.height,
          child: ExpressiveSurfaceButton(
            shapes: m3eIconButtonShapes(height: widget.height),
            height: widget.height,
            color: bg,
            contentColor: fg,
            semanticLabel: newText,
            onTap: widget.onTap,
            // The label never squeezes while the width springs after it: it is
            // laid out whole and clipped to the button (animateContentSize).
            child: ExcludeSemantics(
              child: ClipRect(
                child: OverflowBox(
                  minWidth: 0,
                  maxWidth: double.infinity,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      ClipRect(
                        child: Stack(
                          alignment: Alignment.center,
                          children: [
                            if (l < 1)
                              FractionalTranslation(
                                translation: Offset(0, -l / 3),
                                child: Opacity(
                                  opacity: (1 - l * 2).clamp(0.0, 1.0),
                                  child: Text(oldText, style: style.copyWith(color: fg), maxLines: 1),
                                ),
                              ),
                            FractionalTranslation(
                              translation: Offset(0, (1 - l) / 3),
                              child: Opacity(
                                opacity: l.clamp(0.0, 1.0),
                                child: Text(newText, style: style.copyWith(color: fg), maxLines: 1),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (arrow > 0.01)
                        SizedBox(
                          width: (LandingTokens.primaryArrowGap + LandingTokens.primaryArrow) * arrow,
                          child: ClipRect(
                            child: Opacity(
                              opacity: arrow,
                              child: const Align(
                                alignment: AlignmentDirectional.centerEnd,
                                widthFactor: 1,
                                child: _Arrow(),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  static double _textWidth(String text, TextStyle style, BuildContext context) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      maxLines: 1,
    )..layout();
    final w = painter.width;
    painter.dispose();
    return w;
  }
}

class _Arrow extends StatelessWidget {
  const _Arrow();

  @override
  Widget build(BuildContext context) => const Padding(
    padding: EdgeInsetsDirectional.only(start: LandingTokens.primaryArrowGap),
    child: _ArrowGlyph(),
  );
}

class _ArrowGlyph extends StatelessWidget {
  const _ArrowGlyph();

  @override
  Widget build(BuildContext context) =>
      Icon(YoinSymbols.chevronRight, size: LandingTokens.primaryArrow, color: IconTheme.of(context).color);
}

/// The step dots (LandingDots): the current one long, the done ones half strength.
class _LandingDots extends StatelessWidget {
  const _LandingDots({required this.count, required this.index});

  final int count;
  final int index;

  @override
  Widget build(BuildContext context) {
    final strings = LandingStrings.of(context);
    return Semantics(
      label: strings.dots(index + 1, count),
      child: ExcludeSemantics(
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < count; i++) ...[
              if (i > 0) const SizedBox(width: LandingTokens.dotGap),
              _Dot(state: i == index ? _DotState.current : (i < index ? _DotState.done : _DotState.ahead)),
            ],
          ],
        ),
      ),
    );
  }
}

enum _DotState { done, current, ahead }

class _Dot extends StatefulWidget {
  const _Dot({required this.state});

  final _DotState state;

  @override
  State<_Dot> createState() => _DotStateful();
}

class _DotStateful extends State<_Dot> with TickerProviderStateMixin {
  late final SpringValue _width = SpringValue(
    vsync: this,
    initial: widget.state == _DotState.current ? LandingTokens.dotCurrent : LandingTokens.dot,
    spring: YoinSprings.defaultSpatial,
  );
  late _DotState _from = widget.state;
  late final SpringValue _color = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);

  @override
  void didUpdateWidget(_Dot old) {
    super.didUpdateWidget(old);
    if (old.state == widget.state) return;
    final reduce = MediaQuery.disableAnimationsOf(context);
    final width = widget.state == _DotState.current ? LandingTokens.dotCurrent : LandingTokens.dot;
    _from = old.state;
    _color.snapTo(0);
    if (reduce) {
      _width.snapTo(width);
      _color.snapTo(1);
    } else {
      _width.animateTo(width);
      _color.animateTo(1);
    }
  }

  @override
  void dispose() {
    _width.dispose();
    _color.dispose();
    super.dispose();
  }

  Color _of(ColorScheme colors, _DotState state) => switch (state) {
    _DotState.current => colors.primary,
    _DotState.done => colors.primary.withValues(alpha: 0.55),
    _DotState.ahead => colors.outlineVariant,
  };

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([_width.animation, _color.animation]),
      builder: (context, _) => Container(
        width: math.max(0.0, _width.value),
        height: LandingTokens.dot,
        decoration: ShapeDecoration(
          color: Color.lerp(_of(colors, _from), _of(colors, widget.state), _color.value.clamp(0.0, 1.0)),
          shape: const StadiumBorder(),
        ),
      ),
    );
  }
}
