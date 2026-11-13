import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

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
  });

  final String title;
  final String artist;
  final String? coverUrl;
  final TextStyle pillTitleStyle;
  final TextStyle pillArtistStyle;
  final TextStyle heroTitleStyle;
  final TextStyle heroArtistStyle;
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
    if (controller == null) return child;
    final key = pill ? controller.flightKeys.pill[element]! : controller.flightKeys.player[element]!;
    final hidden = controller.flightActive && controller.flightCanFly(element);
    return Opacity(
      opacity: hidden ? 0 : 1,
      child: KeyedSubtree(key: key, child: child),
    );
  }
}

/// The flight layer, above everything: paints the proxies at the live
/// rects of both slots, lerped by the controller's [NowPlayingOverlayController.flight]
/// spring. Texts are laid out once at the player's size and scaled by
/// height from their start edge (scaleToBounds FillHeight, CenterStart), so
/// they never reflow; the cover lerps its rect and corner radius (4 → 16).
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

  TextPainter? _title;
  TextPainter? _artist;

  Rect? _rectOf(GlobalKey key, RenderBox self) {
    RenderObject? box;
    try {
      box = key.currentContext?.findRenderObject();
    } on FlutterError {
      return null; // The slot is leaving the tree this frame.
    }
    if (box is! RenderBox || !box.attached || !box.hasSize) return null;
    final topLeft = self.globalToLocal(box.localToGlobal(Offset.zero));
    final bottomRight = self.globalToLocal(box.localToGlobal(box.size.bottomRight(Offset.zero)));
    return Rect.fromPoints(topLeft, bottomRight);
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
    void text(
      FlightElement element,
      String value,
      TextStyle pillStyle,
      TextStyle heroStyle,
      TextPainter? cached,
      void Function(TextPainter) store,
    ) {
      final from = _rectOf(keys.pill[element]!, self);
      final to = _rectOf(keys.player[element]!, self);
      if (from == null || to == null) return;
      final painter =
          cached ??
          (TextPainter(
            text: TextSpan(text: value, style: heroStyle),
            maxLines: 1,
            textDirection: direction,
            textScaler: scaler,
          )..layout());
      store(painter);
      final rect = Rect.lerp(from, to, t)!;
      // Scale by height from the start edge; colour crosses with t.
      final scale = rect.height / math.max(1, to.height);
      final color = Color.lerp(pillStyle.color, heroStyle.color, t);
      canvas.save();
      canvas.clipRect(
        Rect.fromLTWH(rect.left, rect.top - rect.height, math.max(rect.width, to.width * scale), rect.height * 3),
      );
      canvas.translate(rect.left, rect.top + (rect.height - painter.height * scale) / 2);
      canvas.scale(scale);
      if (color != null && color != heroStyle.color) {
        canvas.saveLayer(null, Paint()..colorFilter = ColorFilter.mode(color, BlendMode.srcIn));
        painter.paint(canvas, Offset.zero);
        canvas.restore();
      } else {
        painter.paint(canvas, Offset.zero);
      }
      canvas.restore();
    }

    text(FlightElement.title, content.title, content.pillTitleStyle, content.heroTitleStyle, _title, (p) => _title = p);
    text(
      FlightElement.artist,
      content.artist,
      content.pillArtistStyle,
      content.heroArtistStyle,
      _artist,
      (p) => _artist = p,
    );
  }

  @override
  bool shouldRepaint(_FlightPainter old) =>
      old.image != image || old.content != content || old.direction != direction || old.scaler != scaler;
}
