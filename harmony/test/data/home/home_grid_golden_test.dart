// The Dart port of two-column Home's grid (lib/data/home/home_grid_rules.dart) against the shared golden
// vectors, which :shared Kotlin writes and checks (HomeGridGoldenTest / HomeGridGoldenReplayTest). Every
// vector is answered again from its own inputs, section by section, as HomeGridGolden.outputs does; floats
// compare within 1e-4 (the file's rounding). A failure here means the port drifted: regenerate the file only
// from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/home/home_catalog.dart';
import 'package:yoin/data/home/home_edit_rules.dart';
import 'package:yoin/data/home/home_grid_rules.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/home-grid.json';

const _sections = [
  'twoColumn',
  'heights',
  'columns',
  'pack',
  'activitySpans',
  'cellX',
  'unitsNearest',
  'reseats',
  'spanInfo',
  'rowCellWidths',
  'shelfGeometry',
  'shelfPages',
  'shelfScroll',
  'shelfRelease',
  'shelfTide',
];

double _f(Object? v) => switch (v) {
  'NaN' => double.nan,
  'Infinity' => double.infinity,
  '-Infinity' => double.negativeInfinity,
  num n => n.toDouble(),
  _ => throw ArgumentError('not a number: $v'),
};

int _i(Object? v) => (v! as num).toInt();

HomeSection _s(Object? id) => HomeSection.byId(id! as String) ?? (throw ArgumentError('unknown section $id'));

HomeFeedRow _row(Object? ids) {
  final l = (ids! as List).cast<String>();
  return l.length == 1 ? HomeFeedRowFull(_s(l[0])) : HomeFeedRowHalves(_s(l[0]), _s(l[1]));
}

ShelfPageGeometry _geometry(Map<String, Object?> o) =>
    ShelfPageGeometry(pagePx: _f(o['pagePx']), cardPx: _f(o['cardPx']), perPage: _i(o['perPage']), gapPx: _f(o['gapPx']));

Map<String, Object?> _geometryJson(ShelfPageGeometry g) => {
  'pagePx': g.pagePx,
  'cardPx': g.cardPx,
  'perPage': g.perPage,
  'gapPx': g.gapPx,
  'stridePx': g.stridePx,
};

/// What HomeGridGolden.outputs answers for [v] in [section].
Map<String, Object?> _outputs(String section, Map<String, Object?> v) {
  switch (section) {
    case 'twoColumn':
      return {'twoColumn': homeTwoColumn(_f(v['widthDp']), _f(v['heightDp']), v['tabletop']! as bool)};
    case 'heights':
      final units = _i(v['units']);
      return {
        'heightDp': homeGridHeightDp(units),
        'contentDp': homeGridContentDp(units),
        'trackRows': homeGridTrackRows(units),
        'trackRowDp': homeGridTrackRowDp(units, HomeGridTokens.trackRowGap),
      };
    case 'columns':
      final w = _f(v['widthDp']);
      return {
        'coverColumns': homeGridColumns(w, coverDp: homeGridCoverDp(HomeGridTokens.unit)),
        'coverColumnsAt120': homeGridColumns(w, coverDp: homeGridCoverDp(120)),
        'trackColumns': homeGridTrackColumns(w),
        'activityColumns': homeGridActivityColumns(w, HomeGridTokens.columnGap),
      };
    case 'pack':
      return {
        'cells': [
          for (final c in homeGridPack([for (final s in v['spans']! as List) _i(s)], _i(v['columns']), _i(v['rows'])))
            [c.index, c.row, c.column, c.span],
        ],
      };
    case 'activitySpans':
      return {'spans': homeGridActivitySpans(_i(v['count']), _i(v['columns']))};
    case 'cellX':
      final (left, width) = homeGridCellX(_i(v['column']), _i(v['span']), _i(v['columns']), _i(v['widthPx']), _i(v['gapPx']));
      return {
        'cell': [left, width],
      };
    case 'unitsNearest':
      return {'units': homeUnitsNearest(_f(v['heightPx']), HomeUnitRange(_i(v['first']), _i(v['last'])), _f(v['density']))};
    case 'reseats':
      return {'reseats': homeGridReseats(_i(v['last']), _i(v['now']))};
    case 'spanInfo':
      final info = homeSpanInfoOf(
        _s(v['id']),
        [for (final r in v['rows']! as List) _row(r)],
        [for (final id in v['enabledOrder']! as List) _s(id)],
        _f(v['fullPx']),
        _f(v['gapPx']),
      );
      return {
        'info': info == null
            ? null
            : {
                'span': switch (info.span) {
                  HomeSpan.full => 'Full',
                  HomeSpan.leftHalf => 'LeftHalf',
                  HomeSpan.rightHalf => 'RightHalf',
                },
                'currentPx': info.currentPx,
                'halfPx': info.halfPx,
                'fullPx': info.fullPx,
                'hasNext': info.hasNext,
              },
      };
    case 'rowCellWidths':
      final dragged = v['dragged'] as String?;
      return {
        'widths': homeRowCellWidths(
          _row(v['row']),
          _i(v['widthPx']),
          _i(v['gapPx']),
          dragged == null ? null : HomeSection.byId(dragged),
          _i(v['dragPx']),
        ),
      };
    case 'shelfGeometry':
      return {'geometry': _geometryJson(shelfPageGeometry(_f(v['pagePx']), _f(v['cardPx']), _f(v['minGapPx'])))};
    case 'shelfPages':
      final g = _geometry((v['geometry']! as Map).cast<String, Object?>());
      return {
        'pageCount': shelfPageCount(_i(v['cardCount']), g.perPage, _i(v['leadPages'])),
        'fillerPx': shelfFillerPx(_i(v['cardCount']), g),
        'cardWidthDp': homeShelfCardWidthDp(g.pagePx, g.perPage),
      };
    case 'shelfScroll':
      final g = _geometry((v['geometry']! as Map).cast<String, Object?>());
      final scroll = shelfScrollPx(_i(v['index']), _i(v['offsetPx']), g, _i(v['leadPages']));
      return {'scrollPx': scroll, 'offPagePx': shelfOffPagePx(scroll, g.stridePx)};
    case 'shelfRelease':
      return {
        'page': shelfReleasePage(
          _f(v['scrollPx']),
          _f(v['velocityPx']),
          _f(v['stridePx']),
          _i(v['startPage']),
          _i(v['pageCount']),
          _f(v['flingPx']),
        ),
      };
    case 'shelfTide':
      return {'reveal': shelfTideReveal(_f(v['offPagePx']), _f(v['revealPx']))};
  }
  throw ArgumentError('unknown section $section');
}

/// [expected] (decoded JSON) and [actual] agree: numbers within 1e-4 (see below), NaN with NaN, the rest exactly.
bool _close(Object? expected, Object? actual) {
  if (expected is Map && actual is Map) {
    return expected.length == actual.length && expected.keys.every((k) => actual.containsKey(k) && _close(expected[k], actual[k]));
  }
  if (expected is List && actual is List) {
    if (expected.length != actual.length) return false;
    for (var i = 0; i < expected.length; i++) {
      if (!_close(expected[i], actual[i])) return false;
    }
    return true;
  }
  if (expected == 'NaN' && actual is double) return actual.isNaN;
  // 1e-4 plus a hair: a value written rounded to 1e-4 and recomputed in 64-bit (Kotlin's 32-bit Float rounds
  // the same sum onto the line) can land a rounding step past the tolerance.
  if (expected is num && actual is num) return (expected - actual).abs() <= 1e-4 + 1e-9;
  return expected == actual;
}

void main() {
  final root = (jsonDecode(File(_goldenPath).readAsStringSync()) as Map).cast<String, Object?>();

  test('should_matchTheKotlinTokens_when_readFromTheGoldenConstants', () {
    final c = (root['constants']! as Map).cast<String, Object?>();
    expect(_close(c, {
      'unit': HomeGridTokens.unit,
      'gap': HomeGridTokens.gap,
      'titleRow': HomeGridTokens.titleRow,
      'overdrag': HomeGridTokens.overdrag,
      'captionBand': HomeGridTokens.captionBand,
      'columnGap': HomeGridTokens.columnGap,
      'trackRowGap': HomeGridTokens.trackRowGap,
      'trackColumnMin': HomeGridTokens.trackColumnMin,
      'activityColumnMin': HomeGridTokens.activityColumnMin,
      'twoColumnMinWidth': HomeTwoColumnTokens.minWidth,
      'twoColumnMinHeight': HomeTwoColumnTokens.minHeight,
      'twoColumnMinContent': HomeTwoColumnTokens.minContent,
      'pairGap': HomeSpanRules.columnGap,
      'shelfMinGap': HomeShelfPagerRules.minGap,
      'shelfFlingVelocity': HomeShelfPagerRules.flingVelocity,
    }), isTrue, reason: '$c');
  });

  for (final section in _sections) {
    final vectors = (root[section]! as List).cast<Map<String, Object?>>();
    group(section, () {
      for (final v in vectors) {
        test('${v['name']}', () {
          final answer = _outputs(section, v);
          for (final e in answer.entries) {
            expect(_close(v[e.key], e.value), isTrue, reason: '${e.key}: expected ${jsonEncode(v[e.key])} but was ${e.value}');
          }
        });
      }
    });
  }
}
