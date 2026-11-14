import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

/// One group of a [MetaLine] (component/MetaLine.kt).
sealed class MetaGroup {
  const MetaGroup();
}

/// Plain text in the line's ink.
final class MetaPlain extends MetaGroup {
  const MetaPlain(this.text);
  final String text;
}

/// Text at [MetaLineTokens.mutedAlpha].
final class MetaMuted extends MetaGroup {
  const MetaMuted(this.text);
  final String text;
}

/// A kind word ("Album", "Playlist") in [color] (the page accent / primary).
final class MetaKind extends MetaGroup {
  const MetaKind(this.text, {this.color});
  final String text;
  final Color? color;
}

/// A number and its unit: the value Medium-weight tabular, the unit muted, 4dp apart.
final class MetaStat extends MetaGroup {
  const MetaStat(this.value, this.unit);
  final String value;
  final String unit;
}

/// Groups separated by a 12dp gap — never a dot — dropping trailing groups
/// whole when they don't fit; the first group ellipsizes instead.
class MetaLine extends StatelessWidget {
  const MetaLine({super.key, required this.groups, this.style, this.color, this.centered = false});

  final List<MetaGroup> groups;
  final TextStyle? style;
  final Color? color;

  /// Sit centred in the width (a centred card's artist line) instead of at the start.
  final bool centered;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final base = (style ?? theme.textTheme.bodySmall!).copyWith(color: color ?? theme.colorScheme.onSurfaceVariant);
    final ink = base.color!;
    if (groups.isEmpty) return const SizedBox.shrink();
    // Each group as plain spans (a stat is two: value, then its unit 4dp later).
    final parts = [for (final g in groups) _spans(g, base, ink, theme.colorScheme.primary)];
    return LayoutBuilder(
      builder: (context, constraints) {
        final scaler = MediaQuery.textScalerOf(context);
        final direction = Directionality.of(context);
        double measure(TextSpan s) =>
            (TextPainter(text: s, textDirection: direction, textScaler: scaler, maxLines: 1)..layout()).width;
        final widths = [
          for (final p in parts)
            p.fold<double>(0, (sum, s) => sum + measure(s)) + (p.length - 1) * MetaLineTokens.innerGap,
        ];
        var count = 1;
        var used = widths.first;
        while (count < parts.length && used + MetaLineTokens.groupGap + widths[count] <= constraints.maxWidth) {
          used += MetaLineTokens.groupGap + widths[count];
          count++;
        }
        Widget group(List<TextSpan> spans, {bool flexible = false}) {
          if (spans.length == 1) {
            return Text.rich(spans.single, maxLines: 1, overflow: TextOverflow.ellipsis, style: base);
          }
          final children = <Widget>[
            for (final (i, s) in spans.indexed) ...[
              if (i > 0) const SizedBox(width: MetaLineTokens.innerGap),
              if (flexible && i == 0)
                Flexible(child: Text.rich(s, maxLines: 1, overflow: TextOverflow.ellipsis, style: base))
              else
                Text.rich(s, maxLines: 1, style: base),
            ],
          ];
          return Row(mainAxisSize: MainAxisSize.min, children: children);
        }

        return Row(
          mainAxisAlignment: centered ? MainAxisAlignment.center : MainAxisAlignment.start,
          children: [
            Flexible(child: group(parts.first, flexible: true)),
            for (var i = 1; i < count; i++) ...[const SizedBox(width: MetaLineTokens.groupGap), group(parts[i])],
          ],
        );
      },
    );
  }

  static List<TextSpan> _spans(MetaGroup g, TextStyle base, Color ink, Color primary) {
    final muted = base.copyWith(color: ink.withValues(alpha: ink.a * MetaLineTokens.mutedAlpha));
    return switch (g) {
      MetaPlain(:final text) => [TextSpan(text: text, style: base)],
      MetaMuted(:final text) => [TextSpan(text: text, style: muted)],
      MetaKind(:final text, :final color) => [TextSpan(text: text, style: base.copyWith(color: color ?? primary))],
      MetaStat(:final value, :final unit) => [
        TextSpan(
          text: value,
          style: base.copyWith(
            fontWeight: FontWeight.w500,
            fontVariations: _withWeight(base.fontVariations, 500),
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
        TextSpan(text: unit, style: muted),
      ],
    };
  }

  static List<FontVariation> _withWeight(List<FontVariation>? vars, double weight) => [
    for (final v in vars ?? const <FontVariation>[])
      if (v.axis != 'wght') v,
    FontVariation.weight(weight),
  ];
}

/// "h"/"min" stat groups for a total duration (Android totalDurationGroups):
/// hours > 0 → hours, then minutes only if > 0; no hours → minutes even if 0.
List<MetaGroup> durationStatGroups(int totalSec, {required String hourUnit, required String minuteUnit}) {
  final hours = totalSec ~/ 3600;
  final minutes = (totalSec % 3600) ~/ 60;
  return [if (hours > 0) MetaStat('$hours', hourUnit), if (minutes > 0 || hours == 0) MetaStat('$minutes', minuteUnit)];
}

/// The album version as a tag after a title (component/AlbumVersionTitle.kt):
/// rounded 6, secondaryContainer / onSecondaryContainer, labelSmall, h6 v1.
class AlbumVersionTag extends StatelessWidget {
  const AlbumVersionTag(this.version, {super.key, this.style});

  final String version;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.secondaryContainer,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(6)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
        child: Text(
          version,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: (style ?? Theme.of(context).textTheme.labelSmall)?.copyWith(color: colors.onSecondaryContainer),
        ),
      ),
    );
  }
}

/// The album name with the server's " (version)" suffix stripped.
String albumTitleWithoutVersion(String name, String? version) {
  if (version == null || version.isEmpty) return name;
  final suffix = ' ($version)';
  return name.endsWith(suffix) ? name.substring(0, name.length - suffix.length) : name;
}

/// m:ss (h:mm:ss past an hour).
String formatTrackDuration(int? seconds) {
  if (seconds == null || seconds < 0) return '';
  final h = seconds ~/ 3600;
  final m = (seconds % 3600) ~/ 60;
  final s = (seconds % 60).toString().padLeft(2, '0');
  return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:$s' : '$m:$s';
}
