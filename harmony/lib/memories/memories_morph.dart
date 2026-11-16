/// The card ⇄ diary shared-element morph (Android showcase MemoriesMorph,
/// twostate4 `renderPages` / `pair` / `barFollow` / `renderV`, v4.1).
///
/// p ([MemoriesDiaryController]) is the only driver; nothing here owns a
/// value of its own except the bar cover's alpha chase. Every element is laid
/// out complete in both states and only TRANSFORMED here (translation, scale,
/// alpha, a per-frame corner) by a [MorphLayer]: the card and the diary are
/// never resized or cut while p moves, so a drag on the bar, the diary's pull
/// and a back all scrub the same flight. Anchors are read from layout, per
/// page, transform-free ([morphLayoutOffset]), and only at paint.
///
///  · the COVER shrinks and flies into the bar's leading 40 dp slot (size
///    leads position), 8 → 4 dp corners, handing over by alpha to the bar's
///    own cover between fp .62 and .9;
///  · the TITLE holds until p .2, then flies to the diary title (crossfading
///    its twin over .42–.78); the 96 dp EMBLEM travels with it to the native
///    48 right of the diary title (caption first, hand-over .6–.95);
///  · the album row lifts 36 and fades by p .32; the excerpt and the actions
///    drop 56 and fade;
///  · the diary's blocks rise, staggered, behind the title.
///
/// Frozen collapse: closing a diary whose title has already scrolled under the
/// bar never rewinds the text. The text sinks and fades in place first, the
/// cover waits in the bar (fp = p / .55) and the card's title and emblem fade
/// in where they live; the scroll returns to 0 unseen at p < .05.
///
/// Reduced motion: the same p, alpha only — the card is gone by p .5 and the
/// diary starts there, so two layers of text are never on screen at once.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'copy/memory_voice.dart';
import 'memories_layout.dart';
import 'memories_motion.dart';

// ---------------------------------------------------------------- pure choreography

/// How far the title (and the emblem) has travelled: held until p .2; never while frozen.
double morphTitleTravel(double p, {required bool frozen}) =>
    frozen ? 0 : ((p - MemoriesTokens.titleHold) / (1 - MemoriesTokens.titleHold)).clamp(0.0, 1.0);

/// The cover's flight progress fp: p, or, frozen, p / .55 (it waits in the bar while the text sinks).
double morphCoverProgress(double p, {required bool frozen}) =>
    frozen ? (p / MemoriesTokens.frozenCoverSpan).clamp(0.0, 1.0) : p.clamp(0.0, 1.0);

/// The bar cover's shown alpha: the chase held inside its hand-over window,
/// so it is 0 below fp .62 (it never flies at 2.4×) and the page's cover is
/// gone exactly at fp 1.
double barCoverShown(double chase, double fp) {
  final lo = smoothstep(MemoriesTokens.coverFloorFrom, 1, fp);
  final hi = smoothstep(MemoriesTokens.coverSwapFrom, MemoriesTokens.coverSwapTo, fp);
  return math.max(lo, math.min(hi, chase));
}

/// The bar cover's chase target.
double barCoverTarget(double fp) => smoothstep(MemoriesTokens.coverSwapFrom, MemoriesTokens.coverSwapTo, fp);

/// A flying cover's pose: translation from its rest centre, uniform scale,
/// and the corner as SHOWN.
@immutable
class CoverPose {
  const CoverPose({required this.dx, required this.dy, required this.scale, required this.corner});
  final double dx;
  final double dy;
  final double scale;
  final double corner;
}

/// The cover at fp: its size leads its position (1 − (1 − fp)²), it rides
/// the bar's parallax as it joins it, and its shown corner goes card → bar.
CoverPose morphCoverPose({
  required double fp,
  required Offset coverCenter,
  required Offset barCenter,
  required double coverSize,
  required double barCoverSize,
  required double parallax,
  required double cardCorner,
  required double barCorner,
}) {
  final lead = 1 - (1 - fp) * (1 - fp);
  final scale = lerpDouble(1, coverSize > 0 ? barCoverSize / coverSize : 1, lead);
  return CoverPose(
    dx: (barCenter.dx - coverCenter.dx) * fp + parallax * fp,
    dy: (barCenter.dy - coverCenter.dy) * fp,
    scale: scale,
    corner: lerpDouble(cardCorner, barCorner, fp),
  );
}

/// A line of text as the morph reads it: its box's centre x, top y and font size, in page coordinates.
@immutable
class TextAnchor {
  const TextAnchor({required this.cx, required this.y, required this.fontSize});
  final double cx;
  final double y;
  final double fontSize;

  TextAnchor copyWith({double? cx, double? y}) => TextAnchor(cx: cx ?? this.cx, y: y ?? this.y, fontSize: fontSize);

  @override
  bool operator ==(Object other) => other is TextAnchor && other.cx == cx && other.y == y && other.fontSize == fontSize;

  @override
  int get hashCode => Object.hash(cx, y, fontSize);
}

/// Prototype `pair`: both copies travel one path (centre x, top y, scaled
/// from the top centre) and hand over by alpha inside [swapFrom]…[swapTo] of
/// [t]. First = the card's copy, second = the diary's.
(MorphPose, MorphPose) morphTextPair(
  TextAnchor card,
  TextAnchor diary,
  double t, {
  double swapFrom = MemoriesTokens.titleSwapFrom,
  double swapTo = MemoriesTokens.titleSwapTo,
}) {
  final cx = lerpDouble(card.cx, diary.cx, t);
  final y = lerpDouble(card.y, diary.y, t);
  final k = smoothstep(swapFrom, swapTo, t);
  final a = MorphPose(
    dx: cx - card.cx,
    dy: y - card.y,
    scale: lerpDouble(1, _ratio(diary.fontSize, card.fontSize), t),
    alpha: 1 - k,
    origin: Alignment.topCenter,
  );
  final b = MorphPose(
    dx: cx - diary.cx,
    dy: y - diary.y,
    scale: lerpDouble(_ratio(card.fontSize, diary.fontSize), 1, t),
    alpha: k,
    origin: Alignment.topCenter,
  );
  return (a, b);
}

/// The 96 and the native 48 on one flight ([te] = the title's travel): poses
/// relative to each one's rest.
@immutable
class EmblemPose {
  const EmblemPose({required this.seal, required this.dem, required this.captionAlpha});

  /// The card's 96.
  final MorphPose seal;

  /// The diary's native 48.
  final MorphPose dem;
  final double captionAlpha;
}

EmblemPose morphEmblemPose({
  required double te,
  required Offset sealCenter,
  required Offset demCenter,
  required double sealSize,
  required double demSize,
}) {
  final s = lerpDouble(1, _ratio(demSize, sealSize), te);
  final ex = (demCenter.dx - sealCenter.dx) * te;
  final ey = (demCenter.dy - sealCenter.dy) * te;
  final k = smoothstep(MemoriesTokens.emblemSwapFrom, MemoriesTokens.emblemSwapTo, te);
  final landed = te >= 1;
  return EmblemPose(
    seal: MorphPose(dx: ex, dy: ey, scale: s, alpha: 1 - k),
    dem: landed
        ? MorphPose(alpha: k)
        : MorphPose(
            dx: ex + sealCenter.dx - demCenter.dx,
            dy: ey + sealCenter.dy - demCenter.dy,
            scale: _ratio(sealSize * s, demSize),
            alpha: k,
          ),
    captionAlpha: 1 - smoothstep(0, MemoriesTokens.captionFadeTo, te),
  );
}

/// A diary block's rise: (1 − p)(lag + 28k); none while frozen (the whole text sinks as one instead).
double diaryBlockOffset(double p, int k, {required double lag, required double stagger, required bool frozen}) {
  if (frozen || p >= 1) return 0;
  return (1 - p.clamp(0.0, 1.0)) * (lag + math.min(k, MemoriesTokens.blockMaxIndex) * stagger);
}

double diaryBlockAlpha(double p, int k, {required bool frozen}) {
  final kk = math.min(k, MemoriesTokens.blockMaxIndex);
  return frozen
      ? smoothstep(
          MemoriesTokens.frozenTextFadeFrom + kk * MemoriesTokens.frozenTextFadeStep,
          MemoriesTokens.frozenTextFadeTo,
          p,
        )
      : smoothstep(MemoriesTokens.blockFadeFrom + kk * MemoriesTokens.blockFadeStep, MemoriesTokens.blockFadeTo, p);
}

/// Frozen: the text sinks as one block from where it is, (1 − p)·lag.
double diaryFrozenSink(double p, double lag, {required bool frozen}) =>
    frozen && p < 1 ? (1 - p.clamp(0.0, 1.0)) * lag : 0;

/// The blocks' lag: the title's remaining travel (÷ .8, it is held until
/// .2) + 36 dp (+ its height when the diary has no title of its own).
double diaryBlockLag({
  required double cardTitleTop,
  required double diaryTop,
  required double extra,
  double? cardTitleHeight,
}) => math.max(0, cardTitleTop - diaryTop) / (1 - MemoriesTokens.titleHold) + extra + (cardTitleHeight ?? 0);

/// Reduced motion: the card fades out by p .5, the diary in from p .5 — never both at once.
double reducedCardAlpha(double p) => 1 - smoothstep(0, MemoriesTokens.reducedSwap, p);

double reducedDiaryAlpha(double p) => smoothstep(MemoriesTokens.reducedSwap, 1, p);

/// The card's pull-down offset: p below 0 is the rubber band's morph travel (prototype `over`).
double cardRubberBand(double p) => -math.min(0.0, p) * MemoriesTokens.diaryMorphDistance;

/// The bar slots' x on top of the page's own, for a page at [relative] =
/// deck position − page (Android's sign): 0.6·W·rel.
double morphParallax(double relative, double pageWidth) => -barParallax(-relative, pageWidth);

double _ratio(double a, double b) => b > 0 ? a / b : 1;

/// Slot B's album line (also what fallback A's title flies to).
TextStyle memoriesBarAlbumStyle() => gsf(15, weight: 600, height: 1.3, letterSpacing: 0);

// ---------------------------------------------------------------- the layer

/// One element's transform this frame: translation from its rest, a uniform
/// scale about [origin], an alpha and, for a cover, the rounded clip as laid
/// out (its corner as shown ÷ scale).
@immutable
class MorphPose {
  const MorphPose({
    this.dx = 0,
    this.dy = 0,
    this.scale = 1,
    this.alpha = 1,
    this.origin = Alignment.center,
    this.clipRadius,
  });

  static const identity = MorphPose();

  final double dx;
  final double dy;
  final double scale;
  final double alpha;
  final Alignment origin;
  final double? clipRadius;

  bool get isIdentity => dx == 0 && dy == 0 && scale == 1;

  MorphPose copyWith({double? alpha, Alignment? origin, double? clipRadius}) => MorphPose(
    dx: dx,
    dy: dy,
    scale: scale,
    alpha: alpha ?? this.alpha,
    origin: origin ?? this.origin,
    clipRadius: clipRadius ?? this.clipRadius,
  );

  /// The paint transform for a box of [size].
  Matrix4 matrixFor(Size size) {
    final o = origin.alongSize(size);
    return Matrix4.identity()
      ..translateByDouble(dx + o.dx, dy + o.dy, 0, 1)
      ..scaleByDouble(scale, scale, 1, 1)
      ..translateByDouble(-o.dx, -o.dy, 0, 1);
  }

  @override
  bool operator ==(Object other) =>
      other is MorphPose &&
      other.dx == dx &&
      other.dy == dy &&
      other.scale == scale &&
      other.alpha == alpha &&
      other.origin == origin &&
      other.clipRadius == clipRadius;

  @override
  int get hashCode => Object.hash(dx, dy, scale, alpha, origin, clipRadius);

  @override
  String toString() => 'MorphPose(dx: $dx, dy: $dy, scale: $scale, alpha: $alpha, clip: $clipRadius)';
}

/// Applies [pose] to [child] at paint only (Compose's graphicsLayer): the
/// child is laid out untouched, [listenable] repaints without a rebuild, and
/// hit tests follow the transform. Nothing is painted (or hit) at alpha 0.
class MorphLayer extends SingleChildRenderObjectWidget {
  const MorphLayer({super.key, required this.listenable, required this.pose, super.child});

  final Listenable listenable;
  final MorphPose Function() pose;

  @override
  RenderMorphLayer createRenderObject(BuildContext context) => RenderMorphLayer(listenable: listenable, pose: pose);

  @override
  void updateRenderObject(BuildContext context, RenderMorphLayer renderObject) {
    renderObject
      ..listenable = listenable
      ..pose = pose;
  }
}

class RenderMorphLayer extends RenderProxyBox {
  RenderMorphLayer({required Listenable listenable, required MorphPose Function() pose})
    : _listenable = listenable,
      _pose = pose;

  Listenable _listenable;
  set listenable(Listenable value) {
    if (identical(value, _listenable)) return;
    if (attached) {
      _listenable.removeListener(markNeedsPaint);
      value.addListener(markNeedsPaint);
    }
    _listenable = value;
    markNeedsPaint();
  }

  MorphPose Function() _pose;
  set pose(MorphPose Function() value) {
    if (identical(value, _pose)) return;
    _pose = value;
    markNeedsPaint();
  }

  /// The pose last painted (hit tests and paint transforms read it).
  MorphPose? _painted;

  MorphPose get _current => _painted ?? _pose();

  @visibleForTesting
  MorphPose? get debugPainted => _painted;

  final LayerHandle<TransformLayer> _transform = LayerHandle<TransformLayer>();
  final LayerHandle<OpacityLayer> _opacity = LayerHandle<OpacityLayer>();
  final LayerHandle<ClipRSuperellipseLayer> _clip = LayerHandle<ClipRSuperellipseLayer>();

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _listenable.addListener(markNeedsPaint);
  }

  @override
  void detach() {
    _listenable.removeListener(markNeedsPaint);
    super.detach();
  }

  @override
  void dispose() {
    _transform.layer = null;
    _opacity.layer = null;
    _clip.layer = null;
    super.dispose();
  }

  // Composited only when something below is (a repaint boundary): otherwise the pose is canvas work, and a
  // pose at rest (no move, full alpha, no clip) costs nothing at all.
  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    final pose = _pose();
    _painted = pose;
    if (pose.alpha <= 0.001) {
      _transform.layer = null;
      _opacity.layer = null;
      _clip.layer = null;
      return;
    }
    void clipped(PaintingContext c, Offset o) {
      final r = pose.clipRadius;
      if (r == null) {
        _clip.layer = null;
        c.paintChild(child, o);
        return;
      }
      final bounds = Offset.zero & size;
      _clip.layer = c.pushClipRSuperellipse(
        needsCompositing,
        o,
        bounds,
        RSuperellipse.fromRectAndRadius(bounds, Radius.circular(math.max(0, r))),
        (c2, o2) => c2.paintChild(child, o2),
        oldLayer: _clip.layer,
      );
    }

    void faded(PaintingContext c, Offset o) {
      if (pose.alpha >= 0.999) {
        _opacity.layer = null;
        clipped(c, o);
      } else if (needsCompositing) {
        _opacity.layer = c.pushOpacity(o, (pose.alpha * 255).round(), clipped, oldLayer: _opacity.layer);
      } else {
        // Nothing below has a layer of its own: a save layer on this canvas fades it all.
        _opacity.layer = null;
        c.canvas.saveLayer(null, Paint()..color = Color.fromRGBO(0, 0, 0, pose.alpha));
        clipped(c, o);
        c.canvas.restore();
      }
    }

    if (pose.isIdentity) {
      _transform.layer = null;
      faded(context, offset);
      return;
    }
    _transform.layer = context.pushTransform(
      needsCompositing,
      offset,
      pose.matrixFor(size),
      faded,
      oldLayer: _transform.layer,
    );
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    final pose = _current;
    if (pose.alpha <= 0.001) return false;
    return result.addWithPaintTransform(
      transform: pose.matrixFor(size),
      position: position,
      hitTest: (result, position) => super.hitTestChildren(result, position: position),
    );
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    transform.multiply(_current.matrixFor(size));
  }
}

/// A [ValueListenable] read on demand from a [source] that says when it changed.
class MorphValue implements ValueListenable<double> {
  MorphValue(this._source, this._read);

  final Listenable _source;
  final double Function() _read;

  @override
  double get value => _read();

  @override
  void addListener(VoidCallback listener) => _source.addListener(listener);

  @override
  void removeListener(VoidCallback listener) => _source.removeListener(listener);
}

/// Where [box] is laid out inside [ancestor], transform-free: the sum of the
/// layout offsets on the way up. Paint transforms, scroll offsets and every
/// [MorphLayer] are left out, so a flight never measures itself (Android's
/// "outer node in page + element in its inner coordinates"). Null when
/// [ancestor] is not above [box].
Offset? morphLayoutOffset(RenderBox box, RenderObject ancestor) {
  var offset = Offset.zero;
  RenderObject? node = box;
  while (node != null && !identical(node, ancestor)) {
    final data = node.parentData;
    if (data is BoxParentData) offset += data.offset;
    node = node.parent;
  }
  return node == null ? null : offset;
}

RenderBox? _boxOf(GlobalKey key) {
  final context = key.currentContext;
  if (context is! Element || !context.mounted) return null;
  final box = context.renderObject;
  if (box is! RenderBox || !box.attached || !box.hasSize) return null;
  return box;
}

// ---------------------------------------------------------------- the deck

/// Which side of p's thresholds the deck is on; it changes once per crossing,
/// never per frame, so the pages rebuild only then.
@immutable
class MemoriesDiaryPhase {
  const MemoriesDiaryPhase({required this.composed, required this.open, required this.atCard});

  factory MemoriesDiaryPhase.of(double p) => MemoriesDiaryPhase(
    composed: p > MemoriesTokens.diaryHiddenBelow,
    open: p >= 0.5,
    atCard: p <= MemoriesTokens.cardInputBelow,
  );

  /// The diary is laid out under the card (from the morph's first frame).
  final bool composed;

  /// The diary takes input; the card is behind it.
  final bool open;

  /// The card's title and buttons take taps.
  final bool atCard;

  @override
  bool operator ==(Object other) =>
      other is MemoriesDiaryPhase && other.composed == composed && other.open == open && other.atCard == atCard;

  @override
  int get hashCode => Object.hash(composed, open, atCard);
}

/// The deck-level side of the diary (Android MemoriesDiaryDeck): p, which
/// page is current, the frozen collapse bookkeeping and the bar cover's
/// alpha chase. Pages ask it for their [MemoryPageMorph]; it notifies on
/// every frame p, the chase or the deck moves.
class MemoriesDiaryDeck extends ChangeNotifier {
  MemoriesDiaryDeck({required this.diary, required TickerProvider vsync})
    : _chase = SpringValue(vsync: vsync, initial: 0, spring: YoinSprings.fastEffects) {
    diary.addListener(_onDiary);
    _chase.animation.addListener(notifyListeners);
    phase = ValueNotifier(MemoriesDiaryPhase.of(diary.fraction));
  }

  final MemoriesDiaryController diary;
  final SpringValue _chase;

  /// [MemoriesDiaryPhase] of p.
  late final ValueNotifier<MemoriesDiaryPhase> phase;

  /// Reduced motion: alpha only.
  bool reduced = false;

  PageController? _pages;
  PageController? get pages => _pages;
  set pages(PageController? value) {
    if (identical(value, _pages)) return;
    _pages?.removeListener(notifyListeners);
    _pages = value?..addListener(notifyListeners);
  }

  final Map<int, MemoryPageMorph> _morphs = {};

  /// The frozen timing outlives the invisible scroll reset until p reaches 0 or 1.
  bool frozenHold = false;

  double get p => diary.fraction;

  /// The deck's position in pages (the settled page before the deck is laid out).
  double get position {
    final pages = _pages;
    if (pages == null) return 0;
    if (pages.hasClients && pages.position.haveDimensions) return pages.page ?? pages.initialPage.toDouble();
    return pages.initialPage.toDouble();
  }

  /// The page the deck shows most of (Android pagerState.currentPage).
  int get currentPage => position.round();

  /// The page morph for the memory [id] at [page] in the deck.
  MemoryPageMorph morphFor(int id, int page) {
    final morph = _morphs.putIfAbsent(id, () => MemoryPageMorph(deck: this));
    morph.page = page;
    return morph;
  }

  /// Only the memories still in the deck keep a morph.
  void retain(Iterable<int> ids) {
    final keep = ids.toSet();
    final gone = [
      for (final e in _morphs.entries)
        if (!keep.contains(e.key)) e.key,
    ];
    // Its pieces may still be in this frame's tree: it lets go of the deck and its scroll, and is dropped.
    for (final id in gone) {
      _morphs.remove(id)?._release();
    }
  }

  MemoryPageMorph? morphAt(int page) {
    for (final m in _morphs.values) {
      if (m.page == page) return m;
    }
    return null;
  }

  MemoryPageMorph? current() => morphAt(currentPage);

  /// The current diary's title is under the bar (prototype `so ≥ thr`), or the hold keeps that timing.
  bool get frozen {
    if (frozenHold) return true;
    final page = current();
    if (page == null) return false;
    final so = page.scrollOffset;
    final threshold = page.anchors?.freezeAt;
    if (threshold == null) return false;
    return so > 0 && so >= threshold;
  }

  /// The current page's cover flight progress.
  double get fp => morphCoverProgress(p, frozen: frozen);

  double get chase => _chase.value;

  double barShown(double fp) => barCoverShown(_chase.value, fp);

  void _onDiary() {
    final p = this.p;
    // Frozen collapse: once the sunk text is invisible the scroll returns to its top, unseen; the frozen
    // timing holds until p reaches 0 or 1, so the emblem's flight never jumps when the scroll resets.
    final current = this.current();
    if (p < MemoriesTokens.frozenResetBelow && current != null && current.scrollOffset > 0) {
      frozenHold = frozen;
      current.resetScroll();
    }
    if (p <= 0 || p >= 1) frozenHold = false;
    // The bar cover's alpha chases its hand-over target on the fast effects spring (prototype barFollow).
    if (!reduced) _chase.animateTo(barCoverTarget(fp));
    phase.value = MemoriesDiaryPhase.of(p);
    notifyListeners();
  }

  @override
  void dispose() {
    diary.removeListener(_onDiary);
    _pages?.removeListener(notifyListeners);
    for (final m in _morphs.values) {
      m.dispose();
    }
    _morphs.clear();
    _chase.dispose();
    phase.dispose();
    super.dispose();
  }
}

// ---------------------------------------------------------------- one page

/// Everything the morph needs from one page's layout, in page coordinates
/// at rest (scroll 0, no transforms).
@immutable
class MemoryMorphAnchors {
  const MemoryMorphAnchors({
    required this.pageWidth,
    required this.coverCenter,
    required this.coverSize,
    required this.sealCenter,
    required this.sealSize,
    required this.cardTitle,
    required this.cardTitleHeight,
    required this.diaryTitle,
    required this.demCenter,
    required this.barCoverCenter,
    required this.barTitle,
    required this.freezeAt,
    required this.lag,
  });

  final double pageWidth;
  final Offset coverCenter;
  final double coverSize;
  final Offset sealCenter;
  final double sealSize;
  final TextAnchor cardTitle;
  final double cardTitleHeight;

  /// The diary's title (a written one); null for fallback A, whose title flies to the bar.
  final TextAnchor? diaryTitle;
  final Offset? demCenter;
  final Offset barCoverCenter;
  final TextAnchor barTitle;

  /// Scroll past which the diary title (or, without one, the head) is under the bar: a collapse from there is frozen.
  final double freezeAt;
  final double lag;
}

/// What a page tells its morph about itself each build (fonts, the bar's
/// insets), the rest being measured.
@immutable
class MemoryPageMorphSpec {
  const MemoryPageMorphSpec({
    required this.titleKind,
    required this.cardTitleFont,
    required this.diaryTitleFont,
    required this.statusTop,
    required this.barCoverStart,
    required this.slotBStart,
    required this.barTitleWidth,
    required this.barLines,
  });

  final MemoryTitleKind titleKind;
  final double cardTitleFont;
  final double diaryTitleFont;
  final double statusTop;
  final double barCoverStart;
  final double slotBStart;

  /// The bar's album name as it shows (measured, at most its slot).
  final double barTitleWidth;

  /// Slot B's column height (album line + the artist line when there is one).
  final double barLines;
}

/// One page's morph (Android MemoryPageMorph): its anchors, read from the
/// layout of the keyed pieces, and the pose every piece paints with. It
/// notifies when the deck moves or its own diary scrolls.
class MemoryPageMorph extends ChangeNotifier {
  MemoryPageMorph({required this.deck}) {
    deck.addListener(notifyListeners);
  }

  final MemoriesDiaryDeck deck;

  /// This memory's index in the deck.
  int page = 0;

  MemoryPageMorphSpec? spec;

  ScrollController? _scroll;
  ScrollController? get scroll => _scroll;
  set scroll(ScrollController? value) {
    if (identical(value, _scroll)) return;
    _scroll?.removeListener(notifyListeners);
    _scroll = value?..addListener(notifyListeners);
  }

  final GlobalKey pageKey = GlobalKey(debugLabel: 'morph·page');
  final GlobalKey coverKey = GlobalKey(debugLabel: 'morph·cover');
  final GlobalKey sealKey = GlobalKey(debugLabel: 'morph·seal');
  final GlobalKey cardTitleKey = GlobalKey(debugLabel: 'morph·cardTitle');
  final GlobalKey diaryTitleKey = GlobalKey(debugLabel: 'morph·diaryTitle');
  final GlobalKey demKey = GlobalKey(debugLabel: 'morph·dem');
  final GlobalKey headKey = GlobalKey(debugLabel: 'morph·head');

  double get scrollOffset {
    final s = _scroll;
    if (s == null || !s.hasClients || s.positions.length != 1) return 0;
    return s.offset;
  }

  void resetScroll() {
    final s = _scroll;
    if (s != null && s.hasClients && s.positions.length == 1 && s.offset != 0) s.jumpTo(0);
  }

  /// The anchors from this frame's layout; null until every piece is laid out.
  MemoryMorphAnchors? get anchors {
    final spec = this.spec;
    final root = _boxOf(pageKey);
    if (spec == null || root == null) return null;
    final cover = _boxOf(coverKey);
    final seal = _boxOf(sealKey);
    final title = _boxOf(cardTitleKey);
    final head = _boxOf(headKey);
    if (cover == null || seal == null || title == null || head == null) return null;
    final coverAt = morphLayoutOffset(cover, root);
    final sealAt = morphLayoutOffset(seal, root);
    final titleAt = morphLayoutOffset(title, root);
    final headAt = morphLayoutOffset(head, root);
    if (coverAt == null || sealAt == null || titleAt == null || headAt == null) return null;
    final dTitle = _boxOf(diaryTitleKey);
    final dTitleAt = dTitle == null ? null : morphLayoutOffset(dTitle, root);
    final dem = _boxOf(demKey);
    final demAt = dem == null ? null : morphLayoutOffset(dem, root);

    final cardTitle = TextAnchor(cx: titleAt.dx + title.size.width / 2, y: titleAt.dy, fontSize: spec.cardTitleFont);
    final diaryTitle = dTitleAt == null
        ? null
        : TextAnchor(cx: dTitleAt.dx + dTitle!.size.width / 2, y: dTitleAt.dy, fontSize: spec.diaryTitleFont);
    // The diary text starts under the bar: past this scroll its title (or, without one, its head) is under it.
    final viewportTop = spec.statusTop + MemoriesTokens.barHeight;
    final freezeAt = dTitleAt != null
        ? dTitleAt.dy + dTitle!.size.height - viewportTop
        : headAt.dy + head.size.height - viewportTop;
    return MemoryMorphAnchors(
      pageWidth: root.size.width,
      coverCenter: coverAt + cover.size.center(Offset.zero),
      coverSize: cover.size.width,
      sealCenter: sealAt + seal.size.center(Offset.zero),
      sealSize: seal.size.width,
      cardTitle: cardTitle,
      cardTitleHeight: title.size.height,
      diaryTitle: diaryTitle,
      demCenter: demAt == null ? null : demAt + dem!.size.center(Offset.zero),
      barCoverCenter: Offset(
        spec.barCoverStart + MemoriesTokens.barCover / 2,
        spec.statusTop + MemoriesTokens.barHeight / 2,
      ),
      barTitle: TextAnchor(
        cx: spec.slotBStart + spec.barTitleWidth / 2,
        y: spec.statusTop + (MemoriesTokens.barHeight - spec.barLines) / 2,
        fontSize: memoriesBarAlbumStyle().fontSize!,
      ),
      freezeAt: freezeAt,
      lag: diaryBlockLag(
        cardTitleTop: cardTitle.y,
        diaryTop: diaryTitle?.y ?? headAt.dy,
        extra: MemoriesTokens.blockLagExtra,
        cardTitleHeight: diaryTitle == null ? title.size.height : null,
      ),
    );
  }

  // ------------------------------------------------------------ per-frame reads

  bool get isCurrent => deck.currentPage == page;
  double get pc => deck.p.clamp(0.0, 1.0);
  bool get frozen => isCurrent && deck.frozen;
  double get fp => isCurrent ? deck.fp : pc;
  double get _so => isCurrent ? scrollOffset : 0;

  /// rel = deck position − this page (Android's sign: a page to the right is negative).
  double get relative => deck.position - page;

  double _parallax(MemoryMorphAnchors a) => morphParallax(relative, a.pageWidth);

  // ------------------------------------------------------------ poses

  CoverPose _coverPose(MemoryMorphAnchors a, double f) => morphCoverPose(
    fp: f,
    coverCenter: a.coverCenter,
    barCenter: a.barCoverCenter,
    coverSize: a.coverSize,
    barCoverSize: MemoriesTokens.barCover,
    parallax: _parallax(a),
    cardCorner: MemoriesTokens.coverCornerCard,
    barCorner: MemoriesTokens.coverCornerBar,
  );

  /// The card's cover: flies into the bar slot; hands over to the bar's cover by alpha.
  MorphPose cardCover() {
    const rest = MorphPose(clipRadius: MemoriesTokens.coverCornerCard);
    if (deck.reduced) return rest;
    final f = fp;
    if (f <= 0) return rest;
    final a = anchors;
    if (a == null) return rest;
    final pose = _coverPose(a, f);
    final own = lerpDouble(1, barVisibility(relative), math.min(1, f * MemoriesTokens.coverParallaxLead));
    return MorphPose(
      dx: pose.dx,
      dy: pose.dy,
      scale: pose.scale,
      clipRadius: pose.corner / pose.scale,
      alpha: own * (1 - deck.barShown(f)),
    );
  }

  /// The bar's 40 dp cover (its parent already rides the bar's parallax and visibility).
  MorphPose barCover() {
    if (deck.reduced) return MorphPose(alpha: reducedDiaryAlpha(pc), clipRadius: MemoriesTokens.coverCornerBar);
    final f = fp;
    final alpha = deck.barShown(f);
    final a = anchors;
    if (a == null || f >= 1) return MorphPose(alpha: alpha, clipRadius: MemoriesTokens.coverCornerBar);
    final pose = _coverPose(a, f);
    final k = a.coverSize * pose.scale / MemoriesTokens.barCover;
    return MorphPose(
      dx: a.coverCenter.dx + pose.dx - (a.barCoverCenter.dx + _parallax(a)),
      dy: a.coverCenter.dy + pose.dy - a.barCoverCenter.dy,
      scale: k,
      alpha: alpha,
      clipRadius: k > 0 ? pose.corner / k : MemoriesTokens.coverCornerBar,
    );
  }

  /// The card's title: holds, then flies to the diary title (or, fallback A, the bar's album name).
  MorphPose cardTitle() {
    if (deck.reduced) return MorphPose.identity;
    final a = anchors;
    if (a == null) return MorphPose.identity;
    final p = pc;
    final dTitle = a.diaryTitle;
    if (frozen) return MorphPose(alpha: _frozenCardAlpha(p));
    if (dTitle != null) {
      return morphTextPair(a.cardTitle, dTitle.copyWith(y: dTitle.y - _so), morphTitleTravel(p, frozen: false)).$1;
    }
    if (spec?.titleKind == MemoryTitleKind.album) {
      return morphTextPair(
        a.cardTitle,
        a.barTitle.copyWith(cx: a.barTitle.cx + _parallax(a)),
        morphTitleTravel(p, frozen: false),
        swapFrom: MemoriesTokens.albumTitleFadeFrom,
        swapTo: MemoriesTokens.albumTitleFadeTo,
      ).$1;
    }
    return MorphPose(dy: -MemoriesTokens.albumRowLift * p, alpha: 1 - smoothstep(0, MemoriesTokens.albumRowFadeTo, p));
  }

  /// The diary's title: the card title's twin on the same flight; frozen, it fades in place.
  MorphPose diaryTitle() {
    if (deck.reduced) return MorphPose.identity;
    final a = anchors;
    final dTitle = a?.diaryTitle;
    if (a == null || dTitle == null) return MorphPose.identity;
    final p = pc;
    if (frozen) {
      return MorphPose(alpha: smoothstep(MemoriesTokens.frozenTextFadeFrom, MemoriesTokens.frozenTextFadeTo, p));
    }
    return morphTextPair(a.cardTitle, dTitle.copyWith(y: dTitle.y - _so), morphTitleTravel(p, frozen: false)).$2;
  }

  /// The 96 on the cover: travels with the title to the 48 by the diary title, then hands over.
  MorphPose cardSeal() {
    if (deck.reduced || anchors == null) return MorphPose.identity;
    if (frozen) return MorphPose(alpha: _frozenCardAlpha(pc));
    final pose = _emblemPose();
    if (pose == null) return MorphPose(alpha: _frozenCardAlpha(pc));
    return pose.seal;
  }

  /// Frozen (or with no 48 to land on): the card's title and 96 wait on the card and fade.
  double _frozenCardAlpha(double p) =>
      1 - smoothstep(MemoriesTokens.frozenCardFadeFrom, MemoriesTokens.frozenCardFadeTo, p);

  /// The 96's caption alpha (it leaves first).
  double sealCaptionAlpha() {
    if (deck.reduced || frozen) return 1;
    return _emblemPose()?.captionAlpha ?? 1;
  }

  late final ValueListenable<double> captionAlpha = MorphValue(this, sealCaptionAlpha);

  /// The diary's native 48, by the title.
  MorphPose diaryEmblem() {
    if (deck.reduced || anchors == null) return MorphPose.identity;
    final pose = frozen ? null : _emblemPose();
    if (pose == null) {
      return MorphPose(alpha: smoothstep(MemoriesTokens.frozenTextFadeFrom, MemoriesTokens.frozenTextFadeTo, pc));
    }
    return pose.dem;
  }

  EmblemPose? _emblemPose() {
    final a = anchors;
    final dem = a?.demCenter;
    if (a == null || dem == null) return null;
    return morphEmblemPose(
      te: morphTitleTravel(pc, frozen: frozen),
      sealCenter: a.sealCenter,
      demCenter: Offset(dem.dx, dem.dy - _so),
      sealSize: a.sealSize,
      demSize: MemoriesTokens.diaryEmblem,
    );
  }

  /// The album row (and fallback A's artist line): lifts 36 and is gone by p .32.
  MorphPose albumRow() {
    if (deck.reduced) return MorphPose.identity;
    final p = pc;
    return MorphPose(dy: -MemoriesTokens.albumRowLift * p, alpha: 1 - smoothstep(0, MemoriesTokens.albumRowFadeTo, p));
  }

  /// The excerpt: leaves with the actions, a little sooner.
  MorphPose excerpt() {
    if (deck.reduced) return MorphPose.identity;
    final p = pc;
    return MorphPose(dy: MemoriesTokens.teaserDrop * p, alpha: 1 - smoothstep(0, MemoriesTokens.excerptFadeTo, p));
  }

  /// Diary / Go to album: drop 56 and fade.
  MorphPose teaser() {
    if (deck.reduced) return MorphPose.identity;
    final p = pc;
    return MorphPose(dy: MemoriesTokens.teaserDrop * p, alpha: 1 - smoothstep(0, MemoriesTokens.teaserFadeTo, p));
  }

  /// The card face as a whole: the rubber band below p 0; reduced motion fades it out by p .5.
  MorphPose cardFace() => deck.reduced ? MorphPose(alpha: reducedCardAlpha(pc)) : MorphPose(dy: cardRubberBand(deck.p));

  /// The diary viewport: nothing drawn at the card; reduced motion fades it in from p .5.
  MorphPose diaryViewport() {
    if (deck.p <= MemoriesTokens.diaryHiddenBelow) return const MorphPose(alpha: 0);
    return deck.reduced ? MorphPose(alpha: reducedDiaryAlpha(pc)) : MorphPose.identity;
  }

  /// The diary text as one: the frozen sink, and the open spring's overshoot (p > 1 scrolls it a little).
  MorphPose diaryBody() {
    if (deck.reduced) return MorphPose.identity;
    final lag = anchors?.lag ?? 0;
    return MorphPose(
      dy: diaryFrozenSink(deck.p, lag, frozen: frozen) - math.max(0.0, deck.p - 1) * MemoriesTokens.diaryMorphDistance,
    );
  }

  /// The k-th diary block rises (staggered) and fades in behind the title.
  MorphPose diaryBlock(int k) {
    if (deck.reduced) return MorphPose.identity;
    final p = pc;
    final fz = frozen;
    return MorphPose(
      dy: diaryBlockOffset(p, k, lag: anchors?.lag ?? 0, stagger: MemoriesTokens.blockStagger, frozen: fz),
      alpha: diaryBlockAlpha(p, k, frozen: fz),
    );
  }

  void _release() {
    deck.removeListener(notifyListeners);
    scroll = null;
  }

  @override
  void dispose() {
    _release();
    super.dispose();
  }
}
