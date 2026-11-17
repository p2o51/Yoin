import 'package:flutter/material.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';

/// Which way the bubble's tail points (at the mascot).
enum BubbleTail { up, left }

/// The bubble's tail, written by the stage while laying out and read by the
/// bubble while painting (LandingStage.kt BubbleTailState).
class BubbleTailState extends ChangeNotifier {
  BubbleTail _side = BubbleTail.up;
  double _offset = 0;

  BubbleTail get side => _side;

  /// The tip along the edge, from the bubble's start.
  double get offset => _offset;

  void set(BubbleTail side, double offset) {
    if (side == _side && offset == _offset) return;
    _side = side;
    _offset = offset;
    notifyListeners();
  }
}

/// The mascot's speech bubble (LandingBubble): a rounded card whose tail
/// points at the mascot. Its title is Google Sans Flex rounded (ROND 100).
class LandingBubble extends StatelessWidget {
  const LandingBubble({
    super.key,
    required this.title,
    this.body,
    required this.tail,
    required this.background,
    required this.content,
  });

  final String title;
  final String? body;
  final BubbleTailState tail;
  final Color background;
  final Color content;

  @override
  Widget build(BuildContext context) {
    final body = this.body;
    return CustomPaint(
      painter: _BubblePainter(tail: tail, color: background),
      child: Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(
          LandingTokens.bubblePaddingStart,
          LandingTokens.bubblePaddingTop,
          LandingTokens.bubblePaddingStart,
          LandingTokens.bubblePaddingBottom,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: gsf(
                LandingTokens.bubbleTitleSize,
                weight: 600,
                height: LandingTokens.bubbleTitleLine / LandingTokens.bubbleTitleSize,
                round: 100,
              ).copyWith(color: content),
            ),
            if (body != null && body.trim().isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: LandingTokens.bubbleBodyGap),
                child: Text(
                  body,
                  style: Theme.of(context).textTheme.bodyLarge!.copyWith(
                    fontSize: LandingTokens.bubbleBodySize,
                    height: LandingTokens.bubbleBodyLine / LandingTokens.bubbleBodySize,
                    color: content.withValues(alpha: 0.82),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _BubblePainter extends CustomPainter {
  _BubblePainter({required this.tail, required this.color}) : super(repaint: tail);

  final BubbleTailState tail;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const r = LandingTokens.bubbleCorner;
    final path = Path()
      ..addRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(r)))
      ..addPath(bubbleTailPath(tail.side, tail.offset, size), Offset.zero);
    canvas.drawPath(path, Paint()..color = color);
  }

  @override
  bool shouldRepaint(_BubblePainter old) => old.tail != tail || old.color != color;
}

/// A 22 × 11 tail with a rounded tip; [offset] is its tip along the edge.
Path bubbleTailPath(BubbleTail side, double offset, Size size) {
  const half = LandingTokens.bubbleTail;
  const depth = LandingTokens.bubbleTail;
  const edge = LandingTokens.bubbleTailEdge;
  final path = Path();
  if (side == BubbleTail.up) {
    final x = offset.clamp(half + edge, (size.width - half - edge).clamp(half + edge, double.infinity));
    path
      ..moveTo(x - half, 1)
      ..cubicTo(x - half * 0.36, 1, x - half * 0.24, -depth, x, -depth)
      ..cubicTo(x + half * 0.24, -depth, x + half * 0.36, 1, x + half, 1)
      ..close();
  } else {
    final y = offset.clamp(half + edge, (size.height - half - edge).clamp(half + edge, double.infinity));
    path
      ..moveTo(1, y - half)
      ..cubicTo(1, y - half * 0.36, -depth, y - half * 0.24, -depth, y)
      ..cubicTo(-depth, y + half * 0.24, 1, y + half * 0.36, 1, y + half)
      ..close();
  }
  return path;
}
