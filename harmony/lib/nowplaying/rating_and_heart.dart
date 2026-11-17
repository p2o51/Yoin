import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'action_glow.dart';
import 'haptics.dart';
import 'nowplaying_strings.dart';
import 'transport_signal.dart';
import '../ui/component/expressive/expressive_shapes.dart';
import '../ui/component/expressive/expressive_surface_button.dart';

/// "7.4", a full 10 shows "10" (RatingFormat.kt).
String formatRating(double rating) => rating >= 10 ? '10' : rating.toStringAsFixed(1);

/// Rating at a vertical fraction (1 = top), in 0.1 steps.
double ratingAtFraction(double fraction) => (fraction.clamp(0.0, 1.0) * 100).round() / 10;

/// A vertical drag that wins the arena on pointer DOWN, so Now Playing's
/// drag-to-dismiss never steals it (memory np_dismiss_draggable_steals_child_drags).
class _EagerVerticalDrag extends VerticalDragGestureRecognizer {
  @override
  void addAllowedPointer(PointerDownEvent event) {
    super.addAllowedPointer(event);
    resolve(GestureDisposition.accepted);
  }
}

/// The vertical rating slider (RatingSlider.kt): a 48 dp stadium track,
/// filled from the bottom to rating / 10 on the Expressive default spatial
/// spring; the number at the top crosses to onPrimary as the fill passes
/// 80 %. Claims the pointer on down, shows a draft while held (tick per
/// 0.1), and commits only on lift. Unrated draws an empty bar and no number.
class RatingSlider extends StatefulWidget {
  const RatingSlider({super.key, required this.rating, required this.onCommit, required this.songKey});

  final double? rating;
  final ValueChanged<double> onCommit;

  /// A new song cancels a held press without committing.
  final String songKey;

  @override
  State<RatingSlider> createState() => _RatingSliderState();
}

class _RatingSliderState extends State<RatingSlider> with TickerProviderStateMixin {
  late final SpringValue _fill = SpringValue(
    vsync: this,
    initial: (widget.rating ?? 0) / 10,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _labelOnFill = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  double? _draft;
  double? _committed;

  /// Action lights (ActionGlow.kt): a column up the bar while held, a band
  /// across the room when the rating lands, a drain when it is cleared.
  final GlobalRectProbe _glowBounds = GlobalRectProbe();
  NowPlayingTransportSignal? _signal;

  void _glowHold(double value) {
    final signal = _signal;
    final b = _glowBounds.rect;
    if (signal == null || b == null) return;
    signal.glowHold.value = GlowHold(
      kind: GlowHoldKind.rating,
      pointRoot: ratingPoint(b, value, vertical: true, rtl: false),
      barRoot: b,
      value: value,
    );
  }

  void _glowLetGo() {
    final signal = _signal;
    if (signal != null && signal.glowHold.value?.kind == GlowHoldKind.rating) signal.glowHold.value = null;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _signal = NowPlayingSignalScope.maybeOf(context);
  }

  @override
  void didUpdateWidget(RatingSlider oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.songKey != widget.songKey) {
      // A new song cancels the press without a lift: its light goes too.
      if (_draft != null) _glowLetGo();
      _draft = null;
      _committed = null;
    }
    // The draft stays until the stored rating catches up.
    if (_committed != null && widget.rating == _committed) _committed = null;
    _sync();
  }

  @override
  void dispose() {
    _glowLetGo();
    _fill.dispose();
    _labelOnFill.dispose();
    super.dispose();
  }

  double? get _shown => _draft ?? _committed ?? widget.rating;

  void _sync() {
    final shown = _shown ?? 0;
    _fill.animateTo(shown / 10);
    final f = shown / 10;
    _labelOnFill.animateTo(
      ((f - NowPlayingTokens.ratingLabelCrossover) / (1 - NowPlayingTokens.ratingLabelCrossover)).clamp(0.0, 1.0),
    );
  }

  void _setFromLocal(Offset local, double height) {
    final value = ratingAtFraction(1 - local.dy / height);
    if (_draft != null && value != _draft) YoinHaptics.tick();
    if (value == _draft) return;
    setState(() => _draft = value);
    _glowHold(value);
    _sync();
  }

  void _lift() {
    final draft = _draft;
    _glowLetGo();
    if (draft == null) return;
    setState(() {
      _committed = draft;
      _draft = null;
    });
    final b = _glowBounds.rect;
    if (b != null) {
      _signal?.glow(
        ratingGlowKind(draft),
        Rect.fromCircle(center: ratingPoint(b, draft, vertical: true, rtl: false), radius: 1),
        value: draft,
      );
    }
    widget.onCommit(draft);
  }

  @override
  Widget build(BuildContext context) => ReportBounds(
    probe: _glowBounds,
    child: Builder(builder: _buildSlider),
  );

  Widget _buildSlider(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final shown = _shown;
    return LayoutBuilder(
      builder: (context, constraints) {
        final height = constraints.maxHeight;
        return Semantics(
          slider: true,
          value: shown == null ? strings.ratingUnrated : strings.ratingState(formatRating(shown)),
          child: RawGestureDetector(
            behavior: HitTestBehavior.opaque,
            gestures: {
              _EagerVerticalDrag: GestureRecognizerFactoryWithHandlers<_EagerVerticalDrag>(
                _EagerVerticalDrag.new,
                (r) => r
                  ..onStart = ((d) {
                    _draft = null;
                    _setFromLocal(d.localPosition, height);
                  })
                  ..onUpdate = ((d) => _setFromLocal(d.localPosition, height))
                  ..onEnd = ((_) => _lift())
                  ..onCancel = (() {
                    _glowLetGo();
                    setState(() => _draft = null);
                  }),
              ),
            },
            child: SizedBox(
              width: NowPlayingTokens.ratingBarWidth,
              child: AnimatedBuilder(
                animation: Listenable.merge([_fill.animation, _labelOnFill.animation]),
                builder: (context, _) {
                  final fill = _fill.value.clamp(0.0, 1.0);
                  return ClipPath(
                    clipper: const ShapeBorderClipper(shape: StadiumBorder()),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        ColoredBox(color: colors.surfaceContainerHighest),
                        Align(
                          alignment: Alignment.bottomCenter,
                          child: FractionallySizedBox(
                            heightFactor: fill,
                            widthFactor: 1,
                            child: ColoredBox(color: colors.primary),
                          ),
                        ),
                        Align(
                          alignment: Alignment.topCenter,
                          child: Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: ConstrainedBox(
                              constraints: const BoxConstraints(minWidth: 36),
                              child: Opacity(
                                opacity: shown == null ? 0 : 1,
                                child: Text(
                                  shown == null ? '' : formatRating(shown),
                                  textAlign: TextAlign.center,
                                  maxLines: 1,
                                  style: Theme.of(context).textTheme.titleLarge!.copyWith(
                                    color: Color.lerp(colors.onSurfaceVariant, colors.onPrimary, _labelOnFill.value),
                                    fontFeatures: const [FontFeature.tabularFigures()],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The heart (FavoriteButton): a 44 dp circle, tertiaryContainer → tertiary
/// when starred, squishing 1 → 1.25 (star) / 1.15 (un-star) in 90 ms, then
/// springing back.
class FavoriteHeart extends StatefulWidget {
  const FavoriteHeart({super.key, required this.starred, required this.onToggle});

  final bool starred;
  final VoidCallback onToggle;

  @override
  State<FavoriteHeart> createState() => _FavoriteHeartState();
}

class _FavoriteHeartState extends State<FavoriteHeart> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.starred ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final AnimationController _scale = AnimationController.unbounded(vsync: this, value: 1);

  /// The heart's bounds, for its action light (ActionGlow.kt FavoriteOn / Off).
  final GlobalRectProbe _glowBounds = GlobalRectProbe();

  @override
  void didUpdateWidget(FavoriteHeart oldWidget) {
    super.didUpdateWidget(oldWidget);
    _on.animateTo(widget.starred ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    _scale.dispose();
    super.dispose();
  }

  Future<void> _tap() async {
    final starring = !widget.starred;
    starring ? YoinHaptics.confirm() : YoinHaptics.tick();
    NowPlayingSignalScope.maybeOf(
      context,
    )?.glow(starring ? ActionGlowKind.favoriteOn : ActionGlowKind.favoriteOff, _glowBounds.rect);
    widget.onToggle();
    if (MediaQuery.disableAnimationsOf(context)) return;
    await _scale.animateTo(starring ? 1.25 : 1.15, duration: const Duration(milliseconds: 90));
    if (!mounted) return;
    _scale.animateWith(SpringSimulation(YoinSprings.standardDefaultSpatial, _scale.value, 1, _scale.velocity));
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    // 44 dp circle inside a 48 dp touch target (minimumTouchTarget); the
    // squish scales the circle, its ripple and glyph together (graphicsLayer
    // before clip in FavoriteButton). Round, no press morph.
    return SizedBox.square(
      dimension: _heartTouchTarget,
      child: ReportBounds(
        probe: _glowBounds,
        child: Center(
          child: AnimatedBuilder(
            animation: Listenable.merge([_on.animation, _scale]),
            builder: (context, _) {
              final on = _on.value.clamp(0.0, 1.0);
              final content = Color.lerp(colors.onTertiaryContainer, colors.onTertiary, on)!;
              return Transform.scale(
                scale: _scale.value,
                child: SizedBox.square(
                  dimension: NowPlayingTokens.heartSize,
                  child: ExpressiveSurfaceButton(
                    shapes: const InteractionShapes.fixed(CornerRadii.full),
                    height: NowPlayingTokens.heartSize,
                    color: Color.lerp(colors.tertiaryContainer, colors.tertiary, on)!,
                    contentColor: content,
                    semanticLabel: widget.starred ? strings.cdRemoveFavorite : strings.cdAddFavorite,
                    onTap: _tap,
                    child: Icon(
                      widget.starred ? YoinSymbols.favoriteFilled : YoinSymbols.favorite,
                      size: _heartGlyph,
                      color: content,
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

const double _heartTouchTarget = 48;
const double _heartGlyph = 24;
