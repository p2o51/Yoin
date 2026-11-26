import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../data/release/release_marks.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/explicit_badge.dart';

/// A Now Playing title on one line (a [Marquee], or a still line) with the hero "E" after it (Android
/// NowPlayingMarqueeTitle's explicit, PR #343): the title's x-height, never taller than 18. The badge's
/// room is taken out of the title's width first, so the E never scrolls away with a long title; in a box
/// too narrow to leave the title half of it (or all of itself) the badge is left out
/// ([ReleaseMarks.explicitBadgeFits]). [child] draws [text] in [style] and fills the width it is given;
/// it is given its text's width, at most what the badge leaves. [stretch]: the scaleX [child] is drawn at
/// (from its start), which the badge rides so the gap stays the same. The screen reader hears
/// "[text], Explicit" from the line, badge or not.
class NowPlayingExplicitLine extends StatelessWidget {
  const NowPlayingExplicitLine({
    super.key,
    required this.text,
    required this.style,
    required this.explicit,
    required this.child,
    this.stretch,
    this.hero = true,
    this.badgeColor,
  });

  final String text;
  final TextStyle style;
  final bool explicit;
  final Widget child;
  final Animation<double>? stretch;

  /// The hero size (Now Playing's titles); false: the cap height (the docked bar's small title).
  final bool hero;
  final Color? badgeColor;

  @override
  Widget build(BuildContext context) {
    if (!explicit) return child;
    final direction = Directionality.of(context);
    final scaler = MediaQuery.textScalerOf(context);
    final fontSize = explicitTitleFontSize(context, style);
    final side = ReleaseMarks.badgeSide(fontSize, hero: hero);
    final gap = ReleaseMarks.badgeGap(fontSize, hero: hero).toDouble();
    final painter = TextPainter(
      text: TextSpan(text: text, style: DefaultTextStyle.of(context).style.merge(style)),
      textDirection: direction,
      textScaler: scaler,
      maxLines: 1,
    )..layout();
    final natural = painter.width.ceilToDouble();
    final baseline = painter.computeDistanceToActualBaseline(TextBaseline.alphabetic);
    painter.dispose();
    return Semantics(
      label: '$text, ${ComponentStrings.of(context).explicitBadge}',
      excludeSemantics: true,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final room = side.ceilToDouble() + gap;
          final bounded = constraints.hasBoundedWidth;
          final show =
              !bounded ||
              ReleaseMarks.explicitBadgeFits(
                constraints.maxWidth.floor(),
                (constraints.maxWidth - room).floor(),
                natural.ceil(),
              );
          if (!show) return child;
          final available = bounded ? math.max(0.0, constraints.maxWidth - room) : natural;
          // Where the title's text ends, and so where the badge follows it.
          final titleWidth = math.min(natural, available);
          // A box the row fills (the hero) keeps all it leaves the title, so a title drawn stretched
          // (scaleX from its start) isn't cut by its own clip; a loose one wraps the title's text.
          final tight = constraints.hasTightWidth;
          final childWidth = tight ? available : titleWidth;
          final badge = ExplicitBadge(textStyle: style, color: badgeColor, describe: false, hero: hero);
          final sign = direction == TextDirection.ltr ? 1.0 : -1.0;
          return SizedBox(
            width: tight ? constraints.maxWidth : titleWidth + room,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                SizedBox(width: childWidth, child: child),
                PositionedDirectional(
                  start: titleWidth + gap,
                  // On the title's baseline, like a capital letter (its square's bottom is its baseline).
                  top: math.max(0.0, baseline - side),
                  child: stretch == null
                      ? badge
                      : AnimatedBuilder(
                          animation: stretch!,
                          // Rides the title's stretch, so the gap stays the same.
                          builder: (context, child) => Transform.translate(
                            offset: Offset(sign * titleWidth * (stretch!.value - 1), 0),
                            child: child,
                          ),
                          child: badge,
                        ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
