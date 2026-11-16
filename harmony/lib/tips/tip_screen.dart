/// The tips, one per page (Android ui/tips/TipScreen.kt + TipActivity), opened
/// from Home's bubble or Settings › Help and about › Tips. Swiping looks
/// around; "Read" marks (or unmarks) the one on screen; "Next" marks it read
/// and moves to the next unread one — "Done" when it is the last, which
/// closes the page. The bubble on Home only brings up unread tips.
///
/// Back surface: PushPage (YoinPushRoute) with the explicit back button; the
/// system back takes the same Navigator.pop.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../detail/detail_page.dart' show DetailBackButton;
import '../home/home_bubble_tips.dart';
import '../nowplaying/haptics.dart';
import '../settings/settings_metrics.dart';
import '../settings/sync/cloud_sync_texts.dart' show fixedSyncDate;
import '../settings/tips.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/expressive/expressive_shapes.dart';
import '../ui/component/expressive/expressive_surface_button.dart';
import '../ui/shapes/shape_path.dart';
import 'tip_illustration.dart';

/// The tip pages' own strings (strings_home.xml, key in the comment).
class TipStrings {
  const TipStrings({required this.zh});

  static TipStrings of(BuildContext context) =>
      TipStrings(zh: Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  final bool zh;

  String position(int index, int count) => '$index / $count'; // tip_position
  String get read => zh ? '已读' : 'Read'; // tip_read
  String get next => zh ? '下一条' : 'Next'; // tip_next
  String get done => zh ? '完成' : 'Done'; // tip_done

  /// tip_updated, with the day as the locale writes it ("2026年11月4日", "Nov 4, 2026").
  String updated(DateTime day) {
    final date = fixedSyncDate(day, sameYear: false, chinese: zh);
    return zh ? '更新于 $date' : 'Updated $date';
  }
}

class TipScreen extends ConsumerStatefulWidget {
  const TipScreen({super.key, required this.startTip, this.tips});

  final HomeBubbleTip startTip;

  /// The pages; [HomeBubbleTip.available] unless a test says otherwise.
  final List<HomeBubbleTip>? tips;

  @override
  ConsumerState<TipScreen> createState() => _TipScreenState();
}

class _TipScreenState extends ConsumerState<TipScreen> with TickerProviderStateMixin {
  late final List<HomeBubbleTip> _tips = widget.tips ?? HomeBubbleTip.available();
  late final PageController _pager = PageController(initialPage: math.max(0, _tips.indexOf(widget.startTip)));

  /// The page the pager rests on (Compose settledPage): only its demo plays.
  late final ValueNotifier<int> _settled = ValueNotifier(_pager.initialPage);
  late int _current = _pager.initialPage;

  /// "Next" moves the pager on the Standard default spatial spring (Android's pageSpring).
  late final AnimationController _pageSpring;

  @override
  void initState() {
    super.initState();
    _pageSpring = AnimationController.unbounded(vsync: this)
      ..addListener(() {
        if (_pager.hasClients) _pager.jumpTo(_pageSpring.value);
      })
      ..addStatusListener((status) {
        if (status.isCompleted) _settled.value = _current;
      });
  }

  @override
  void dispose() {
    _pageSpring.dispose();
    _pager.dispose();
    _settled.dispose();
    super.dispose();
  }

  bool _onScroll(ScrollNotification n) {
    if (n.depth != 0 || !_pager.hasClients) return false;
    // A finger on the pager takes it from the spring.
    if (n is ScrollStartNotification && n.dragDetails != null) _pageSpring.stop();
    final page = _pager.page ?? _current.toDouble();
    final nearest = page.round().clamp(0, _tips.length - 1);
    if (nearest != _current) setState(() => _current = nearest);
    if (n is ScrollEndNotification && !_pageSpring.isAnimating) _settled.value = nearest;
    return false;
  }

  void _next(HomeBubbleTip current, HomeBubbleTip? target) {
    YoinHaptics.confirm();
    ref.read(tipReadProvider.notifier).setRead(current, true);
    if (target == null) {
      Navigator.of(context).maybePop();
      return;
    }
    final index = _tips.indexOf(target);
    if (MediaQuery.disableAnimationsOf(context)) {
      _pager.jumpToPage(index);
      _settled.value = index;
      return;
    }
    final position = _pager.position;
    _pageSpring.value = position.pixels;
    _pageSpring.animateWith(
      SpringSimulation(YoinSprings.standardDefaultSpatial, position.pixels, index * position.viewportDimension, 0),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = TipStrings.of(context);
    final read = ref.watch(tipReadProvider);
    final current = _tips[_current];
    final nextUnread = nextUnreadTip(current, read, _tips);
    final padding = MediaQuery.paddingOf(context);
    return Scaffold(
      backgroundColor: colors.surface,
      body: Padding(
        padding: EdgeInsets.only(
          left: padding.left,
          top: padding.top,
          right: padding.right,
          // A window with no navigation bar still keeps the buttons off its rounded edge.
          bottom: math.max(padding.bottom, TipTokens.bottomFloor),
        ),
        child: Column(
          children: [
            SizedBox(
              height: TipTokens.barHeight,
              child: Row(
                children: [
                  const Padding(
                    padding: EdgeInsetsDirectional.only(start: SettingsMetrics.backStart),
                    child: DetailBackButton(),
                  ),
                  const Spacer(),
                  Padding(
                    padding: const EdgeInsetsDirectional.only(end: TipTokens.positionEnd),
                    child: Text(
                      strings.position(_current + 1, _tips.length),
                      style: Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: NotificationListener<ScrollNotification>(
                onNotification: _onScroll,
                child: PageView.builder(
                  controller: _pager,
                  itemCount: _tips.length,
                  itemBuilder: (context, page) => ValueListenableBuilder<int>(
                    valueListenable: _settled,
                    builder: (context, settled, _) =>
                        _TipPage(tip: _tips[page], page: page, pager: _pager, playing: settled == page),
                  ),
                ),
              ),
            ),
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: TipTokens.proseMaxWidth),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: TipTokens.buttonsPaddingH,
                    vertical: TipTokens.buttonsPaddingV,
                  ),
                  child: Row(
                    children: [
                      _ReadToggle(
                        checked: read.contains(current),
                        label: strings.read,
                        onChanged: (checked) {
                          YoinHaptics.click();
                          ref.read(tipReadProvider.notifier).setRead(current, checked);
                        },
                      ),
                      const SizedBox(width: TipTokens.buttonGap),
                      const Spacer(),
                      _NextButton(
                        label: nextUnread == null ? strings.done : strings.next,
                        chevron: nextUnread != null,
                        onTap: () => _next(current, nextUnread),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// One tip: how it is done (the demo phone), its symbol on an Expressive
/// shape beside the title, then the text and the day it last changed.
class _TipPage extends StatelessWidget {
  const _TipPage({required this.tip, required this.page, required this.pager, required this.playing});

  final HomeBubbleTip tip;
  final int page;
  final PageController pager;
  final bool playing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final zh = Localizations.maybeLocaleOf(context)?.languageCode == 'zh';
    return SingleChildScrollView(
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: TipTokens.proseMaxWidth),
          child: Padding(
            padding: const EdgeInsets.all(TipTokens.pagePadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TipIllustration(tip: tip, playing: playing),
                const SizedBox(height: TipTokens.illustrationGap),
                Row(
                  children: [
                    _TipTile(tip: tip, page: page, pager: pager, settled: playing),
                    const SizedBox(width: TipTokens.titleGap),
                    Expanded(
                      child: Semantics(
                        header: true,
                        child: Text(
                          tip.title(zh: zh),
                          style: theme.textTheme.headlineMedium!.copyWith(color: colors.onSurface),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: TipTokens.textGap),
                Text(
                  tip.text(zh: zh),
                  style: theme.textTheme.bodyLarge!.copyWith(
                    color: colors.onSurfaceVariant,
                    fontWeight: FontWeight.w400,
                  ),
                ),
                const SizedBox(height: TipTokens.updatedGap),
                Text(
                  TipStrings.of(context).updated(tip.updated),
                  style: theme.textTheme.labelLarge!.copyWith(color: colors.outline),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A different silhouette per tip, so paging reads as moving on (Android tipPolygon).
RoundedPolygon tipShape(int index) => switch (index % 6) {
  0 => MaterialShapes.cookie9Sided,
  1 => MaterialShapes.clover4Leaf,
  2 => MaterialShapes.sunny,
  3 => MaterialShapes.cookie6Sided,
  4 => MaterialShapes.gem,
  _ => MaterialShapes.pentagon,
};

final Map<int, Morph> _tipMorphs = {};

/// The growing tile's morph: the previous tip's shape into this one's.
Morph _tipMorph(int index) => _tipMorphs[index % 6] ??= Morph(tipShape(index - 1), tipShape(index));

/// The tip's symbol on its shape. Swiping drives it straight from the pager:
/// it turns and shrinks a little as its page slides away; arriving, the
/// shape grows in from the previous tip's on the spatial spring.
class _TipTile extends StatefulWidget {
  const _TipTile({required this.tip, required this.page, required this.pager, required this.settled});

  final HomeBubbleTip tip;
  final int page;
  final PageController pager;
  final bool settled;

  @override
  State<_TipTile> createState() => _TipTileState();
}

class _TipTileState extends State<_TipTile> with TickerProviderStateMixin {
  late final SpringValue _grown = SpringValue(
    vsync: this,
    initial: widget.settled ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );

  @override
  void didUpdateWidget(_TipTile old) {
    super.didUpdateWidget(old);
    if (old.settled == widget.settled) return;
    if (widget.settled && !MediaQuery.disableAnimationsOf(context)) {
      _grown.animateTo(1);
    } else {
      _grown.snapTo(widget.settled ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _grown.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final own = tipShape(widget.page);
    final morph = _tipMorph(widget.page);
    return AnimatedBuilder(
      animation: Listenable.merge([widget.pager, _grown.animation]),
      builder: (context, child) {
        final page = widget.pager.hasClients && widget.pager.position.haveDimensions
            ? widget.pager.page ?? widget.page.toDouble()
            : widget.page.toDouble();
        final offset = (page - widget.page).clamp(-1.0, 1.0);
        final shrink = 1 - offset.abs() * TipTokens.tileShrink;
        final grown = _grown.value;
        // Settled: the shape itself (toShape); growing: the morph from the
        // previous tip's shape (MorphProgressShape).
        final ShapeBorder shape = grown >= 1 ? PolygonBorder(own) : MorphBorder(morph, grown);
        return Transform.rotate(
          angle: offset * TipTokens.tileTurnDegrees * math.pi / 180,
          child: Transform.scale(
            scale: shrink,
            child: DecoratedBox(
              decoration: ShapeDecoration(color: colors.primaryContainer, shape: shape),
              child: child,
            ),
          ),
        );
      },
      child: SizedBox.square(
        dimension: TipTokens.tileSize,
        child: Icon(widget.tip.icon, size: TipTokens.tileSymbol, color: colors.onPrimaryContainer),
      ),
    );
  }
}

/// "Read": an M3 Expressive toggle button — round at rest, its corners
/// tightening while pressed and squaring off checked, the check unfolding
/// before the label (ToggleButton + ToggleButtonDefaults.shapesFor).
class _ReadToggle extends StatelessWidget {
  const _ReadToggle({required this.checked, required this.label, required this.onChanged});

  final bool checked;
  final String label;
  final ValueChanged<bool> onChanged;

  static const _shapes = InteractionShapes(
    rest: CornerRadii.full,
    pressed: CornerRadii.all(M3eShapeTokens.cornerSmall),
    checked: CornerRadii.all(M3eShapeTokens.cornerMedium),
  );

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final content = checked ? colors.onPrimary : colors.onSurfaceVariant;
    return ExpressiveSurfaceButton(
      shapes: _shapes,
      height: TipTokens.buttonHeight,
      minWidth: M3eButtonTokens.minWidth,
      checked: checked,
      color: checked ? colors.primary : colors.surfaceContainer,
      contentColor: content,
      padding: const EdgeInsetsDirectional.symmetric(horizontal: 24),
      semanticLabel: label,
      semanticValue: checked ? label : null,
      onTap: () => onChanged(!checked),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _SpringWidthReveal(
            visible: checked,
            child: Padding(
              padding: const EdgeInsetsDirectional.only(end: M3eButtonTokens.iconSpacing),
              child: Icon(YoinSymbols.check, size: 20, color: content),
            ),
          ),
          Text(label, style: Theme.of(context).textTheme.labelLarge!.copyWith(color: content)),
        ],
      ),
    );
  }
}

/// "Next ›" / "Done": a filled button whose corners tighten while pressed (ButtonDefaults.shapes()).
class _NextButton extends StatelessWidget {
  const _NextButton({required this.label, required this.chevron, required this.onTap});

  final String label;
  final bool chevron;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return ExpressiveSurfaceButton(
      shapes: const InteractionShapes(rest: CornerRadii.full, pressed: CornerRadii.all(M3eShapeTokens.cornerSmall)),
      height: TipTokens.buttonHeight,
      minWidth: M3eButtonTokens.minWidth,
      color: colors.primary,
      contentColor: colors.onPrimary,
      padding: const EdgeInsetsDirectional.symmetric(horizontal: 24),
      semanticLabel: label,
      onTap: onTap,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onPrimary)),
          _SpringWidthReveal(
            visible: chevron,
            child: Padding(
              padding: const EdgeInsetsDirectional.only(start: M3eButtonTokens.iconSpacing),
              child: Icon(YoinSymbols.chevronRight, size: 20, color: colors.onPrimary),
            ),
          ),
        ],
      ),
    );
  }
}

/// [child] unfolding sideways on the spatial spring and fading on the effects spring.
class _SpringWidthReveal extends StatefulWidget {
  const _SpringWidthReveal({required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  State<_SpringWidthReveal> createState() => _SpringWidthRevealState();
}

class _SpringWidthRevealState extends State<_SpringWidthReveal> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_SpringWidthReveal old) {
    super.didUpdateWidget(old);
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _size.snapTo(target);
      _alpha.snapTo(target);
    } else {
      _size.animateTo(target);
      _alpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_size.animation, _alpha.animation]),
    builder: (context, child) => ClipRect(
      child: Align(
        alignment: AlignmentDirectional.centerStart,
        widthFactor: _size.value.clamp(0.0, 1.0),
        child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
      ),
    ),
    child: widget.child,
  );
}
