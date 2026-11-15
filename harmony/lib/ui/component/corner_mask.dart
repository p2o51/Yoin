import 'dart:math' as math;

import 'package:flutter/widgets.dart';

/// Rounds a surface's corners WITHOUT a clip layer: the square content is
/// painted as is and the corner wedges outside the round are painted over
/// with what lies behind the surface ([fill] colour or [gradient] across the
/// whole box). On HarmonyOS an anti-aliased clip over a surface that hosts a
/// modal sheet / scrolling layers rasterised black wedges and a dark seam at
/// the clip edge (QA r2_33, r3_03, r3_14); painting the corners avoids the
/// clip altogether.
class CornerMask extends StatelessWidget {
  const CornerMask({
    super.key,
    required this.child,
    required this.radius,
    this.topStart = false,
    this.bottomStart = false,
    this.topEnd = false,
    this.bottomEnd = false,
    this.fill,
    this.gradient,
  }) : assert(fill != null || gradient != null);

  final Widget child;
  final double radius;
  final bool topStart;
  final bool bottomStart;
  final bool topEnd;
  final bool bottomEnd;
  final Color? fill;
  final Gradient? gradient;

  @override
  Widget build(BuildContext context) {
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return CustomPaint(
      foregroundPainter: _CornerPainter(
        radius: radius,
        topLeft: rtl ? topEnd : topStart,
        bottomLeft: rtl ? bottomEnd : bottomStart,
        topRight: rtl ? topStart : topEnd,
        bottomRight: rtl ? bottomStart : bottomEnd,
        fill: fill,
        gradient: gradient,
      ),
      child: child,
    );
  }
}

class _CornerPainter extends CustomPainter {
  _CornerPainter({
    required this.radius,
    required this.topLeft,
    required this.bottomLeft,
    required this.topRight,
    required this.bottomRight,
    required this.fill,
    required this.gradient,
  });

  final double radius;
  final bool topLeft;
  final bool bottomLeft;
  final bool topRight;
  final bool bottomRight;
  final Color? fill;
  final Gradient? gradient;

  @override
  void paint(Canvas canvas, Size size) {
    final r = math.min(radius, math.min(size.width, size.height) / 2);
    if (r <= 0.01) return;
    final rect = Offset.zero & size;
    final paint = Paint()..isAntiAlias = true;
    if (gradient != null) {
      paint.shader = gradient!.createShader(rect);
    } else {
      paint.color = fill!;
    }
    // A slightly larger wedge than the round so no hairline of content shows.
    const bleed = 0.5;
    void wedge(Offset corner, double sx, double sy) {
      final path = Path()
        ..moveTo(corner.dx - sx * bleed, corner.dy - sy * bleed)
        ..lineTo(corner.dx + sx * r, corner.dy - sy * bleed)
        ..lineTo(corner.dx + sx * r, corner.dy)
        ..arcToPoint(
          Offset(corner.dx, corner.dy + sy * r),
          radius: Radius.circular(r),
          clockwise: sx * sy < 0,
        )
        ..lineTo(corner.dx - sx * bleed, corner.dy + sy * r)
        ..close();
      canvas.drawPath(path, paint);
    }

    if (topLeft) wedge(Offset.zero, 1, 1);
    if (topRight) wedge(Offset(size.width, 0), -1, 1);
    if (bottomLeft) wedge(Offset(0, size.height), 1, -1);
    if (bottomRight) wedge(Offset(size.width, size.height), -1, -1);
  }

  @override
  bool shouldRepaint(_CornerPainter old) =>
      old.radius != radius ||
      old.topLeft != topLeft ||
      old.bottomLeft != bottomLeft ||
      old.topRight != topRight ||
      old.bottomRight != bottomRight ||
      old.fill != fill ||
      old.gradient != gradient;
}
