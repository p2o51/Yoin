import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

// A Library grid keeps its place when its columns change (Android
// LibraryGridAnchor.kt, KeepGridAnchorAcrossWidthChanges). A Flutter grid
// keeps its scroll offset in pixels, so a rotation, a resize, a fold, a split
// opening or a short shelf growing its cells would otherwise show whatever
// row now sits at that offset — with half the columns, rows from twice as far
// down. The grid instead brings the row of the item the user scrolled to back
// to the top, in the same layout pass (no frame of the wrong rows). As on
// Android (a lazy grid's scrollToItem), "the top" is the grid's top padding
// line, where its rows sit at rest: the row lands [LibraryGridLayout.top]
// below the grid's edge, not flush against it.

/// One layout of a Library grid: what its rows look like now.
@immutable
final class LibraryGridLayout {
  const LibraryGridLayout({required this.width, required this.columns, required this.stride, this.top = 0});

  /// The grid's width (the scroll view's, padding included).
  final double width;

  /// Cells per row.
  final int columns;

  /// A row and the gap under it.
  final double stride;

  /// The grid's top padding: rows are read, and brought back, at this line
  /// below the grid's edge (Android's content padding).
  final double top;

  @override
  bool operator ==(Object other) =>
      other is LibraryGridLayout &&
      other.width == width &&
      other.columns == columns &&
      other.stride == stride &&
      other.top == top;

  @override
  int get hashCode => Object.hash(width, columns, stride, top);
}

/// The place in a grid as rows: [index] is the first item of the row at the
/// top padding line (-1: the head — the sort row — still there, above the
/// first row), [offset] how far that row's top (or the grid's top) has
/// scrolled past the line.
typedef LibraryGridPlace = ({int index, double offset});

/// Where the grid sits at [pixels], its first row reaching the top padding
/// line at [leading] px (the head's height), rows [stride] apart, [columns]
/// per row, [itemCount] items.
LibraryGridPlace libraryGridPlaceAt({
  required double pixels,
  required double leading,
  required double stride,
  required int columns,
  required int itemCount,
}) {
  if (pixels < leading || stride <= 0 || itemCount <= 0) return (index: -1, offset: math.max(0, pixels));
  final lastRow = (itemCount - 1) ~/ math.max(1, columns);
  final row = math.min(((pixels - leading) / stride).floor(), lastRow);
  return (index: row * columns, offset: pixels - (leading + row * stride));
}

/// The anchor for a fast-scroller jump that left the row starting at item
/// [rowStart] at the top (Android libraryJumpAnchor, in item indices): the
/// section that starts on that row — the one the bubble names there, the
/// last — else the row itself. A later column change then keeps that
/// section's first item in the top row, however the new columns break the rows.
int libraryJumpAnchor({required int rowStart, required int columns, required List<int> sectionStarts}) {
  if (rowStart < 0) return rowStart;
  final rowEnd = rowStart + math.max(1, columns) - 1;
  int? section;
  for (final start in sectionStarts) {
    if (start > rowEnd) break;
    section = start;
  }
  if (section == null) return rowStart;
  return section >= rowStart ? section : rowStart;
}

/// The item a Library grid brings back to its top when its layout changes. A
/// scroll records it as it goes (any pixel change the position makes itself:
/// drags, flings, jumps); a layout-time correction is no scroll. A
/// fast-scroller jump then moves it to the section it landed on ([jumped]).
///
/// An [exact] anchor (the search surface's grids) also holds where in that
/// row the grid sat, [offset] px past its top, at the grid [width] the scroll
/// left it at, and how tall a row was then, [rowPx]. Back at that width the
/// grid lands exactly where the user left it, offset into the row and all —
/// also when the grid is built again (a type switched away from and back);
/// at another width it keeps the same share of the row scrolled past. A
/// plain anchor brings the row flush to the top.
class LibraryGridAnchor {
  LibraryGridAnchor({this.exact = false});

  final bool exact;

  /// First item of the anchored row; -1 is the grid's head.
  int index = -1;
  double offset = 0;
  double width = 0;
  double rowPx = 0;

  LibraryGridLayout? _layout;
  int _itemCount = 0;
  double _head = 0;

  /// The layout (and head) the position's pixels were last placed for.
  (LibraryGridLayout, double)? _settled;

  /// The head's measured height (the sort row; 0 without one), from [LibraryGridHead].
  double get head => _head;

  /// The current layout, as the grid's build lays it out: [itemCount] items
  /// on rows of [layout]. Read by the position when the viewport lays out.
  void layOut(LibraryGridLayout layout, int itemCount) {
    _layout = layout;
    _itemCount = itemCount;
  }

  /// The head measured [extent] tall this layout pass.
  void measureHead(double extent) => _head = extent;

  /// Forgets the place: a new query starts the search's grids at the top.
  void reset() {
    index = -1;
    offset = 0;
    width = 0;
    rowPx = 0;
  }

  LibraryGridPlace? _placeAt(double pixels) {
    final layout = _layout;
    if (layout == null) return null;
    return libraryGridPlaceAt(
      pixels: pixels,
      // Row 0 reaches the padding line once the head has scrolled past it.
      leading: _head,
      stride: layout.stride,
      columns: layout.columns,
      itemCount: _itemCount,
    );
  }

  /// A scroll left the grid at [pixels].
  void record(double pixels) {
    final layout = _layout;
    final place = _placeAt(pixels);
    if (layout == null || place == null || _settled != (layout, _head)) return;
    index = place.index;
    offset = place.offset;
    width = layout.width;
    rowPx = layout.stride;
  }

  /// A fast-scroller jump left the grid at [pixels]: the anchor moves to the
  /// section that starts on the top row ([libraryJumpAnchor]).
  void jumped(double pixels, List<int> sectionStarts) {
    final layout = _layout;
    final place = _placeAt(pixels);
    if (layout == null || place == null) return;
    index = libraryJumpAnchor(rowStart: place.index, columns: layout.columns, sectionStarts: sectionStarts);
    offset = 0;
    width = layout.width;
    rowPx = layout.stride;
  }

  /// Where the position should be for the layout just laid out, or null to
  /// stay: only when the layout changed since the pixels were placed (or, for
  /// an exact anchor, the grid was built again: [fresh], the position's first
  /// layout — kept by the position, as a grid fading out on the same
  /// controller lays out too).
  @visibleForTesting
  double? restoreTarget({bool fresh = false}) {
    final layout = _layout;
    if (layout == null) return null;
    final now = (layout, _head);
    final was = _settled;
    _settled = now;
    if (was == null && !(fresh && exact)) return null;
    if (was == now && !(fresh && exact)) return null;
    if (exact && fresh && width <= 0) return null;
    final leading = _head;
    if (index < 0) return exact ? math.min(offset, leading) : 0;
    final lastRow = math.max(0, (_itemCount - 1) ~/ math.max(1, layout.columns));
    final row = math.min(index ~/ math.max(1, layout.columns), lastRow);
    final rowTop = leading + row * layout.stride;
    if (!exact) return rowTop;
    if (width > 0 && layout.width == width) return rowTop + offset;
    if (rowPx > 0) return rowTop + offset * layout.stride / rowPx;
    return rowTop;
  }
}

/// A [ScrollController] for a Library grid that keeps its [anchor] (see
/// [LibraryGridAnchor]). The grid feeds the anchor its layout each build
/// ([LibraryGridAnchor.layOut]) and its head's height ([LibraryGridHead]).
///
/// [anchor] may outlive the controller: the search's grids keep theirs in
/// [LibraryGridAnchor]s held beside the query, so a search that stepped aside
/// for the detail column comes back where it was left.
class LibraryGridScrollController extends ScrollController {
  LibraryGridScrollController({bool exact = false, LibraryGridAnchor? anchor})
    : assert(anchor == null || anchor.exact == exact),
      anchor = anchor ?? LibraryGridAnchor(exact: exact);

  final LibraryGridAnchor anchor;

  @override
  ScrollPosition createScrollPosition(ScrollPhysics physics, ScrollContext context, ScrollPosition? oldPosition) {
    return _AnchoredScrollPosition(
      anchor: anchor,
      physics: physics,
      context: context,
      initialPixels: initialScrollOffset,
      keepScrollOffset: keepScrollOffset,
      oldPosition: oldPosition,
      debugLabel: debugLabel,
    );
  }
}

class _AnchoredScrollPosition extends ScrollPositionWithSingleContext {
  _AnchoredScrollPosition({
    required this.anchor,
    required super.physics,
    required super.context,
    super.initialPixels,
    super.keepScrollOffset,
    super.oldPosition,
    super.debugLabel,
  });

  final LibraryGridAnchor anchor;

  /// No layout yet: the grid is built (again).
  bool _fresh = true;

  @override
  bool applyContentDimensions(double minScrollExtent, double maxScrollExtent) {
    final target = anchor.restoreTarget(fresh: _fresh);
    _fresh = false;
    if (target != null && hasPixels) {
      final clamped = target.clamp(minScrollExtent, math.max(minScrollExtent, maxScrollExtent));
      // A correction relays the viewport out at the anchored row, this pass.
      if ((clamped - pixels).abs() > precisionErrorTolerance) {
        correctBy(clamped - pixels);
        return false;
      }
    }
    return super.applyContentDimensions(minScrollExtent, maxScrollExtent);
  }

  @override
  void didUpdateScrollPositionBy(double delta) {
    anchor.record(pixels);
    super.didUpdateScrollPositionBy(delta);
  }
}

/// The grid's head (its sort row): reports its height to [anchor] as it lays out.
class LibraryGridHead extends SingleChildRenderObjectWidget {
  const LibraryGridHead({super.key, required this.anchor, super.child});

  final LibraryGridAnchor anchor;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderLibraryGridHead(anchor);

  @override
  void updateRenderObject(BuildContext context, RenderLibraryGridHead renderObject) => renderObject.anchor = anchor;
}

/// [LibraryGridHead]'s render object.
class RenderLibraryGridHead extends RenderProxyBox {
  RenderLibraryGridHead(this._anchor);

  LibraryGridAnchor _anchor;
  LibraryGridAnchor get anchor => _anchor;

  /// Another grid's anchor: it learns the head's height on the next layout.
  set anchor(LibraryGridAnchor value) {
    if (identical(value, _anchor)) return;
    _anchor = value;
    markNeedsLayout();
  }

  @override
  void performLayout() {
    super.performLayout();
    anchor.measureHead(size.height);
  }
}
