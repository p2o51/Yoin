/// All Memories (Android ledger/MemoryLedgerScreen.kt, owner 2026-11-08,
/// views 2026-11-09): every memory on one page, so the listener can see
/// what Yoin keeps for them. From the top: what it adds up to, the grid (tap
/// a cell to see just that stretch, tap it again to see everything), then
/// one button for three views: the timeline, cards, and a sortable list. The
/// pull-down Memories deck stays the way to come across them at random.
///
/// Back surface: PushPage ([MemoryLedgerScreen.open], YoinPushRoute) with
/// Settings' bar and its explicit back button. An album opens its album
/// page, as on Android. Every account's memories are here; another account's
/// say whose they are ("From …" with its mark) and are read-only, and its
/// album opens the way the deck's Go to album does: the one-off switch
/// notice, the switch, then the album.
///
/// As the page scrolls up past its grid, the grid's squares rise into the top
/// bar and piece together as its mosaic; the bar's title and back button turn
/// white and the status bar icons light (Android MemoryGridLift,
/// memory_grid_lift.dart). Scrolling back down they come home.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../app/providers.dart';
import '../../data/memory/memory_grid.dart';
import '../../data/memory/memory_ledger.dart';
import '../../data/memory/memory_ledger_list.dart';
import '../../detail/detail_page.dart';
import '../../detail/detail_routes.dart';
import '../../nowplaying/haptics.dart';
import '../../settings/settings_colors.dart';
import '../../settings/settings_components.dart';
import '../../settings/settings_metrics.dart';
import '../../shell/bar_suppression.dart';
import '../../theme/motion.dart';
import '../../theme/system_bars.dart';
import '../../theme/theme.dart';
import '../../theme/tokens.dart';
import '../../ui/adaptive/yoin_window_info.dart';
import '../../ui/component/cover_art.dart';
import '../../ui/component/desktop_input.dart';
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_dropdown_menu.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../../ui/navigation/yoin_push_route.dart';
import '../../settings/account_identity.dart';
import '../copy/memory_copy.dart';
import '../memory_title_editor.dart';
import '../other_profiles.dart';
import 'memory_grid_lift.dart';
import 'memory_grid_view.dart';
import 'memory_ledger_strings.dart';
import 'memory_ledger_view_model.dart';

/// How the page lays out at a width (Android LedgerLayout): how many
/// columns the timeline and the cards run in, how wide it may grow, whether
/// the top runs large. A short window (a landscape phone) is the phone
/// layout whatever its width: height is judged first.
@immutable
final class MemoryLedgerLayout {
  const MemoryLedgerLayout({
    required this.wide,
    required this.columns,
    required this.lanes,
    required this.maxWidth,
    required this.gutter,
  });

  /// Larger stats over a wider, centred grid, and larger month headers.
  final bool wide;

  /// The timeline's columns: 1 on a phone (everything full width), more as a masonry.
  final int columns;

  /// The cards view's columns.
  final int lanes;
  final double maxWidth;
  final double gutter;

  static MemoryLedgerLayout of(YoinWindowInfo window) {
    if (window.isCompactHeight) {
      return const MemoryLedgerLayout(wide: false, columns: 1, lanes: 2, maxWidth: 720, gutter: 12);
    }
    final w = window.width;
    if (w >= 1100) return const MemoryLedgerLayout(wide: true, columns: 3, lanes: 3, maxWidth: 1240, gutter: 28);
    if (w >= YoinWindowInfo.wideWidth) {
      return const MemoryLedgerLayout(wide: true, columns: 2, lanes: 2, maxWidth: 1040, gutter: 28);
    }
    if (w >= YoinWindowInfo.mediumWidth) {
      return const MemoryLedgerLayout(wide: false, columns: 2, lanes: 2, maxWidth: 840, gutter: 20);
    }
    return const MemoryLedgerLayout(wide: false, columns: 1, lanes: 2, maxWidth: 720, gutter: 12);
  }
}

/// The view picked last time, kept on this device (Android SharedPreferences "yoin_memory_ledger").
const String memoryLedgerViewKey = 'memory_ledger.view';

class MemoryLedgerScreen extends ConsumerStatefulWidget {
  const MemoryLedgerScreen({super.key});

  /// Opens All Memories as a pushed page (Home's Memories grid calls this).
  static Future<void> open(BuildContext context) =>
      Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const MemoryLedgerScreen()));

  @override
  ConsumerState<MemoryLedgerScreen> createState() => _MemoryLedgerScreenState();
}

class _MemoryLedgerScreenState extends ConsumerState<MemoryLedgerScreen> with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();
  late final LedgerColorBook _colors = LedgerColorBook(vsync: this);

  // The grid's lift into the top bar.
  final MemoryGridLift _lift = MemoryGridLift();
  late final MemoryGridLiftProgress _liftProgress = MemoryGridLiftProgress(_lift, _scroll);
  final GlobalKey _liftLayer = GlobalKey();

  /// The real grid steps aside the moment its copies take off, so no square shows twice.
  final ValueNotifier<bool> _lifted = ValueNotifier(false);

  /// 0 → 1 as the bar forms: its title and back button turn white.
  late final SpringValue _barWhite = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  bool _formed = false;
  double _barBottom = kToolbarHeight;
  MemoryGrid? _pixelsOf;
  List<LiftPixel> _pixels = const [];
  MemoryLedgerView _view = MemoryLedgerView.timeline;
  MemoryLedgerSort _sort = MemoryLedgerSort.recent;

  /// The cell picked on the grid (its index); a reload may take it away.
  int? _selected;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _restoreView();
  }

  RenderBox? _layerBox() {
    final box = _liftLayer.currentContext?.findRenderObject();
    return box is RenderBox && box.attached && box.hasSize ? box : null;
  }

  /// Follows the lift: hides the real grid once its copies take off, and
  /// turns the bar white once it has formed.
  void _onScroll() {
    final box = _layerBox();
    // The list's layout lags its offset here: the grid's last measured place, moved by the offset.
    final p = box == null || _pixels.isEmpty ? 0.0 : _liftProgress.of(box, _barBottom, measure: false);
    _lifted.value = p > 0.001;
    final formed = p > MemoryLedgerTokens.liftFormed;
    if (formed == _formed) return;
    setState(() => _formed = formed);
    if (MediaQuery.disableAnimationsOf(context)) {
      _barWhite.snapTo(formed ? 1 : 0);
    } else {
      _barWhite.animateTo(formed ? 1 : 0);
    }
  }

  Future<void> _restoreView() async {
    try {
      final stored = await ref.read(keyValueStoreProvider).read(memoryLedgerViewKey);
      // A view a later build dropped reads as the timeline.
      final view = MemoryLedgerView.values.where((v) => v.name == stored).firstOrNull;
      if (view != null && mounted) setState(() => _view = view);
    } catch (_) {}
  }

  void _setView(MemoryLedgerView view) {
    setState(() => _view = view);
    ref.read(keyValueStoreProvider).write(memoryLedgerViewKey, view.name).ignore();
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    _colors.dispose();
    _lifted.dispose();
    _barWhite.dispose();
    super.dispose();
  }

  /// The account in use's album opens its page. Another account's is that
  /// account's (Android's ledger leaves it shut; here it opens as the deck's
  /// Go to album does): Yoin switches there first, asking the first time
  /// with the one-off notice, and opens the album once the account is in use.
  void _openAlbum(MemoryLedgerAlbum? album) {
    final state = ref.read(memoryLedgerProvider).value;
    if (album == null || state == null || !state.opens(album)) return;
    YoinHaptics.click();
    if (state.isOwn(album)) {
      DetailRoutes.openAlbum(context, album.albumId);
      return;
    }
    final owner = state.profile(album.profileId);
    if (owner == null) return;
    final container = ProviderScope.containerOf(context, listen: false);
    requestAccountSwitch(
      context,
      owner: owner.asOther,
      subject: album.name,
      purpose: AccountSwitchPurpose.openAlbum,
      proceed: () => switchAccountThen(
        container,
        owner.id,
        provider: album.albumId.provider,
        onLanded: (_) {
          if (mounted) DetailRoutes.openAlbum(context, album.albumId);
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final ledger = ref.watch(memoryLedgerProvider);
    final state = ledger.value;
    final source = ref.watch(activeSourceProvider).value;
    if (state != null) {
      for (final album in ledgerAlbumsIn(state.grid.cells)) {
        final cover = album.cover;
        _colors.request(album, cover == null ? null : source?.resolveCoverUrl(cover, size: 200));
      }
    }
    final layout = MemoryLedgerLayout.of(YoinWindowInfo.of(context));
    // The bar's bottom from the screen's top, status bar included: the list runs under the bar, the lift lands in it.
    _barBottom = MediaQuery.paddingOf(context).top + kToolbarHeight;
    if (!identical(_pixelsOf, state?.grid)) {
      _pixelsOf = state?.grid;
      _pixels = state == null || state.isEmpty ? const [] : liftPixels(state.grid);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _onScroll();
      });
    }
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        extendBodyBehindAppBar: true,
        appBar: _LedgerBar(
          title: strings.title,
          controller: _scroll,
          titleTop: SettingsBar.headlineOffset,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
          white: _barWhite.animation,
          formed: _formed,
        ),
        body: state == null
            ? Padding(
                padding: EdgeInsets.only(top: _barBottom),
                child: const Center(child: YoinLoadingIndicator()),
              )
            : Stack(
                children: [
                  // The list's viewport starts under the bar (Android pads it by barHeight): nothing scrolls
                  // behind the transparent bar; only the lift's squares fly up into it.
                  Padding(
                    padding: EdgeInsets.only(top: _barBottom),
                    child: Align(
                      alignment: Alignment.topCenter,
                      child: ConstrainedBox(
                        constraints: BoxConstraints(maxWidth: layout.maxWidth),
                        child: ListView(
                          controller: _scroll,
                          padding: EdgeInsets.fromLTRB(
                            MemoryLedgerTokens.pageSide,
                            MemoryLedgerTokens.pageTop,
                            MemoryLedgerTokens.pageSide,
                            MemoryLedgerTokens.pageBottom + MediaQuery.paddingOf(context).bottom,
                          ),
                          children: _body(context, state, layout, strings),
                        ),
                      ),
                    ),
                  ),
                  Positioned.fill(
                    child: IgnorePointer(
                      child: CustomPaint(
                        key: _liftLayer,
                        painter: MemoryGridLiftPainter(
                          lift: _lift,
                          progress: _liftProgress,
                          pixels: _pixels,
                          colors: _colors,
                          faint: colors.onSurface.withValues(alpha: 0.07),
                          barBottom: _barBottom,
                          into: _layerBox,
                          reduced: MediaQuery.disableAnimationsOf(context),
                          rtl: Directionality.of(context) == TextDirection.rtl,
                          repaint: Listenable.merge([_scroll, _colors]),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  List<Widget> _body(
    BuildContext context,
    MemoryLedgerState state,
    MemoryLedgerLayout layout,
    MemoryLedgerStrings strings,
  ) {
    final theme = Theme.of(context);
    if (state.isEmpty) {
      return [
        SettingsHeadline(title: strings.title),
        Padding(
          padding: const EdgeInsets.only(top: MemoryLedgerTokens.emptyTop),
          child: Text(strings.emptyTitle, style: theme.textTheme.titleMedium),
        ),
      ];
    }
    final selected = _selected != null && _selected! < state.grid.cells.length ? _selected : null;
    final cell = selected == null ? null : state.grid.cells[selected];
    final shown = cell == null
        ? state.entries
        : [
            for (final e in state.entries)
              if (cell.contains(state.dayOf(e.at))) e,
          ];
    final first = state.stats.firstAt;
    return [
      SettingsHeadline(title: strings.title),
      Padding(
        padding: const EdgeInsets.only(top: MemoryLedgerTokens.statsTop),
        child: _Stats(stats: state.stats, large: layout.wide),
      ),
      Padding(
        padding: const EdgeInsets.only(top: MemoryLedgerTokens.gridTop),
        child: Align(
          alignment: layout.wide ? Alignment.topCenter : AlignmentDirectional.topStart,
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: layout.wide ? MemoryLedgerTokens.gridMaxWide : MemoryLedgerTokens.gridMax,
            ),
            child: ValueListenableBuilder<bool>(
              valueListenable: _lifted,
              builder: (context, lifted, child) => Opacity(opacity: lifted ? 0 : 1, child: child),
              child: MemoryGridView(
                grid: state.grid,
                colors: _colors,
                placement: MemoryGridPlacement.page,
                selected: selected,
                lift: _lift,
                onCellClick: (i) {
                  YoinHaptics.tick();
                  setState(() => _selected = selected == i ? null : i);
                },
              ),
            ),
          ),
        ),
      ),
      Padding(
        padding: const EdgeInsets.only(top: MemoryLedgerTokens.barTop, bottom: MemoryLedgerTokens.barBottom),
        child: _ViewBar(
          view: _view,
          onView: _setView,
          sort: _sort,
          onSort: (s) => setState(() => _sort = s),
          cell: cell,
          scale: state.grid.scale,
          onClearCell: () => setState(() => _selected = null),
        ),
      ),
      SpringCrossFade(
        childKey: (_view, _sort, selected),
        fit: StackFit.loose,
        child: shown.isEmpty
            ? Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Text(
                  strings.rangeEmpty,
                  style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              )
            : _views(context, state, shown, layout, picked: cell != null),
      ),
      if (first != null)
        Padding(
          padding: const EdgeInsets.only(top: MemoryLedgerTokens.sinceTop),
          child: Text(
            strings.since(strings.longDate(state.dayOf(first))),
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.outline),
          ),
        ),
    ];
  }

  Widget _views(
    BuildContext context,
    MemoryLedgerState state,
    List<MemoryLedgerEntry> shown,
    MemoryLedgerLayout layout, {
    required bool picked,
  }) {
    switch (_view) {
      case MemoryLedgerView.timeline:
        final rows = [
          for (final r in memoryTimelineRows(shown, today: state.today, dayOf: state.dayOf))
            // A stretch picked on the grid: just its months, no quiet ones around it.
            if (!picked || r is! TimelineQuiet) r,
        ];
        return _Timeline(rows: rows, state: state, layout: layout, onOpen: _openAlbum);
      case MemoryLedgerView.cards:
        final items = memoryLedgerItems(shown);
        return _Masonry(
          columns: layout.lanes,
          gutter: layout.gutter,
          children: [
            for (final item in items)
              switch (item) {
                MemoryLedgerAlbumItem() => _AlbumGridCard(item: item, state: state, onOpen: _openAlbum),
                MemoryLedgerSongItem() => _SongGridCard(item: item, state: state, onOpen: _openAlbum),
              },
          ],
        );
      case MemoryLedgerView.list:
        final items = sortLedgerItems(memoryLedgerItems(shown), _sort);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [for (final item in items) _ListRow(item: item, state: state, onOpen: _openAlbum)],
        );
    }
  }
}

/// Settings' bar for this page (SettingsBar's metrics and title hand-off)
/// whose title and back button turn white, and status bar icons light, as
/// the grid's mosaic forms under them.
class _LedgerBar extends StatelessWidget implements PreferredSizeWidget {
  const _LedgerBar({
    required this.title,
    required this.controller,
    required this.titleTop,
    required this.titleHeight,
    required this.white,
    required this.formed,
  });

  final String title;
  final ScrollController controller;
  final double titleTop;
  final double titleHeight;
  final Animation<double> white;
  final bool formed;

  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);

  double _progress() {
    if (!controller.hasClients) return 0;
    return ((controller.offset - titleTop) / titleHeight.clamp(1.0, double.infinity)).clamp(0.0, 1.0);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AppBar(
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      // Over the formed mosaic: light icons; else as the page.
      systemOverlayStyle: yoinStatusBarStyle(formed ? Brightness.dark : Theme.of(context).brightness),
      scrolledUnderElevation: 0,
      elevation: 0,
      automaticallyImplyLeading: false,
      leadingWidth: SettingsMetrics.backStart + SettingsMetrics.backTouch + SettingsMetrics.backEnd,
      titleSpacing: 0,
      leading: Padding(
        padding: const EdgeInsetsDirectional.only(start: SettingsMetrics.backStart, end: SettingsMetrics.backEnd),
        child: AnimatedBuilder(
          animation: white,
          builder: (context, _) {
            final t = white.value.clamp(0.0, 1.0);
            return DetailBackButton(
              container: Color.lerp(colors.surfaceContainerHighest, Colors.black.withValues(alpha: 0.28), t),
              content: Color.lerp(colors.onSurface, Colors.white, t),
            );
          },
        ),
      ),
      title: AnimatedBuilder(
        animation: Listenable.merge([controller, white]),
        builder: (context, _) {
          final p = _progress();
          return Opacity(
            opacity: p,
            child: FractionalTranslation(
              translation: Offset(0, (1 - p) * 0.5),
              child: ExcludeSemantics(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleLarge!.copyWith(
                    color: Color.lerp(colors.onSurface, Colors.white, white.value.clamp(0.0, 1.0)),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// [children] dealt into [columns] columns in turn (Android's staggered
/// lanes); one column is a plain list.
class _Masonry extends StatelessWidget {
  const _Masonry({required this.columns, required this.gutter, required this.children});

  final int columns;
  final double gutter;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    if (columns <= 1) return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var c = 0; c < columns; c++) ...[
          if (c > 0) SizedBox(width: gutter),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [for (var i = c; i < children.length; i += columns) children[i]],
            ),
          ),
        ],
      ],
    );
  }
}

class _Stats extends StatelessWidget {
  const _Stats({required this.stats, required this.large});

  final MemoryLedgerStats stats;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final figures = [
      (stats.albums, strings.statAlbums),
      (stats.writings, strings.statWritings),
      (stats.characters, strings.statCharacters),
      (stats.ratedTracks, strings.statRated),
    ];
    final size = large ? MemoryLedgerTokens.statFigureWide : MemoryLedgerTokens.statFigure;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < figures.length; i++) ...[
          if (i > 0) SizedBox(width: large ? MemoryLedgerTokens.statGapWide : MemoryLedgerTokens.statGap),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _grouped(figures[i].$1),
                  maxLines: 1,
                  style: gsf(
                    size,
                    weight: 600,
                    height: (size + 6) / size,
                    letterSpacing: 0,
                  ).copyWith(color: theme.colorScheme.onSurface),
                ),
                Text(
                  figures[i].$2,
                  maxLines: 2,
                  style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}

/// 12,345 (the integer format of every locale Yoin ships in).
String _grouped(int n) {
  final digits = n.toString();
  final out = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) out.write(',');
    out.write(digits[i]);
  }
  return out.toString();
}

/// Under the grid: the picked stretch (tap to clear) on the start side; the
/// list's order and the view on the end.
class _ViewBar extends StatelessWidget {
  const _ViewBar({
    required this.view,
    required this.onView,
    required this.sort,
    required this.onSort,
    required this.cell,
    required this.scale,
    required this.onClearCell,
  });

  final MemoryLedgerView view;
  final ValueChanged<MemoryLedgerView> onView;
  final MemoryLedgerSort sort;
  final ValueChanged<MemoryLedgerSort> onSort;
  final MemoryGridCell? cell;
  final MemoryGridScale scale;
  final VoidCallback onClearCell;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final cell = this.cell;
    return Row(
      children: [
        Expanded(
          child: Align(
            alignment: AlignmentDirectional.centerStart,
            child: MemorySpringFold(
              visible: cell != null,
              child: cell == null
                  ? const SizedBox.shrink()
                  : _CellChip(label: _cellLabel(strings, cell), onTap: onClearCell),
            ),
          ),
        ),
        if (view == MemoryLedgerView.list) ...[
          _MenuButton<MemoryLedgerSort>(
            label: _sortLabel(strings, sort),
            icon: YoinSymbols.sort,
            options: MemoryLedgerSort.values,
            selected: sort,
            optionLabel: (s) => _sortLabel(strings, s),
            onSelect: onSort,
          ),
          const SizedBox(width: 8),
        ],
        _MenuButton<MemoryLedgerView>(
          label: _viewLabel(strings, view),
          icon: _viewIcon(view),
          options: MemoryLedgerView.values,
          selected: view,
          optionLabel: (v) => _viewLabel(strings, v),
          optionIcon: _viewIcon,
          onSelect: onView,
        ),
      ],
    );
  }

  String _cellLabel(MemoryLedgerStrings strings, MemoryGridCell cell) => switch (scale) {
    MemoryGridScale.month => strings.monthLong(cell.start),
    MemoryGridScale.week => '${strings.dayNoYear(cell.start)} – ${strings.dayNoYear(cell.end)}',
    MemoryGridScale.day => strings.dayNoYear(cell.start),
  };
}

String _viewLabel(MemoryLedgerStrings s, MemoryLedgerView v) => switch (v) {
  MemoryLedgerView.timeline => s.viewTimeline,
  MemoryLedgerView.cards => s.viewCards,
  MemoryLedgerView.list => s.viewList,
};

IconData _viewIcon(MemoryLedgerView v) => switch (v) {
  MemoryLedgerView.timeline => YoinSymbols.viewTimeline,
  MemoryLedgerView.cards => YoinSymbols.viewCards,
  MemoryLedgerView.list => YoinSymbols.viewList,
};

String _sortLabel(MemoryLedgerStrings s, MemoryLedgerSort v) => switch (v) {
  MemoryLedgerSort.recent => s.sortRecent,
  MemoryLedgerSort.score => s.sortScore,
  MemoryLedgerSort.name => s.sortName,
};

class _CellChip extends StatelessWidget {
  const _CellChip({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Material(
      color: colors.secondaryContainer,
      shape: const StadiumBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: MemoryLedgerTokens.menuHeight,
          child: Padding(
            padding: const EdgeInsetsDirectional.only(start: 14, end: 10),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onSecondaryContainer),
                  ),
                ),
                const SizedBox(width: 6),
                Icon(YoinSymbols.close, size: MemoryLedgerTokens.menuIcon, color: colors.onSecondaryContainer),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MenuButton<T> extends StatelessWidget {
  const _MenuButton({
    required this.label,
    required this.icon,
    required this.options,
    required this.selected,
    required this.optionLabel,
    required this.onSelect,
    this.optionIcon,
  });

  final String label;
  final IconData icon;
  final List<T> options;
  final T selected;
  final String Function(T) optionLabel;
  final IconData Function(T)? optionIcon;
  final ValueChanged<T> onSelect;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Builder(
      builder: (anchor) => Material(
        color: colors.surfaceContainerHigh,
        shape: const StadiumBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () async {
            final picked = await showYoinDropdownMenu<T>(
              context: anchor,
              anchor: globalRectOf(anchor),
              items: [
                for (final o in options)
                  YoinMenuItem(value: o, label: optionLabel(o), icon: optionIcon?.call(o), checked: o == selected),
              ],
            );
            if (picked != null && picked != selected) onSelect(picked);
          },
          child: SizedBox(
            height: MemoryLedgerTokens.menuHeight,
            child: Padding(
              padding: const EdgeInsetsDirectional.only(start: 10, end: 14),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, size: MemoryLedgerTokens.menuIcon, color: colors.onSurfaceVariant),
                  const SizedBox(width: 6),
                  Text(label, style: Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onSurface)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- the timeline

class _Timeline extends StatelessWidget {
  const _Timeline({required this.rows, required this.state, required this.layout, required this.onOpen});

  final List<MemoryTimelineRow> rows;
  final MemoryLedgerState state;
  final MemoryLedgerLayout layout;
  final ValueChanged<MemoryLedgerAlbum?> onOpen;

  @override
  Widget build(BuildContext context) {
    // One column on a phone (album cards alternate sides); a masonry wider
    // than that, each month's cards under its full-width header.
    final out = <Widget>[];
    var run = <Widget>[];
    void flush() {
      if (run.isEmpty) return;
      out.add(_Masonry(columns: layout.columns, gutter: layout.gutter, children: run));
      run = [];
    }

    final masonry = layout.columns > 1;
    for (final row in rows) {
      switch (row) {
        case TimelineMonth():
          flush();
          out.add(_MonthHeader(row: row, today: state.today, wide: layout.wide));
        case TimelineQuiet():
          flush();
          out.add(_QuietMonths(row: row, today: state.today));
        case TimelineAlbum():
          run.add(_TimelineAlbumCard(row: row, state: state, masonry: masonry, onOpen: onOpen));
        case TimelineNote():
          run.add(_NoteCard(entry: row.entry, state: state, onOpen: onOpen));
        case TimelineScores():
          run.add(_ScoresCard(entries: row.entries, state: state));
      }
    }
    flush();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: out);
  }
}

/// The month is the page's landmark: large and in the serif, so the eye
/// finds where it is while scrolling.
class _MonthHeader extends StatelessWidget {
  const _MonthHeader({required this.row, required this.today, required this.wide});

  final TimelineMonth row;
  final DateTime today;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final size = wide ? MemoryLedgerTokens.monthHeaderWide : MemoryLedgerTokens.monthHeader;
    return Padding(
      padding: EdgeInsets.only(
        top: wide ? MemoryLedgerTokens.monthTopWide : MemoryLedgerTokens.monthTop,
        bottom: wide ? 12 : 8,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Flexible(
            child: Text(
              strings.monthName(row.month, today),
              style: memoryWrittenTitleStyle(size, height: (size + 6) / size, color: theme.colorScheme.onSurface),
            ),
          ),
          const SizedBox(width: 12),
          Padding(
            padding: EdgeInsets.only(bottom: wide ? 8 : 6),
            child: Text(
              strings.monthCount(row.count),
              style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
        ],
      ),
    );
  }
}

class _QuietMonths extends StatelessWidget {
  const _QuietMonths({required this.row, required this.today});

  final TimelineQuiet row;
  final DateTime today;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final text = row.from == row.to
        ? strings.quietOne(strings.monthName(row.from, today))
        // Earlier month first: "July to August".
        : strings.quietRange(strings.monthName(row.to, today), strings.monthName(row.from, today));
    final dash = theme.colorScheme.outlineVariant;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Row(
        children: [
          Expanded(child: _DashedRule(color: dash)),
          const SizedBox(width: 10),
          Text(text, style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          const SizedBox(width: 10),
          Expanded(child: _DashedRule(color: dash)),
        ],
      ),
    );
  }
}

/// The receipt's dash (About's), so the two read as one family.
class _DashedRule extends StatelessWidget {
  const _DashedRule({required this.color});
  final Color color;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 1,
    width: double.infinity,
    child: CustomPaint(painter: _DashPainter(color)),
  );
}

class _DashPainter extends CustomPainter {
  _DashPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1;
    for (var x = 0.0; x < size.width; x += 7) {
      canvas.drawLine(Offset(x, size.height / 2), Offset((x + 4).clamp(0, size.width), size.height / 2), paint);
    }
  }

  @override
  bool shouldRepaint(_DashPainter old) => old.color != color;
}

/// The big number and what it scores.
class _ScoreLine extends StatelessWidget {
  const _ScoreLine({required this.score, this.label, this.size = 26});

  final double score;
  final String? label;
  final double size;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          MemoryScores.text(score),
          style: gsf(size, weight: 600, height: 1.2, letterSpacing: 0).copyWith(color: theme.colorScheme.onSurface),
        ),
        if (label != null) ...[
          const SizedBox(width: 8),
          Padding(
            padding: const EdgeInsets.only(bottom: 3),
            child: Text(label!, style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
        ],
      ],
    );
  }
}

/// Two pieces of one line told apart by tone and a gap, not by a dot.
class _Pair extends StatelessWidget {
  const _Pair({required this.first, this.second, required this.style});

  final String first;
  final String? second;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Row(
      children: [
        Flexible(
          child: Text(
            first,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: style.copyWith(color: colors.onSurfaceVariant),
          ),
        ),
        if (second != null) ...[
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              second!,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: style.copyWith(color: colors.outline),
            ),
          ),
        ],
      ],
    );
  }
}

/// An album's cover, whole and square at [size].
Widget _cover(MemoryLedgerAlbum? album, double size, double corner, ColorScheme colors) => album == null
    ? SizedBox.square(
        dimension: size,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: colors.onSurface.withValues(alpha: 0.07),
            borderRadius: BorderRadius.circular(corner),
          ),
        ),
      )
    : CoverArt(cover: album.cover, size: size, radius: corner);

/// An album's month on the timeline: the cover whole, then what was kept on
/// it. No container: the cards stand on the page. On a phone the covers take
/// turns starting from the left and from the right; what's written under
/// them runs the full width. The memory title is the loudest line.
class _TimelineAlbumCard extends StatelessWidget {
  const _TimelineAlbumCard({required this.row, required this.state, required this.masonry, required this.onOpen});

  final TimelineAlbum row;
  final MemoryLedgerState state;
  final bool masonry;
  final ValueChanged<MemoryLedgerAlbum?> onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final item = row.item;
    final title = item.title;
    final review = item.review;
    final opens = state.opens(item.album);
    final from = state.fromProfile(item.album.profileId);
    return YoinHover(
      onTap: opens ? () => onOpen(item.album) : null,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: opens ? () => onOpen(item.album) : null,
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: masonry ? 12 : 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              LayoutBuilder(
                builder: (context, c) {
                  final width = masonry
                      ? c.maxWidth
                      : (c.maxWidth * MemoryLedgerTokens.timelineCoverShare).clamp(
                          0.0,
                          MemoryLedgerTokens.timelineCoverMax,
                        );
                  return Align(
                    alignment: row.end && !masonry ? AlignmentDirectional.topEnd : AlignmentDirectional.topStart,
                    child: _cover(item.album, width, MemoryLedgerTokens.timelineCoverCorner, theme.colorScheme),
                  );
                },
              ),
              const SizedBox(height: 14),
              if (title != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(
                    title,
                    style: memoryWrittenTitleStyle(24, height: 1.33, color: theme.colorScheme.onSurface),
                  ),
                ),
              _Pair(
                first: item.album.name,
                second: item.album.artist.trim().isEmpty ? null : item.album.artist,
                style: theme.textTheme.bodyMedium!,
              ),
              if (item.score != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: _ScoreLine(score: item.score!, label: strings.albumScore, size: 34),
                ),
              if (review != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    review,
                    maxLines: 6,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurface),
                  ),
                ),
              if (item.songs.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      for (final song in item.songs) ...[
                        _DashedRule(color: theme.colorScheme.outlineVariant),
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: _SongScoreRow(title: song.title ?? strings.unknownSong, score: song.score),
                        ),
                      ],
                      _DashedRule(color: theme.colorScheme.outlineVariant),
                    ],
                  ),
                ),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(
                  spacing: MemoryLedgerTokens.fromAfterDate,
                  children: [
                    Text(
                      strings.shortDay(state.dayOf(item.at), state.today),
                      style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.outline),
                    ),
                    if (from != null) Flexible(child: _FromLine(owner: from)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Another account's memory says whose it is, with its mark, as the deck's
/// "From …" line does (Android ledger FromLine, memory_ledger_from).
class _FromLine extends StatelessWidget {
  const _FromLine({required this.owner});

  final LedgerProfile owner;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      spacing: MemoryLedgerTokens.fromMarkGap,
      children: [
        AccountMark(
          shapeIndex: owner.shape,
          identity: ServiceIdentity.forProvider(owner.provider),
          size: MemoryLedgerTokens.fromMark,
        ),
        Flexible(
          child: Text(
            MemoryLedgerStrings.of(context).from(owner.name),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}

class _SongScoreRow extends StatelessWidget {
  const _SongScoreRow({required this.title, required this.score});
  final String title;
  final double score;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Expanded(
          child: Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
        Text(
          MemoryScores.text(score),
          style: gsf(14, weight: 600, height: 1.4, letterSpacing: 0).copyWith(color: theme.colorScheme.onSurface),
        ),
      ],
    );
  }
}

String _kindLabel(MemoryLedgerStrings s, MemoryLedgerKind kind) => switch (kind) {
  MemoryLedgerKind.songNote => s.kindSongNote,
  MemoryLedgerKind.review => s.kindReview,
  MemoryLedgerKind.title => s.kindTitle,
  MemoryLedgerKind.albumRating => s.kindAlbumRating,
  MemoryLedgerKind.trackRating => s.kindTrackRating,
};

String _anchor(int ms) => '${ms ~/ 60000}:${((ms ~/ 1000) % 60).toString().padLeft(2, '0')}';

/// A soft patch, so the notes read apart from the album cards that stand bare on the page.
Widget _patch(BuildContext context, {required Widget child, VoidCallback? onTap, double corner = 24}) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 8),
  child: Material(
    color: Theme.of(context).colorScheme.surfaceContainerLow,
    borderRadius: BorderRadius.circular(corner),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: Padding(padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16), child: child),
    ),
  ),
);

/// A song note on the timeline: what was written leads, large, in the serif
/// (it's the listener's own voice); the song and where in it under it, then
/// its album small beside a small cover.
class _NoteCard extends StatelessWidget {
  const _NoteCard({required this.entry, required this.state, required this.onOpen});

  final MemoryLedgerEntry entry;
  final MemoryLedgerState state;
  final ValueChanged<MemoryLedgerAlbum?> onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final album = entry.album;
    final text = entry.text;
    final song = entry.trackTitle;
    final at = entry.positionMs;
    return _patch(
      context,
      corner: MemoryLedgerTokens.noteCorner,
      onTap: state.opens(album) ? () => onOpen(album) : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  _kindLabel(strings, entry.kind),
                  maxLines: 1,
                  style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ),
              Text(
                strings.shortDay(state.dayOf(entry.at), state.today),
                style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.outline),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (text != null && text.trim().isNotEmpty)
            Text(
              text,
              maxLines: 10,
              overflow: TextOverflow.ellipsis,
              style: memoryWrittenTitleStyle(
                19,
                height: 28 / 19,
                color: theme.colorScheme.onSurface,
              ).copyWith(fontWeight: FontWeight.w400),
            )
          else if (entry.score != null)
            _ScoreLine(score: entry.score!, size: 30),
          if (song != null) ...[
            const SizedBox(height: 10),
            _Pair(first: song, second: at != null && at > 0 ? _anchor(at) : null, style: theme.textTheme.labelLarge!),
          ],
          const SizedBox(height: 10),
          Row(
            children: [
              _cover(album, MemoryLedgerTokens.noteCover, 6, theme.colorScheme),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _Pair(
                      first: album?.name ?? strings.unknownAlbum,
                      second: album == null || album.artist.trim().isEmpty ? null : album.artist,
                      style: theme.textTheme.bodySmall!,
                    ),
                    if (state.fromProfile(entry.profileId) case final from?) _FromLine(owner: from),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// A month's song scores whose album nobody could name, on one card, best first.
class _ScoresCard extends StatelessWidget {
  const _ScoresCard({required this.entries, required this.state});

  final List<MemoryLedgerEntry> entries;
  final MemoryLedgerState state;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final sorted = [...entries]..sort((a, b) => (b.score ?? 0).compareTo(a.score ?? 0));
    return _patch(
      context,
      corner: MemoryLedgerTokens.noteCorner,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  strings.kindTrackRating,
                  style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ),
              Text(
                strings.shortDay(state.dayOf(entries.first.at), state.today),
                style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.outline),
              ),
            ],
          ),
          const SizedBox(height: 6),
          for (var i = 0; i < sorted.length; i++) ...[
            if (i > 0) _DashedRule(color: theme.colorScheme.outlineVariant),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      sorted[i].trackTitle ?? strings.unknownSong,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurface),
                    ),
                  ),
                  Text(
                    MemoryScores.text(sorted[i].score ?? 0),
                    style: gsf(
                      16,
                      weight: 600,
                      height: 1.4,
                      letterSpacing: 0,
                    ).copyWith(color: theme.colorScheme.onSurface),
                  ),
                ],
              ),
            ),
          ],
          for (final id in {for (final e in entries) e.profileId})
            if (state.fromProfile(id) case final from?)
              Padding(
                padding: const EdgeInsets.only(top: MemoryLedgerTokens.fromInScores),
                child: _FromLine(owner: from),
              ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------- cards and list

class _CardFoot extends StatelessWidget {
  const _CardFoot({required this.score, required this.at, required this.state});

  final double? score;
  final int at;
  final MemoryLedgerState state;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Expanded(child: score == null ? const SizedBox.shrink() : _ScoreLine(score: score!, size: 20)),
          Padding(
            padding: const EdgeInsets.only(bottom: 3),
            child: Text(
              MemoryLedgerStrings.of(context).shortDay(state.dayOf(at), state.today),
              style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.outline),
            ),
          ),
        ],
      ),
    );
  }
}

Widget _gridCard(BuildContext context, {required VoidCallback? onTap, required List<Widget> children}) => Padding(
  padding: const EdgeInsets.only(bottom: 12),
  child: Material(
    color: Theme.of(context).colorScheme.surfaceContainerLow,
    borderRadius: BorderRadius.circular(MemoryLedgerTokens.cardCorner),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
      ),
    ),
  ),
);

/// Cards: an album (its cover whole, its title, its score).
class _AlbumGridCard extends StatelessWidget {
  const _AlbumGridCard({required this.item, required this.state, required this.onOpen});

  final MemoryLedgerAlbumItem item;
  final MemoryLedgerState state;
  final ValueChanged<MemoryLedgerAlbum?> onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final title = item.title;
    return _gridCard(
      context,
      onTap: state.opens(item.album) ? () => onOpen(item.album) : null,
      children: [
        LayoutBuilder(
          builder: (context, c) =>
              _cover(item.album, c.maxWidth, MemoryLedgerTokens.cardCoverCorner, theme.colorScheme),
        ),
        const SizedBox(height: 6),
        if (title != null)
          Text(
            title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: memoryWrittenTitleStyle(16, height: 1.4, color: theme.colorScheme.onSurface),
          ),
        Text(
          item.album.name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.onSurface),
        ),
        if (item.album.artist.trim().isNotEmpty)
          Text(
            item.album.artist,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        _CardFoot(score: item.score, at: item.at, state: state),
      ],
    );
  }
}

/// Cards: a song (a small cover, its name, its newest note, its score).
class _SongGridCard extends StatelessWidget {
  const _SongGridCard({required this.item, required this.state, required this.onOpen});

  final MemoryLedgerSongItem item;
  final MemoryLedgerState state;
  final ValueChanged<MemoryLedgerAlbum?> onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final album = item.album;
    final note = item.note?.text;
    return _gridCard(
      context,
      onTap: state.opens(album) ? () => onOpen(album) : null,
      children: [
        Row(
          children: [
            _cover(album, MemoryLedgerTokens.songCardCover, 8, theme.colorScheme),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.title ?? strings.unknownSong,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.onSurface),
                  ),
                  Text(
                    album == null ? strings.unknownAlbum : (album.artist.trim().isEmpty ? album.name : album.artist),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
          ],
        ),
        if (note != null && note.trim().isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              note,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
        _CardFoot(score: item.score, at: item.at, state: state),
      ],
    );
  }
}

/// The list: a line each. An album's line stands taller (its cover larger,
/// its memory title under its name); a song's is shorter (told apart by
/// height, not by shape).
class _ListRow extends StatelessWidget {
  const _ListRow({required this.item, required this.state, required this.onOpen});

  final MemoryLedgerItem item;
  final MemoryLedgerState state;
  final ValueChanged<MemoryLedgerAlbum?> onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final item = this.item;
    final album = item.album;
    final isAlbum = item is MemoryLedgerAlbumItem;
    final under = switch (item) {
      MemoryLedgerAlbumItem() => item.album.artist,
      MemoryLedgerSongItem() => album == null ? '' : (album.artist.trim().isEmpty ? album.name : album.artist),
    };
    final title = item is MemoryLedgerAlbumItem ? item.title : null;
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: state.opens(album) ? () => onOpen(album) : null,
      child: Padding(
        padding: EdgeInsets.symmetric(vertical: isAlbum ? 10 : 6),
        child: Row(
          children: [
            _cover(
              album,
              isAlbum ? MemoryLedgerTokens.listAlbumCover : MemoryLedgerTokens.listSongCover,
              8,
              theme.colorScheme,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.name.trim().isEmpty ? strings.unknownSong : item.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.onSurface),
                  ),
                  if (title != null)
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: memoryWrittenTitleStyle(
                        14,
                        height: 1.43,
                        color: theme.colorScheme.onSurface,
                      ).copyWith(fontWeight: FontWeight.w400),
                    ),
                  if (under.trim().isNotEmpty)
                    Text(
                      under,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Text(
              strings.shortDay(state.dayOf(item.at), state.today),
              style: theme.textTheme.labelSmall!.copyWith(color: theme.colorScheme.outline),
            ),
            const SizedBox(width: 12),
            ConstrainedBox(
              constraints: const BoxConstraints(minWidth: 32),
              child: Text(
                item.score == null ? '—' : MemoryScores.text(item.score!),
                textAlign: TextAlign.end,
                style: gsf(
                  14,
                  weight: 600,
                  height: 1.43,
                  letterSpacing: 0,
                ).copyWith(color: item.score == null ? theme.colorScheme.outline : theme.colorScheme.onSurface),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
