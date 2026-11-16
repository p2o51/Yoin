import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'marquee.dart';
import 'overlay_controller.dart';

/// The three elements that fly between the pill and the player (Android
/// sharedBounds keys np_cover / np_title / np_artist).
enum FlightElement { cover, title, artist }

/// Where each element sits on each side. The pill and the player wrap their
/// element in a [FlightSlot] with these keys; the flight layer reads their
/// live rects while it paints.
class FlightKeys {
  /// The flight layer's own box (rects are converted into it).
  final GlobalKey layer = GlobalKey(debugLabel: 'flight.layer');
  final Map<FlightElement, GlobalKey> pill = {
    for (final e in FlightElement.values) e: GlobalKey(debugLabel: 'pill.$e'),
  };
  final Map<FlightElement, GlobalKey> player = {
    for (final e in FlightElement.values) e: GlobalKey(debugLabel: 'player.$e'),
  };

  /// Each slot's last measured rect during this flight. A slot that leaves
  /// the tree mid-flight (the bar unmounting, a relayout) keeps its end of
  /// the flight where it was last seen instead of dropping the proxy: both
  /// real slots are hidden while the flight is active, so a dropped proxy is
  /// a visible blink.
  final Map<GlobalKey, Rect> lastRects = {};

  /// Which end the flight leaves from: the pill (open) or the player (close).
  bool leavesPill = true;

  /// The hero title's marquee, read when a close starts ([heroTitleScroll]).
  final MarqueeProbe heroTitleMarquee = MarqueeProbe();

  /// How far the hero title had scrolled when this flight left the player
  /// (0 for an open: the slot is hidden, so its marquee rests at the start).
  double heroTitleScroll = 0;

  /// The hero texts' live horizontal scale springs (the hero registers
  /// them): the flight lands on their value of the moment, not on a rest
  /// value the spring may not have reached yet.
  final Map<FlightElement, ValueListenable<double>> heroScale = {};

  /// The rect [key]'s end of the flight is drawn at this frame, from its
  /// [measured] rect (null: not laid out). The end the flight leaves keeps
  /// the rect it was first seen at — Compose sharedBounds starts from the
  /// outgoing bounds, it doesn't chase them: the player slides away (and
  /// the bar drops) while the proxies are already flying, and chasing them
  /// pulled the hero texts off the bottom of the screen, so on close they
  /// landed in the pill frames after the cover. The arriving end is live.
  Rect? resolve(GlobalKey key, Rect? measured) {
    final leaving = leavesPill ? pill.containsValue(key) : player.containsValue(key);
    if (leaving && lastRects.containsKey(key)) return lastRects[key];
    if (measured == null) return lastRects[key];
    return lastRects[key] = measured;
  }
}

/// What the flying proxies draw (the pill's content, captured at flight start).
@immutable
final class FlightContent {
  const FlightContent({
    required this.title,
    required this.artist,
    required this.coverUrl,
    required this.pillTitleStyle,
    required this.pillArtistStyle,
    required this.heroTitleStyle,
    required this.heroArtistStyle,
    this.heroTitleScaleX = 1,
    this.heroArtistScaleX = 1,
  });

  final String title;
  final String artist;
  final String? coverUrl;
  final TextStyle pillTitleStyle;
  final TextStyle pillArtistStyle;
  final TextStyle heroTitleStyle;
  final TextStyle heroArtistStyle;

  /// The hero texts' resting horizontal scale (heroRestScale): the proxies
  /// grow into it so the landing matches the real text.
  final double heroTitleScaleX;
  final double heroArtistScaleX;

  @override
  bool operator ==(Object other) =>
      other is FlightContent &&
      other.title == title &&
      other.artist == artist &&
      other.coverUrl == coverUrl &&
      other.pillTitleStyle == pillTitleStyle &&
      other.pillArtistStyle == pillArtistStyle &&
      other.heroTitleStyle == heroTitleStyle &&
      other.heroArtistStyle == heroArtistStyle &&
      other.heroTitleScaleX == heroTitleScaleX &&
      other.heroArtistScaleX == heroArtistScaleX;

  @override
  int get hashCode => Object.hash(
    title,
    artist,
    coverUrl,
    pillTitleStyle,
    pillArtistStyle,
    heroTitleStyle,
    heroArtistStyle,
    heroTitleScaleX,
    heroArtistScaleX,
  );
}

/// One element's slot: measured by [keyFor], hidden while the proxy flies.
class FlightSlot extends StatelessWidget {
  const FlightSlot({super.key, required this.element, required this.pill, required this.child});

  final FlightElement element;

  /// True on the pill side, false on the player side.
  final bool pill;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final controller = NowPlayingOverlayScope.maybeOf(context);
    if (controller == null || FlightSlotsOff.of(context)) return child;
    final key = pill ? controller.flightKeys.pill[element]! : controller.flightKeys.player[element]!;
    final hidden = controller.flightActive && controller.flightCanFly(element);
    Widget slot = KeyedSubtree(key: key, child: child);
    // A hidden text slot holds its marquee at the start of its line: the
    // proxy draws the line unscrolled, so the slot must reappear unscrolled
    // (the marquee restarts its initial delay from the handover). Always in
    // the tree for text slots, so hiding never reshapes the subtree.
    if (element != FlightElement.cover) slot = TickerMode(enabled: !hidden, child: slot);
    return Opacity(opacity: hidden ? 0 : 1, child: slot);
  }
}

/// Whether the [FlightSlot]s below take part in the flight: off for the
/// outgoing form of the bar while the chrome cross-fades between forms (the
/// incoming form's pill owns the slot keys; two would collide).
class FlightSlotsOff extends InheritedWidget {
  const FlightSlotsOff({super.key, required this.off, required super.child});

  final bool off;

  static bool of(BuildContext context) => context.dependOnInheritedWidgetOfExactType<FlightSlotsOff>()?.off ?? false;

  @override
  bool updateShouldNotify(FlightSlotsOff oldWidget) => oldWidget.off != off;
}

/// The flight layer, above everything: paints the proxies at the live
/// rects of both slots, lerped by the controller's [NowPlayingOverlayController.flight]
/// spring. Each text is drawn twice, as Compose sharedBounds does: the pill's
/// own line (pill style, laid out at the pill's size) and the player's line
/// (hero style, laid out at the player's size), each scaled by height from
/// its start edge (scaleToBounds FillHeight, CenterStart) so neither
/// reflows, crossfading with the flight ([flightTextPoses]). At each end the
/// proxy is that end's real text at scale 1, so the handover is invisible;
/// the cover lerps its rect and corner radius (4 → 16).
class FlightLayer extends StatefulWidget {
  const FlightLayer({super.key, required this.controller, required this.content});

  final NowPlayingOverlayController controller;
  final FlightContent? content;

  @override
  State<FlightLayer> createState() => _FlightLayerState();
}

class _FlightLayerState extends State<FlightLayer> {
  ImageStream? _stream;
  ImageStreamListener? _listener;
  ui.Image? _image;
  String? _url;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _resolve();
  }

  @override
  void didUpdateWidget(FlightLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    _resolve();
  }

  void _resolve() {
    final url = widget.content?.coverUrl;
    if (url == _url) return;
    _url = url;
    _unlisten();
    _image = null;
    if (url == null) return;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    // The pill's decode (same url + cache width): already in memory.
    final provider = ResizeImage(NetworkImage(url), width: (pillCoverRequestDp * dpr).round());
    final stream = provider.resolve(createLocalImageConfiguration(context));
    final listener = ImageStreamListener((info, _) {
      if (mounted) setState(() => _image = info.image);
    }, onError: (_, _) {});
    stream.addListener(listener);
    _stream = stream;
    _listener = listener;
  }

  void _unlisten() {
    final listener = _listener;
    if (listener != null) _stream?.removeListener(listener);
    _stream = null;
    _listener = null;
  }

  @override
  void dispose() {
    _unlisten();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final content = widget.content;
    if (content == null) return const SizedBox.shrink();
    return IgnorePointer(
      child: RepaintBoundary(
        key: widget.controller.flightKeys.layer,
        child: CustomPaint(
          size: Size.infinite,
          painter: _FlightPainter(
            controller: widget.controller,
            content: content,
            image: _image,
            direction: Directionality.of(context),
            scaler: MediaQuery.textScalerOf(context),
          ),
        ),
      ),
    );
  }
}

/// The pill decodes its cover at this fixed size so twins hit the same cache entry.
const double pillCoverRequestDp = 96;

/// The hero title marquee's edge fade (Android MarqueeText fadeEdge 28 dp);
/// the hero and the flight's proxy of it draw the same one.
const double heroTitleEdgeFade = 28;

class _FlightPainter extends CustomPainter {
  _FlightPainter({
    required this.controller,
    required this.content,
    required this.image,
    required this.direction,
    required this.scaler,
  }) : super(repaint: Listenable.merge([controller, controller.flight.animation, controller.motion]));

  final NowPlayingOverlayController controller;
  final FlightContent content;
  final ui.Image? image;
  final TextDirection direction;
  final TextScaler scaler;

  final Map<FlightElement, ({TextPainter pill, TextPainter hero})> _painters = {};

  Rect? _rectOf(GlobalKey key, RenderBox self) {
    final keys = controller.flightKeys;
    RenderObject? box;
    try {
      box = key.currentContext?.findRenderObject();
    } on FlutterError {
      return keys.resolve(key, null); // The slot is leaving the tree this frame.
    }
    if (box is! RenderBox || !box.attached || !box.hasSize) return keys.resolve(key, null);
    final topLeft = self.globalToLocal(box.localToGlobal(Offset.zero));
    final bottomRight = self.globalToLocal(box.localToGlobal(box.size.bottomRight(Offset.zero)));
    return keys.resolve(key, Rect.fromPoints(topLeft, bottomRight));
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (!controller.flightActive) return;
    final self = controller.flightKeys.layer.currentContext?.findRenderObject();
    if (self is! RenderBox || !self.hasSize) return;
    final t = controller.flight.value;
    final keys = controller.flightKeys;

    // Cover.
    if (image != null && controller.flightCanFly(FlightElement.cover)) {
      final from = _rectOf(keys.pill[FlightElement.cover]!, self);
      final to = _rectOf(keys.player[FlightElement.cover]!, self);
      if (from != null && to != null) {
        final rect = Rect.lerp(from, to, t)!;
        final radius = ui.lerpDouble(4, 16, t)!;
        canvas.save();
        canvas.clipRSuperellipse(RSuperellipse.fromRectAndRadius(rect, Radius.circular(radius)));
        paintImage(canvas: canvas, rect: rect, image: image!, fit: BoxFit.cover, filterQuality: FilterQuality.medium);
        canvas.restore();
      }
    }

    // Title and artist.
    void text(FlightElement element, String value, TextStyle pillStyle, TextStyle heroStyle, double heroScaleX) {
      final from = _rectOf(keys.pill[element]!, self);
      final to = _rectOf(keys.player[element]!, self);
      if (from == null || to == null) return;
      final painters = _painters[element] ??= (pill: _layout(value, pillStyle), hero: _layout(value, heroStyle));
      final poses = flightTextPoses(
        from: from,
        to: to,
        t: t,
        pillSize: painters.pill.size,
        heroSize: painters.hero.size,
        heroScaleX: keys.heroScale[element]?.value ?? heroScaleX,
      );
      _paintLine(canvas, painters.pill, poses.pill);
      if (element == FlightElement.title) {
        // The hero title is a marquee: scrolled as it was left, with its edge fades.
        _paintMarqueeLine(canvas, painters.hero, poses.hero, box: to.width, scroll: keys.heroTitleScroll);
      } else {
        _paintLine(canvas, painters.hero, poses.hero);
      }
    }

    text(FlightElement.title, content.title, content.pillTitleStyle, content.heroTitleStyle, content.heroTitleScaleX);
    text(
      FlightElement.artist,
      content.artist,
      content.pillArtistStyle,
      content.heroArtistStyle,
      content.heroArtistScaleX,
    );
  }

  // The same layout as Marquee / Text: one line, the app's text scaler.
  TextPainter _layout(String value, TextStyle style) => TextPainter(
    text: TextSpan(text: value, style: style),
    maxLines: 1,
    textDirection: direction,
    textScaler: scaler,
  )..layout();

  void _paintLine(Canvas canvas, TextPainter painter, FlightTextPose pose) {
    if (pose.alpha <= 0) return;
    canvas.save();
    canvas.clipRect(pose.clip);
    canvas.translate(pose.origin.dx, pose.origin.dy);
    canvas.scale(pose.scaleX, pose.scale);
    if (pose.alpha < 1) {
      canvas.saveLayer(null, Paint()..color = Color.fromRGBO(0, 0, 0, pose.alpha));
      painter.paint(canvas, Offset.zero);
      canvas.restore();
    } else {
      painter.paint(canvas, Offset.zero);
    }
    canvas.restore();
  }

  /// [painter]'s line as the hero's [Marquee] draws it in a [box] that wide:
  /// a line that fits is a plain Text; one that overflows is clipped to the
  /// box, scrolled left by [scroll] (wrapping after a third-of-the-box gap)
  /// and masked by the 28 dp edge fades.
  void _paintMarqueeLine(
    Canvas canvas,
    TextPainter painter,
    FlightTextPose pose, {
    required double box,
    required double scroll,
  }) {
    if (pose.alpha <= 0) return;
    if (painter.width <= box) {
      _paintLine(canvas, painter, pose);
      return;
    }
    final bounds = Rect.fromLTWH(0, 0, box, painter.height);
    final lap = painter.width + box / 3;
    canvas.save();
    canvas.clipRect(pose.clip);
    canvas.translate(pose.origin.dx, pose.origin.dy);
    canvas.scale(pose.scaleX, pose.scale);
    canvas.clipRect(bounds);
    canvas.saveLayer(bounds, Paint()..color = Color.fromRGBO(0, 0, 0, pose.alpha));
    painter.paint(canvas, Offset(-scroll, 0));
    painter.paint(canvas, Offset(-scroll + lap, 0));
    final stop = (heroTitleEdgeFade / box).clamp(0.0, 0.5);
    canvas.drawRect(
      bounds,
      Paint()
        ..blendMode = BlendMode.dstIn
        ..shader = LinearGradient(
          colors: const [Color(0x00000000), Color(0xFF000000), Color(0xFF000000), Color(0x00000000)],
          stops: [0, stop, 1 - stop, 1],
        ).createShader(bounds),
    );
    canvas.restore();
    canvas.restore();
  }

  @override
  bool shouldRepaint(_FlightPainter old) =>
      old.image != image || old.content != content || old.direction != direction || old.scaler != scaler;
}

/// Where one flying line is drawn: its text box's top-left [origin], its
/// [scale] (height) and [scaleX] (height × the hero's horizontal rest
/// scale), its [alpha] in the crossfade, and its [clip].
@immutable
final class FlightTextPose {
  const FlightTextPose({
    required this.origin,
    required this.scale,
    required this.scaleX,
    required this.alpha,
    required this.clip,
  });

  final Offset origin;
  final double scale;
  final double scaleX;
  final double alpha;
  final Rect clip;
}

/// The crossfade window of the two lines, in flight progress: the pill's
/// line alone below [start], the player's alone above [end].
const double flightTextFadeStart = 0.25;
const double flightTextFadeEnd = 0.75;

/// The two poses of one flying text at flight progress [t] (0 = pill, 1 =
/// player) between the slots [from] and [to]. Each line is scaled from its
/// own end's size, so at t = 0 the pill line sits exactly on the pill's
/// text (scale 1, alpha 1, clipped like the pill's marquee) and at t = 1 the
/// hero line sits exactly on the player's.
({FlightTextPose pill, FlightTextPose hero}) flightTextPoses({
  required Rect from,
  required Rect to,
  required double t,
  required Size pillSize,
  required Size heroSize,
  double heroScaleX = 1,
}) {
  final rect = Rect.lerp(from, to, t)!;
  final heroAlpha = ((t - flightTextFadeStart) / (flightTextFadeEnd - flightTextFadeStart)).clamp(0.0, 1.0);

  Rect clipFor(double width) => Rect.fromLTWH(rect.left, rect.top - rect.height, width, rect.height * 3);

  // The pill's line, scaled from the pill slot's height. A line that fits
  // the pill is unclipped (a Text); one that overflows is clipped to the
  // slot like the marquee.
  final pillScale = rect.height / math.max(1, from.height);
  final pillWidth = pillSize.width * pillScale;
  final pill = FlightTextPose(
    origin: Offset(rect.left, rect.top + (rect.height - pillSize.height * pillScale) / 2),
    scale: pillScale,
    scaleX: pillScale,
    alpha: 1 - heroAlpha,
    clip: clipFor(pillSize.width <= from.width + 0.5 ? math.max(rect.width, pillWidth) : rect.width),
  );

  // The player's line, scaled from the player slot's height, growing into
  // the hero's resting horizontal scale.
  final heroScale = rect.height / math.max(1, to.height);
  final sx = ui.lerpDouble(1, heroScaleX, t)!;
  final hero = FlightTextPose(
    origin: Offset(rect.left, rect.top + (rect.height - heroSize.height * heroScale) / 2),
    scale: heroScale,
    scaleX: heroScale * sx,
    alpha: heroAlpha,
    clip: clipFor(math.max(rect.width, to.width * heroScale)),
  );
  return (pill: pill, hero: hero);
}
