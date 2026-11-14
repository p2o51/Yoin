/// The Memories grid, drawn (Android ledger/MemoryGridView.kt). A cell is the
/// album's cover told by its two colours, never the cover itself (a
/// streaming service's artwork may not be cut up): an album you wrote about
/// splits its base and accent on the diagonal; one you only scored is its
/// base, solid; two albums in a week or day splice their bases on the
/// diagonal. A month is a 2×2 of its top four albums. Nothing kept: a faint cell.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../data/memory/memory_grid.dart';
import '../../data/memory/memory_ledger.dart';
import '../../home/widgets/cover_palette.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import 'memory_ledger_strings.dart';

/// One album's two colours, as the grid paints it.
@immutable
final class LedgerAlbumColors {
  const LedgerAlbumColors(this.base, this.accent);
  final Color base;
  final Color accent;

  static LedgerAlbumColors lerp(LedgerAlbumColors a, LedgerAlbumColors b, double t) =>
      LedgerAlbumColors(Color.lerp(a.base, b.base, t)!, Color.lerp(a.accent, b.accent, t)!);
}

/// Until its cover is read (or without one) an album wears a muted colour of
/// its own, picked from its key, so cells don't all wait in one grey.
LedgerAlbumColors fallbackLedgerColors(String key) {
  var hash = 0;
  for (final unit in key.codeUnits) {
    hash = (31 * hash + unit) & 0x7fffffff;
  }
  final hue = (hash % 360).toDouble();
  return LedgerAlbumColors(
    const HSLColor.fromAHSL(1, 0, 0.32, 0.42).withHue(hue).toColor(),
    const HSLColor.fromAHSL(1, 0, 0.42, 0.66).withHue((hue + 24) % 360).toColor(),
  );
}

/// The albums [cells] show, each once.
List<MemoryLedgerAlbum> ledgerAlbumsIn(List<MemoryGridCell> cells) {
  final seen = <String>{};
  return [
    for (final c in cells)
      for (final m in c.marks)
        if (seen.add(m.album.key)) m.album,
  ];
}

/// The albums' colours, read off their covers the way every Yoin card is
/// (Home's CoverPaletteCache), each springing in on the effects spring as
/// its read lands.
class LedgerColorBook extends ChangeNotifier {
  LedgerColorBook({required this.vsync, CoverPaletteCache? cache}) : _cache = cache ?? CoverPaletteCache.instance;

  final TickerProvider vsync;
  final CoverPaletteCache _cache;
  final Map<String, _ColorEntry> _entries = {};
  bool _disposed = false;

  LedgerAlbumColors colorsOf(MemoryLedgerAlbum album) {
    final fallback = fallbackLedgerColors(album.key);
    final e = _entries[album.key];
    final to = e?.to;
    if (e == null || to == null) return fallback;
    return LedgerAlbumColors.lerp(fallback, to, e.t.value.clamp(0.0, 1.0));
  }

  /// Reads [album]'s cover at [url] once.
  void request(MemoryLedgerAlbum album, String? url) {
    if (url == null || _entries.containsKey(album.key)) return;
    final entry = _ColorEntry(SpringValue(vsync: vsync, initial: 1, spring: YoinSprings.defaultEffects));
    entry.t.animation.addListener(_notify);
    _entries[album.key] = entry;
    if (_cache.contains(url)) {
      final colors = _cache.peek(url);
      if (colors != null) entry.to = LedgerAlbumColors(colors.base, colors.accent);
      return;
    }
    final image = ResizeImage(NetworkImage(url), width: 200, height: 200);
    _cache.colorsFor(url, image).then((colors) {
      if (_disposed || colors == null) return;
      entry.to = LedgerAlbumColors(colors.base, colors.accent);
      entry.t.snapTo(0);
      entry.t.animateTo(1);
      _notify();
    });
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    for (final e in _entries.values) {
      e.t.dispose();
    }
    super.dispose();
  }
}

final class _ColorEntry {
  _ColorEntry(this.t);
  final SpringValue t;
  LedgerAlbumColors? to;
}

/// Where the grid sits: Home's card (compact) or the All Memories page.
enum MemoryGridPlacement { home, page }

/// The grid, for its scale. A week is 13×2 (half a year), a day 17×7 (a
/// column a week); a month is a row of 2×2 quads, the last six on Home and
/// every one on the page (in rows of four, with its name and count).
/// [onCellClick] gets the tapped cell's index in [MemoryGrid.cells];
/// [selected] is outlined.
class MemoryGridView extends StatelessWidget {
  const MemoryGridView({
    super.key,
    required this.grid,
    required this.colors,
    required this.placement,
    this.selected,
    this.onCellClick,
  });

  final MemoryGrid grid;
  final LedgerColorBook colors;
  final MemoryGridPlacement placement;
  final int? selected;
  final ValueChanged<int>? onCellClick;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final home = placement == MemoryGridPlacement.home;
    final cells = grid.scale == MemoryGridScale.month && home && grid.cells.length > 6
        ? grid.cells.sublist(grid.cells.length - 6)
        : grid.cells;
    final offset = grid.cells.length - cells.length;
    final faint = scheme.onSurface.withValues(alpha: 0.07);
    return ListenableBuilder(
      listenable: colors,
      builder: (context, _) => switch (grid.scale) {
        MemoryGridScale.month => _MonthQuads(
          cells: cells,
          offset: offset,
          colors: colors,
          faint: faint,
          outline: scheme.primary,
          columns: home ? 6 : 4,
          selected: selected,
          onCellClick: onCellClick,
          captions: !home,
        ),
        MemoryGridScale.week || MemoryGridScale.day => _CellWall(
          cells: cells,
          week: grid.scale == MemoryGridScale.week,
          colors: colors,
          faint: faint,
          outline: scheme.primary,
          selected: selected,
          onCellClick: onCellClick,
        ),
      },
    );
  }
}

void _drawSplit(Canvas canvas, Color first, Color second, Rect rect, double radius) {
  canvas.save();
  canvas.clipRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)));
  canvas.drawRect(rect, Paint()..color = second);
  final triangle = Path()
    ..moveTo(rect.left, rect.top)
    ..lineTo(rect.right, rect.top)
    ..lineTo(rect.left, rect.bottom)
    ..close();
  canvas.drawPath(triangle, Paint()..color = first);
  canvas.restore();
}

void _drawMark(Canvas canvas, MemoryGridMark mark, Rect rect, double radius, LedgerColorBook colors) {
  final c = colors.colorsOf(mark.album);
  if (mark.wrote) {
    _drawSplit(canvas, c.base, c.accent, rect, radius);
  } else {
    canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)), Paint()..color = c.base);
  }
}

/// A week's or day's cell: nothing, one album (split if written about), or two spliced.
void _drawCell(Canvas canvas, MemoryGridCell cell, Rect rect, double radius, LedgerColorBook colors, Color faint) {
  if (cell.future) return;
  final marks = cell.marks;
  if (marks.isEmpty) {
    canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)), Paint()..color = faint);
  } else if (marks.length == 1) {
    _drawMark(canvas, marks.first, rect, radius, colors);
  } else {
    _drawSplit(canvas, colors.colorsOf(marks[0].album).base, colors.colorsOf(marks[1].album).base, rect, radius);
  }
}

class _CellWall extends StatelessWidget {
  const _CellWall({
    required this.cells,
    required this.week,
    required this.colors,
    required this.faint,
    required this.outline,
    required this.selected,
    required this.onCellClick,
  });

  final List<MemoryGridCell> cells;
  final bool week;
  final LedgerColorBook colors;
  final Color faint;
  final Color outline;
  final int? selected;
  final ValueChanged<int>? onCellClick;

  int get _columns => week ? 13 : 17;
  int get _rows => week ? 2 : 7;
  double get _gap => week ? MemoryLedgerTokens.weekGap : MemoryLedgerTokens.dayGap;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, c) {
      final cell = (c.maxWidth - _gap * (_columns - 1)) / _columns;
      final height = cell * _rows + _gap * (_rows - 1);
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapUp: onCellClick == null
            ? null
            : (d) {
                final column = (d.localPosition.dx / (cell + _gap)).floor().clamp(0, _columns - 1);
                final row = (d.localPosition.dy / (cell + _gap)).floor().clamp(0, _rows - 1);
                final index = week ? row * _columns + column : column * _rows + row;
                if (index < cells.length && !cells[index].isEmpty) onCellClick!(index);
              },
        child: CustomPaint(size: Size(c.maxWidth, height), painter: _WallPainter(this, cell)),
      );
    },
  );
}

class _WallPainter extends CustomPainter {
  _WallPainter(this.wall, this.cell);
  final _CellWall wall;
  final double cell;

  @override
  void paint(Canvas canvas, Size size) {
    final gap = wall._gap;
    final radius = cell * 0.18;
    for (var i = 0; i < wall.cells.length; i++) {
      final column = wall.week ? i % wall._columns : i ~/ wall._rows;
      final row = wall.week ? i ~/ wall._columns : i % wall._rows;
      final rect = Rect.fromLTWH(column * (cell + gap), row * (cell + gap), cell, cell);
      _drawCell(canvas, wall.cells[i], rect, radius, wall.colors, wall.faint);
      if (i == wall.selected) {
        const stroke = 2.0;
        canvas.drawRRect(
          RRect.fromRectAndRadius(rect.inflate(stroke), Radius.circular(radius + stroke)),
          Paint()
            ..color = wall.outline
            ..style = PaintingStyle.stroke
            ..strokeWidth = stroke,
        );
      }
    }
  }

  @override
  bool shouldRepaint(_WallPainter old) => true;
}

class _MonthQuads extends StatelessWidget {
  const _MonthQuads({
    required this.cells,
    required this.offset,
    required this.colors,
    required this.faint,
    required this.outline,
    required this.columns,
    required this.selected,
    required this.onCellClick,
    required this.captions,
  });

  final List<MemoryGridCell> cells;
  final int offset;
  final LedgerColorBook colors;
  final Color faint;
  final Color outline;
  final int columns;
  final int? selected;
  final ValueChanged<int>? onCellClick;
  final bool captions;

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final label = theme.textTheme.labelSmall!;
    final rows = <Widget>[];
    for (var start = 0; start < cells.length; start += columns) {
      final row = cells.sublist(start, math.min(start + columns, cells.length));
      rows.add(
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (var j = 0; j < columns; j++) ...[
              if (j > 0) SizedBox(width: columns > 4 ? 8 : 12),
              Expanded(
                child: j >= row.length
                    // A short last row keeps its cells the size of the rest.
                    ? const SizedBox.shrink()
                    : _quad(context, row[j], offset + start + j, strings, label, theme.colorScheme),
              ),
            ],
          ],
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < rows.length; i++) ...[if (i > 0) const SizedBox(height: 12), rows[i]],
      ],
    );
  }

  Widget _quad(
    BuildContext context,
    MemoryGridCell cell,
    int index,
    MemoryLedgerStrings strings,
    TextStyle label,
    ColorScheme scheme,
  ) {
    final tap = onCellClick != null && !cell.isEmpty ? () => onCellClick!(index) : null;
    return Semantics(
      label: strings.monthLong(cell.start),
      button: tap != null,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: tap,
        child: Column(
          children: [
            AspectRatio(aspectRatio: 1, child: CustomPaint(painter: _QuadPainter(this, cell, index == selected))),
            Text(
              strings.monthShort(cell.start),
              style: label.copyWith(color: scheme.onSurfaceVariant),
              textAlign: TextAlign.center,
              maxLines: 1,
            ),
            if (captions)
              Text(
                cell.entries > 0 ? strings.monthCount(cell.entries) : '—',
                style: label.copyWith(color: scheme.outline),
                textAlign: TextAlign.center,
                maxLines: 1,
              ),
          ],
        ),
      ),
    );
  }
}

class _QuadPainter extends CustomPainter {
  _QuadPainter(this.quads, this.cell, this.outlined);
  final _MonthQuads quads;
  final MemoryGridCell cell;
  final bool outlined;

  @override
  void paint(Canvas canvas, Size size) {
    const gap = MemoryLedgerTokens.monthQuadGap;
    final quad = (size.width - gap) / 2;
    final radius = quad * 0.16;
    for (var i = 0; i < 4; i++) {
      final rect = Rect.fromLTWH((i % 2) * (quad + gap), (i ~/ 2) * (quad + gap), quad, quad);
      if (i < cell.marks.length) {
        _drawMark(canvas, cell.marks[i], rect, radius, quads.colors);
      } else {
        canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(radius)), Paint()..color = quads.faint);
      }
    }
    if (outlined) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(Offset.zero & size, Radius.circular(radius * 1.6)),
        Paint()
          ..color = quads.outline
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2,
      );
    }
  }

  @override
  bool shouldRepaint(_QuadPainter old) => true;
}
