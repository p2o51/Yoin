import 'dart:math' as math;

import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/material.dart' show Theme;

import '../../home/widgets/seam.dart' show SeamFlow;
import '../../theme/tokens.dart';
import '../../ui/component/corner_mask.dart';
import '../../ui/component/seam/seam.dart';

/// A live picture of one scroll-edge style (ScrollEdgePreview.kt; Settings ›
/// Scroll edge, the landing): a row of filter chips stands for the fixed
/// chrome, and a column of covers drifts up under it through the REAL seam
/// ([ChromeSeam] pinned to [style]). The drift is slow enough to watch; the
/// seam is fed a speed that swells and eases every seven seconds, so each
/// style also shows how it widens under a fast scroll. At rest ([playing]
/// false), or with animations off, the covers stop part-way through the seam
/// so the look still reads.
///
/// Its rounded corners are painted over in [cornerFill] (what lies behind
/// it), never clipped (HarmonyOS clip seams).
class ScrollEdgePreview extends StatefulWidget {
  const ScrollEdgePreview({
    super.key,
    required this.style,
    required this.playing,
    required this.cornerFill,
    this.columns = 2,
    this.corner = ScrollEdgeTokens.previewCorner,
  });

  final SeamTopStyle style;
  final bool playing;
  final Color cornerFill;
  final int columns;
  final double corner;

  @override
  State<ScrollEdgePreview> createState() => _ScrollEdgePreviewState();
}

class _ScrollEdgePreviewState extends State<ScrollEdgePreview> with TickerProviderStateMixin {
  late final SeamFlow _flow = SeamFlow(vsync: this);
  late final Ticker _ticker = createTicker(_tick);
  final _scroll = ScrollController();
  final _block = GlobalKey();
  double _blockHeight = 0;
  double _position = 0;
  Duration? _last;

  bool get _animate => widget.playing && !MediaQuery.disableAnimationsOf(context);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _flow.reduced = MediaQuery.disableAnimationsOf(context);
    _schedule();
  }

  @override
  void didUpdateWidget(ScrollEdgePreview old) {
    super.didUpdateWidget(old);
    if (old.playing != widget.playing) _schedule();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _scroll.dispose();
    _flow.dispose();
    super.dispose();
  }

  /// After layout: measure one block, then drift or hold.
  void _schedule() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final box = _block.currentContext?.findRenderObject();
      if (box is RenderBox && box.hasSize) _blockHeight = box.size.height;
      if (_blockHeight <= 0) return;
      if (_animate) {
        if (!_ticker.isActive) {
          _last = null;
          _ticker.start();
        }
      } else {
        _ticker.stop();
        _position = _blockHeight * ScrollEdgeTokens.restFraction;
        _jump();
        _flow.drive(travel: 0, speed: 0);
      }
    });
  }

  void _tick(Duration elapsed) {
    final last = _last;
    _last = elapsed;
    if (last == null || _blockHeight <= 0) return;
    final dt = (elapsed - last).inMicroseconds / 1e6;
    final t = elapsed.inMicroseconds / 1e6;
    final swell = 0.5 - 0.5 * math.cos(2 * math.pi * t / ScrollEdgeTokens.cyclePeriodSec);
    final delta =
        (ScrollEdgeTokens.driftSlowDp + (ScrollEdgeTokens.driftFastDp - ScrollEdgeTokens.driftSlowDp) * swell) * dt;
    _position = (_position + delta) % _blockHeight;
    _jump();
    _flow.drive(travel: delta, speed: ScrollEdgeTokens.seamSpeedPeakDp * swell);
  }

  void _jump() {
    if (_scroll.hasClients) _scroll.jumpTo(_position);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final page = colors.surfaceContainerLow;
    return ExcludeSemantics(
      child: CornerMask(
        radius: widget.corner,
        topStart: true,
        topEnd: true,
        bottomStart: true,
        bottomEnd: true,
        fill: widget.cornerFill,
        child: ColoredBox(
          color: page,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(
                  ScrollEdgeTokens.chipsPaddingH,
                  ScrollEdgeTokens.chipsPaddingTop,
                  ScrollEdgeTokens.chipsPaddingH,
                  ScrollEdgeTokens.chipsPaddingBottom,
                ),
                // A narrow preview cuts the chip row off at its edge.
                child: SizedBox(
                  height: ScrollEdgeTokens.chipHeight,
                  child: ClipRect(
                    child: OverflowBox(
                      alignment: AlignmentDirectional.centerStart,
                      maxWidth: double.infinity,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          for (final (i, width) in ScrollEdgeTokens.chipWidths.indexed) ...[
                            if (i > 0) const SizedBox(width: ScrollEdgeTokens.chipGap),
                            Container(
                              width: width,
                              height: ScrollEdgeTokens.chipHeight,
                              decoration: BoxDecoration(
                                color: colors.surfaceContainerHighest,
                                borderRadius: BorderRadius.circular(ScrollEdgeTokens.chipRadius),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              Expanded(
                child: ChromeSeam(
                  style: widget.style,
                  flow: _flow,
                  fullReveal: true,
                  background: SeamBackground([page]),
                  child: SingleChildScrollView(
                    controller: _scroll,
                    physics: const NeverScrollableScrollPhysics(),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (var copy = 0; copy < ScrollEdgeTokens.blockRepeats; copy++)
                          _CoverBlock(key: copy == 0 ? _block : null, columns: widget.columns),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Three rows of covers with two text lines under each; repeated so the drift can wrap without a seam.
class _CoverBlock extends StatelessWidget {
  const _CoverBlock({super.key, required this.columns});

  final int columns;

  @override
  Widget build(BuildContext context) {
    final ink = Theme.of(context).colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        ScrollEdgeTokens.blockPaddingH,
        0,
        ScrollEdgeTokens.blockPaddingH,
        ScrollEdgeTokens.rowGap,
      ),
      child: Column(
        children: [
          for (var row = 0; row < 3; row++) ...[
            if (row > 0) const SizedBox(height: ScrollEdgeTokens.rowGap),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var column = 0; column < columns; column++) ...[
                  if (column > 0) const SizedBox(width: ScrollEdgeTokens.columnGap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        AspectRatio(
                          aspectRatio: 1,
                          child: SeamDissolve(
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                borderRadius: BorderRadius.circular(ShapeTokens.cover),
                                gradient: LinearGradient(
                                  begin: Alignment.topLeft,
                                  end: Alignment.bottomRight,
                                  colors:
                                      scrollEdgePreviewCovers[(row * columns + column) %
                                          scrollEdgePreviewCovers.length],
                                ),
                              ),
                            ),
                          ),
                        ),
                        _TextLine(
                          fraction: 0.8,
                          color: ink.withValues(alpha: 0.35),
                          top: ScrollEdgeTokens.firstLineTop,
                        ),
                        _TextLine(
                          fraction: 0.5,
                          color: ink.withValues(alpha: 0.22),
                          top: ScrollEdgeTokens.secondLineTop,
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// A line of "text": it fades into the seam like text.
class _TextLine extends StatelessWidget {
  const _TextLine({required this.fraction, required this.color, required this.top});

  final double fraction;
  final Color color;
  final double top;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(top: top),
    child: FractionallySizedBox(
      alignment: AlignmentDirectional.centerStart,
      widthFactor: fraction,
      child: SeamFade(
        fontSize: 0,
        child: Container(
          height: ScrollEdgeTokens.lineHeight,
          decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(ScrollEdgeTokens.lineRadius)),
        ),
      ),
    ),
  );
}

/// The preview's made-up covers (ScrollEdgePreview.kt CoverColors): artwork, not theme colours.
const List<List<Color>> scrollEdgePreviewCovers = [
  [Color(0xFF9113FF), Color(0xFF5B2BD0)],
  [Color(0xFFFF59CD), Color(0xFFD9479F)],
  [Color(0xFF192396), Color(0xFF4253D6)],
  [Color(0xFFF2A541), Color(0xFFE0702E)],
  [Color(0xFF2FA58A), Color(0xFF1E6F86)],
  [Color(0xFF7A5CFF), Color(0xFFC49BFF)],
];
