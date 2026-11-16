import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../theme/tokens.dart';
import '../../ui/component/meta_line.dart' show VersionTitleRow;
import '../home_metrics.dart';
import 'seam.dart';

/// One line that scrolls when it does not fit (MarqueeText.kt / basicMarquee):
/// 1.2 s before the first pass, 1.8 s between passes, 30 dp/s, a third of the
/// width between copies. It always flows while visible (TickerMode pauses it
/// when Home is covered). Reduced motion: a static ellipsis.
class MarqueeText extends StatelessWidget {
  const MarqueeText(this.text, {super.key, required this.style});

  final String text;
  final TextStyle style;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) {
          final painter = TextPainter(
            text: TextSpan(text: text, style: style),
            textDirection: Directionality.of(context),
            textScaler: MediaQuery.textScalerOf(context),
            maxLines: 1,
          )..layout();
          final width = painter.width;
          final height = painter.height;
          painter.dispose();
          if (width <= constraints.maxWidth || MediaQuery.disableAnimationsOf(context) || !constraints.hasBoundedWidth) {
            return Text(text, style: style, maxLines: 1, overflow: TextOverflow.ellipsis, softWrap: false);
          }
          return _Marquee(text: text, style: style, textWidth: width, boxWidth: constraints.maxWidth, textHeight: height);
        },
      );
}

class _Marquee extends StatefulWidget {
  const _Marquee({required this.text, required this.style, required this.textWidth, required this.boxWidth, required this.textHeight});

  final String text;
  final TextStyle style;
  final double textWidth;
  final double boxWidth;
  final double textHeight;

  @override
  State<_Marquee> createState() => _MarqueeState();
}

class _MarqueeState extends State<_Marquee> with SingleTickerProviderStateMixin {
  final ValueNotifier<double> _offset = ValueNotifier(0);
  late final _ticker = createTicker(_tick)..start();

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _ticker;
  }

  double get _spacing => widget.boxWidth / 3;

  void _tick(Duration elapsed) {
    final distance = widget.textWidth + _spacing;
    final scrollMs = distance / HomeTimings.marqueeVelocity * 1000;
    final t = elapsed.inMicroseconds / 1000 - HomeTimings.marqueeInitialDelay.inMilliseconds;
    if (t <= 0) {
      _offset.value = 0;
      return;
    }
    final cycle = scrollMs + HomeTimings.marqueeRepeatDelay.inMilliseconds;
    final local = t % cycle;
    _offset.value = math.min(local, scrollMs) / scrollMs * distance;
  }

  @override
  void dispose() {
    _ticker.dispose();
    _offset.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final line = Text(widget.text, style: widget.style, maxLines: 1, softWrap: false);
    final rtl = Directionality.of(context) == TextDirection.rtl ? -1.0 : 1.0;
    return ClipRect(
      child: ShaderMask(
        blendMode: BlendMode.dstIn,
        shaderCallback: (rect) => const LinearGradient(
          colors: [Color(0x00FFFFFF), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0x00FFFFFF)],
          stops: [0, 0.04, 0.96, 1],
        ).createShader(rect),
        child: SizedBox(
          width: widget.boxWidth,
          height: widget.textHeight,
          child: OverflowBox(
            alignment: AlignmentDirectional.centerStart,
            maxWidth: double.infinity,
            child: ValueListenableBuilder<double>(
              valueListenable: _offset,
              child: Row(mainAxisSize: MainAxisSize.min, children: [line, SizedBox(width: _spacing), line]),
              builder: (context, dx, child) => Transform.translate(offset: Offset(-dx * rtl, 0), child: child),
            ),
          ),
        ),
      ),
    );
  }
}

/// A MetaLine group: a plain value, or a stat (value + muted unit).
sealed class MetaGroup {
  const MetaGroup();
}

final class MetaPlain extends MetaGroup {
  const MetaPlain(this.text);
  final String text;
}

final class MetaStat extends MetaGroup {
  const MetaStat(this.value, this.unit);
  final String value;
  final String unit;
}

/// MetaLine.kt: groups 12 apart, never joined by a glyph; a stat's unit sits
/// 4 after its value at 60 % alpha; values are Medium with tabular figures.
/// Groups that do not fit are dropped whole from the end.
class MetaLine extends StatelessWidget {
  const MetaLine({super.key, required this.groups, required this.style});

  final List<MetaGroup> groups;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    final valueStyle = style.copyWith(fontWeight: FontWeight.w500, fontFeatures: const [FontFeature.tabularFigures()]);
    final unitStyle = style.copyWith(color: style.color?.withValues(alpha: (style.color?.a ?? 1) * MetaLineTokens.mutedAlpha));
    InlineSpan span(MetaGroup g) => switch (g) {
          MetaPlain(:final text) => TextSpan(text: text, style: style),
          MetaStat(:final value, :final unit) => TextSpan(children: [
              TextSpan(text: value, style: valueStyle),
              const WidgetSpan(child: SizedBox(width: MetaLineTokens.innerGap)),
              TextSpan(text: unit, style: unitStyle),
            ]),
        };
    return LayoutBuilder(builder: (context, constraints) {
      final scaler = MediaQuery.textScalerOf(context);
      final direction = Directionality.of(context);
      double widthOf(MetaGroup g) {
        final painter = TextPainter(
          text: switch (g) {
            MetaPlain(:final text) => TextSpan(text: text, style: style),
            MetaStat(:final value, :final unit) => TextSpan(children: [TextSpan(text: value, style: valueStyle), TextSpan(text: unit, style: unitStyle)]),
          },
          textDirection: direction,
          textScaler: scaler,
          maxLines: 1,
        )..layout();
        final w = painter.width + (g is MetaStat ? MetaLineTokens.innerGap : 0);
        painter.dispose();
        return w;
      }

      final kept = <MetaGroup>[];
      var used = 0.0;
      for (final g in groups) {
        final w = widthOf(g) + (kept.isEmpty ? 0 : MetaLineTokens.groupGap);
        if (used + w > constraints.maxWidth + 0.5) break;
        used += w;
        kept.add(g);
      }
      return Text.rich(
        TextSpan(children: [
          for (var i = 0; i < kept.length; i++) ...[
            if (i > 0) const WidgetSpan(child: SizedBox(width: MetaLineTokens.groupGap)),
            span(kept[i]),
          ],
        ]),
        maxLines: 1,
        softWrap: false,
      );
    });
  }
}

/// Strips a trailing " (version)" from an album name when the version is shown as a tag.
String albumNameWithoutVersion(String name, String? version) {
  if (version == null || version.isEmpty) return name;
  final suffix = ' ($version)';
  return name.toLowerCase().endsWith(suffix.toLowerCase()) ? name.substring(0, name.length - suffix.length) : name;
}

/// AlbumVersionTag: secondaryContainer, 6 corner, 6 × 1 padding, labelMedium.
class AlbumVersionTag extends StatelessWidget {
  const AlbumVersionTag(this.version, {super.key});
  final String version;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelMedium!.copyWith(color: colors.onSecondaryContainer);
    return DecoratedBox(
      decoration: BoxDecoration(color: colors.secondaryContainer, borderRadius: BorderRadius.circular(HomeVersionTagMetrics.radius)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: HomeVersionTagMetrics.paddingH, vertical: HomeVersionTagMetrics.paddingV),
        child: SeamFade(fontSize: style.fontSize ?? 12, child: Text(version, style: style, maxLines: 1)),
      ),
    );
  }
}

/// A title line with an optional version tag after it (AlbumVersionTitle).
/// [marquee] scrolls an overflowing title; otherwise it ellipsizes.
class VersionTitle extends StatelessWidget {
  const VersionTitle({super.key, required this.title, required this.style, this.version, this.marquee = false});

  final String title;
  final String? version;
  final TextStyle style;
  final bool marquee;

  @override
  Widget build(BuildContext context) {
    final v = version;
    final name = albumNameWithoutVersion(title, v);
    final text = SeamFade(
      fontSize: style.fontSize ?? 14,
      child: marquee
          ? MarqueeText(name, style: style)
          : Text(name, style: style, maxLines: 1, overflow: TextOverflow.ellipsis, softWrap: false),
    );
    if (v == null || v.isEmpty) return text;
    return VersionTitleRow(
      name: name,
      nameStyle: style,
      title: text,
      tag: AlbumVersionTag(v),
      gap: HomeVersionTagMetrics.gap,
    );
  }
}

/// A plain faded text line (the seam fade + ellipsis), the common card caption.
class SeamText extends StatelessWidget {
  const SeamText(this.text, {super.key, required this.style, this.maxLines = 1});

  final String text;
  final TextStyle style;
  final int maxLines;

  @override
  Widget build(BuildContext context) => SeamFade(
        fontSize: style.fontSize ?? 14,
        child: Text(text, style: style, maxLines: maxLines, overflow: TextOverflow.ellipsis, softWrap: maxLines > 1),
      );
}
