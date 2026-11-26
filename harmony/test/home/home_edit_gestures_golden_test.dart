// The Dart gesture machine (lib/home/home_edit_gestures.dart) against the shared golden vectors, which
// :shared Kotlin writes and checks (HomeEditGesturesGoldenTest / HomeEditGesturesGoldenReplayTest).
// `machine` feeds one finger's events (a timer "next" fires the one pending) and checks each answer, the
// state after it and the next timer; `timeline` drives HomeEditGestureTimeline (samples at their own stamps,
// the clock moved on, a wait timing out, a late wake's grace) and checks every action stepped. Floats compare
// within 1e-4. A failure here means the port drifted: regenerate the file only from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_edit_gestures.dart';
import 'package:yoin/home/home_section.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/home-edit-gestures.json';

void main() {
  final root = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, Object?>;

  test('should_matchTheConstants_when_readFromTheGoldenFile', () {
    final c = root['constants']! as Map<String, Object?>;
    expect(c['bodyHoldMinMs'], HomeEditGestureTokens.bodyHoldMinMs);
    expect(c['kickTap'], HomeEditGestureTokens.kickTap);
    expect(c['lateTimerMs'], HomeEditGestureTokens.lateTimerMs);
    expect(c['stalledInputGraceMs'], HomeEditGestureTokens.stalledInputGraceMs);
  });

  var count = 0;
  group('machine', () {
    for (final raw in root['machine']! as List) {
      final v = raw as Map<String, Object?>;
      count++;
      test(v['name']! as String, () {
        final machine = HomeEditGestureMachine(longPressMs: v['longPressMs']! as int, slopPx: _d(v['slopPx']));
        final events = v['events']! as List;
        final steps = v['steps']! as List;
        for (var i = 0; i < events.length; i++) {
          final actions = machine.handle(_event(events[i] as Map<String, Object?>, machine));
          final want = steps[i] as Map<String, Object?>;
          final got = {
            'actions': _actionsJson(actions),
            'state': _stateName(machine.state),
            'pending': _timerJson(machine.pendingTimer),
          };
          expect(
            _close(got, want),
            isTrue,
            reason: 'step $i: expected ${jsonEncode(want)}\nbut was ${jsonEncode(got)}',
          );
        }
      });
    }
  });

  group('timeline', () {
    for (final raw in root['timeline']! as List) {
      final v = raw as Map<String, Object?>;
      count++;
      test(v['name']! as String, () {
        final machine = HomeEditGestureMachine(longPressMs: v['longPressMs']! as int, slopPx: _d(v['slopPx']));
        final flinging = v['flinging'] == true;
        final stepped = <HomeEditGestureAction>[];
        final timeline = HomeEditGestureTimeline(
          machine,
          flinging: () => flinging,
          step: (e) {
            final a = machine.handle(e);
            stepped.addAll(a);
            return a;
          },
        )..start(v['start']! as int);
        final ops = v['ops']! as List;
        final results = v['results']! as List;
        for (var i = 0; i < ops.length; i++) {
          final o = ops[i] as Map<String, Object?>;
          stepped.clear();
          int? grace;
          switch (o['op']) {
            case 'step':
              stepped.addAll(machine.handle(_event(o['event']! as Map<String, Object?>, machine)));
            case 'sample':
              timeline.sample(_event(o['event']! as Map<String, Object?>, machine));
            case 'advance':
              timeline.advanceTo(o['t']! as int);
            case 'timeout':
              timeline.timeout();
            case 'grace':
              grace = timeline.graceAfter(o['wall']! as int);
            default:
              fail('unknown op ${o['op']}');
          }
          final got = <String, Object?>{
            'actions': _actionsJson(stepped),
            'state': _stateName(machine.state),
            'now': timeline.now,
            'untilDue': timeline.untilDue(),
            'grace': ?grace,
          };
          final want = results[i] as Map<String, Object?>;
          expect(_close(got, want), isTrue, reason: 'op $i: expected ${jsonEncode(want)}\nbut was ${jsonEncode(got)}');
        }
      });
    }
  });

  test('should_replayEveryVector_when_theFileIsRead', () {
    expect(count, (root['machine']! as List).length + (root['timeline']! as List).length);
    // ignore: avoid_print
    print('home-edit-gestures.json: $count vectors');
  });
}

double _d(Object? v) => (v! as num).toDouble();

Offset _point(Object? v) {
  final l = v! as List;
  return Offset(_d(l[0]), _d(l[1]));
}

Size _size(Object? v) {
  if (v == null) return Size.zero;
  final l = v as List;
  return Size(_d(l[0]), _d(l[1]));
}

HomeSection _section(Object? id) => HomeSection.byId(id! as String)!;

HomeEditHit _hit(Map<String, Object?> o) => switch (o['kind']) {
  'Excluded' => const HomeEditHitExcluded(),
  'Handle' => HomeEditHitHandle(_section(o['section']), _point(o['local']), _size(o['size'])),
  'Block' => HomeEditHitBlock(_section(o['section']), _point(o['local']), _size(o['size'])),
  'Blank' => HomeEditHitBlank(o['nearest'] == null ? null : _section(o['nearest'])),
  final k => throw StateError('unknown hit $k'),
};

HomeEditTimer _timerKind(String name) => switch (name) {
  'HalfT' => HomeEditTimer.halfT,
  'LongPress' => HomeEditTimer.longPress,
  'BodyHold' => HomeEditTimer.bodyHold,
  'BlankHold' => HomeEditTimer.blankHold,
  _ => throw StateError('unknown timer $name'),
};

String _timerName(HomeEditTimer kind) => switch (kind) {
  HomeEditTimer.halfT => 'HalfT',
  HomeEditTimer.longPress => 'LongPress',
  HomeEditTimer.bodyHold => 'BodyHold',
  HomeEditTimer.blankHold => 'BlankHold',
};

String _stateName(HomeEditGestureState s) => '${s.name[0].toUpperCase()}${s.name.substring(1)}';

HomeEditGestureEvent _event(Map<String, Object?> o, HomeEditGestureMachine machine) {
  int t() => o['t']! as int;
  return switch (o['e']) {
    'down' => HomeEditDown(
      hit: _hit(o['hit']! as Map<String, Object?>),
      position: _point(o['at']),
      editing: o['editing'] == true,
      flingInProgress: o['fling'] == true,
      t: t(),
      secondaryClick: o['secondary'] == true,
    ),
    'move' => HomeEditMove(_point(o['at']), t()),
    'up' => HomeEditUp(t()),
    'cancel' => HomeEditCancel(t()),
    'timer' => HomeEditTimerFired(
      o['kind'] == 'next' ? machine.pendingTimer!.kind : _timerKind(o['kind']! as String),
      flinging: o['flinging'] == true,
    ),
    'editEnded' => const HomeEditEditEnded(),
    'carryEnded' => const HomeEditCarryEnded(),
    'other' => const HomeEditOtherPointer(),
    'pinchDown' => HomeEditPinchDown(_d(o['span'])),
    'pinchMove' => HomeEditPinchMove(_d(o['span'])),
    'pinchUp' => const HomeEditPinchUp(),
    final e => throw StateError('unknown event $e'),
  };
}

List<Object?> _pointJson(Offset p) => [p.dx, p.dy];

List<Object?> _actionsJson(List<HomeEditGestureAction> actions) => [for (final a in actions) _actionJson(a)];

Map<String, Object?> _actionJson(HomeEditGestureAction a) => switch (a) {
  HomeEditAbandon() => {'a': 'Abandon'},
  HomeEditStartCharge(:final section, :final local) => {
    'a': 'StartCharge',
    'section': section.id,
    'local': _pointJson(local),
  },
  HomeEditReleaseCharge() => {'a': 'ReleaseCharge'},
  HomeEditLongPressHaptic() => {'a': 'LongPressHaptic'},
  HomeEditDragStartHaptic() => {'a': 'DragStartHaptic'},
  HomeEditEnter(:final origin, :final lifted) => {'a': 'Enter', 'origin': origin?.id, 'lifted': lifted},
  HomeEditLatchPlate(:final section, :final local, :final size) => {
    'a': 'LatchPlate',
    'section': section.id,
    'local': _pointJson(local),
    'size': [size.width, size.height],
  },
  HomeEditLift(:final section, :final local) => {'a': 'Lift', 'section': section.id, 'local': _pointJson(local)},
  HomeEditDropLift(:final kick) => {'a': 'DropLift', 'kick': kick},
  HomeEditOpenPlaceholders() => {'a': 'OpenPlaceholders'},
  HomeEditStartCarry(:final y, :final t) => {'a': 'StartCarry', 'y': y, 't': t},
  HomeEditCarryMove(:final y, :final t) => {'a': 'CarryMove', 'y': y, 't': t},
  HomeEditCarryRelease(:final cancelled, :final t) => {'a': 'CarryRelease', 'cancelled': cancelled, 't': t},
  HomeEditPinchStart() => {'a': 'PinchStart'},
  HomeEditPinchBy(:final scale) => {'a': 'PinchBy', 'scale': scale},
  HomeEditPinchEnd() => {'a': 'PinchEnd'},
  HomeEditBlockTap(:final section, :final local) => {
    'a': 'BlockTap',
    'section': section.id,
    'local': local == null ? null : _pointJson(local),
  },
  HomeEditExitBlank() => {'a': 'ExitBlank'},
  HomeEditTouch() => {'a': 'Touch'},
  HomeEditPointerDown(:final down) => {'a': 'PointerDown', 'down': down},
  HomeEditConsumeRest() => {'a': 'ConsumeRest'},
  HomeEditConsumeOther() => {'a': 'ConsumeOther'},
};

Object? _timerJson(HomeEditTimerAt? t) => t == null ? null : {'kind': _timerName(t.kind), 'atMs': t.atMs};

/// Deep JSON equality with numbers within 1e-4 (the file rounds its floats to 1e-4).
bool _close(Object? a, Object? b) {
  if (a is num && b is num) return (a - b).abs() <= 1e-4 + 1e-9;
  if (a is Map && b is Map) {
    if (a.length != b.length) return false;
    for (final e in a.entries) {
      if (!b.containsKey(e.key) || !_close(e.value, b[e.key])) return false;
    }
    return true;
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!_close(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}
