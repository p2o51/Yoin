import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

import 'action_glow.dart';
import 'edge_dot_band.dart';
import 'transport_pulse.dart';

/// A monotonic clock in ms (Android's SystemClock.uptimeMillis).
final Stopwatch _uptime = Stopwatch()..start();
int uptimeMs() => _uptime.elapsedMilliseconds;

/// Where one widget sits on screen, read on demand (global px, transforms
/// included) — never pushed per layout, so reporting costs nothing until a
/// light or the field asks.
class GlobalRectProbe {
  RenderBox? _box;

  /// The widget's current bounds, or null while it isn't laid out on screen.
  Rect? get rect {
    final box = _box;
    if (box == null || !box.attached || !box.hasSize) return null;
    final r = MatrixUtils.transformRect(box.getTransformTo(null), Offset.zero & box.size);
    return r.isEmpty ? null : r;
  }
}

/// Reports [child]'s on-screen bounds into [probe] (TrackGlowBounds /
/// reportFocusCover / reportGlowAnchor on Android).
class ReportBounds extends SingleChildRenderObjectWidget {
  const ReportBounds({super.key, required this.probe, super.child});

  final GlobalRectProbe probe;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderReportBounds(probe);

  @override
  void updateRenderObject(BuildContext context, RenderReportBounds renderObject) => renderObject.probe = probe;
}

class RenderReportBounds extends RenderProxyBox {
  RenderReportBounds(this._probe);

  GlobalRectProbe _probe;

  set probe(GlobalRectProbe value) {
    if (identical(value, _probe)) return;
    if (identical(_probe._box, this)) _probe._box = null;
    _probe = value;
    if (attached) _probe._box = this;
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _probe._box = this;
  }

  @override
  void detach() {
    if (identical(_probe._box, this)) _probe._box = null;
    super.detach();
  }
}

/// The shared signal Now Playing's controls publish to the background
/// (NowPlayingAuroraBackground.kt NowPlayingTransportSignal), so it can answer
/// the *finger*, not just the committed state:
///
///  - [playHeld] — PLAY/PAUSE is pressed (the gather converges on [playKey]);
///  - [lastTap] — the last transport tap, stamped with its time and kind: the
///    release burst radiates from it only while it is fresh, and a tap is
///    spent once; a PREVIOUS the player answered with a restart is spent as
///    soon as the playhead shows it ([observePlayhead]);
///  - the lyrics column ([claimLyricsSurface]) and the outro's in-place
///    hand-over ([publishLyricsHandover]), read when a song change commits;
///  - [cover] — where the cover sits; the focus field opens out from it;
///  - the last finger-down ([recordPointerDown]) and lyric-line taps
///    ([recordLineTap]) — a tapped line lights a faint band at its height;
///  - action lights ([glow]), a held finger ([glowHold]) and a lyrics wait
///    ([lyricsWaiting]).
///
/// The listenables are observed by the background only; nothing here rebuilds
/// the screen.
class NowPlayingTransportSignal {
  NowPlayingTransportSignal({int Function()? clock}) : _now = clock ?? uptimeMs;

  final int Function() _now;

  int get nowMs => _now();

  /// PLAY/PAUSE is held: the gather (the background's anticipation light).
  final ValueNotifier<bool> playHeld = ValueNotifier(false);

  /// The PLAY key, where the gather converges.
  final GlobalRectProbe playKey = GlobalRectProbe();

  /// The cover as laid out now (read in paint).
  final GlobalRectProbe cover = GlobalRectProbe();

  /// The last transport key tapped, for its ring of dots.
  final ValueNotifier<TapDots?> tapDots = ValueNotifier(null);

  /// A transport key was tapped: [boundsRoot] its outline, [lean] −1 PREVIOUS, +1 NEXT, 0 PLAY.
  void recordTapDots(Rect? boundsRoot, {required int lean}) {
    if (boundsRoot == null || boundsRoot.isEmpty) return;
    tapDots.value = TapDots(boundsRoot: boundsRoot, lean: lean, atUptimeMs: _now());
  }

  // ── Action lights (action_glow.dart) ──
  final Map<GlowAnchor, GlobalRectProbe> _anchors = {};

  /// The probe a control reports into as [anchor] (the Write pill, the cover…).
  GlobalRectProbe anchorProbe(GlowAnchor anchor) => _anchors.putIfAbsent(anchor, GlobalRectProbe.new);

  Rect? anchorOf(GlowAnchor anchor) => anchor == GlowAnchor.cover ? cover.rect : _anchors[anchor]?.rect;

  /// Lights fired since the background last took them.
  final List<ActionGlow> pendingGlows = [];

  /// Bumped on every [glow].
  final ValueNotifier<int> glowFires = ValueNotifier(0);

  /// A finger held on the rating bar or the wave bar.
  final ValueNotifier<GlowHold?> glowHold = ValueNotifier(null);

  /// Something is being fetched for the lyrics (a translation): the slow sweep runs.
  final ValueNotifier<bool> lyricsWaiting = ValueNotifier(false);

  /// The lyrics column's bounds (global px), or null when none is on screen.
  Rect? get lyricsAreaRoot => lyricsSurface?.boundsRoot;

  /// Where the current lyric line lands (global px), or null with no lyrics on screen.
  double? get currentLineRootY {
    final s = lyricsSurface;
    return s == null ? null : s.boundsRoot.top + s.landingFromTopPx;
  }

  /// Light the background for an action at [anchorRoot] (the control, global
  /// px). A light with nowhere to start from only fires for the kinds that
  /// don't need one.
  void glow(ActionGlowKind kind, Rect? anchorRoot, {double value = 0, double targetY = double.nan, Rect? areaRoot}) {
    final Rect anchor;
    if (anchorRoot != null && !anchorRoot.isEmpty) {
      anchor = anchorRoot;
    } else if (kind.anchorless) {
      anchor = Rect.zero;
    } else {
      return;
    }
    if (pendingGlows.length >= _maxPendingGlows) pendingGlows.removeAt(0);
    pendingGlows.add(
      ActionGlow(
        kind: kind,
        anchorRoot: anchor,
        bornUptimeMs: _now(),
        value: value,
        targetY: targetY,
        areaRoot: areaRoot,
      ),
    );
    glowFires.value++;
  }

  /// Bumped whenever the current lyric line moves on.
  final ValueNotifier<int> lyricLineChanges = ValueNotifier(0);

  void lyricLineChanged() => lyricLineChanges.value++;

  /// Bumped on every lyric-line tap.
  final ValueNotifier<int> lineTaps = ValueNotifier(0);

  /// The tapped line's height (global px), for the [lineTaps] that just fired.
  double lineTapY = 0;

  Offset? _pointerDown;
  int _pointerDownMs = 0;

  /// A finger went down at [global].
  void recordPointerDown(Offset global) {
    _pointerDown = global;
    _pointerDownMs = _now();
  }

  /// A lyric line (or note row) was tapped to seek: the line is where the
  /// finger last went down, if that was just now; a seek with no fresh
  /// finger (keyboard, accessibility) lights nothing.
  void recordLineTap() {
    final at = _pointerDown;
    if (at == null || _now() - _pointerDownMs > _lineTapFingerValidityMs) return;
    lineTapY = at.dy;
    lineTaps.value++;
  }

  TransportTap? _lastTap;
  TransportTap? get lastTap => _lastTap;

  /// The song the screen has committed to (the pulse key's), published by the background.
  Object? committedSongId;

  /// The committed playing flag, published with [committedSongId].
  bool committedPlaying = false;

  /// The resume to playing the player still owes after a skip or a restart.
  PlaySettle? playSettle;

  /// The user sought: the player may buffer at the new spot, and that dip
  /// and resume are the seek's, not a play/pause ([seekPlaySettle]).
  void recordSeek() {
    final settle = seekPlaySettle(committedSongId, playing: committedPlaying, nowUptimeMs: _now());
    if (settle != null) playSettle = settle;
  }

  /// [positionMs]: the song-scoped playhead at the tap ([unknownTapPositionMs] if not known).
  void recordTap(Offset centerRoot, TransportTapKind kind, {int positionMs = unknownTapPositionMs}) {
    _lastTap = TransportTap(
      centerRoot: centerRoot,
      uptimeMs: _now(),
      kind: kind,
      songId: committedSongId,
      positionMs: positionMs,
    );
  }

  void spendTap() => _lastTap = null;

  /// The transport row's playhead, every tick: a PREVIOUS tap the player
  /// answered with a restart is spent here, and its buffering settled.
  void observePlayhead(int positionMs) {
    final tap = _lastTap;
    if (tap == null || !tap.restartedInPlace(committedSongId, positionMs)) return;
    _lastTap = null;
    final songId = tap.songId;
    if (songId != null) {
      playSettle = PlaySettle.after(songId, _now(), resumeUntilUptimeMs: tap.uptimeMs + transportTapValidityMs);
    }
  }

  // The reporting lyrics column: several reporters may exist during a posture
  // swap; the last to claim owns the signal, and leaving releases only what it owns.
  Object? _lyricsOwner;
  final Map<Object, GlobalRectProbe> _lyricsProbes = {};
  bool _lyricsPrimary = false;
  double _lyricsLanding = 0;
  bool _lyricsClipLight = false;

  LyricsSurface? get lyricsSurface {
    final owner = _lyricsOwner;
    if (owner == null) return null;
    final bounds = _lyricsProbes[owner]?.rect;
    if (bounds == null) return null;
    return LyricsSurface(
      owner: owner,
      boundsRoot: bounds,
      primary: _lyricsPrimary,
      landingFromTopPx: _lyricsLanding,
      clipLight: _lyricsClipLight,
    );
  }

  void claimLyricsSurface(
    Object owner,
    GlobalRectProbe probe, {
    required bool primary,
    required double landingFromTop,
    required bool clipLight,
  }) {
    _lyricsOwner = owner;
    _lyricsProbes[owner] = probe;
    _lyricsPrimary = primary;
    _lyricsLanding = landingFromTop;
    _lyricsClipLight = clipLight;
  }

  void releaseLyricsSurface(Object owner) {
    _lyricsProbes.remove(owner);
    if (identical(_lyricsOwner, owner)) {
      _lyricsOwner = null;
      _lyricsPrimary = false;
    }
  }

  LyricsHandover? _handover;
  LyricsHandover? get lyricsHandover => _handover;

  /// The lyrics page for [fromSongId] says whether it has staged [toSongId]
  /// in place. Clearing only touches its own entry.
  void publishLyricsHandover(Object fromSongId, Object? toSongId, {required bool staged}) {
    if (staged && toSongId != null) {
      _handover = LyricsHandover(fromSongId: fromSongId, toSongId: toSongId);
    } else if (_handover?.fromSongId == fromSongId) {
      _handover = null;
    }
  }

  void dispose() {
    playHeld.dispose();
    tapDots.dispose();
    glowFires.dispose();
    glowHold.dispose();
    lyricsWaiting.dispose();
    lyricLineChanges.dispose();
    lineTaps.dispose();
  }
}

/// Provides the signal to Now Playing's controls (LocalNowPlayingTransportSignal).
/// Reading it never subscribes: the signal itself is stable.
class NowPlayingSignalScope extends InheritedWidget {
  const NowPlayingSignalScope({super.key, required this.signal, required super.child});

  final NowPlayingTransportSignal signal;

  static NowPlayingTransportSignal? maybeOf(BuildContext context) =>
      context.getInheritedWidgetOfExactType<NowPlayingSignalScope>()?.signal;

  @override
  bool updateShouldNotify(NowPlayingSignalScope oldWidget) => !identical(signal, oldWidget.signal);
}

/// Publishes this widget as the lyrics column to the signal: where it sits,
/// whether the lyrics are the primary surface right now ([primary]) and where
/// the new title lands ([landingFromTop]); [clipLight] keeps the flow light
/// inside a column beside the cover (TransportPulse.kt reportLyricsSurface).
class LyricsSurfaceReporter extends StatefulWidget {
  const LyricsSurfaceReporter({
    super.key,
    required this.primary,
    required this.landingFromTop,
    this.clipLight = false,
    required this.child,
  });

  final bool primary;
  final double landingFromTop;
  final bool clipLight;
  final Widget child;

  @override
  State<LyricsSurfaceReporter> createState() => _LyricsSurfaceReporterState();
}

class _LyricsSurfaceReporterState extends State<LyricsSurfaceReporter> {
  final Object _owner = Object();
  final GlobalRectProbe _probe = GlobalRectProbe();
  NowPlayingTransportSignal? _signal;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final signal = NowPlayingSignalScope.maybeOf(context);
    if (!identical(signal, _signal)) _signal?.releaseLyricsSurface(_owner);
    _signal = signal;
  }

  @override
  void dispose() {
    _signal?.releaseLyricsSurface(_owner);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Claimed on every build (Android's SideEffect): the last to compose owns it.
    _signal?.claimLyricsSurface(
      _owner,
      _probe,
      primary: widget.primary,
      landingFromTop: widget.landingFromTop,
      clipLight: widget.clipLight,
    );
    return ReportBounds(probe: _probe, child: widget.child);
  }
}

/// Lights waiting for the background beyond this drop the oldest.
const int _maxPendingGlows = 6;

/// A finger-down older than this isn't the line a seek came from.
const int _lineTapFingerValidityMs = 1500;
