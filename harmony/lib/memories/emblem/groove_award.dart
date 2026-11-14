/// 唱片刻纹 · the award, frame-driven (Android GrooveAward.kt, from
/// twostate4.html's awardChans / setPending / awardRun / settleFrom / nod).
/// Each channel is a closed-form function of t taken from [GrooveScript]; a
/// [Ticker] advances t and writes the channel values, and the emblem reads
/// them only in paint (a repaint, never a rebuild, per frame). Nothing here is
/// a tween.
///
///  · pending: a card due its award waits at the choreography's frame 0
///    (nothing cut), so it never shows the finished disc and then wipes it.
///    The diary's 48dp copy waits uncut, small (×.6) and, from tier 2, a
///    quarter-turn back.
///  · interrupted: beats stop at once; every channel settles to rest from
///    wherever it is on one envelope spring e: 1 → 0. Angles take the short way.
///  · replay: grooves stay cut; the disc's turns are rescaled to whole turns
///    and the label joins the script from rest on a stiff spring; only the
///    climax beats play.
///  · reduced motion: grooves and label develop by alpha (~200ms, critically
///    damped) and the one beat lands as the reveal finishes. A flip keeps the
///    state and only restyles it.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';

import 'groove_geometry.dart';
import 'groove_haptics.dart';
import 'groove_script.dart';

enum GrooveAwardMode { first, replay }

/// What an emblem attached to a controller draws (set by `GrooveEmblem`).
@immutable
final class GrooveAwardSpec {
  const GrooveAwardSpec({
    required this.tier,
    required this.kind,
    required this.trackRated,
    required this.size,
    required this.surface,
  });

  final int tier;
  final GrooveScoreKind kind;
  final List<bool> trackRated;
  final double size;
  final GrooveSurface surface;

  @override
  bool operator ==(Object other) =>
      other is GrooveAwardSpec &&
      other.tier == tier &&
      other.kind == kind &&
      listEquals(other.trackRated, trackRated) &&
      other.size == size &&
      other.surface == surface;

  @override
  int get hashCode => Object.hash(tier, kind, Object.hashAll(trackRated), size, surface);
}

const int _disc = 0;
const int _labelScale = 1;
const int _labelAlpha = 2;
const int _emblemScale = 3;
const int _emblemAlpha = 4;
const int _tintAlpha = 5;
const int _flare = 6;
const int _burstAlpha = 7;
const int _burstScale = 8;
const int _scalarCount = 9;

/// One animated value: [fn] of t, its [rest], and whether it is an angle.
final class _Chan {
  _Chan(this.index, this.rest, this.fn, {this.angular = false});
  final int index;
  final double rest;
  final bool angular;
  final double Function(double t) fn;
}

/// Prototype `nrm`: an angle as its nearest equivalent to 0.
double _nearestRest(double a) => ((a % 360) + 540) % 360 - 180;

enum _Pose { rest, pending, uncut }

/// Drives one emblem's award. Create it in the owner's State, pass it to one
/// `GrooveEmblem`, dispose it with the owner. Commands sent before the emblem
/// attaches are applied when it does.
///
/// Notifies listeners when [isAnimating] or [isAwarded] change (not per frame).
class GrooveAwardController extends ChangeNotifier {
  GrooveAwardController({this.onBeat = playGrooveBeatFallback});

  /// Where due beats go (default: [YoinHaptics] stand-ins). Null = silent.
  final GrooveBeatSink? onBeat;

  // ---------------------------------------------------------------- binding

  GrooveAwardSpec? _spec;
  GrooveScript? _script;
  GrooveGeometry? _geometry;
  bool _tiny = false;
  Ticker? _ticker;
  bool _reducedMotion = false;
  void Function()? _queued;

  int _orders = 1;
  int _rings = 0;
  Float64List _rest = Float64List(_scalarCount);
  Float64List _values = Float64List(_scalarCount);

  /// Repaints the attached emblem; the painter listens to this, not to the controller.
  final _Repaint _frame = _Repaint();

  /// The emblem's repaint signal. Internal to `GrooveEmblem`.
  Listenable get repaint => _frame;

  /// The user's reduced motion, as last set by the emblem.
  bool get reducedMotion => _reducedMotion;

  /// Called by `GrooveEmblem` when it mounts or its inputs change.
  void attach(GrooveAwardSpec spec, TickerProvider vsync, {required bool reducedMotion}) {
    _ticker ??= vsync.createTicker(_onTick);
    if (spec != _spec) {
      _reducedMotion = reducedMotion;
      _stopAll(notify: false);
      _spec = spec;
      _script = grooveScriptFor(tier: spec.tier, kind: spec.kind, trackRated: spec.trackRated, size: spec.size);
      _geometry = grooveGeometry(spec.trackRated, spec.size, spec.kind, spec.surface);
      _tiny = grooveLayout(spec.trackRated, spec.kind, spec.size).tiny;
      final g = _geometry!;
      _orders = math.max(math.max(1, g.ratedRings.length), _script?.ratedRingCount ?? 0);
      _rings = math.max(g.rings.length, _script?.ringCount ?? 0);
      _rest = Float64List(_scalarCount + 3 * _orders + _rings);
      _rest[_labelScale] = 1;
      _rest[_labelAlpha] = 1;
      _rest[_emblemScale] = 1;
      _rest[_emblemAlpha] = 1;
      _rest[_tintAlpha] = 1;
      _rest[_burstScale] = 1;
      for (var j = 0; j < _orders; j++) {
        _rest[_scalarCount + j] = 1;
        _rest[_scalarCount + _orders + j] = 1;
      }
      _values = Float64List.fromList(_rest);
      _writePose();
    } else {
      updateReducedMotion(reducedMotion);
    }
    final queued = _queued;
    _queued = null;
    queued?.call();
    _bump();
  }

  /// Called by `GrooveEmblem` when it unmounts: everything stops, the pose is kept.
  void detach() {
    _stopAll(notify: false);
    _ticker?.dispose();
    _ticker = null;
  }

  /// The user's reduced motion changed under a live emblem: a still pose is
  /// redrawn in the new mode at once; a run finishes in its own mode, then re-poses.
  void updateReducedMotion(bool reduced) {
    if (reduced == _reducedMotion) return;
    _reducedMotion = reduced;
    if (_isAnimating) {
      _reposeWhenDone = true;
      return;
    }
    _writePose();
    _bump();
  }

  bool get _attached => _ticker != null && _spec != null;

  void _whenAttached(void Function() command) {
    if (_attached) {
      command();
    } else {
      _queued = command;
    }
  }

  // ---------------------------------------------------------------- public state

  /// The award's tier (0: unrated, no ceremony).
  int get tier => _script?.tier ?? 0;

  bool _isAnimating = false;

  /// True while a run, nod or settle moves the picture.
  bool get isAnimating => _isAnimating;

  bool _isAwarded = false;

  /// A first award reached its climax (its strongest beat), or finished.
  /// Cut off before that, it did not count ([revoke]).
  bool get isAwarded => _isAwarded;

  /// The current run's strongest beat has passed (true when nothing runs).
  bool get climaxed => _climaxMs == null || _elapsedMs >= _climaxMs!;

  // ---------------------------------------------------------------- channels (read in paint only)

  double _read(int i) => i < _values.length ? _values[i] : 0;

  double get discDegrees => _read(_disc);
  double get labelScale => _read(_labelScale);
  double get labelAlpha => _read(_labelAlpha);
  double get emblemScale => _read(_emblemScale);
  double get emblemAlpha => _read(_emblemAlpha);

  /// 0 while waiting uncut (kept for the tilt tint; no tilt on HarmonyOS yet).
  double get tintAlpha => _read(_tintAlpha);
  double get flare => _read(_flare);
  double get burstAlpha => _read(_burstAlpha);
  double get burstScale => _read(_burstScale);

  int _cutProgressAt(int j) => _scalarCount + j;
  int _cutAlphaAt(int j) => _scalarCount + _orders + j;
  int _cutGlowAt(int j) => _scalarCount + 2 * _orders + j;
  int _ringGlowAt(int i) => _scalarCount + 3 * _orders + i;

  /// How much of each cut in ring order [order] is drawn, 0–1.
  double cutProgress(int order) => order >= 0 && order < _orders ? _read(_cutProgressAt(order)) : 1;
  double cutAlpha(int order) => order >= 0 && order < _orders ? _read(_cutAlphaAt(order)) : 1;
  double cutGlow(int order) => order >= 0 && order < _orders ? _read(_cutGlowAt(order)) : 0;
  double ringGlow(int ring) => ring >= 0 && ring < _rings ? _read(_ringGlowAt(ring)) : 0;

  void _bump() => _frame.ping();

  // ---------------------------------------------------------------- poses

  _Pose _pose = _Pose.rest;
  bool _reposeWhenDone = false;

  /// The waiting pose. On a cover: the script's frame 0 (nothing cut; reduced
  /// motion: grooves and label undeveloped). On the bar: uncut, ×.6 and, tier
  /// 2+, −90° (reduced motion: dimmed to .35). `false` shows the finished emblem.
  void setPending(bool pending) {
    _stopAll();
    _pose = pending ? _Pose.pending : _Pose.rest;
    if (!_attached) return;
    if (_script == null) _pose = _Pose.rest;
    _writePose();
    _bump();
  }

  /// The diary emblem landed on a card still due its award: full size and
  /// upright, grooves uncut (reduced motion: undeveloped).
  void setUncut() {
    _stopAll();
    _pose = _Pose.uncut;
    if (!_attached) return;
    if (_script == null) _pose = _Pose.rest;
    _writePose();
    _bump();
  }

  /// Stops everything and shows the finished emblem at once (e.g. a card off
  /// screen). Does not change [isAwarded].
  void finishInstantly() => setPending(false);

  void _writePose() {
    _reposeWhenDone = false;
    _values.setAll(0, _rest);
    final s = _script;
    if (s == null) return;
    final surface = _spec!.surface;
    switch (_pose) {
      case _Pose.rest:
        break;
      case _Pose.pending:
        _values[_tintAlpha] = 0;
        if (surface == GrooveSurface.bar && _reducedMotion) {
          for (var j = 0; j < _orders; j++) {
            _values[_cutAlphaAt(j)] = 0;
          }
          _values[_emblemAlpha] = barWaitAlpha;
        } else if (surface == GrooveSurface.bar) {
          for (var j = 0; j < _orders; j++) {
            _values[_cutProgressAt(j)] = 0;
          }
          _values[_emblemScale] = barWaitScale;
          if (s.tier >= 2) _values[_disc] = barWaitTurn;
        } else if (_reducedMotion) {
          for (var j = 0; j < _orders; j++) {
            _values[_cutAlphaAt(j)] = 0;
          }
          _values[_labelAlpha] = 0;
        } else {
          for (final c in _channels(s, GrooveAwardMode.first)) {
            _values[c.index] = c.fn(0);
          }
        }
      case _Pose.uncut:
        _values[_tintAlpha] = 0;
        for (var j = 0; j < _orders; j++) {
          if (_reducedMotion) {
            _values[_cutAlphaAt(j)] = 0;
          } else {
            _values[_cutProgressAt(j)] = 0;
          }
        }
    }
  }

  void _reposeIfFlipped() {
    if (!_reposeWhenDone) return;
    _writePose();
    _bump();
  }

  // ---------------------------------------------------------------- the award

  /// Plays the card award (tier-scaled) from frame 0; a run already playing is
  /// replaced. Unrated: nothing.
  void play({GrooveAwardMode mode = GrooveAwardMode.first}) => _whenAttached(() {
    final s = _script;
    if (s == null) return;
    final chans = _channels(s, mode);
    final duration = _reducedMotion ? reducedDurationS : s.durationS;
    final beats = grooveAwardBeats(
      s,
      mode == GrooveAwardMode.first ? GrooveBeatMode.first : GrooveBeatMode.replay,
      tiny: _tiny,
      reducedMotion: _reducedMotion,
    );
    _stopAll(notify: false);
    _pose = _Pose.rest;
    _values.setAll(0, _rest);
    _firstRun = mode == GrooveAwardMode.first;
    _launch(_Run(chans, beats, isDone: (t) => t >= duration, endAt: duration));
  });

  /// The diary emblem's nod: scale .6 → 1 (tier 2+ also −90° → 0°); [again]
  /// starts from rest and dips first. Reduced motion: the dimmed emblem
  /// develops to full alpha (a replayed nod is the beat alone). Grooves that
  /// wait uncut stay uncut: the card's award cuts them.
  void nod({bool again = false}) => _whenAttached(() {
    final s = _script;
    if (s == null) return;
    final beats = grooveAwardBeats(
      s,
      again ? GrooveBeatMode.nodAgain : GrooveBeatMode.nod,
      tiny: _tiny,
      reducedMotion: _reducedMotion,
    );
    _stopAll(notify: false);
    if (_pose == _Pose.pending) _pose = _Pose.uncut;
    final chans = <_Chan>[];
    final envelopes = <bool Function(double)>[];
    void add(_Chan c, ({double z, double k}) spec, double x0, double v0) {
      chans.add(c);
      final eps = c.angular ? _angleEpsilon : _valueEpsilon;
      envelopes.add((t) => GrooveMath.springEnvelope(spec.z, spec.k, t, x0, c.rest, v0) < eps);
    }

    if (_reducedMotion) {
      if (!again) {
        final from = _values[_emblemAlpha];
        add(_Chan(_emblemAlpha, 1, (t) => _spring(GrooveSprings.reveal, t, from, 1)), GrooveSprings.reveal, from, 0);
      }
    } else {
      final from = again ? 1.0 : barWaitScale;
      final v0 = again ? nodAgainScaleVelocity : 0.0;
      add(_Chan(_emblemScale, 1, (t) => _spring(GrooveSprings.nod, t, from, 1, v0)), GrooveSprings.nod, from, v0);
      if (s.tier >= 2) {
        final turnFrom = again ? 0.0 : barWaitTurn;
        final tv0 = again ? nodAgainTurnVelocity : 0.0;
        add(
          _Chan(_disc, 0, (t) => _spring(GrooveSprings.nod, t, turnFrom, 0, tv0), angular: true),
          GrooveSprings.nod,
          turnFrom,
          tv0,
        );
      }
    }
    _firstRun = false;
    _launch(_Run(chans, beats, isDone: (t) => t > _minSettleS && envelopes.every((e) => e(t)), endAt: null));
  });

  /// Stops the beats at once and lets the picture settle to rest from where it is.
  void interrupt() {
    final run = _run;
    if (run == null || run.chans.isEmpty || run.settling) return;
    if (_firstRun && climaxed) _setAwarded(true);
    _startSettle(run.chans);
  }

  /// Leaving the card: if a first award has not reached its climax yet, it is
  /// cut short and does not count (returns true; [isAwarded] stays false, so
  /// the owner shows it pending and plays it in full next time). Past the
  /// climax it counts. Either way the picture settles to rest.
  bool revoke() {
    final cutShort = _run != null && !_run!.settling && _firstRun && !climaxed;
    interrupt();
    return cutShort;
  }

  /// A drag that may still come back: beats due while paused are dropped, the
  /// picture keeps playing.
  void pause() => _beatsPaused = true;

  /// The drag let go on the same card: later beats land on time.
  void resume() {
    if (!_beatsPaused) return;
    _beatsPaused = false;
    final from = _elapsedMs + _resumeLeadMs;
    _run?.beats.removeWhere((b) => b.atMs < from);
  }

  // ---------------------------------------------------------------- frame loop

  _Run? _run;
  bool _firstRun = false;
  bool _beatsPaused = false;
  int _elapsedMs = 0;
  int? _climaxMs;

  void _setAnimating(bool v) {
    if (_isAnimating == v) return;
    _isAnimating = v;
    notifyListeners();
  }

  void _setAwarded(bool v) {
    if (_isAwarded == v) return;
    _isAwarded = v;
    notifyListeners();
  }

  void _launch(_Run run) {
    _run = run;
    _beatsPaused = false;
    _elapsedMs = 0;
    _climaxMs = run.beats.isEmpty ? null : strongestBeat(run.beats).atMs;
    for (final c in run.chans) {
      _values[c.index] = c.fn(0);
    }
    _bump();
    _setAnimating(true);
    _ticker!
      ..stop()
      ..start();
  }

  void _startSettle(List<_Chan> chans) {
    final spec = _reducedMotion ? GrooveSprings.effects : GrooveSprings.settle;
    final snap = [for (final c in chans) c.angular ? _nearestRest(_values[c.index]) : _values[c.index]];
    _run = _Run(
      chans,
      [],
      isDone: (_) => false,
      endAt: null,
      settling: true,
      settle: (t) {
        final e = GrooveMath.springAt(spec.z, spec.k, t, 1, 0);
        if (e.abs() < _settleEnd && t > _minSettleS) return true;
        for (var i = 0; i < chans.length; i++) {
          _values[chans[i].index] = chans[i].rest + (snap[i] - chans[i].rest) * e;
        }
        return false;
      },
    );
    _climaxMs = null;
    _setAnimating(true);
    _ticker!
      ..stop()
      ..start();
  }

  void _onTick(Duration elapsed) {
    final run = _run;
    if (run == null) {
      _ticker?.stop();
      return;
    }
    final t = elapsed.inMicroseconds / 1e6;
    _elapsedMs = elapsed.inMilliseconds;
    if (run.settling) {
      if (run.settle!(t)) {
        _finish(run.chans);
      } else {
        _bump();
      }
      return;
    }
    _fireBeats(run);
    if (_firstRun && climaxed) _setAwarded(true);
    if (run.isDone(t)) {
      final endAt = run.endAt;
      if (endAt != null) {
        for (final c in run.chans) {
          _values[c.index] = c.fn(endAt);
        }
      }
      final atRest = run.chans.every((c) {
        final v = _values[c.index];
        final d = c.angular ? _nearestRest(v) - c.rest : v - c.rest;
        return d.abs() < (c.angular ? _restAngleTolerance : _restValueTolerance);
      });
      if (atRest || endAt == null) {
        if (_firstRun) _setAwarded(true);
        _finish(run.chans);
      } else {
        if (_firstRun) _setAwarded(true);
        _startSettle(run.chans);
      }
      return;
    }
    for (final c in run.chans) {
      _values[c.index] = c.fn(t);
    }
    _bump();
  }

  void _fireBeats(_Run run) {
    while (run.beats.isNotEmpty && run.beats.first.atMs <= _elapsedMs) {
      final beat = run.beats.removeAt(0);
      if (!_beatsPaused) onBeat?.call(beat);
    }
  }

  void _finish(List<_Chan> chans) {
    for (final c in chans) {
      _values[c.index] = c.rest;
    }
    _run = null;
    _climaxMs = null;
    _ticker?.stop();
    _bump();
    _setAnimating(false);
    _reposeIfFlipped();
  }

  void _stopAll({bool notify = true}) {
    _run = null;
    _climaxMs = null;
    _beatsPaused = false;
    _ticker?.stop();
    if (notify) {
      _setAnimating(false);
    } else {
      _isAnimating = false;
    }
  }

  // ---------------------------------------------------------------- channels (twostate4 awardChans)

  List<_Chan> _channels(GrooveScript s, GrooveAwardMode mode) {
    final out = <_Chan>[];
    if (_reducedMotion) {
      double develop(double t) => GrooveMath.cl(_spring(GrooveSprings.reveal, t, 0, 1));
      if (mode == GrooveAwardMode.first) {
        for (var j = 0; j < _orders; j++) {
          out.add(_Chan(_cutAlphaAt(j), 1, develop));
        }
        out.add(_Chan(_labelAlpha, 1, develop));
      } else {
        out.add(
          _Chan(_labelAlpha, 1, (t) {
            final dip = GrooveMath.cl(_spring(GrooveSprings.dip, t, 0, 1));
            return 1 - 0.45 * dip * math.exp(-math.max(0.0, t - 0.04) / 0.12);
          }),
        );
      }
      return out;
    }
    final replay = mode == GrooveAwardMode.replay;
    // a replay starts from the resting picture: tier 2's −330° becomes −360°, tier 1's −16° becomes 0°
    final d0 = s.disc(0);
    final discScale = !replay ? 1.0 : (d0 != 0 ? (d0 / 360 + 0.5).floorToDouble() * 360 / d0 : 0.0);
    out.add(_Chan(_disc, 0, (t) => s.disc(t) * discScale, angular: true));
    out.add(
      _Chan(_labelScale, 1, (t) {
        if (!replay) return s.label(t);
        return 1 + (s.label(t) - 1) * GrooveMath.cl(_spring(GrooveSprings.replayJoin, t, 0, 1));
      }),
    );
    if (!replay) {
      for (var j = 0; j < _orders; j++) {
        out.add(_Chan(_cutProgressAt(j), 1, (t) => s.cut(j, t)));
      }
    }
    for (var j = 0; j < _orders; j++) {
      out.add(_Chan(_cutGlowAt(j), 0, (t) => s.cutGlow(j, t)));
    }
    if (s.tier == 4) {
      for (var i = 0; i < _rings; i++) {
        out.add(_Chan(_ringGlowAt(i), 0, (t) => s.ringGlow(i, t)));
      }
      out.add(_Chan(_flare, 0, s.flare));
      out.add(_Chan(_burstAlpha, 0, s.burstAlpha));
      out.add(_Chan(_burstScale, 1, s.burstScale));
    }
    return out;
  }

  static double _spring(({double z, double k}) spec, double t, double from, double to, [double v0 = 0]) =>
      GrooveMath.springAt(spec.z, spec.k, t, from, to, v0);

  @override
  void dispose() {
    detach();
    _frame.dispose();
    super.dispose();
  }

  /// The bar emblem's waiting pose.
  static const double barWaitScale = 0.6;
  static const double barWaitTurn = -90;
  static const double barWaitAlpha = 0.35;

  /// A replayed nod's dip: initial velocities of scale and turn.
  static const double nodAgainScaleVelocity = -7;
  static const double nodAgainTurnVelocity = -1500;

  /// The reduced award's length (its beat lands at [GrooveBeatTimes.revealMs]).
  static const double reducedDurationS = 0.34;

  static const int _resumeLeadMs = 30;
  static const double _settleEnd = 0.002;
  static const double _minSettleS = 0.05;
  static const double _valueEpsilon = 0.001;
  static const double _angleEpsilon = 0.01;
  static const double _restValueTolerance = 0.003;
  static const double _restAngleTolerance = 0.2;
}

final class _Repaint extends ChangeNotifier {
  void ping() => notifyListeners();
}

final class _Run {
  _Run(
    this.chans,
    List<GrooveBeat> beats, {
    required this.isDone,
    required this.endAt,
    this.settling = false,
    this.settle,
  }) : beats = [...beats];
  final List<_Chan> chans;
  final List<GrooveBeat> beats;
  final bool Function(double t) isDone;
  final double? endAt;
  final bool settling;
  final bool Function(double t)? settle;
}
