/// The "E" badge (Android component/ExplicitBadge.kt, owner 2026-11-25 / 26): a song or a release its
/// service marks explicit wears a small rounded square with an E cut out of it, right after its title,
/// Apple Music style. Only explicit ever shows one; a clean or unknown one lays out exactly as before. The
/// square is the title's cap height, sits on its baseline like a capital letter, and is drawn in the
/// title's neutral secondary colour (onSurfaceVariant). The E is a knockout, so whatever lies behind shows
/// through (a cover's shape, the album page's spectrum bar, Now Playing's aurora).
///
/// Now Playing's big titles take the [ExplicitBadge.hero] size: the title's x-height, never taller than
/// 18 (ReleaseMarks.heroMaxSideDp). Geometry and the size / gap / fit rules are :shared's
/// ([ReleaseMarks], golden release-marks.json), so every platform draws the same E.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import '../../data/release/release_marks.dart';
import '../../theme/theme.dart';
import 'component_strings.dart';

/// The font size (logical px, the user's text scale applied) a title set in [style] draws at.
double explicitTitleFontSize(BuildContext context, TextStyle? style) {
  final size = style?.fontSize ?? DefaultTextStyle.of(context).style.fontSize ?? 14;
  return MediaQuery.textScalerOf(context).scale(size);
}

/// The badge for a title set in [textStyle]: a cap-height rounded square in [color] (onSurfaceVariant)
/// with the letter E cut out. Its baseline is its bottom, so a baseline-aligned row sets it on the
/// title's baseline. Says "Explicit" to the screen reader unless [describe] is false (a title around it
/// says it instead: [ExplicitTitle]).
class ExplicitBadge extends StatelessWidget {
  const ExplicitBadge({super.key, required this.textStyle, this.color, this.describe = true, this.hero = false});

  final TextStyle? textStyle;
  final Color? color;
  final bool describe;

  /// Now Playing's big titles: the title's x-height rather than its cap height.
  final bool hero;

  @override
  Widget build(BuildContext context) {
    final side = ReleaseMarks.badgeSide(explicitTitleFontSize(context, textStyle), hero: hero);
    final square = ExplicitSquare(side: side, color: color ?? Theme.of(context).colorScheme.onSurfaceVariant);
    if (!describe) return ExcludeSemantics(child: square);
    return Semantics(label: ComponentStrings.of(context).explicitBadge, child: square);
  }
}

/// The square itself, [side] logical px, in [color] with the E knocked out (no semantics of its own).
class ExplicitSquare extends LeafRenderObjectWidget {
  const ExplicitSquare({super.key, required this.side, required this.color});

  final double side;
  final Color color;

  @override
  RenderExplicitSquare createRenderObject(BuildContext context) => RenderExplicitSquare(side, color);

  @override
  void updateRenderObject(BuildContext context, RenderExplicitSquare renderObject) => renderObject
    ..side = side
    ..color = color;
}

/// [ExplicitSquare]'s render box: reports its bottom as its alphabetic baseline.
class RenderExplicitSquare extends RenderBox {
  RenderExplicitSquare(this._side, this._color);

  double _side;
  set side(double value) {
    if (value == _side) return;
    _side = value;
    _letter?.dispose();
    _letter = null;
    markNeedsLayout();
  }

  Color _color;
  set color(Color value) {
    if (value == _color) return;
    _color = value;
    markNeedsPaint();
  }

  TextPainter? _letter;

  /// The E, bold, its cap height [ReleaseMarks.letterShare] of the square, drawn to clear (dstOut).
  TextPainter get _e => _letter ??= TextPainter(
    text: TextSpan(
      text: 'E',
      style: gsf(_side * ReleaseMarks.letterShare / ReleaseMarks.capHeightEm, weight: 700, letterSpacing: 0).copyWith(
        foreground: Paint()
          ..color = const Color(0xFF000000)
          ..blendMode = BlendMode.dstOut,
      ),
    ),
    textDirection: TextDirection.ltr,
    textScaler: TextScaler.noScaling,
  )..layout();

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.constrain(Size.square(_side));

  @override
  void performLayout() => size = computeDryLayout(constraints);

  @override
  double computeMinIntrinsicWidth(double height) => _side;
  @override
  double computeMaxIntrinsicWidth(double height) => _side;
  @override
  double computeMinIntrinsicHeight(double width) => _side;
  @override
  double computeMaxIntrinsicHeight(double width) => _side;

  @override
  double? computeDistanceToActualBaseline(TextBaseline baseline) => size.height;

  @override
  double? computeDryBaseline(BoxConstraints constraints, TextBaseline baseline) => computeDryLayout(constraints).height;

  @override
  void paint(PaintingContext context, Offset offset) {
    final canvas = context.canvas;
    final rect = offset & size;
    final s = size.shortestSide;
    // Its own layer, so the E clears the square and not what lies under it.
    canvas.saveLayer(rect.inflate(1), Paint());
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, Radius.circular(s * ReleaseMarks.cornerShare)),
      Paint()
        ..color = _color
        ..isAntiAlias = true,
    );
    final e = _e;
    final fontSize = s * ReleaseMarks.letterShare / ReleaseMarks.capHeightEm;
    final capHeight = fontSize * ReleaseMarks.capHeightEm;
    final ascent = e.computeDistanceToActualBaseline(TextBaseline.alphabetic);
    // The E's cap box centred in the square; its advance (with side bearings) centres it across.
    e.paint(canvas, offset + Offset((s - e.width) / 2, (s + capHeight) / 2 - ascent));
    canvas.restore();
  }

  @override
  void dispose() {
    _letter?.dispose();
    super.dispose();
  }
}

/// [child] (a title drawing [text] in [style]) with the [ExplicitBadge] right after its last glyph when
/// [explicit]; without it, [child] alone, laid out exactly as before (ExplicitTitle.kt). A cut title ends
/// on its ellipsis and the badge follows it, so the badge never falls off the end; on [maxLines] > 1 the
/// badge follows the last line. [fillsWidth]: [child] takes all the width it is given (a marquee), so it
/// is given just its text's width and the badge follows that. On a box too narrow to leave the title
/// half of it (or all of itself), the badge is left out ([ReleaseMarks.explicitBadgeFits]).
///
/// The screen reader hears "Explicit" from the title itself, so it is said even when the badge is left
/// out; the title's own semantics are replaced by "[text], Explicit" (keep taps outside [child]).
class ExplicitTitle extends StatelessWidget {
  const ExplicitTitle({
    super.key,
    required this.text,
    required this.explicit,
    required this.style,
    required this.child,
    this.maxLines = 1,
    this.fillsWidth = false,
    this.hero = false,
    this.badgeColor,
  });

  final String text;
  final bool explicit;
  final TextStyle? style;
  final Widget child;
  final int maxLines;
  final bool fillsWidth;
  final bool hero;
  final Color? badgeColor;

  @override
  Widget build(BuildContext context) {
    if (!explicit) return child;
    final full = DefaultTextStyle.of(context).style.merge(style);
    final fontSize = explicitTitleFontSize(context, full);
    return Semantics(
      label: '$text, ${ComponentStrings.of(context).explicitBadge}',
      excludeSemantics: true,
      child: ExplicitTitleLayout(
        text: text,
        style: full,
        textScaler: MediaQuery.textScalerOf(context),
        textDirection: Directionality.of(context),
        maxLines: maxLines,
        fillsWidth: fillsWidth,
        gap: ReleaseMarks.badgeGap(fontSize, hero: hero).toDouble(),
        children: [
          child,
          ExplicitBadge(textStyle: style, color: badgeColor, describe: false, hero: hero),
        ],
      ),
    );
  }
}

/// Lays out [ExplicitTitle]'s title (first child) and badge (second child).
class ExplicitTitleLayout extends MultiChildRenderObjectWidget {
  const ExplicitTitleLayout({
    super.key,
    required this.text,
    required this.style,
    required this.textScaler,
    required this.textDirection,
    required this.maxLines,
    required this.fillsWidth,
    required this.gap,
    required super.children,
  });

  final String text;
  final TextStyle style;
  final TextScaler textScaler;
  final TextDirection textDirection;
  final int maxLines;
  final bool fillsWidth;
  final double gap;

  @override
  RenderExplicitTitle createRenderObject(BuildContext context) => RenderExplicitTitle(this);

  @override
  void updateRenderObject(BuildContext context, RenderExplicitTitle renderObject) => renderObject.update(this);
}

class _TitleParentData extends ContainerBoxParentData<RenderBox> {}

/// [ExplicitTitleLayout]'s render object (ExplicitTitle.kt's Layout).
class RenderExplicitTitle extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _TitleParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _TitleParentData> {
  RenderExplicitTitle(ExplicitTitleLayout config) : _config = config;

  ExplicitTitleLayout _config;
  bool _badgeShown = false;

  /// Whether the last layout kept the badge (a narrow box leaves it out).
  bool get badgeShown => _badgeShown;

  void update(ExplicitTitleLayout config) {
    final old = _config;
    _config = config;
    if (old.text != config.text ||
        old.style != config.style ||
        old.textScaler != config.textScaler ||
        old.textDirection != config.textDirection ||
        old.maxLines != config.maxLines ||
        old.fillsWidth != config.fillsWidth ||
        old.gap != config.gap) {
      markNeedsLayout();
    }
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _TitleParentData) child.parentData = _TitleParentData();
  }

  RenderBox get _title => firstChild!;
  RenderBox get _badge => lastChild!;

  TextPainter _painter({int? maxLines, String? ellipsis}) => TextPainter(
    text: TextSpan(text: _config.text, style: _config.style),
    textDirection: _config.textDirection,
    textScaler: _config.textScaler,
    maxLines: maxLines,
    ellipsis: ellipsis,
  );

  /// The title's one-line width, +1: the measured width is rounded down; a pixel less would cut or scroll.
  double _natural() {
    final p = _painter(maxLines: 1)..layout();
    final w = p.width.ceilToDouble() + 1;
    p.dispose();
    return w;
  }

  @override
  double computeMinIntrinsicWidth(double height) => _title.getMinIntrinsicWidth(height);

  @override
  double computeMaxIntrinsicWidth(double height) =>
      _title.getMaxIntrinsicWidth(height) + _config.gap + _badge.getMaxIntrinsicWidth(height);

  @override
  double computeMinIntrinsicHeight(double width) =>
      math.max(_title.getMinIntrinsicHeight(width), _badge.getMinIntrinsicHeight(width));

  @override
  double computeMaxIntrinsicHeight(double width) =>
      math.max(_title.getMaxIntrinsicHeight(width), _badge.getMaxIntrinsicHeight(width));

  @override
  double? computeDistanceToActualBaseline(TextBaseline baseline) {
    final childBaseline = _title.getDistanceToActualBaseline(baseline);
    if (childBaseline == null) return null;
    return childBaseline + (_title.parentData! as _TitleParentData).offset.dy;
  }

  @override
  Size computeDryLayout(BoxConstraints constraints) => _layout(constraints, dry: true);

  @override
  void performLayout() => size = _layout(constraints, dry: false);

  Size _layout(BoxConstraints constraints, {required bool dry}) {
    final title = _title;
    final badge = _badge;
    final gap = _config.gap;
    final bounded = constraints.hasBoundedWidth;
    final natural = _natural();
    final badgeSize = dry
        ? badge.getDryLayout(const BoxConstraints())
        : (badge..layout(const BoxConstraints(), parentUsesSize: true)).size;
    final maxWidth = constraints.maxWidth;
    // The title wins: on a narrow tile the badge goes rather than squeeze the name to a few glyphs.
    final show =
        !bounded ||
        ReleaseMarks.explicitBadgeFits(maxWidth.floor(), (maxWidth - badgeSize.width - gap).floor(), natural.ceil());
    final room = show ? badgeSize.width + gap : 0.0;
    final available = bounded ? math.max(0.0, maxWidth - room) : double.infinity;
    final titleMax = _config.fillsWidth ? math.min(natural, available) : available;
    final titleConstraints = BoxConstraints(maxWidth: titleMax, maxHeight: constraints.maxHeight);
    final titleSize = dry
        ? title.getDryLayout(titleConstraints)
        : (title..layout(titleConstraints, parentUsesSize: true)).size;
    if (!dry) _badgeShown = show;
    if (!show) {
      if (!dry) (title.parentData! as _TitleParentData).offset = Offset.zero;
      return constraints.constrain(titleSize);
    }
    double end;
    double baseline;
    if (_config.fillsWidth) {
      end = titleSize.width;
      baseline =
          (dry
              ? title.getDryBaseline(titleConstraints, TextBaseline.alphabetic)
              : title.getDistanceToActualBaseline(TextBaseline.alphabetic)) ??
          (titleSize.height + badgeSize.height) / 2;
    } else {
      final laid = _painter(maxLines: _config.maxLines, ellipsis: '…')..layout(maxWidth: math.max(titleSize.width, 1));
      final lines = laid.computeLineMetrics();
      if (lines.isEmpty) {
        end = 0;
        baseline = (titleSize.height + badgeSize.height) / 2;
      } else {
        final last = lines.last;
        final lineEnd = _config.textDirection == TextDirection.ltr ? last.left + last.width : laid.width - last.left;
        end = lineEnd.ceilToDouble().clamp(0.0, titleSize.width);
        baseline = last.baseline;
      }
      laid.dispose();
    }
    final badgeX = end + gap;
    // The square's bottom on the last line's baseline, like a capital letter's.
    final badgeY = math.max(0.0, baseline - badgeSize.height);
    final width = math.max(titleSize.width, badgeX + badgeSize.width);
    final height = math.max(titleSize.height, badgeY + badgeSize.height);
    final size = constraints.constrain(Size(width, height));
    if (!dry) {
      final rtl = _config.textDirection == TextDirection.rtl;
      (title.parentData! as _TitleParentData).offset = Offset(rtl ? size.width - titleSize.width : 0, 0);
      (badge.parentData! as _TitleParentData).offset = Offset(
        rtl ? size.width - badgeX - badgeSize.width : badgeX,
        badgeY,
      );
    }
    return size;
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    context.paintChild(_title, (_title.parentData! as _TitleParentData).offset + offset);
    if (_badgeShown) context.paintChild(_badge, (_badge.parentData! as _TitleParentData).offset + offset);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    final data = _title.parentData! as _TitleParentData;
    return result.addWithPaintOffset(
      offset: data.offset,
      position: position,
      hitTest: (result, transformed) => _title.hitTest(result, position: transformed),
    );
  }
}
