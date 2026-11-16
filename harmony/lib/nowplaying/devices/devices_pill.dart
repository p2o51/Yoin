/// The bottom pills' parts (Android BottomPills.kt): one pill that widens
/// its label under the finger and folds to its icon when the row is narrow,
/// the measure-first rule that decides the fold, and the cast chip shown
/// while the system session plays on a cast device (CastButton Connected).
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import '../haptics.dart';

/// Whether the pills can carry their labels in [maxWidth] (Android
/// rememberBottomPillsLayout): measure first, never drop a pill.
@immutable
final class BottomPillsLayout {
  const BottomPillsLayout({required this.labelled, required this.iconOnlyWidth, required this.maxWidth});

  /// Every pill shows its label.
  final bool labelled;
  final double iconOnlyWidth;
  final double maxWidth;

  /// In icon-only mode a pressed pill still opens its label when that alone fits.
  bool fitsPressedLabel(double labelWidth) =>
      iconOnlyWidth + NowPlayingTokens.pillLabelGap + labelWidth <= maxWidth - NowPlayingTokens.pillsSafetyMargin;
}

/// The fold for pills with [labelWidths] in [maxWidth], after a cast chip of [castWidth] (0 = none).
BottomPillsLayout bottomPillsLayout({
  required double maxWidth,
  required List<double> labelWidths,
  double castWidth = 0,
}) {
  const bare = NowPlayingTokens.pillPaddingH * 2 + NowPlayingTokens.pillIcon;
  final gaps = NowPlayingTokens.pillGap * (labelWidths.length - 1);
  final cast = castWidth > 0 ? castWidth + NowPlayingTokens.castChipRowGap : 0.0;
  final iconOnly =
      cast +
      (bare > NowPlayingTokens.pillIconOnlyMin ? bare : NowPlayingTokens.pillIconOnlyMin) * labelWidths.length +
      gaps;
  var labelled = cast + gaps;
  for (final w in labelWidths) {
    labelled += bare + NowPlayingTokens.pillLabelGap + w;
  }
  return BottomPillsLayout(
    labelled: labelled <= maxWidth - NowPlayingTokens.pillsSafetyMargin,
    iconOnlyWidth: iconOnly,
    maxWidth: maxWidth,
  );
}

/// [label]'s width in the pills' label style.
double pillLabelWidth(BuildContext context, String label) {
  final style = Theme.of(context).textTheme.labelMedium!;
  return (TextPainter(
    text: TextSpan(text: label, style: style),
    textDirection: Directionality.of(context),
    textScaler: MediaQuery.textScalerOf(context),
    maxLines: 1,
  )..layout()).width;
}

/// The cast chip's width for [name] (CastConnectedChrome + the name).
double castChipWidth(BuildContext context, String name) {
  final style = Theme.of(context).textTheme.labelLarge!;
  final text = (TextPainter(
    text: TextSpan(text: name, style: style),
    textDirection: Directionality.of(context),
    textScaler: MediaQuery.textScalerOf(context),
    maxLines: 1,
  )..layout()).width;
  return NowPlayingTokens.castChipPaddingH * 2 +
      NowPlayingTokens.castChipIcon +
      NowPlayingTokens.castChipIconGap +
      text;
}

/// One Now Playing pill (BottomPills PillButton): icon, then a label that
/// widens by 80 % while pressed (labelled rows) and folds away on the
/// effects spring when the row is too narrow ([showLabel] false). In a
/// folded row the pressed pill may still open its label ([labelOnPress]).
/// [onPressedChanged] lets the row fold the other pills' labels meanwhile.
class NowPlayingPillButton extends StatefulWidget {
  const NowPlayingPillButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onTap,
    required this.background,
    required this.foreground,
    this.corner,
    this.showLabel = true,
    this.labelOnPress = false,
    this.pressWiden = true,
    this.onPressedChanged,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final Color background;
  final Color foreground;

  /// Rounded-rectangle corner; null = capsule.
  final double? corner;
  final bool showLabel;
  final bool labelOnPress;

  /// Whether a pressed label widens (labelled rows only; Android pressWiden).
  final bool pressWiden;
  final ValueChanged<bool>? onPressedChanged;

  @override
  State<NowPlayingPillButton> createState() => _NowPlayingPillButtonState();
}

class _NowPlayingPillButtonState extends State<NowPlayingPillButton> with TickerProviderStateMixin {
  late final SpringValue _press = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);
  late final SpringValue _label = SpringValue(
    vsync: this,
    initial: widget.showLabel ? 1 : 0,
    spring: YoinSprings.fastEffects,
  );
  bool _pressed = false;

  @override
  void didUpdateWidget(NowPlayingPillButton old) {
    super.didUpdateWidget(old);
    _syncLabel();
  }

  void _syncLabel() => _label.animateTo(widget.showLabel || (_pressed && widget.labelOnPress) ? 1 : 0);

  void _setPressed(bool pressed) {
    if (_pressed == pressed) return;
    _pressed = pressed;
    // Icon-only rows don't widen a pressed label past its natural width.
    _press.animateTo(pressed && widget.pressWiden && widget.showLabel ? 1 : 0);
    _syncLabel();
    widget.onPressedChanged?.call(pressed);
  }

  @override
  void dispose() {
    _press.dispose();
    _label.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.labelMedium!.copyWith(color: widget.foreground);
    final labelWidth = pillLabelWidth(context, widget.label);
    const bare = NowPlayingTokens.pillPaddingH * 2 + NowPlayingTokens.pillIcon;
    final corner = widget.corner;
    final shape = corner == null
        ? const StadiumBorder() as ShapeBorder
        : RoundedRectangleBorder(borderRadius: BorderRadius.circular(corner));
    return Semantics(
      button: true,
      label: widget.label,
      onTap: widget.onTap,
      excludeSemantics: true,
      child: GestureDetector(
        onTapDown: (_) => _setPressed(true),
        onTapUp: (_) => _setPressed(false),
        onTapCancel: () => _setPressed(false),
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: Listenable.merge([_press.animation, _label.animation]),
          builder: (context, _) {
            final fraction = _label.value.clamp(0.0, 1.0);
            final open =
                (NowPlayingTokens.pillLabelGap + labelWidth * (1 + NowPlayingTokens.pillPressWiden * _press.value)) *
                fraction;
            final width = bare + open;
            return Container(
              height: NowPlayingTokens.pillHeight,
              width: width < NowPlayingTokens.pillIconOnlyMin ? NowPlayingTokens.pillIconOnlyMin : width,
              decoration: ShapeDecoration(color: widget.background, shape: shape),
              alignment: Alignment.center,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(widget.icon, size: NowPlayingTokens.pillIcon, color: widget.foreground),
                  ClipRect(
                    child: Align(
                      alignment: AlignmentDirectional.centerStart,
                      widthFactor: fraction,
                      child: Opacity(
                        opacity: fraction,
                        child: Padding(
                          padding: const EdgeInsetsDirectional.only(start: NowPlayingTokens.pillLabelGap),
                          child: Text(widget.label, style: style, maxLines: 1, softWrap: false),
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
    );
  }
}

/// The Devices pill: primary, a 20 dp corner (BottomPills' Devices).
class NowPlayingDevicesPill extends StatelessWidget {
  const NowPlayingDevicesPill({
    super.key,
    required this.label,
    required this.onTap,
    this.showLabel = true,
    this.labelOnPress = false,
    this.pressWiden = true,
    this.onPressedChanged,
  });

  final String label;
  final VoidCallback onTap;
  final bool showLabel;
  final bool labelOnPress;
  final bool pressWiden;
  final ValueChanged<bool>? onPressedChanged;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return NowPlayingPillButton(
      key: const ValueKey('np-pill-devices'),
      icon: YoinSymbols.devices,
      label: label,
      onTap: () {
        YoinHaptics.contextClick();
        onTap();
      },
      background: colors.primary,
      foreground: colors.onPrimary,
      corner: NowPlayingTokens.devicesPillCorner,
      showLabel: showLabel,
      labelOnPress: labelOnPress,
      pressWiden: pressWiden,
      onPressedChanged: onPressedChanged,
    );
  }
}

/// CastButton, Connected: the cast device's name on a primary tonal button;
/// opens the Devices sheet. Grows in and folds away on the springs.
class SystemCastChip extends StatefulWidget {
  const SystemCastChip({super.key, required this.name, required this.onTap, this.maxNameWidth});

  /// null hides the chip (nothing cast).
  final String? name;
  final VoidCallback onTap;
  final double? maxNameWidth;

  @override
  State<SystemCastChip> createState() => _SystemCastChipState();
}

class _SystemCastChipState extends State<SystemCastChip> with TickerProviderStateMixin {
  late final SpringValue _size;
  late final SpringValue _alpha;
  String? _shown;

  @override
  void initState() {
    super.initState();
    final shown = widget.name == null ? 0.0 : 1.0;
    _size = SpringValue(vsync: this, initial: shown, spring: YoinSprings.standardDefaultSpatial);
    _alpha = SpringValue(vsync: this, initial: shown, spring: YoinSprings.defaultEffects);
    _shown = widget.name;
  }

  @override
  void didUpdateWidget(SystemCastChip old) {
    super.didUpdateWidget(old);
    if (widget.name != null) _shown = widget.name;
    final target = widget.name == null ? 0.0 : 1.0;
    _size.animateTo(target);
    _alpha.animateTo(target);
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final name = _shown;
    if (name == null) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onPrimary);
    final maxName = widget.maxNameWidth;
    return AnimatedBuilder(
      animation: Listenable.merge([_size.animation, _alpha.animation]),
      builder: (context, child) {
        final size = _size.value.clamp(0.0, 1.0);
        if (size <= 0.001 && widget.name == null) return const SizedBox.shrink();
        return ClipRect(
          child: Align(
            alignment: AlignmentDirectional.centerStart,
            widthFactor: size,
            child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
          ),
        );
      },
      child: Padding(
        padding: const EdgeInsetsDirectional.only(end: NowPlayingTokens.castChipRowGap),
        // No clip on the capsule (HarmonyOS draws clipped corners with wedges): a shaped fill.
        child: GestureDetector(
          onTap: () {
            YoinHaptics.contextClick();
            widget.onTap();
          },
          child: Container(
            height: NowPlayingTokens.castChipHeight,
            padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.castChipPaddingH),
            decoration: ShapeDecoration(color: colors.primary, shape: const StadiumBorder()),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(YoinSymbols.castConnected, size: NowPlayingTokens.castChipIcon, color: colors.onPrimary),
                const SizedBox(width: NowPlayingTokens.castChipIconGap),
                ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: maxName ?? double.infinity),
                  child: Text(name, style: text, maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
