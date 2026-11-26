/// Every section on the two-column grid (parity p4 C2; Android's two-column section layouts in
/// HomeEditorialContent.kt / HomeGridUnits.kt / HomeShelfPager.kt; iOS HomeView.gridSection): each is a 40dp
/// title row and then its cards laid out for its row's units, the whole exactly [homeGridHeightDp] tall (the
/// box is Home's, [HomeGridBox]). Activities: the phone's bento for the column at the units' preset (1 S, 2 M,
/// 3 XL). Jump Back In: [units] rows of U − 44 covers, as many columns as fit, a signal card two columns wide.
/// Recently Added: three track rows a unit filling the box exactly, ⌊W / 280⌋ columns, then the albums stacked
/// one a unit; one shelf, its tracks the first page in a half. The cover shelves (Recently Played, Your
/// Playlists, Made For You, On Your Other Accounts): a unit's row of cards, two rows at 2 units, every card one
/// unit tall. Rediscover: unit-tall cards, two a page in a half. Lyric Cards: whole cards the box's height, one a
/// page in a half and two in a full row. All Memories: its card filling the box.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../data/home/home_grid_rules.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart' show MadeForYouPlaylist;
import '../memories/ledger/memory_ledger_home_card.dart';
import '../memories/ledger/memory_ledger_view_model.dart';
import '../theme/tokens.dart';
import 'activity_bento.dart';
import 'home_edit_wiggle.dart';
import 'home_feed_rows.dart';
import 'home_metrics.dart';
import 'home_other_profiles.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_shelves.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'jump_back_in.dart';
import 'lyric_highlight_section.dart';
import 'lyric_highlight_view_model.dart';
import 'rediscover_section.dart';
import 'rediscover_selection.dart' show HomeRediscoverItem;

/// Activities' row preset in two columns (Android activityGridPreset): the phone's bento for the column, its
/// rows from the units — 1 S, 2 M, 3 XL.
HomeRowPreset homeActivityGridPreset(int units) => units <= 1
    ? HomeRowPreset.s
    : units == 2
    ? HomeRowPreset.m
    : HomeRowPreset.xl;

/// A cover card's side on the grid: U − 44 (106).
double get homeGridCover => homeGridCoverDp();

/// [items] in stacks of [rows] (a column of the shelf each).
List<List<T>> homeGridStacks<T>(List<T> items, int rows) => [
  for (var i = 0; i < items.length; i += rows) items.sublist(i, math.min(i + rows, items.length)),
];

/// A stack of unit-tall cards, [HomeGridTokens.gap] apart.
class _UnitStack extends StatelessWidget {
  const _UnitStack({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.start,
    spacing: HomeGridTokens.gap,
    children: [
      for (final child in children)
        SizedBox(height: HomeGridTokens.unit, child: HomeUnitClip(child: child)),
    ],
  );
}

/// Activities on the grid.
class GridActivitiesSection extends StatelessWidget {
  const GridActivitiesSection({super.key, required this.content, required this.frame, required this.cell});

  final HomeContent content;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      HomeGridTitleRow(title: HomeStrings.of(context).sectionActivities, frame: frame),
      Padding(
        padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
        child: ActivitiesSection(
          content: content,
          frame: frame,
          preset: homeActivityGridPreset(cell.units.clamp(1, 3)),
          showTitle: false,
        ),
      ),
    ],
  );
}

/// Jump Back In on the grid (Android JbiUnitSection): the title row, then [HomeColumnCell.units] rows of
/// covers, as many columns as fit, a signal card two columns wide lying beside its copy. Cards past the last row
/// wait for a larger count.
class GridJumpBackInSection extends StatelessWidget {
  const GridJumpBackInSection({super.key, required this.cards, required this.frame, required this.cell});

  final List<HomeWidgetCard> cards;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;

  @override
  Widget build(BuildContext context) {
    final columns = homeGridColumns(frame.content);
    final slots = homeGridPack([for (final c in cards) c.expanded ? 2 : 1], columns, cell.units.clamp(1, 4));
    final cover = homeGridCover;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HomeGridTitleRow(title: HomeStrings.of(context).sectionJumpBackIn, frame: frame),
        Padding(
          padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
          child: HomeGridCells(
            slots: slots,
            columns: columns,
            children: [
              for (final slot in slots)
                cards[slot.index].expanded
                    ? JbiSignalCard(key: ValueKey(cards[slot.index].stableId), card: cards[slot.index], coverSide: cover)
                    : JbiCoverCell(key: ValueKey(cards[slot.index].stableId), card: cards[slot.index], coverSide: cover),
            ],
          ),
        ),
      ],
    );
  }
}

/// Recently Added on the grid (Android RecentlyAddedSection on the grid): three track rows a unit filling the box
/// exactly, ⌊W / 280⌋ columns, then the albums stacked one a unit; one shelf, paged in a half (the tracks its
/// first page), free in a full row.
class GridRecentlyAddedSection extends StatelessWidget {
  const GridRecentlyAddedSection({
    super.key,
    required this.tracks,
    required this.albums,
    required this.frame,
    required this.cell,
    required this.scrollable,
  });

  final List<Track> tracks;
  final List<Album> albums;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;
  final bool scrollable;

  @override
  Widget build(BuildContext context) {
    final u = cell.units.clamp(1, 2);
    final columns = homeGridTrackColumns(frame.content);
    final rows = homeGridTrackRows(u);
    final rowHeight = homeGridTrackRowDp(u);
    final shown = tracks.take(columns * rows).toList();
    final stacks = homeGridStacks(albums, u);
    return HomeGridShelf(
      title: HomeStrings.of(context).sectionRecentlyAdded,
      frame: frame,
      half: cell.half,
      cardWidth: homeGridCover,
      height: homeGridContentDp(u),
      cardCount: stacks.length,
      scrollable: scrollable,
      lead: shown.isEmpty
          ? null
          : (context, width) {
              final slots = homeGridPack(List.filled(shown.length, 1), columns, rows);
              return HomeGridCells(
                slots: slots,
                columns: columns,
                gap: HomeTokens.recentlyAddedTileGap,
                rowHeight: rowHeight,
                rowGap: HomeGridTokens.trackRowGap,
                children: [
                  for (final slot in slots)
                    TrackTile(key: ValueKey(shown[slot.index].id), track: shown[slot.index], coverSize: rowHeight),
                ],
              );
            },
      cardBuilder: (context, i) => _UnitStack(
        children: [for (final album in stacks[i]) AlbumShelfCard(key: ValueKey(album.id), album: album, size: homeGridCover)],
      ),
    );
  }
}

/// A cover shelf on the grid (Android homeShelfCards): a unit's row of cards, two rows at 2 units, every card one
/// unit tall; paged by whole cards in a half, free in a full row.
class GridCoverShelf<T> extends StatelessWidget {
  const GridCoverShelf({
    super.key,
    required this.title,
    required this.items,
    required this.frame,
    required this.cell,
    required this.scrollable,
    required this.card,
  });

  final String title;
  final List<T> items;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;
  final bool scrollable;
  final Widget Function(T item, double size) card;

  @override
  Widget build(BuildContext context) {
    final rows = cell.units.clamp(1, 2);
    final stacks = homeGridStacks(items, rows);
    return HomeGridShelf(
      title: title,
      frame: frame,
      half: cell.half,
      cardWidth: homeGridCover,
      height: homeGridContentDp(rows),
      cardCount: stacks.length,
      scrollable: scrollable,
      cardBuilder: (context, i) => _UnitStack(children: [for (final item in stacks[i]) card(item, homeGridCover)]),
    );
  }
}

/// Recently Played on the grid.
Widget gridRecentlyPlayed(BuildContext context, List<Album> albums, HomeFeedFrame frame, HomeColumnCell cell, bool scrollable) =>
    GridCoverShelf<Album>(
      title: HomeStrings.of(context).sectionRecentlyPlayed,
      items: albums,
      frame: frame,
      cell: cell,
      scrollable: scrollable,
      card: (album, size) => AlbumShelfCard(key: ValueKey(album.id), album: album, size: size),
    );

/// Your Playlists on the grid.
Widget gridYourPlaylists(BuildContext context, List<Playlist> playlists, HomeFeedFrame frame, HomeColumnCell cell, bool scrollable) =>
    GridCoverShelf<Playlist>(
      title: HomeStrings.of(context).sectionYourPlaylists,
      items: playlists,
      frame: frame,
      cell: cell,
      scrollable: scrollable,
      card: (playlist, size) => PlaylistShelfCard(key: ValueKey(playlist.id), playlist: playlist, size: size),
    );

/// Made For You on the grid.
Widget gridMadeForYou(
  BuildContext context,
  List<MadeForYouPlaylist> playlists,
  HomeFeedFrame frame,
  HomeColumnCell cell,
  bool scrollable,
) => GridCoverShelf<MadeForYouPlaylist>(
  title: HomeStrings.of(context).sectionMadeForYou,
  items: playlists,
  frame: frame,
  cell: cell,
  scrollable: scrollable,
  card: (playlist, size) => MadeForYouShelfCard(key: ValueKey(playlist.id), playlist: playlist, size: size),
);

/// On Your Other Accounts on the grid.
Widget gridOtherProfiles(
  BuildContext context,
  List<OtherProfileItem> items,
  HomeFeedFrame frame,
  HomeColumnCell cell,
  bool scrollable,
) => GridCoverShelf<OtherProfileItem>(
  title: HomeStrings.of(context).sectionOtherProfiles,
  items: items,
  frame: frame,
  cell: cell,
  scrollable: scrollable,
  card: (item, size) => OtherProfileCard(key: ValueKey(item.stableId), item: item, size: size),
);

/// Rediscover on the grid (Android RediscoverSection on the grid): every card one unit tall, a unit's row of them,
/// two rows at 2 units; two cards a page in a half, the full row's free shelf otherwise.
class GridRediscoverSection extends StatelessWidget {
  const GridRediscoverSection({
    super.key,
    required this.items,
    required this.nowMs,
    required this.frame,
    required this.cell,
    required this.scrollable,
  });

  final List<HomeRediscoverItem> items;
  final int nowMs;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;
  final bool scrollable;

  @override
  Widget build(BuildContext context) {
    final rows = cell.units.clamp(1, 2);
    final width = cell.half
        ? homeShelfCardWidthDp(frame.content, 2)
        : math.min(frame.content * HomeRediscoverMetrics.shelfCardFraction, HomeGridUnitsTokens.rediscoverMaxWidth);
    final stacks = homeGridStacks(items, rows);
    return HomeGridShelf(
      title: HomeStrings.of(context).sectionRediscover,
      frame: frame,
      half: cell.half,
      cardWidth: width,
      height: homeGridContentDp(rows),
      cardCount: stacks.length,
      fullGap: HomeRediscoverMetrics.gap,
      minGap: HomeShelfPagerRules.minGap,
      scrollable: scrollable,
      cardBuilder: (context, i) => _UnitStack(
        children: [for (final item in stacks[i]) RediscoverCard(key: ValueKey(item.albumId), item: item, nowMs: nowMs)],
      ),
    );
  }
}

/// Numbers of the grid's own section layouts (dp).
abstract final class HomeGridUnitsTokens {
  /// A full row's Rediscover card is at most this wide.
  static const double rediscoverMaxWidth = 420;
}

/// Lyric Cards on the grid (Android LyricUnitSection): the title row, then whole cards a page — one in a half and
/// two in a full row — each exactly the box's height under the title row; the intro sized to the box the same way.
class GridLyricCardsSection extends StatelessWidget {
  const GridLyricCardsSection({
    super.key,
    required this.cards,
    required this.count,
    required this.frame,
    required this.cell,
    required this.onPlay,
    required this.scrollable,
    this.intro,
  });

  final List<HomeLyricHighlight> cards;
  final int count;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;
  final ValueChanged<HomeLyricHighlight> onPlay;
  final bool scrollable;

  /// The first-use intro, in place of the cards.
  final Widget? intro;

  @override
  Widget build(BuildContext context) {
    final height = homeGridContentDp(cell.units.clamp(1, 2));
    final title = HomeStrings.of(context).sectionLyricHighlight;
    if (intro != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          HomeGridTitleRow(title: title, frame: frame),
          Padding(
            padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
            child: SizedBox(height: height, child: HomeUnitClip(child: intro!)),
          ),
        ],
      );
    }
    final perPage = cell.half ? 1 : 2;
    final shown = cards.take(count).toList();
    return HomeGridShelf(
      title: title,
      frame: frame,
      // A full row pages too, by whole cards (Android pagedFull).
      half: true,
      cardWidth: homeShelfCardWidthDp(frame.content, perPage),
      height: height,
      cardCount: shown.length,
      minGap: HomeShelfPagerRules.minGap,
      scrollable: scrollable,
      cardBuilder: (context, i) => SizedBox(
        height: height,
        child: LyricHighlightCard(key: ValueKey(shown[i].songId), card: shown[i], onPlay: () => onPlay(shown[i])),
      ),
    );
  }
}

/// All Memories on the grid: the title row, then its card filling the box under it.
class GridMemorySection extends StatelessWidget {
  const GridMemorySection({super.key, required this.state, required this.frame, required this.cell});

  final MemoryLedgerState state;
  final HomeFeedFrame frame;
  final HomeColumnCell cell;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      HomeGridTitleRow(title: HomeStrings.of(context).sectionMemoryGrid, frame: frame),
      Padding(
        padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
        child: SizedBox(
          height: cell.content,
          child: HomeUnitClip(child: HomeEditBlockWiggle(child: MemoryLedgerHomeCard(state: state))),
        ),
      ),
    ],
  );
}

/// An empty section's stand-in on the grid while editing: the title row and its placeholder line.
class GridPlaceholderSection extends StatelessWidget {
  const GridPlaceholderSection({super.key, required this.frame, required this.child});

  final HomeFeedFrame frame;
  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
    child: child,
  );
}
