import 'dart:math' as math;

import 'package:flutter/widgets.dart';

import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'detail_page.dart';
import 'pull_up_reveal.dart';

/// Which layout a detail page takes in its container (Android
/// AlbumDetailContent / PlaylistDetailContent / ArtistBody on main;
/// adaptive-principles §1): height first, then width.
enum DetailLayout {
  /// The phone pull-up: cover over meta over the flowing titles.
  compact,

  /// A Compact-width window too short for that hero (a fold's outer screen,
  /// 16:9): the cover at the start with the meta stacked beside it.
  short,

  /// A landscape handset (height < 480): the hero turned sideways, same pull-up.
  landscape,

  /// 600–840: a plain scrolling list whose first item is the hero row.
  medium,

  /// ≥ 840 (a full window): the identity column beside the whole list.
  wide;

  /// Height < 480 or ≥ Medium width: the bar is the centred one or the edge
  /// capsules, and the page is not the phone hero.
  bool get isSide => this == short || this == landscape;
}

/// The layout for [window] (the page's own container: a column reads its
/// column's width). [hasShort]: the page has a Short portrait (the Album).
/// Tabletop stays on the Compact path.
DetailLayout resolveDetailLayout(YoinWindowInfo window, {bool hasShort = false, double fontScale = 1}) {
  if (window.isCompactHeight) return DetailLayout.landscape;
  return switch (window.layoutMode) {
    LayoutMode.wide => DetailLayout.wide,
    LayoutMode.medium => DetailLayout.medium,
    LayoutMode.compact || LayoutMode.tabletop =>
      hasShort && !albumHeroFitsPortrait(window.width, window.height, fontScale: fontScale)
          ? DetailLayout.short
          : DetailLayout.compact,
  };
}

/// The Album's height breakpoint on Compact width (albumHeroFitsPortrait):
/// whether a [width] × [height] container keeps the portrait hero. Not a
/// size class: the hero grows with the width (0.74 × width, ≤ 300), so what
/// decides is the height left once its band is taken. 390 × 844 keeps 499
/// (portrait); a ~420 × 640 outer screen keeps ~284 (short).
bool albumHeroFitsPortrait(double width, double height, {double fontScale = 1}) {
  final band =
      PullUpMath.heroCoverSide(width, fraction: DetailTokens.albumCoverWidthFraction, max: DetailTokens.albumCoverMax) +
      DetailLayoutTokens.heroBand;
  final needed = DetailLayoutTokens.portraitChromeReserve + DetailLayoutTokens.portraitTextReserve * fontScale;
  return height - band >= needed;
}

/// The Short portrait's cover side for a page [width] wide (albumShortCoverSidePx):
/// over 60 % of the width, leaving the meta its minimum, at most 260.
double albumShortCoverSide(double width) {
  final besideMeta = width - (DetailLayoutTokens.shortHorizontalChrome + DetailLayoutTokens.shortMetaMinWidth);
  return math.max(
    0,
    math.min(math.min(width * DetailLayoutTokens.shortCoverFraction, besideMeta), DetailLayoutTokens.shortCoverMax),
  );
}

/// The landscape Album cover for a body [bodyHeight] tall (under the header).
double albumLandscapeCoverSide(double bodyHeight) => math.max(
  0,
  math.min(DetailLayoutTokens.landscapeCoverSide, bodyHeight - DetailLayoutTokens.landscapeCoverBottomRoom),
);

/// The landscape Playlist cover for a body [bodyHeight] tall.
double playlistLandscapeCoverSide(double bodyHeight) => math.max(
  0,
  math.min(DetailLayoutTokens.landscapeCoverSide, bodyHeight - DetailLayoutTokens.playlistLandscapeCoverBottomRoom),
);

/// The Medium overview's horizontal inset for a page [width] wide: the list
/// is a column at most 720 wide, centred, 16 in.
double detailMediumInset(double width) =>
    (width - math.min(width, DetailLayoutTokens.pageMaxWidth)) / 2 + DetailLayoutTokens.pageInset;

/// Where the cover rests and where it docks as the stamp, for the layouts
/// whose cover flies into the bar (AlbumBarGeometry), in the page's space
/// (origin at the page's top-start, below nothing: the header is inside).
@immutable
final class DetailBarGeometry {
  const DetailBarGeometry({required this.cover, required this.stamp});

  /// The resting cover (Medium: at scroll 0).
  final Rect cover;
  final Rect stamp;

  @override
  bool operator ==(Object other) => other is DetailBarGeometry && other.cover == cover && other.stamp == stamp;

  @override
  int get hashCode => Object.hash(cover, stamp);
}

/// The stamp side for [layout] (AlbumBarStampSize / AlbumBarLandscapeStampSize).
double detailStampSide(DetailLayout layout) =>
    layout == DetailLayout.landscape ? DetailLayoutTokens.landscapeStamp : DetailTokens.albumStamp;

/// [albumBarGeometry] for Short, Landscape and Medium (Compact keeps its own
/// in the scaffold). [topInset] is the status bar; [headerBottom] the
/// header's lower edge (status bar included). Computed start-based,
/// mirrored for [rtl].
DetailBarGeometry detailSideBarGeometry(
  DetailLayout layout, {
  required double width,
  required double height,
  required double topInset,
  required double headerBottom,
  bool rtl = false,
}) {
  final stampSide = detailStampSide(layout);
  final stampStart = width - DetailTokens.albumStampEndInset - stampSide;
  final stampTop =
      topInset +
      DetailHeaderMetrics.paddingTop +
      (DetailHeaderMetrics.rowHeight - stampSide) / 2 -
      PullUpTokens.titleLift;
  final (double start, double top, double side) = switch (layout) {
    DetailLayout.short => (
      DetailLayoutTokens.shortCoverStart,
      headerBottom + DetailLayoutTokens.sideCoverTopGap,
      albumShortCoverSide(width),
    ),
    DetailLayout.landscape => (
      DetailLayoutTokens.albumLandscapeCoverStart,
      headerBottom + DetailLayoutTokens.sideCoverTopGap,
      albumLandscapeCoverSide(height - headerBottom),
    ),
    DetailLayout.medium => (
      detailMediumInset(width),
      headerBottom + DetailLayoutTokens.listTopPadding,
      DetailLayoutTokens.mediumCoverSide,
    ),
    DetailLayout.compact || DetailLayout.wide => throw ArgumentError.value(layout, 'layout', 'no side bar geometry'),
  };
  double mirror(double x, double w) => rtl ? width - x - w : x;
  return DetailBarGeometry(
    cover: Rect.fromLTWH(mirror(start, side), top, side, side),
    stamp: Rect.fromLTWH(mirror(stampStart, stampSide), stampTop, stampSide, stampSide),
  );
}

/// The whole Yoin mark hugging a cover (huggingPose): 1.3 × its side,
/// hanging from its top edge, shrunk so the near arm stays [clearance] off
/// the page's start edge. [cover] is start-based (mirror the result for RTL).
Rect albumHuggingMark(Rect cover, {double clearance = DetailLayoutTokens.huggingEdgeClearance}) {
  final centre = cover.left + cover.width / 2;
  final mark = math.max(
    0.0,
    math.min(
      cover.width * DetailLayoutTokens.huggingMarkScale,
      (centre - clearance) / DetailLayoutTokens.huggingArmReach,
    ),
  );
  return Rect.fromLTWH(
    centre - mark / 2,
    cover.top + cover.width / 2 - mark / 2 + (mark - cover.width) / 2,
    mark,
    mark,
  );
}

/// The Medium cover → bar progress at a list scrolled [offset] with a
/// [coverSide] cover: 0 at rest, 1 once the cover's height has gone by.
double detailMediumBarProgress(double offset, double coverSide) =>
    coverSide <= 0 ? 0 : (offset / coverSide).clamp(0.0, 1.0);
