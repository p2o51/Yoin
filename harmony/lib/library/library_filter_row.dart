import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/expressive_segment_chip.dart';
import '../ui/component/song_list_item.dart';
import 'library_sort.dart';
import 'library_strings.dart';

/// The chip just turned on is scrolled at least this clear of both ends.
const chipRevealMargin = 24.0;

String libraryTabLabel(LibraryStrings s, LibraryTab tab) => switch (tab) {
  LibraryTab.all => s.searchFilterAll,
  LibraryTab.playlists => s.tabPlaylists,
  LibraryTab.artists => s.tabArtists,
  LibraryTab.albums => s.tabAlbums,
  LibraryTab.songs => s.tabSongs,
  LibraryTab.favorites => s.tabFavorites,
};

/// The view switch (LibraryFilterRow): chips in order; with one on, an ✕
/// unfolds in front of the row and the chips' start padding springs 16 → 8.
/// The chip just turned on scrolls into view (spring), alongside the unfold
/// and once more when it lands; then the row belongs to the user.
class LibraryFilterRow extends StatefulWidget {
  const LibraryFilterRow({super.key, required this.tabs, required this.selected, required this.onSelect});

  final List<LibraryTab> tabs;
  final LibraryTab selected;

  /// Called with the tapped chip (the view model turns a second tap into All).
  final ValueChanged<LibraryTab> onSelect;

  @override
  State<LibraryFilterRow> createState() => _LibraryFilterRowState();
}

class _LibraryFilterRowState extends State<LibraryFilterRow> with TickerProviderStateMixin {
  final _scroll = ScrollController();
  final _keys = <LibraryTab, GlobalKey>{};
  late final SpringValue _start = SpringValue(
    vsync: this,
    initial: widget.selected == LibraryTab.all ? 16 : 8,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _reveal = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial);
  int _revealFrames = 0;

  @override
  void initState() {
    super.initState();
    _reveal.animation.addListener(_applyReveal);
  }

  @override
  void didUpdateWidget(LibraryFilterRow old) {
    super.didUpdateWidget(old);
    if (old.selected != widget.selected) {
      _start.animateTo(widget.selected == LibraryTab.all ? 16 : 8);
      if (widget.selected != LibraryTab.all) {
        // Follow the chip while the ✕ unfolds (about 30 frames), then once more.
        _revealFrames = 30;
        WidgetsBinding.instance.addPostFrameCallback((_) => _revealSelected());
      }
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    _start.dispose();
    _reveal.dispose();
    super.dispose();
  }

  void _revealSelected() {
    if (!mounted || !_scroll.hasClients) return;
    final chip = _keys[widget.selected]?.currentContext?.findRenderObject() as RenderBox?;
    final row = context.findRenderObject() as RenderBox?;
    if (chip == null || row == null || !chip.attached) return;
    final left = chip.localToGlobal(Offset.zero, ancestor: row).dx;
    final right = left + chip.size.width;
    final width = row.size.width;
    final p = _scroll.position;
    var target = p.pixels;
    if (left < chipRevealMargin) target += left - chipRevealMargin;
    if (right > width - chipRevealMargin) target += right - (width - chipRevealMargin);
    target = target.clamp(p.minScrollExtent, p.maxScrollExtent);
    if ((target - p.pixels).abs() > 0.5) {
      if ((_reveal.value - p.pixels).abs() > 1) _reveal.snapTo(p.pixels);
      _reveal.animateTo(target);
    }
    if (_revealFrames-- > 0) WidgetsBinding.instance.addPostFrameCallback((_) => _revealSelected());
  }

  void _applyReveal() {
    if (_scroll.hasClients && (_scroll.offset - _reveal.value).abs() > 0.1) _scroll.jumpTo(_reveal.value);
  }

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final on = widget.selected != LibraryTab.all;
    return Semantics(
      container: true,
      child: Row(
        children: [
          HorizontalSpringReveal(
            visible: on,
            child: Padding(
              padding: const EdgeInsetsDirectional.only(start: 16),
              child: IconButton.filledTonal(
                tooltip: strings.cdClearFilter,
                style: IconButton.styleFrom(
                  backgroundColor: colors.surfaceContainerHigh,
                  foregroundColor: colors.onSurfaceVariant,
                  fixedSize: const Size.square(LibraryTokens.chipHeight),
                  minimumSize: const Size.square(LibraryTokens.chipHeight),
                ),
                icon: const Icon(YoinSymbols.close, size: LibraryTokens.clearIcon),
                onPressed: () => widget.onSelect(widget.selected),
              ),
            ),
          ),
          Expanded(
            child: NotificationListener<ScrollStartNotification>(
              onNotification: (n) {
                // A user drag takes the row back.
                if (n.dragDetails != null) {
                  _revealFrames = 0;
                  _reveal.snapTo(_scroll.offset);
                }
                return false;
              },
              child: HorizontalEdgeFade(
                controller: _scroll,
                child: AnimatedBuilder(
                  animation: _start.animation,
                  builder: (context, _) => SingleChildScrollView(
                    controller: _scroll,
                    scrollDirection: Axis.horizontal,
                    padding: EdgeInsetsDirectional.only(start: _start.value, end: 16),
                    child: Row(
                      children: [
                        for (final (i, tab) in widget.tabs.indexed) ...[
                          if (i > 0) const SizedBox(width: LibraryTokens.chipGap),
                          ExpressiveSegmentChip(
                            key: _keys.putIfAbsent(tab, GlobalKey.new),
                            label: libraryTabLabel(strings, tab),
                            selected: widget.selected == tab,
                            onTap: () => widget.onSelect(tab),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
