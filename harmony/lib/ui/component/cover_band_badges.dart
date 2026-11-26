/// The marks on a cover's backdrop band (Android component/CoverBandBadges.kt, owner 2026-11-26, PR #343).
/// With the Expressive shape behind a cover (Home's tiles, Library's grid, search tiles) the cover sits in
/// the bottom-end `HomeBackdropMetrics.artworkFraction` of its square and the shape shows in a band above
/// it. The release's kind ("EP", "Single"), its version tag ("全景声") and its Explicit "E" sit in that
/// band, start-aligned with the cover's start edge and centred in the band: on the shape, never on the art,
/// and the title under the cover stays the name alone. The cover's top-end corner is the memory sticker's,
/// so the row stops short of it. Where they go and which are left out on a short band is :shared's rule
/// ([ReleaseMarks.coverBand]).
library;

import 'package:flutter/material.dart';

import '../../data/release/release_marks.dart';
import '../../theme/tokens.dart';
import 'explicit_badge.dart';
import 'meta_line.dart' show AlbumVersionTag;

/// The band over a [coverFraction] cover, filling the box it is given (lay it over the backdrop square).
/// Nothing when there is no [kind], [version] or [explicit].
class CoverBandBadges extends StatelessWidget {
  const CoverBandBadges({
    super.key,
    required this.coverFraction,
    this.kind,
    this.version,
    this.explicit = false,
    this.stickerRoom = false,
  });

  final double coverFraction;
  final String? kind;
  final String? version;
  final bool explicit;

  /// The cover's top-end corner holds a memory sticker: the row stops short of it.
  final bool stickerRoom;

  @override
  Widget build(BuildContext context) {
    final tag = version?.trim();
    final shownTag = tag == null || tag.isEmpty ? null : tag;
    if (shownTag == null && kind == null && !explicit) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    final chipStyle = theme.textTheme.labelSmall!.copyWith(color: colors.onSurfaceVariant);
    final badgeStyle = theme.textTheme.labelSmall!.copyWith(fontSize: ReleaseMarks.bandBadgeTextSp);

    CoverBandItem chipItem(String text) {
      final p = TextPainter(
        text: TextSpan(text: text, style: chipStyle),
        textDirection: direction,
        textScaler: scaler,
        maxLines: 1,
      )..layout();
      final item = CoverBandItem(
        (p.width + CoverBandTokens.chipPaddingH * 2).ceil(),
        (p.height + CoverBandTokens.chipPaddingV * 2).ceil(),
      );
      p.dispose();
      return item;
    }

    final side = ReleaseMarks.badgeSide(explicitTitleFontSize(context, badgeStyle)).ceil();
    return ExcludeSemantics(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final w = constraints.maxWidth.round();
          final h = constraints.maxHeight.round();
          final rule = ReleaseMarks.coverBand(
            width: w,
            height: h,
            coverFraction: coverFraction,
            stickerRoom: stickerRoom ? ReleaseMarks.bandStickerRoomDp.round() : 0,
            gap: ReleaseMarks.bandGapDp.round(),
            minTagWidth: ReleaseMarks.bandMinTagWidthDp.round(),
            kind: kind == null ? null : chipItem(kind!),
            version: shownTag == null ? null : chipItem(shownTag),
            explicit: explicit ? CoverBandItem(side, side) : null,
          );
          final rtl = direction == TextDirection.rtl;
          return Stack(
            clipBehavior: Clip.none,
            children: [
              for (final p in rule.placed)
                Positioned(
                  // Mirrored by hand: the cover sits bottom-end, so in RTL the band starts at the right.
                  left: (rtl ? w - p.x - p.width : p.x).toDouble(),
                  top: ((rule.band - p.height) ~/ 2).toDouble(),
                  width: p.width.toDouble(),
                  height: p.height.toDouble(),
                  child: switch (p.role) {
                    CoverBandRole.kind => BandChip(kind!, style: chipStyle),
                    CoverBandRole.version => BandChip(shownTag!, style: chipStyle),
                    // In the chips' surface colour, its E cut out to the shape's (the cover's) colour.
                    CoverBandRole.explicit => ExplicitBadge(
                      textStyle: badgeStyle,
                      color: colors.surface,
                      describe: false,
                    ),
                  },
                ),
            ],
          );
        },
      ),
    );
  }
}

/// A tag on the band: not the title's tonal tag (on the shape's tint it would vanish), the page's surface.
class BandChip extends StatelessWidget {
  const BandChip(this.text, {super.key, this.style});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surface,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(CoverBandTokens.chipRadius)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: CoverBandTokens.chipPaddingH,
          vertical: CoverBandTokens.chipPaddingV,
        ),
        child: Text(
          text,
          maxLines: 1,
          softWrap: false,
          overflow: TextOverflow.ellipsis,
          style: style ?? Theme.of(context).textTheme.labelSmall!.copyWith(color: colors.onSurfaceVariant),
        ),
      ),
    );
  }
}

/// A list row's kind tag ("EP", "Single"), version tag and "E" at the row's end, vertically centred, so
/// the title stays the name alone and the row keeps its height (Android RowEndBadges). The version tag
/// is capped so it never squeezes the title. Nothing when there is nothing to mark.
class RowEndBadges extends StatelessWidget {
  const RowEndBadges({super.key, this.kind, this.version, this.explicit = false});

  final String? kind;
  final String? version;
  final bool explicit;

  @override
  Widget build(BuildContext context) {
    final tag = version?.trim();
    final shownTag = tag == null || tag.isEmpty ? null : tag;
    if (shownTag == null && kind == null && !explicit) return const SizedBox.shrink();
    final labelSmall = Theme.of(context).textTheme.labelSmall!;
    return Row(
      mainAxisSize: MainAxisSize.min,
      spacing: ReleaseMarks.bandGapDp,
      children: [
        if (kind != null) AlbumVersionTag(kind!),
        if (shownTag != null)
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: CoverBandTokens.rowVersionMax),
            child: AlbumVersionTag(shownTag),
          ),
        if (explicit) ExplicitBadge(textStyle: labelSmall.copyWith(fontSize: ReleaseMarks.bandBadgeTextSp)),
      ],
    );
  }
}
