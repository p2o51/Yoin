import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../theme/motion.dart';
import '../theme/playback_theme.dart';
import '../theme/tokens.dart';
import 'action_glow.dart';
import 'edge_dot_band.dart';
import 'focus_field.dart';
import 'transport_pulse.dart';
import 'transport_signal.dart';

/// Room constants (NowPlayingAuroraBackground.kt L1404-1436, RoomAmbient.kt).
abstract final class RoomMetrics {
  static const double darkPointAlpha = 0.42;
  static const double lightSaturation = 1.8;
  static const double neighbourAlpha = 0.42;
  static const double auraAlpha = 0.36;
  static const double veilAlpha = 0.40;

  /// A song change's wash: the colours flow (or cross over) this long, EaseOutCubic.
  static const int washFlowMs = 820;

  /// Play / pause: the ring (play) or sink (pause), FastOutSlowIn.
  static const int playBurstMs = 1900;
  static const int pauseBurstMs = 2200;
  static const double pulseAlphaDark = 0.42;
  static const double pulseAlphaLight = 0.34;

  /// A tapped lyric line's band.
  static const int lineBandMs = 1400;

  /// Focus: a new cover's colours cross the field over this long (FastOutSlowIn).
  static const int fieldCrossMs = 900;

  /// Light focus: the field fades in over this long instead of the stage spring.
  static const int focusFieldInMs = 420;

  /// How long a commit waits for the new cover's colours before flowing anyway.
  static const int flowWaitMs = 700;

  /// A commit's flow still takes the new cover's colours this long after it.
  static const int pendingFlowValidMs = 1500;

  /// Colours that changed this shortly before a commit are that commit's song change.
  static const int coloursFirstClaimMs = 400;

  /// The focus field's pace (≤30 fps) and its lattice's width in cells.
  static const int focusFrameMs = 32;
  static const int focusGridWidth = 50;

  /// At most this many lights draw at once; a new one past it ends the oldest.
  static const int maxRunningGlows = 6;
}

/// Now Playing's reactive room (NowPlayingAuroraBackground.kt, gradient lab,
/// owner 2026-11-03). At rest it is still; it only moves to answer something
/// the user did or is waiting on.
///
///  - **Room at rest** — dark: the surfaceContainer → surface gradient and
///    four soft points of the cover's lifted colours (screen); light: the iOS
///    room (neighbour blobs, the cover's aura, a veil, 1.8 saturation).
///  - **Song change** — the new song's wash flows in: from the tapped side
///    while the cover leads (NEXT from the end, PREVIOUS from the start), with
///    the lyric stream while the lyrics lead (next from below). It waits for
///    the new cover's colours (up to [RoomMetrics.flowWaitMs]); untapped
///    changes cross over in place ([resolveTransportPulse]).
///  - **Lyric tap** — a wide, faint band of the cover's colours at the line.
///  - **Play / pause** — gather while the finger holds PLAY; on commit, rings
///    out (play) or a bloom sinks (pause) from the tapped key.
///  - **Focus** (the cover tapped) — the whole room in the cover's colours: a
///    16-point field opening out from the cover ([focusFieldPoints]), drifting,
///    on a colour journey through the song, with the edge dot band.
///  - **Transport taps** — a ring of lattice dots leaves the tapped key.
///  - **Action lights** ([paintActionGlow]) and held fingers / lyrics waits.
///
/// Cost: still at rest (no ticker). The bursts and flows run their ~1–2 s;
/// the focus field is a 50-cell-wide lattice drawn as one colour mesh at
/// ≤30 fps, and only while focus shows. Reduced motion: colours cross over in
/// place, nothing travels, the field holds still.
class NowPlayingBackground extends StatefulWidget {
  const NowPlayingBackground({
    super.key,
    required this.field,
    this.focus,
    this.signal,
    this.pulseKey,
    this.isPlaying = false,
    this.songProgress,
    this.journeyFromRight = true,
    this.darkRoom,
    this.lightRoom,
    this.lyricColor,
  });

  final CoverField? field;

  /// Focus (tap the cover): the room fills with the cover's colours.
  final Animation<double>? focus;

  /// The controls' signal (taps, holds, lights, the cover's bounds).
  final NowPlayingTransportSignal? signal;

  /// The committed state (song, playing, queue position), null when not playing.
  final TransportPulseKey? pulseKey;
  final bool isPlaying;

  /// How far through the song the playhead is (0..1), read in paint: focus's colour journey.
  final double Function()? songProgress;

  /// The side the journey enters from: true after a skip forward or an auto-advance.
  final bool journeyFromRight;

  /// The colours dark / light focus paints the room in; null = the cover's toned.
  final RoomPalette? darkRoom;
  final RoomPalette? lightRoom;

  /// The current lyric line's colour: light focus keeps the room light enough for it.
  final Color? lyricColor;

  @override
  State<NowPlayingBackground> createState() => _NowPlayingBackgroundState();
}

/// A run of [durationMs] started at [startMs] (monotonic), eased by [curve].
class _Run {
  _Run(this.durationMs, [this.curve = Curves.linear]);
  final int durationMs;
  final Curve curve;
  int startMs = -(1 << 40);

  void start(int now) => startMs = now;
  bool running(int now) => now - startMs < durationMs;
  double raw(int now) => ((now - startMs) / durationMs).clamp(0.0, 1.0);
  double value(int now) => curve.transform(raw(now));
}

class _NowPlayingBackgroundState extends State<NowPlayingBackground> with TickerProviderStateMixin {
  final _repaint = _Repaint();
  late final Ticker _ticker = createTicker(_onTick);
  Duration _lastTick = Duration.zero;
  int _lastPaintMs = 0;

  /// The drifting clock (ms): advances only while the field shows, so focus
  /// picks up where it left off.
  double _clock = 0;

  /// Frame time the drift has not stepped by yet, and when it last stepped.
  double _clockOwed = 0;
  int _lastStepMs = 0;

  // Rest wash and its song-change flow.
  CoverField? _washShown;
  CoverField? _washPrevious;
  bool _washHasPrevious = false;
  Offset _washDir = Offset.zero;
  final _wash = _Run(RoomMetrics.washFlowMs, Curves.easeOutCubic);
  Offset? _pendingDir;
  int _pendingAtMs = 0;
  Timer? _flowTimeout;
  CoverField? _lastChangeFrom;
  bool _lastChangeClaimable = false;
  int _lastChangeAtMs = 0;

  // Play / pause.
  late final SpringValue _gather = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  _Run _burstRun = _Run(RoomMetrics.playBurstMs, Curves.fastOutSlowIn);
  bool _burstIsPlay = true;
  Offset? _burstFocal;
  Offset? _gatherFocal;

  // A tapped lyric line.
  final _band = _Run(RoomMetrics.lineBandMs);
  double _bandY = 0;

  // Focus.
  CoverField? _fieldShown;
  CoverField? _fieldFrom;
  RoomPalette? _roomShown;
  RoomPalette? _roomFrom;
  bool _crossFromRight = true;
  final _fieldFade = _Run(RoomMetrics.fieldCrossMs, Curves.fastOutSlowIn);
  late final AnimationController _fieldIn = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: RoomMetrics.focusFieldInMs),
  );
  late final Animation<double> _fieldInEased = CurvedAnimation(parent: _fieldIn, curve: Curves.fastOutSlowIn);
  final JourneyState _journey = JourneyState();
  final _FocusMesh _mesh = _FocusMesh();
  bool _focusOn = false;
  final EdgeBandState _edge = EdgeBandState();
  final DotLayers _edgeDots = DotLayers(bandSegments);
  late final SpringValue _bandPlaying = SpringValue(
    vsync: this,
    initial: widget.isPlaying ? 1 : 0,
    spring: YoinSprings.of(MotionTokens.expressiveSlowEffects),
  );

  // Transport tap dots.
  final _tapDots = _Run(tapDotsMs);
  TapDots? _tapShown;
  final DotLayers _tapLayer = DotLayers(1);

  // Action lights.
  final List<ActionGlow> _glows = [];
  late final SpringValue _holdFade = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _waitFade = SpringValue(
    vsync: this,
    initial: 0,
    spring: YoinSprings.of(MotionTokens.expressiveSlowEffects),
  );
  GlowHold? _lastHold;

  NowPlayingTransportSignal? _signal;

  bool get _reduced => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  /// The real clock (the signal's): when a commit or a colour change came, for the decision windows.
  int get _now => _signal?.nowMs ?? uptimeMs();

  /// The effects' frame clock (ms): advances with the ticker and holds while
  /// it is stopped, so a light fired after a still spell starts at its first frame.
  int _t = 0;
  int _tBase = 0;

  @override
  void initState() {
    super.initState();
    _washShown = widget.field;
    _fieldShown = widget.field;
    widget.focus?.addListener(_onFocus);
    for (final a in [_gather.animation, _bandPlaying.animation, _holdFade.animation, _waitFade.animation, _fieldIn]) {
      a.addListener(_repaint.ping);
    }
    _attach(widget.signal);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _roomShown ??= Theme.of(context).brightness == Brightness.dark ? widget.darkRoom : widget.lightRoom;
    _onFocus();
  }

  void _attach(NowPlayingTransportSignal? signal) {
    final old = _signal;
    if (identical(old, signal)) return;
    if (old != null) {
      old.playHeld.removeListener(_onHeld);
      old.tapDots.removeListener(_onTapDots);
      old.glowFires.removeListener(_onGlows);
      old.glowHold.removeListener(_onHold);
      old.lyricsWaiting.removeListener(_onWaiting);
      old.lyricLineChanges.removeListener(_onLine);
      old.lineTaps.removeListener(_onLineTap);
    }
    _signal = signal;
    if (signal != null) {
      signal.playHeld.addListener(_onHeld);
      signal.tapDots.addListener(_onTapDots);
      signal.glowFires.addListener(_onGlows);
      signal.glowHold.addListener(_onHold);
      signal.lyricsWaiting.addListener(_onWaiting);
      signal.lyricLineChanges.addListener(_onLine);
      signal.lineTaps.addListener(_onLineTap);
    }
  }

  @override
  void didUpdateWidget(NowPlayingBackground oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.focus, widget.focus)) {
      oldWidget.focus?.removeListener(_onFocus);
      widget.focus?.addListener(_onFocus);
    }
    _attach(widget.signal);
    if (oldWidget.isPlaying != widget.isPlaying) _bandPlaying.animateTo(widget.isPlaying ? 1 : 0);
    // The colours first, then the commit (Android's effect order).
    if (oldWidget.field != widget.field) _onWashTarget();
    _syncField();
    if (oldWidget.pulseKey != widget.pulseKey) _onPulse(oldWidget.pulseKey, widget.pulseKey);
    _wake();
  }

  @override
  void dispose() {
    widget.focus?.removeListener(_onFocus);
    _attach(null);
    _flowTimeout?.cancel();
    _ticker.dispose();
    _gather.dispose();
    _bandPlaying.dispose();
    _holdFade.dispose();
    _waitFade.dispose();
    _fieldIn.dispose();
    _mesh.dispose();
    _repaint.dispose();
    super.dispose();
  }

  // ── Focus ──

  double get _focusValue => (widget.focus?.value ?? 0).clamp(0.0, 1.0);

  void _onFocus() {
    final on = _focusValue > 0.02;
    if (on != _focusOn) {
      _focusOn = on;
      if (on) {
        _edge.enter(_clock);
        _fieldIn.forward();
      } else {
        // Leaving stays fast: the stage spring alone carries it out.
        _fieldIn.value = 0;
      }
    }
    _wake();
  }

  /// A new cover (or room) while focused crosses the field over instead of cutting.
  void _syncField() {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final room = dark ? widget.darkRoom : widget.lightRoom;
    if (widget.field == _fieldShown && room == _roomShown) return;
    final outgoing = _fieldShown;
    final outgoingRoom = _roomShown;
    _fieldShown = widget.field;
    _roomShown = room;
    // Out of focus nothing shows the field: just take the new one.
    final shown = widget.field != null && _focusValue > 0.001;
    if (outgoing == null || widget.field == null || !shown || _reduced) {
      _fieldFrom = null;
      _roomFrom = null;
      return;
    }
    _fieldFrom = outgoing;
    _roomFrom = outgoingRoom;
    _crossFromRight = widget.journeyFromRight;
    _fieldFade.start(_t);
  }

  // ── Song change: the wash ──

  void _flow(CoverField? from, CoverField? to, Offset dir) {
    _washPrevious = from;
    _washHasPrevious = true;
    _washShown = to;
    _washDir = _reduced ? Offset.zero : dir;
    _wash.start(_t);
    _wake();
  }

  void _onWashTarget() {
    final target = widget.field;
    if (target == _washShown) return;
    final now = _now;
    final pending = _pendingDir;
    final dir = pending != null && now - _pendingAtMs <= RoomMetrics.pendingFlowValidMs ? pending : null;
    _pendingDir = null;
    _flowTimeout?.cancel();
    // Unclaimed: a commit landing just after may still run it as its flow.
    _lastChangeClaimable = dir == null;
    _lastChangeFrom = _washShown;
    _lastChangeAtMs = now;
    _flow(_washShown, target, dir ?? Offset.zero);
  }

  void _onPulse(TransportPulseKey? previous, TransportPulseKey? current) {
    final signal = _signal;
    final now = _now;
    final surface = signal?.lyricsSurface;
    final decision = resolveTransportPulse(
      previous: previous,
      current: current,
      tap: signal?.lastTap,
      nowUptimeMs: now,
      lyricsPrimary: surface != null && surface.primary && surface.boundsRoot.height > 0,
      handover: signal?.lyricsHandover,
      settle: signal?.playSettle,
    );
    if (decision.tapUsed) signal?.spendTap();
    signal?.playSettle = decision.settle;
    // The edge band bursts again on a song change in focus.
    if (previous?.songId != current?.songId && previous != null && current != null && _focusValue > 0.5) {
      _edge.songChange(_clock);
    }
    final rtl = Directionality.maybeOf(context) == TextDirection.rtl;
    final Offset? flowDir;
    switch (decision.pulse) {
      case PulseNone():
        flowDir = null;
      case PulseBurst(:final isPlay, :final focalRoot):
        flowDir = null;
        if (!_reduced) {
          _burstIsPlay = isPlay;
          _burstFocal = focalRoot;
          _burstRun = _Run(isPlay ? RoomMetrics.playBurstMs : RoomMetrics.pauseBurstMs, Curves.fastOutSlowIn)
            ..start(_t);
        }
      case PulseSideFlow(:final fromEnd):
        flowDir = Offset(fromEnd != rtl ? 1 : -1, 0);
      case PulseFlowLight(:final forward):
        flowDir = Offset(0, forward ? 1 : -1);
    }
    if (flowDir == null) return;
    final dir = flowDir;
    if (_lastChangeClaimable && now - _lastChangeAtMs <= RoomMetrics.coloursFirstClaimMs) {
      // The colours beat the commit: re-run that change as this flow.
      _lastChangeClaimable = false;
      _flow(_lastChangeFrom, _washShown, dir);
    } else {
      // Wait for the new cover's colours; flow anyway (same album) if none come.
      _pendingDir = dir;
      _pendingAtMs = now;
      _flowTimeout?.cancel();
      _flowTimeout = Timer(const Duration(milliseconds: RoomMetrics.flowWaitMs), () {
        if (!mounted || _pendingDir != dir) return;
        _pendingDir = null;
        _flow(_washShown, _washShown, dir);
      });
    }
  }

  // ── Signal listeners ──

  void _onHeld() {
    final held = _signal?.playHeld.value ?? false;
    if (held) _gatherFocal = _signal?.playKey.rect?.center;
    _gather.animateTo(held && !_reduced ? 1 : 0);
    _wake();
  }

  void _onTapDots() {
    final tap = _signal?.tapDots.value;
    if (tap == null || _reduced) return;
    _tapShown = tap;
    _tapDots.start(_t);
    _wake();
  }

  void _onGlows() {
    final signal = _signal;
    if (signal == null || signal.pendingGlows.isEmpty) return;
    if (!_reduced) {
      // Each light runs on the frame clock from the frame after it fired.
      for (final g in signal.pendingGlows) {
        addHandingOver(
          _glows,
          ActionGlow(
            kind: g.kind,
            anchorRoot: g.anchorRoot,
            bornUptimeMs: _t,
            value: g.value,
            targetY: g.targetY,
            areaRoot: g.areaRoot,
          ),
        );
      }
      while (_glows.length > RoomMetrics.maxRunningGlows) {
        _glows.removeAt(0);
      }
    }
    signal.pendingGlows.clear();
    _wake();
  }

  void _onHold() {
    final hold = _signal?.glowHold.value;
    if (hold != null) _lastHold = hold;
    _holdFade.animateTo(hold != null && !_reduced ? 1 : 0);
    _wake();
  }

  void _onWaiting() {
    _waitFade.animateTo(_signal?.lyricsWaiting.value == true && !_reduced ? 1 : 0);
    _wake();
  }

  void _onLine() {
    // Out of focus a new line moves nothing here: no repaint.
    if (_focusValue <= 0.02) return;
    _edge.lineChange(_clock);
    _wake();
  }

  void _onLineTap() {
    final signal = _signal;
    if (signal == null || _reduced) return;
    _bandY = signal.lineTapY;
    _band.start(_t);
    _wake();
  }

  // ── The clock ──

  bool get _fieldLive => widget.field != null && _focusValue > 0.001;

  /// Something runs every frame (not just the focus field's ≤30 fps).
  bool _fastLive(int now) =>
      _wash.running(now) ||
      _burstRun.running(now) ||
      _band.running(now) ||
      _fieldFade.running(now) ||
      _tapDots.running(now) ||
      _glows.isNotEmpty ||
      _holdFade.value > 0.001 ||
      _waitFade.value > 0.001 ||
      (_fieldLive && _edge.bursting(_clock));

  /// Runs the ticker while anything moves; otherwise just repaints once.
  void _wake() {
    if (!mounted) return;
    if (!_ticker.isActive && (_fieldLive || _fastLive(_t))) {
      _lastTick = Duration.zero;
      _tBase = _t;
      _ticker.start();
    }
    _repaint.ping();
  }

  void _onTick(Duration elapsed) {
    final dt = elapsed - _lastTick;
    _lastTick = elapsed;
    _t = _tBase + elapsed.inMilliseconds;
    final now = _t;
    final fieldLive = _fieldLive;
    // Reduced motion: the field holds still (it fades in place with the focus).
    // The drift steps at the field's ≤30 fps pace (every frame only while the
    // edge band bursts), so a light running alongside repaints over the same
    // field instead of re-rendering it every frame.
    if (fieldLive && !_reduced) {
      _clockOwed += math.min(dt.inMicroseconds / 1000, 250);
      if (_edge.bursting(_clock) || now - _lastStepMs >= RoomMetrics.focusFrameMs) {
        _clock += _clockOwed;
        _clockOwed = 0;
        _lastStepMs = now;
      }
    }
    _glows.removeWhere((g) => g.isDone(now));
    final fast = _fastLive(now);
    if (!fast && !fieldLive) {
      _ticker.stop();
      _repaint.ping();
      return;
    }
    if (fast || now - _lastPaintMs >= RoomMetrics.focusFrameMs) {
      _lastPaintMs = now;
      _repaint.ping();
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final signal = widget.signal;
    // Published with each build (Android's SideEffect): taps are stamped with it.
    signal?.committedSongId = widget.pulseKey?.songId;
    signal?.committedPlaying = widget.pulseKey?.isPlaying == true;
    // The finger-down a lyric-line tap lights its band at is recorded by the
    // screen above the content: the room sits under it and never sees the press.
    return RepaintBoundary(
      child: CustomPaint(
        painter: _RoomPainter(state: this, scheme: scheme, repaint: _repaint),
        child: const SizedBox.expand(),
      ),
    );
  }
}

class _Repaint extends ChangeNotifier {
  void ping() => notifyListeners();
}

class _RoomPainter extends CustomPainter {
  _RoomPainter({required this.state, required this.scheme, required Listenable repaint}) : super(repaint: repaint);

  final _NowPlayingBackgroundState state;
  final ColorScheme scheme;

  /// The rest wash's colours, lifted toward the page (lift in NowPlayingAuroraBackground.kt).
  static Color _lift(Color c, bool dark) {
    double ch(double v) => dark ? math.min(1, v * 1.15 + 25 / 255) : v * 0.55 + 0.45;
    return Color.from(alpha: 1, red: ch(c.r), green: ch(c.g), blue: ch(c.b));
  }

  @override
  void paint(Canvas canvas, Size size) {
    final s = state;
    if (!s.mounted) return;
    final w = size.width;
    final h = size.height;
    if (w <= 0 || h <= 0) return;
    final box = s.context.findRenderObject() as RenderBox?;
    final origin = box != null && box.attached && box.hasSize ? box.localToGlobal(Offset.zero) : Offset.zero;
    final rect = Offset.zero & size;
    final dark = scheme.brightness == Brightness.dark;
    final blend = dark ? BlendMode.screen : BlendMode.srcOver;
    final now = s._t;
    final signal = s._signal;

    // The room: base, the neighbours, then the wash (flowing on a song change), under the veil.
    if (dark) {
      canvas.drawRect(
        rect,
        Paint()
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [scheme.surfaceContainer, scheme.surface],
          ).createShader(rect),
      );
    } else {
      canvas.saveLayer(rect, Paint()..colorFilter = ColorFilter.matrix(_saturation(RoomMetrics.lightSaturation)));
      canvas.drawRect(rect, Paint()..color = scheme.surface);
      _paintNeighbours(canvas, size);
    }
    final e = s._wash.value(now);
    if (e >= 1 || !s._washHasPrevious) {
      _paintWash(canvas, size, s._washShown, 0, 0, 1, dark);
    } else if (s._washDir == Offset.zero) {
      _paintWash(canvas, size, s._washPrevious, 0, 0, 1 - e, dark);
      _paintWash(canvas, size, s._washShown, 0, 0, e, dark);
    } else {
      final dir = s._washDir;
      final travel = (dir.dx != 0 ? w : h) * 0.9;
      _paintWash(canvas, size, s._washPrevious, -dir.dx * travel * e, -dir.dy * travel * e, 1 - e, dark);
      _paintWash(
        canvas,
        size,
        s._washShown,
        dir.dx * travel * (1 - e),
        dir.dy * travel * (1 - e),
        math.min(1, e * 1.4),
        dark,
      );
    }
    if (!dark) {
      canvas.drawRect(rect, Paint()..color = scheme.surface.withValues(alpha: RoomMetrics.veilAlpha));
      canvas.restore();
    }

    // Focus: the whole room in the cover's colours.
    final field = s._fieldShown;
    final f = s._focusValue;
    if (field != null && f > 0.001) {
      final cover =
          signal?.cover.rect?.shift(-origin) ?? Rect.fromCircle(center: Offset(w * 0.5, h * 0.33), radius: w * 0.3);
      final t = s._clock;
      final journeyK = s._journey.step(
        s.widget.songProgress?.call() ?? 0,
        enterFromRight: s.widget.journeyFromRight,
        nowMs: t,
      );
      List<FieldPoint> raw(CoverField source, RoomPalette? room) => focusFieldPoints(
        field: source,
        dark: dark,
        width: w,
        height: h,
        coverCenterX: cover.center.dx,
        coverCenterY: cover.center.dy,
        coverSize: math.max(math.min(cover.width, cover.height), w * 0.3),
        spread: _smoothstep(f),
        timeMs: t,
        room: room,
      );
      List<FieldPoint> pointsOf(CoverField source, RoomPalette? room) {
        final base = raw(source, room);
        if (journeyK <= 0.001) return base;
        final toward = raw(journeyedField(source), room == null ? null : journeyedRoom(room));
        return journeyFieldColors(base, toward, w, journeyK, fromRight: s._journey.fromRight);
      }

      final incoming = pointsOf(field, s._roomShown);
      final outgoing = s._fieldFrom;
      final crossing = outgoing != null && s._fieldFade.running(now);
      final mixed = crossing
          ? crossFieldColors(
              pointsOf(outgoing, s._roomFrom ?? s._roomShown),
              incoming,
              s._fieldFade.value(now),
              fromRight: s._crossFromRight,
              width: w,
            )
          : incoming;
      final lyric = s.widget.lyricColor;
      final points = !dark && lyric != null ? readableUnder(mixed, lyric.toARGB32()) : mixed;
      final alpha = dark ? math.min(1.0, f * 1.25) : math.min(math.min(1.0, f * 1.25), s._fieldInEased.value);
      s._mesh.paint(canvas, points, scheme.surface.toARGB32(), w, h, alpha);
      if (s._edge.alive(t) && !s._reduced) {
        paintEdgeBand(
          canvas,
          size,
          state: s._edge,
          dots: s._edgeDots,
          nowMs: t,
          focus: f,
          playing: s._bandPlaying.value.clamp(0.0, 1.0),
          cover: cover,
          lyrics: signal?.lyricsAreaRoot?.shift(-origin).center,
          palette: field.argbPalette,
          primary: scheme.primary,
          dark: dark,
        );
      }
    }

    // A tapped lyric line.
    if (s._band.running(now)) {
      _paintLineBand(canvas, size, s._washShown, s._bandY - origin.dy, s._band.raw(now), dark, blend);
    }

    // Play / pause: gather while held, then ring out or sink.
    final cap = dark ? RoomMetrics.pulseAlphaDark : RoomMetrics.pulseAlphaLight;
    final playTint = HSLColor.fromAHSL(
      1,
      argbToHsl(scheme.primary.toARGB32()).$1,
      dark ? 0.75 : 0.85,
      dark ? 0.6 : 0.62,
    ).toColor();
    final pauseTint = HSLColor.fromAHSL(
      1,
      argbToHsl(scheme.tertiary.toARGB32()).$1,
      dark ? 0.6 : 0.7,
      dark ? 0.6 : 0.62,
    ).toColor();
    final fallback = Offset(w * 0.5, h * 0.62);
    final g = s._gather.value.clamp(0.0, 1.0);
    if (g > 0.001) {
      final focal = s._gatherFocal != null ? s._gatherFocal! - origin : fallback;
      _bloom(canvas, focal, w * (0.55 - 0.25 * g), s.widget.isPlaying ? pauseTint : playTint, cap * 1.1 * g, blend);
    }
    final burst = s._burstRun;
    if (burst.running(now)) {
      final sweep = burst.value(now);
      final focal = s._burstFocal != null ? s._burstFocal! - origin : fallback;
      final env = math.sin(math.pi * sweep);
      if (s._burstIsPlay) {
        final center = Offset(focal.dx, focal.dy - h * 0.05 * sweep);
        for (var r = 0; r < 2; r++) {
          final radius = h * (0.1 + 0.5 * sweep + 0.13 * r);
          final ring = playTint.withValues(alpha: (cap * 1.2 * env * (1 - 0.35 * r)).clamp(0.0, 1.0));
          canvas.drawCircle(
            center,
            radius,
            Paint()
              ..blendMode = blend
              ..shader = ui.Gradient.radial(
                center,
                radius,
                [const Color(0x00000000), const Color(0x00000000), ring, ring.withValues(alpha: 0)],
                const [0, 0.72, 0.88, 1],
              ),
          );
        }
      } else {
        _bloom(
          canvas,
          Offset(focal.dx, focal.dy + h * 0.05 * sweep),
          h * (0.42 - 0.22 * sweep),
          pauseTint,
          cap * 1.1 * env,
          blend,
        );
      }
    }

    // A transport key tapped: its ring of dots.
    final tapped = s._tapShown;
    if (tapped != null && s._tapDots.running(now)) {
      paintTapDots(
        canvas,
        s._tapLayer,
        tapped.boundsRoot.shift(-origin),
        tapped.lean,
        s._tapDots.raw(now),
        scheme.primary.withValues(alpha: dark ? 0.75 : 0.6),
      );
    }

    // Action lights: a held finger, a lyrics wait, and the fired lights.
    final holdA = s._holdFade.value;
    final waitA = s._waitFade.value;
    if (s._glows.isEmpty && holdA <= 0.001 && waitA <= 0.001) return;
    final pal = GlowPalette(scheme.primary, scheme.secondary, scheme.tertiary, dark: dark);
    final held = s._lastHold;
    if (held != null && holdA > 0.001) paintGlowHold(canvas, held, origin, holdA.clamp(0.0, 1.0), pal, blend);
    if (waitA > 0.001) {
      paintGlowWait(canvas, size, signal?.lyricsAreaRoot, origin, now.toDouble(), waitA.clamp(0.0, 1.0), pal, blend);
    }
    for (final glow in s._glows) {
      paintActionGlow(canvas, size, glow, (now - glow.bornUptimeMs).toDouble(), origin, pal, blend);
    }
  }

  /// The previous / next songs' colours past the top / bottom edges (light
  /// room). Neighbour blobs default to the secondary / tertiary containers.
  void _paintNeighbours(Canvas canvas, Size size) {
    final w = size.width, h = size.height;
    final k = (w / 402).clamp(0.85, 1.6);
    void blob(Color c, double cy, double height) {
      canvas.drawOval(
        Rect.fromCenter(center: Offset(w / 2, cy), width: w * 1.2, height: height * k),
        Paint()
          ..color = c.withValues(alpha: RoomMetrics.neighbourAlpha)
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 50 * k),
      );
    }

    blob(scheme.secondaryContainer, 60 * k, 300);
    blob(scheme.tertiaryContainer, h - 50 * k, 320);
  }

  /// The rest wash offset by a song change's flow: dark, four soft points of
  /// the cover's lifted colours across the top; light, the cover's aura.
  void _paintWash(Canvas canvas, Size size, CoverField? field, double dx, double dy, double alpha, bool dark) {
    if (alpha <= 0.001) return;
    final w = size.width, h = size.height;
    if (dark) {
      final k1 = _lift(field?.palette[0] ?? scheme.primary, true);
      final k2 = _lift(field?.palette[1] ?? scheme.tertiary, true);
      final k3 = _lift(field?.palette[2] ?? scheme.secondary, true);
      void point(Color c, double x, double y, double r) {
        final center = Offset(w * x + dx, h * y + dy);
        final radius = w * r;
        final a = RoomMetrics.darkPointAlpha * alpha.clamp(0.0, 1.0);
        canvas.drawCircle(
          center,
          radius,
          Paint()
            ..blendMode = BlendMode.screen
            ..shader = RadialGradient(
              colors: [
                c.withValues(alpha: a),
                c.withValues(alpha: a * 0.4),
                c.withValues(alpha: 0),
              ],
              stops: const [0, 0.6, 1],
            ).createShader(Rect.fromCircle(center: center, radius: radius)),
        );
      }

      point(k1, 0.12, 0.06, 0.85);
      point(k3, 0.86, 0.10, 0.70);
      point(k2, 0.52, 0.30, 0.75);
      point(k1, 0.95, 0.42, 0.50);
      return;
    }
    final k = (w / 402).clamp(0.85, 1.6);
    canvas.drawCircle(
      Offset(w / 2 + dx, h / 2 + dy),
      180 * k,
      Paint()
        ..color = (field?.palette[0] ?? scheme.primary).withValues(alpha: RoomMetrics.auraAlpha * alpha.clamp(0.0, 1.0))
        ..maskFilter = MaskFilter.blur(BlurStyle.normal, 95 * k),
    );
  }

  /// Two wide, faint ellipses of the cover's colours at the tapped line.
  void _paintLineBand(Canvas canvas, Size size, CoverField? field, double y, double b, bool dark, BlendMode blend) {
    if (b <= 0.001) return;
    final w = size.width;
    final env = math.pow(math.sin(math.pi * b), 1.5).toDouble();
    final grow = 0.85 + 0.15 * (1 - math.pow(1 - b, 3));
    // A touch denser than the lab's .34 / .26 (owner 2026-11-03).
    final a = (dark ? 0.42 : 0.34) * env;
    final radius = w * 0.36 * grow;
    final k1 = _lift(field?.palette[0] ?? scheme.primary, dark);
    final k3 = _lift(field?.palette[2] ?? scheme.secondary, dark);
    for (final (color, offset, m) in [(k1, -0.18, 1.0), (k3, 0.22, 0.8)]) {
      final center = Offset(w * (0.5 + offset), y);
      canvas
        ..save()
        ..translate(center.dx, center.dy)
        ..scale(2.6 * grow, 1)
        ..drawCircle(
          Offset.zero,
          radius,
          Paint()
            ..blendMode = blend
            ..shader = ui.Gradient.radial(
              Offset.zero,
              radius,
              [
                color.withValues(alpha: (a * m).clamp(0.0, 1.0)),
                color.withValues(alpha: (a * m * 0.45).clamp(0.0, 1.0)),
                color.withValues(alpha: 0),
              ],
              const [0, 0.55, 1],
            ),
        )
        ..restore();
    }
  }

  static void _bloom(Canvas canvas, Offset center, double radius, Color color, double alpha, BlendMode blend) {
    if (radius <= 0 || alpha <= 0.001) return;
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..blendMode = blend
        ..shader = ui.Gradient.radial(center, radius, [
          color.withValues(alpha: alpha.clamp(0.0, 1.0)),
          color.withValues(alpha: 0),
        ]),
    );
  }

  static List<double> _saturation(double s) {
    const lr = 0.2126, lg = 0.7152, lb = 0.0722;
    final sr = (1 - s) * lr, sg = (1 - s) * lg, sb = (1 - s) * lb;
    return [
      sr + s, sg, sb, 0, 0, //
      sr, sg + s, sb, 0, 0, //
      sr, sg, sb + s, 0, 0, //
      0, 0, 0, 1, 0,
    ];
  }

  @override
  bool shouldRepaint(_RoomPainter old) => old.scheme != scheme || !identical(old.state, state);
}

double _smoothstep(double x) {
  final c = x.clamp(0.0, 1.0);
  return c * c * (3 - 2 * c);
}

/// The focus field's lattice, drawn as one Gouraud colour mesh (Android's
/// FocusCanvas: a ~50-cell-wide bitmap upscaled bilinearly). Redraws for
/// other reasons (a press, a light) reuse the last colours.
class _FocusMesh {
  int _cols = 0;
  int _rows = 0;
  double _w = 0;
  double _h = 0;
  Float32List _positions = Float32List(0);
  Uint16List _indices = Uint16List(0);
  Int32List _colors = Int32List(0);
  Int32List _shown = Int32List(0);
  List<FieldPoint>? _lastPoints;
  int _lastBase = 0;
  double _lastAlpha = -1;
  ui.Vertices? _vertices;

  void _layout(double w, double h) {
    const gw = RoomMetrics.focusGridWidth;
    final gh = (gw * h / w).round().clamp(8, 240);
    if (gw + 1 == _cols && gh + 1 == _rows && w == _w && h == _h) return;
    _cols = gw + 1;
    _rows = gh + 1;
    _w = w;
    _h = h;
    _positions = Float32List(_cols * _rows * 2);
    for (var j = 0; j < _rows; j++) {
      for (var i = 0; i < _cols; i++) {
        final v = (j * _cols + i) * 2;
        _positions[v] = i * w / gw;
        _positions[v + 1] = j * h / gh;
      }
    }
    _indices = Uint16List(gw * gh * 6);
    var n = 0;
    for (var j = 0; j < gh; j++) {
      for (var i = 0; i < gw; i++) {
        final a = j * _cols + i;
        final b = a + 1;
        final c = a + _cols;
        final d = c + 1;
        _indices
          ..[n++] = a
          ..[n++] = b
          ..[n++] = c
          ..[n++] = b
          ..[n++] = d
          ..[n++] = c;
      }
    }
    _colors = Int32List(_cols * _rows);
    _shown = Int32List(_cols * _rows);
    _lastPoints = null;
    _vertices?.dispose();
    _vertices = null;
  }

  void paint(Canvas canvas, List<FieldPoint> points, int base, double w, double h, double alpha) {
    if (alpha <= 0.001) return;
    _layout(w, h);
    final same = _lastPoints != null && base == _lastBase && listEquals(_lastPoints, points);
    if (!same) {
      renderFocusField(points, base, w, h, _cols - 1, _rows - 1, _colors);
      _lastPoints = points;
      _lastBase = base;
      _lastAlpha = -1;
    }
    if (_vertices == null || alpha != _lastAlpha) {
      final a = (alpha.clamp(0.0, 1.0) * 255).round() << 24;
      for (var i = 0; i < _colors.length; i++) {
        _shown[i] = a | (_colors[i] & 0x00FFFFFF);
      }
      _lastAlpha = alpha;
      _vertices?.dispose();
      _vertices = ui.Vertices.raw(ui.VertexMode.triangles, _positions, colors: _shown, indices: _indices);
    }
    // The vertex colours as they are (dst = the mesh's own colours).
    canvas.drawVertices(_vertices!, BlendMode.dst, Paint());
  }

  void dispose() {
    _vertices?.dispose();
    _vertices = null;
  }
}
