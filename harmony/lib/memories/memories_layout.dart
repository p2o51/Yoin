/// The Memories page budget for its container (Android showcase
/// MemoriesLayout + MemoriesTypeScale + MemoryCardMetrics): the phone's two
/// states, and Medium (≥ 600 wide) as the phone's two states enlarged in a
/// column (design.md 默认选项 ③). The spread (≥ 900, two pages side by side)
/// is not ported yet: wide windows get Medium.
library;

import 'package:flutter/foundation.dart';

import '../theme/tokens.dart';

enum MemoriesTier { phone, medium }

@immutable
final class MemoriesLayout {
  const MemoriesLayout({
    required this.tier,
    required this.short,
    required this.cover,
    required this.seal,
    required this.air1,
    required this.pillStart,
    required this.dotsEnd,
    required this.type,
  });

  final MemoriesTier tier;

  /// The container is shorter than 760 dp: the short exhibit.
  final bool short;
  final double cover;
  final double seal;

  /// Air above the cover.
  final double air1;
  final double pillStart;
  final double dotsEnd;
  final MemoriesType type;

  bool get wide => tier != MemoriesTier.phone;

  /// The card column's width inside [width].
  double cardColumn(double width) => wide && width > MemoriesTokens.cardColumn ? MemoriesTokens.cardColumn : width;

  /// The diary column's width inside [width].
  double diaryColumn(double width) =>
      wide ? (width - 32 < MemoriesTokens.diaryColumn ? width - 32 : MemoriesTokens.diaryColumn) : width;

  double get titleTop => short ? 22 : 30;
  double get titleMaxWidth => wide ? 424 : 340;
  double get excerptMaxWidth => wide ? 400 : 324;
  double get teaserBottom => short ? MemoriesTokens.teaserBottomShort : MemoriesTokens.teaserBottom;

  /// Where the bar's 40 dp cover sits: after the pill (pillStart + 48).
  double get barCoverStart => pillStart + 48;

  /// Slot A ("Memories / Last heard") and slot B (album, artist ⌄) starts.
  double get slotAStart => pillStart + 100;
  double get slotBStart => pillStart + 102;
}

/// Type sizes per tier (MemoriesTypeScale).
@immutable
final class MemoriesType {
  const MemoriesType({
    required this.cardTitle,
    required this.cardArtist,
    required this.paragraph,
    required this.review,
    required this.reviewLine,
    required this.reviewShort,
    required this.reviewShortLine,
    required this.note,
    required this.stamp,
    required this.trackNumber,
    required this.linerColumn,
    required this.noteLine,
    required this.separatorTop,
    required this.separatorBottom,
    required this.stat,
  });

  final double cardTitle;
  final double cardArtist;
  final double paragraph;
  final double review;
  final double reviewLine;
  final double reviewShort;
  final double reviewShortLine;
  final double note;
  final double stamp;
  final double trackNumber;
  final double linerColumn;
  final double noteLine;
  final double separatorTop;
  final double separatorBottom;
  final double stat;

  static const phone = MemoriesType(
    cardTitle: 24,
    cardArtist: 13,
    paragraph: 16,
    review: 16,
    reviewLine: 1.85,
    reviewShort: 26,
    reviewShortLine: 1.5,
    note: 15.5,
    stamp: 12.5,
    trackNumber: 13,
    linerColumn: 40,
    noteLine: 25.6,
    separatorTop: 30,
    separatorBottom: 24,
    stat: 34,
  );

  static const large = MemoriesType(
    cardTitle: 27,
    cardArtist: 14,
    paragraph: 17,
    review: 17.5,
    reviewLine: 1.9,
    reviewShort: 30,
    reviewShortLine: 1.45,
    note: 16.5,
    stamp: 13,
    trackNumber: 13,
    linerColumn: 44,
    noteLine: 27,
    separatorTop: 34,
    separatorBottom: 26,
    stat: 38,
  );
}

double _clamp(double x, double lo, double hi) => x < lo ? lo : (x > hi ? hi : x);
double _jsRound(double x) => (x + 0.5).floorToDouble();

/// Medium's cover: grows with the height, 256 (168 short) up to 360.
double mediumCover(double height) => _clamp(
  height - MemoriesTokens.mediumCoverOffset,
  height < MemoriesTokens.shortBelow ? MemoriesTokens.phoneCoverShort : MemoriesTokens.phoneCover,
  MemoriesTokens.mediumCoverMax,
);

/// The budget for a container of [width] × [height] (the page's own size,
/// never the device's).
MemoriesLayout memoriesLayoutFor(double width, double height) {
  final short = height < MemoriesTokens.shortBelow;
  if (width < MemoriesTokens.mediumFrom) {
    return MemoriesLayout(
      tier: MemoriesTier.phone,
      short: short,
      cover: short ? MemoriesTokens.phoneCoverShort : MemoriesTokens.phoneCover,
      seal: short ? MemoriesTokens.phoneSealShort : MemoriesTokens.phoneSeal,
      air1: short ? MemoriesTokens.phoneAir1Short : MemoriesTokens.phoneAir1,
      pillStart: MemoriesTokens.pillStartPhone,
      dotsEnd: MemoriesTokens.dotsEndPhone,
      type: MemoriesType.phone,
    );
  }
  final cover = mediumCover(height);
  return MemoriesLayout(
    tier: MemoriesTier.medium,
    short: short,
    cover: cover,
    seal: _jsRound(
      _clamp(
        cover * MemoriesTokens.mediumSealRatio,
        short ? MemoriesTokens.phoneSealShort : MemoriesTokens.sealMin,
        MemoriesTokens.sealMax,
      ),
    ),
    air1: short
        ? MemoriesTokens.mediumAir1Min
        : _clamp(
            _jsRound((height - MemoriesTokens.shortBelow) * MemoriesTokens.mediumAir1Ratio),
            MemoriesTokens.mediumAir1Min,
            MemoriesTokens.mediumAir1Max,
          ),
    pillStart: MemoriesTokens.pillStartMedium,
    dotsEnd: MemoriesTokens.dotsEndMedium,
    type: short ? MemoriesType.phone : MemoriesType.large,
  );
}

/// The excerpt to show: the first candidate whose block fits [slot], else
/// the attribution line alone (always last). Never a cut sentence.
int? pickExcerpt(
  int count,
  bool Function(int index) attributionOnly,
  double slot,
  double Function(int index) heightOf,
) {
  for (var i = 0; i < count; i++) {
    if (attributionOnly(i) || heightOf(i) <= slot) return i;
  }
  return count == 0 ? null : count - 1;
}

/// A page's bar visibility at its distance [rel] from the settled page.
double barVisibility(double rel) => rel.abs() > 1.6 ? 0 : _clamp(1 - rel.abs() * 2.2, 0, 1);

/// The bar slots' extra x on top of the page's own: they travel at 40%.
double barParallax(double rel, double width) => rel * width * 0.6;

/// The page dot a tap at [x] lands on: the nearest centre.
int nearestDot(double x, int count, double pitch, double firstCentre) {
  if (count <= 0) return 0;
  final i = ((x - firstCentre) / pitch).round();
  return i.clamp(0, count - 1);
}
