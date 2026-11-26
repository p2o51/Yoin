// The Dart port of Home edit's drag (lib/data/home/home_drag_rules.dart, home_drag_core.dart, and the span
// and pinch rules of home_edit_rules.dart) against the shared golden vectors, which :shared Kotlin writes
// and checks (HomeDragGoldenTest / HomeDragGoldenReplayTest). Every vector is answered again from its own
// inputs, section by section, as HomeDragGolden.outputs does; the timelines run HomeDragCore on the fixed
// row model of HomeDragTimelines.kt, rebuilt here exactly. Floats compare within 1e-4 (the file's
// rounding). A failure here means the port drifted: regenerate the file only from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/home/home_catalog.dart';
import 'package:yoin/data/home/home_drag_core.dart';
import 'package:yoin/data/home/home_drag_rules.dart';
import 'package:yoin/data/home/home_edit_rules.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/home-drag.json';

const _sections = [
  'rel',
  'pairSide',
  'inOwnRow',
  'rowPart',
  'pairZone',
  'sideAllowed',
  'laneOf',
  'placeBeside',
  'insertRow',
  'swapRow',
  'swapDir',
  'sideStep',
  'rescaleGrabX',
  'laneWidth',
  'edge',
  'edgeGuard',
  'exitKeepsDrop',
  'layoutFollows',
  'spanAxis',
  'spanWidth',
  'spanTarget',
  'spanCommit',
  'spanKey',
  'pinch',
  'timelines',
];

typedef _Json = Map<String, Object?>;

double _f(Object? v) => switch (v) {
  'NaN' => double.nan,
  'Infinity' => double.infinity,
  '-Infinity' => double.negativeInfinity,
  num n => n.toDouble(),
  _ => throw ArgumentError('not a number: $v'),
};

int _i(Object? v) => (v! as num).toInt();

_Json _o(Object? v) => (v! as Map).cast<String, Object?>();

HomeSection _s(Object? id) => HomeSection.byId(id! as String) ?? (throw ArgumentError('unknown section $id'));

List<HomeSection> _ids(Object? v) => [for (final id in v! as List) _s(id)];

Map<HomeSection, HomeSection> _pairs(Object? v) => {
  for (final p in v! as List) _s((p as List)[0]): _s(p[1]),
};

HomeFeedRow _row(Object? ids) {
  final l = (ids! as List).cast<String>();
  return l.length == 1 ? HomeFeedRowFull(_s(l[0])) : HomeFeedRowHalves(_s(l[0]), _s(l[1]));
}

DragRowBand _band(Object? v) {
  final o = _o(v);
  return DragRowBand(_row(o['row']), _f(o['top']), _f(o['bottom']));
}

HomeDragLane? _lane(Object? name) => switch (name) {
  null => null,
  'Full' => HomeDragLane.full,
  'Left' => HomeDragLane.left,
  'Right' => HomeDragLane.right,
  _ => throw ArgumentError('unknown lane $name'),
};

String? _laneName(HomeDragLane? lane) => switch (lane) {
  null => null,
  HomeDragLane.full => 'Full',
  HomeDragLane.left => 'Left',
  HomeDragLane.right => 'Right',
};

HomeSpan _span(Object? name) => switch (name) {
  'Full' => HomeSpan.full,
  'LeftHalf' => HomeSpan.leftHalf,
  'RightHalf' => HomeSpan.rightHalf,
  _ => throw ArgumentError('unknown span $name'),
};

String? _commit(HomeSpanCommit? c) => switch (c) {
  null => null,
  HomeSpanCommit.none => 'None',
  HomeSpanCommit.toHalf => 'ToHalf',
  HomeSpanCommit.toFull => 'ToFull',
};

HomeEditExitReason _reason(Object? name) => switch (name) {
  'Done' => HomeEditExitReason.done,
  'Back' => HomeEditExitReason.back,
  'Blank' => HomeEditExitReason.blank,
  'Programmatic' => HomeEditExitReason.programmatic,
  _ => throw ArgumentError('unknown reason $name'),
};

_Json _arrangementJson(DragArrangement a) => {
  'ids': [for (final s in a.ids) s.id],
  'pairs': [
    for (final e in a.pairs.entries) [e.key.id, e.value.id],
  ],
};

_Json _stepJson(DragSideStep step) => switch (step) {
  DragSideStepVertical() => {'kind': 'Vertical'},
  DragSideStepStay() => {'kind': 'Stay'},
  DragSideStepArrange(:final arrangement, :final side) => {
    'kind': 'Arrange',
    'arrangement': _arrangementJson(arrangement),
    'side': _laneName(side),
  },
};

/// What HomeDragGolden.outputs answers for [v] in [section].
_Json _outputs(String section, _Json v, Map<String, _Json> layouts) {
  switch (section) {
    case 'rel':
      return {'rel': dragRel(_f(v['fingerX']), _f(v['contentLeft']), _f(v['contentWidth']))};
    case 'pairSide':
      return {'next': _laneName(dragPairSide(_lane(v['side'])!, _f(v['rel'])))};
    case 'inOwnRow':
      return {'in': dragInOwnRow(_f(v['fingerY']), _band(v['band']), _f(v['bufferPx']))};
    case 'rowPart':
      return {
        'part': switch (dragRowPart(_f(v['fingerY']), _band(v['band']))) {
          DragRowPart.above => 'Above',
          DragRowPart.middle => 'Middle',
          DragRowPart.below => 'Below',
        },
      };
    case 'pairZone':
      return {'zone': _laneName(dragPairZone(_f(v['rel'])))};
    case 'sideAllowed':
      return {'allowed': dragSideAllowed(_row(v['row']), _s(v['dragged']))};
    case 'laneOf':
      final rows = [for (final r in v['rows']! as List) _row(r)];
      final s = _s(v['section']);
      return {'lane': _laneName(dragLaneOf(s, rows)), 'rowIndex': dragRowIndexOf(s, rows)};
    case 'placeBeside':
      return {
        'arrangement': _arrangementJson(
          dragPlaceBeside(_ids(v['ids']), _pairs(v['pairs']), _s(v['dragged']), _s(v['target']), _lane(v['side'])!),
        ),
      };
    case 'insertRow':
      return {'arrangement': _arrangementJson(dragInsertRow(_ids(v['ids']), _pairs(v['pairs']), _s(v['dragged']), _i(v['gap'])))};
    case 'swapRow':
      return {
        'after': [
          for (final s in dragSwapRow(_ids(v['ids']), _pairs(v['pairs']), _s(v['dragged']), _i(v['dir']), v['twoColumn']! as bool))
            s.id,
        ],
      };
    case 'swapDir':
      return {
        'dir': dragSwapDir(
          _f(v['visualTop']),
          _band(v['slot']),
          v['prev'] is Map ? _band(v['prev']) : null,
          v['next'] is Map ? _band(v['next']) : null,
        ),
      };
    case 'sideStep':
      final layout = layouts[v['layout']] ?? (throw ArgumentError('unknown layout ${v['layout']}'));
      return _stepJson(
        dragSideStep(
          ids: _ids(layout['ids']),
          pairs: _pairs(layout['pairs']),
          dragged: _s(layout['dragged']),
          side: _lane(layout['side']),
          rel: _f(v['rel']),
          fingerY: _f(v['fingerY']),
          bands: [for (final b in layout['bands']! as List) _band(b)],
          bufferPx: _f(layout['bufferPx']),
          pairing: v['pairing']! as bool,
        ),
      );
    case 'rescaleGrabX':
      return {'rescaled': dragRescaleGrabX(_f(v['grabX']), _f(v['oldWidth']), _f(v['newWidth']))};
    case 'laneWidth':
      return {'width': dragLaneWidth(_lane(v['lane'])!, _f(v['contentWidth']), _f(v['gap']))};
    case 'edge':
      final (y, top, bottom, edge) = (_f(v['y']), _f(v['top']), _f(v['bottom']), _f(v['edgePx']));
      return {
        'edge': dragEdgeOf(y, top, bottom, edge),
        'speed': dragAutoScrollSpeed(y, top, bottom, edge, _f(v['minPx']), _f(v['maxPx'])),
      };
    case 'edgeGuard':
      return {
        'holds': dragEdgeGuardHolds(
          _i(v['startEdge']),
          _f(v['startY']),
          _f(v['y']),
          _f(v['heldMs']),
          _f(v['pushPx']),
          _f(v['dwellMs']),
        ),
      };
    case 'exitKeepsDrop':
      return {'keeps': dragExitKeepsDrop(_reason(v['reason']))};
    case 'layoutFollows':
      return {
        'follows': dragLayoutFollows(_ids(v['visible']), [for (final r in v['rows']! as List) _row(r)], _s(v['dragged'])),
      };
    case 'spanAxis':
      return {
        'axis': switch (homeHandleAxis(_f(v['dx']), _f(v['dy']), _f(v['slopPx']), v['horizontal']! as bool)) {
          null => null,
          HomeHandleAxis.horizontal => 'Horizontal',
          HomeHandleAxis.vertical => 'Vertical',
        },
      };
    case 'spanWidth':
      return {
        'widthPx': homeSpanDragWidth(_span(v['from']), _f(v['startPx']), _f(v['dx']), _f(v['halfPx']), _f(v['fullPx'])),
      };
    case 'spanTarget':
      return {
        'target': switch (homeSpanTarget(_f(v['widthPx']), _f(v['halfPx']), _f(v['fullPx']))) {
          HomeSpan.full => 'Full',
          HomeSpan.leftHalf => 'LeftHalf',
          HomeSpan.rightHalf => 'RightHalf',
        },
      };
    case 'spanCommit':
      return {'commit': _commit(homeSpanCommit(_span(v['from']), _span(v['target']), v['hasNext']! as bool))};
    case 'spanKey':
      return {'commit': _commit(homeSpanKey(_span(v['from']), v['left']! as bool, v['hasNext']! as bool))};
    case 'pinch':
      final fromOn = v['fromOn']! as bool;
      final progress = homeListPinchProgress(fromOn, _f(v['scale']));
      return {'progress': progress, 'settlesOn': homeListSettlesOn(fromOn, progress)};
    case 'timelines':
      return _timeline(v);
  }
  throw ArgumentError('unknown section $section');
}

/// HomeDragTimelines.run: [HomeDragCore] frame by frame on the fixed layout model — rows rowHeight px tall,
/// gap apart, the first at y = top minus the scroll so far; a pair is one row; the content from x = 0
/// across contentWidth, a right half at (W − columnGap) / 2 + columnGap. The list shows the arrangement it
/// last laid out: the one before the lift, then after every frame the live one (placed again) unless the
/// frame says `"laidOut": false`. A scroll consumes what it asks, held between 0 and scrollMax.
_Json _timeline(_Json v) {
  final density = _f(v['density']);
  final twoColumn = v['twoColumn'] == true;
  final order = _ids(v['order']);
  final pairs = twoColumn
      ? {
          for (final e in _pairs(v['pairs']).entries)
            if (order.contains(e.key) && order.contains(e.value)) e.key: e.value,
        }
      : <HomeSection, HomeSection>{};
  final rowHeight = _f(v['rowHeight']);
  final gap = _f(v['gap']);
  final top = _f(v['top']);
  final width = _f(v['contentWidth']);
  final columnGap = _f(v['columnGap']);
  final areaTop = _f(v['areaTop']);
  final areaBottom = _f(v['areaBottom']);
  final scrollMax = _f(v['scrollMax']);
  final dragged = _s(v['dragged']);
  final finger = [for (final n in v['finger']! as List) _f(n)];

  final core = HomeDragCore(density);
  var scrolled = 0.0;
  List<DragItemBand> bands(DragArrangement a) {
    var y = top - scrolled;
    return [
      for (final row in dragRows(a.ids, a.pairs, twoColumn))
        () {
          final band = DragItemBand(row.first, y, y + rowHeight);
          y += rowHeight + gap;
          return band;
        }(),
    ];
  }

  HomeDragView view(DragArrangement a) => HomeDragView(
    visible: bands(a),
    contentLeft: 0,
    contentWidth: width,
    areaTop: areaTop,
    areaBottom: areaBottom,
    twoColumn: twoColumn,
  );

  final before = DragArrangement(order, pairs);
  final rows = dragRows(before.ids, before.pairs, twoColumn);
  final draggedRow = rows.firstWhere((r) => r.sections.contains(dragged));
  final band = bands(before).firstWhere((b) => b.key == draggedRow.first);
  final left = dragLaneOf(dragged, rows) == HomeDragLane.right ? (width - columnGap) / 2 + columnGap : 0.0;
  core.start(
    dragged: dragged,
    before: before,
    twoColumn: twoColumn,
    fingerX: finger[0],
    fingerY: finger[1],
    blockLeft: left,
    blockTop: band.top,
    areaTop: areaTop,
    areaBottom: areaBottom,
  );
  var shown = before;
  core.placed();
  final started = {
    'side': _laneName(core.side),
    'grab': [core.grabX, core.grabY],
  };

  final decisions = <_Json>[];
  for (final e in v['frames']! as List) {
    final f = _o(e);
    final m = f['move'];
    if (m is List) core.move(_f(m[0]), _f(m[1]));
    final r = core.frame(_f(f['dt']), view(shown));
    decisions.add(switch (r) {
      HomeDragFrameNone() => {'kind': 'None'},
      HomeDragFrameStep(:final arrangement, :final side) => {
        'kind': 'Step',
        'arrangement': _arrangementJson(arrangement),
        'side': _laneName(side),
      },
      HomeDragFrameScroll(:final px) => () {
        final lo = -scrolled, hi = scrollMax - scrolled;
        final consumed = px < lo ? lo : (px > hi ? hi : px);
        scrolled += consumed;
        core.scrolled(consumed);
        return <String, Object?>{'kind': 'Scroll', 'px': px, 'consumed': consumed};
      }(),
    });
    if (f['laidOut'] != false) {
      shown = core.arrangement ?? shown;
      core.placed();
    }
  }

  final end = _o(v['end']);
  final DragArrangement? write;
  switch (end['kind']) {
    case 'release':
      final m = end['move'];
      if (m is List) core.move(_f(m[0]), _f(m[1]));
      write = core.release(cancelled: end['cancelled'] == true, view: view(shown));
    case 'exit':
      write = core.exit(_reason(end['reason']));
    case 'cancel':
      write = null;
    default:
      throw ArgumentError('unknown end ${end['kind']}');
  }
  final arrangement = core.arrangement;
  final result = {
    'write': write == null ? null : _arrangementJson(write),
    'arrangement': arrangement == null ? null : _arrangementJson(arrangement),
    'side': _laneName(core.side),
    'scrolled': scrolled,
  };
  core.end();
  return {'started': started, 'decisions': decisions, 'result': result};
}

/// [expected] (decoded JSON) and [actual] agree: numbers within 1e-4 (see below), NaN with NaN, the rest
/// exactly.
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
  // 1e-4 plus a hair: a value written rounded to 1e-4 and recomputed in 64-bit (Kotlin's 32-bit Float
  // rounds the same sum onto the line) can land a rounding step past the tolerance.
  if (expected is num && actual is num) return (expected - actual).abs() <= 1e-4 + 1e-9;
  return expected == actual;
}

void main() {
  final root = _o(jsonDecode(File(_goldenPath).readAsStringSync()));
  final layouts = _o(root['layouts']).map((k, v) => MapEntry(k, _o(v)));

  test('should_matchTheKotlinTokens_when_readFromTheGoldenConstants', () {
    final c = _o(root['constants']);
    expect(_close(c, {
      'rowBuffer': HomeDragRuleTokens.rowBuffer,
      'swapToLeft': HomeDragRuleTokens.swapToLeft,
      'swapToRight': HomeDragRuleTokens.swapToRight,
      'rowGapZone': HomeDragRuleTokens.rowGapZone,
      'pairLeft': HomeDragRuleTokens.pairLeft,
      'pairRight': HomeDragRuleTokens.pairRight,
      'autoScrollEdge': HomeDragTokens.autoScrollEdge,
      'autoScrollMin': HomeDragTokens.autoScrollMin,
      'autoScrollMax': HomeDragTokens.autoScrollMax,
      'edgeStartPush': HomeDragTokens.edgeStartPush,
      'edgeStartDwellMs': HomeDragTokens.edgeStartDwellMs,
      'changeTravel': HomeDragTokens.changeTravel,
      'maxFrameSeconds': HomeDragTokens.maxFrameSeconds,
      'spanColumnGap': HomeSpanRules.columnGap,
      'spanUnderHalf': HomeSpanRules.underHalf,
      'pinchInFull': HomeListPinchRules.pinchInFull,
      'pinchOutFull': HomeListPinchRules.pinchOutFull,
      'pinchCommit': HomeListPinchRules.pinchCommit,
    }), isTrue, reason: '$c');
  });

  for (final section in _sections) {
    final vectors = (root[section]! as List).cast<Map<String, Object?>>();
    group(section, () {
      for (final v in vectors) {
        test('${v['name']}', () {
          final answer = _outputs(section, v, layouts);
          for (final e in answer.entries) {
            expect(
              _close(v[e.key], e.value),
              isTrue,
              reason: '${e.key}: expected ${jsonEncode(v[e.key])} but was ${e.value}',
            );
          }
        });
      }
    });
  }
}
