/// Edit Home, in place (Android ui/home/edit/*, docs/design.md 主页 › 就地编辑):
/// a long-press anywhere on the feed (or the account card's / the feed
/// footer's "Edit Home") enters; every section gets a plate, a hide button and
/// — with more than one — a drag handle; hidden sections wait in the "Hidden"
/// tray at the feed's end, a + puts one back where it was, "Reset Home" goes
/// back to the default. Activities and Jump Back In take a row preset
/// (S / M / L / XL): the ⌟ at their plate's bottom-end corner resizes them
/// (home_rows_resize.dart, home_rows_handle.dart), and the reader's / the
/// keyboard's "More rows" / "Fewer rows" step one preset. A tap on a Jump
/// Back In card hides it for three days or a week (home_jbi_hidden.dart).
/// Every step saves at
/// once (per account) and can be undone (20 steps; leaving clears them).
/// Done, system back or a tap on the page's blank space leaves. The edit
/// controller is the only writer.
///
/// While editing, the cards sway (home_edit_wiggle.dart), the bar's two
/// destinations become [Undo|Add] and [Done] (BarEditPose: Add scrolls to the
/// tray when there is nothing to undo), the header's controls fade away, and
/// full-bleed shelves are cut back to their plate ([HomeEditShelfClip]).
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../nowplaying/haptics.dart';
import '../shell/bar_edit_pose.dart';
import '../shell/shell_state.dart';
import '../shell/yoin_section.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'home_carry.dart' show pressRect;
import 'home_edit_hints.dart';
import 'home_metrics.dart';
import 'home_section.dart';
import 'home_strings.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_text.dart';
import 'widgets/seam.dart';

/// Undo keeps this many steps.
const int homeEditUndoLimit = 20;

@immutable
final class HomeEditState {
  const HomeEditState({
    this.editing = false,
    this.undo = const [],
    this.trayRequests = 0,
    this.hints = const HomeEditSessionHints(),
  });

  final bool editing;

  /// The current (or last) session's hints: kept past Done so the tray's
  /// "New" chips fade out with it; the next session replaces them.
  final HomeEditSessionHints hints;

  /// Layouts before each step, oldest first.
  final List<HomeLayout> undo;

  /// Bumped by the bar's Add: Home scrolls the "Hidden" tray into view.
  final int trayRequests;

  bool get canUndo => undo.isNotEmpty;
}

/// The one writer of Home's layout while editing (HomeEditController).
class HomeEditController extends Notifier<HomeEditState> {
  @override
  HomeEditState build() {
    // Another account: out of edit mode, nothing to undo.
    ref.watch(activeSourceProvider);
    // Library in front: edit mode folds away.
    ref.listen(shellSectionProvider, (_, section) {
      if (section != YoinSection.home) exit();
    });
    // The device's hint record starts loading with Home, not on the first long-press: a session
    // that starts before it lands shows no "New" chip.
    ref.listen(homeEditHintsProvider, (_, _) {});
    return const HomeEditState();
  }

  /// A section is being carried (Home sets it): the bar's left slot does nothing meanwhile.
  bool carrying = false;

  /// Mid-carry an exit waits for the drop (Home sets it; Android
  /// HomeEditLayer.deferExit): true when the carry took the exit, which then
  /// runs through [finishExit] once the strips have unfolded.
  bool Function({required bool done})? deferExit;

  /// Enters edit mode. [haptic] false: the long-press already played it (HomeEditGestures).
  void enter({bool haptic = true}) {
    if (state.editing) return;
    if (haptic) YoinHaptics.longPress();
    // Snapshot the session's hints before recording it (the "New" chips are this session's).
    final hints = ref.read(homeEditHintsProvider.notifier).startSession(ref.read(homeLayoutProvider));
    state = HomeEditState(editing: true, hints: hints);
  }

  /// Leaves edit mode (system back, Library, the app hidden, a blank tap).
  /// Mid-carry it waits for the carry to finish (Android commitAndExit).
  void exit() => _leave(done: false);

  /// The bar's Done: leaves with the confirm haptic.
  void done() => _leave(done: true);

  void _leave({required bool done}) {
    if (!state.editing) {
      carrying = false;
      return;
    }
    if (deferExit?.call(done: done) ?? false) return;
    finishExit(done: done);
  }

  /// The exit itself, now (a deferred one once the carry has finished).
  void finishExit({required bool done}) {
    carrying = false;
    if (!state.editing) return;
    if (done) YoinHaptics.confirm();
    state = HomeEditState(hints: state.hints);
  }

  /// The bar's left slot: Undo while there is history, else Add (scrolls to
  /// the tray) while sections are hidden, else nothing.
  void barLeftSlot() {
    // Mid-carry the bar's Undo / Add wait (Android barLeftSlotClick): the feed's order is in the finger's hands.
    if (!state.editing || carrying) return;
    switch (resolveEditLeftSlot(state.undo.length, hiddenSections(ref.read(homeLayoutProvider)).length)) {
      case BarEditLeftSlot.undo:
        undo();
      case BarEditLeftSlot.add:
        YoinHaptics.click();
        state = HomeEditState(
          editing: true,
          undo: state.undo,
          trayRequests: state.trayRequests + 1,
          hints: state.hints,
        );
      case BarEditLeftSlot.undoDisabled:
        break;
    }
  }

  void _apply(HomeLayout next) {
    final layouts = ref.read(homeLayoutProvider.notifier);
    final current = ref.read(homeLayoutProvider);
    if (identical(next, current)) return;
    final undo = [...state.undo, current];
    state = HomeEditState(
      editing: state.editing,
      undo: undo.length > homeEditUndoLimit ? undo.sublist(undo.length - homeEditUndoLimit) : undo,
      trayRequests: state.trayRequests,
      hints: state.hints,
    );
    layouts.set(next);
  }

  void hide(HomeSection section) {
    YoinHaptics.toggle(on: false);
    _apply(withSectionEnabled(ref.read(homeLayoutProvider), section, false));
  }

  void show(HomeSection section) {
    YoinHaptics.toggle(on: true);
    _apply(withSectionEnabled(ref.read(homeLayoutProvider), section, true));
  }

  /// [section] at row preset [rows] (D1). Saved at once, one Undo step;
  /// false when it changed nothing (the resize gives the confirm haptic).
  bool setRows(HomeSection section, HomeRowPreset rows) {
    final layout = ref.read(homeLayoutProvider);
    final next = withSectionRows(layout, section, rows);
    if (identical(next, layout)) return false;
    _apply(next);
    return true;
  }

  /// A carry's drop (Android commitOrder): the sections the feed draws in
  /// [order], saved at once as one Undo step with the confirm haptic; false
  /// when it changed nothing.
  bool commitOrder(List<HomeSection> order) {
    final layout = ref.read(homeLayoutProvider);
    final next = withSectionsOrdered(layout, layout.visibleV1Sections, order);
    if (identical(next, layout)) return false;
    YoinHaptics.confirm();
    _apply(next);
    return true;
  }

  /// Moves [section] to [to] among the sections the feed draws.
  void move(HomeSection section, int to) {
    final layout = ref.read(homeLayoutProvider);
    final next = withSectionMoved(layout, layout.visibleV1Sections, section, to);
    if (identical(next, layout)) return;
    YoinHaptics.confirm();
    _apply(next);
  }

  void reset() {
    YoinHaptics.confirm();
    final layouts = ref.read(homeLayoutProvider.notifier);
    final current = ref.read(homeLayoutProvider);
    final next = resetLayout(current);
    // Sections this build doesn't know stay in the saved layout (Android reset keeps retained ids).
    if (next.sections.any((p) => p.section == null)) {
      _apply(next);
      return;
    }
    // Already the default: nothing to undo (as _apply), but a saved copy still goes.
    if (!current.isDefault) {
      final undo = [...state.undo, current];
      state = HomeEditState(
        editing: state.editing,
        undo: undo.length > homeEditUndoLimit ? undo.sublist(undo.length - homeEditUndoLimit) : undo,
        trayRequests: state.trayRequests,
        hints: state.hints,
      );
    }
    // Restore default deletes the saved layout (Android clearLayout), so it syncs as "default".
    layouts.clear();
  }

  void undo() {
    if (!state.canUndo) return;
    YoinHaptics.click();
    final previous = state.undo.last;
    state = HomeEditState(
      editing: state.editing,
      undo: state.undo.sublist(0, state.undo.length - 1),
      trayRequests: state.trayRequests,
      hints: state.hints,
    );
    ref.read(homeLayoutProvider.notifier).set(previous);
  }
}

final homeEditProvider = NotifierProvider<HomeEditController, HomeEditState>(HomeEditController.new);

/// The sections the tray offers back: hidden, and built in this port.
List<HomeSection> hiddenSections(HomeLayout layout) => [
      for (final p in layout.sections)
        if (!p.enabled && (p.section?.availableInV1 ?? false)) p.section!,
    ];

double homeEditSmoothstep(double x) {
  final t = x.clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// The plate's side outset: the page margin − 4, kept within 8–24.
double homeEditPlateOutset(double margin) =>
    (margin - HomeEditMetrics.plateInset).clamp(HomeEditMetrics.plateOutsetMin, HomeEditMetrics.plateOutsetMax);

/// One section's plate in edit mode, drawn behind [child] at [progress]
/// (0 = normal feed, 1 = editing): surfaceContainerHigh, 20 corners, out to
/// the page margin − 4 and 12 above and below. The hide button and the drag
/// handle sit at its top end. [child] takes no taps while editing.
class HomeEditPlate extends StatelessWidget {
  const HomeEditPlate({
    super.key,
    required this.progress,
    this.lift = 0,
    required this.section,
    required this.start,
    required this.end,
    required this.child,
    this.onHide,
    this.dragHandle,
    this.index = 0,
    this.count = 1,
    this.onMoveUp,
    this.onMoveDown,
    this.rowCount,
    this.onMoreRows,
    this.onFewerRows,
    this.resizeHandle,
    this.childTaps = false,
    this.onPlateTap,
    this.liftTint = 0,
    this.grow,
    this.hideKey,
  });

  /// 0..1: a lifted plate's colour toward surfaceContainerHighest (HomeCarryEngine liftTint).
  final double liftTint;

  /// The plate growing from the press square (the entry latch), or the press
  /// charge's pre-show; null = the full plate at [progress].
  final HomeEditPlateGrow? grow;

  /// The hide button's key: the edit gestures leave it alone.
  final Key? hideKey;

  /// A tap on the plate while editing (it kicks the block).
  final VoidCallback? onPlateTap;

  /// While editing, [child] still takes taps (Jump Back In's cards open their "not now" menu).
  final bool childTaps;

  /// TalkBack's Move up / Move down (null at the ends).
  final VoidCallback? onMoveUp;
  final VoidCallback? onMoveDown;

  /// A resizable section: the rows it shows now (the reader hears them), its
  /// "More rows" / "Fewer rows" (null at the ends) and the ⌟ handle, whose
  /// bottom-end corner is the plate's.
  final int? rowCount;
  final VoidCallback? onMoreRows;
  final VoidCallback? onFewerRows;
  final Widget? resizeHandle;

  final double progress;

  /// 0..1: lifted by a drag, the plate casts a shadow (HomeEditTokens LiftShadow).
  final double lift;
  final HomeSection section;

  /// The page margins the plate reaches out from.
  final double start;
  final double end;
  final Widget child;
  final VoidCallback? onHide;
  final Widget? dragHandle;
  final int index;
  final int count;

  @override
  Widget build(BuildContext context) {
    final e = progress.clamp(0.0, 1.0);
    final on = e > 0.001;
    final colors = Theme.of(context).colorScheme;
    final plateColor = Color.lerp(
      colors.surfaceContainerHigh,
      colors.surfaceContainerHighest,
      liftTint.clamp(0.0, 1.0),
    )!;
    final shadows = lift <= 0
        ? null
        : [
            BoxShadow(
              color: colors.shadow.withValues(alpha: HomeEditMetrics.liftShadowAlpha * lift),
              blurRadius: HomeEditMetrics.liftShadow * 2 * lift,
              offset: Offset(0, HomeEditMetrics.liftShadow / 2 * lift),
            ),
          ];
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final strings = HomeStrings.of(context);
    final vertical = HomeEditMetrics.plateOutsetV * e;
    final name = strings.sectionTitle(section);
    // One structure in both modes, so entering and leaving keep every
    // section's state (a shelf's scroll, a palette in flight).
    return Semantics(
      container: on,
      label: on
          ? (rowCount == null
              ? strings.sectionIndexCd(index + 1, count)
              : strings.sectionIndexRowsCd(index + 1, count, strings.rowsLabel(rowCount!)))
          : null,
      customSemanticsActions: on
          ? {
              CustomSemanticsAction(label: strings.editMoveUp): ?onMoveUp,
              CustomSemanticsAction(label: strings.editMoveDown): ?onMoveDown,
              CustomSemanticsAction(label: strings.editMoreRows): ?onMoreRows,
              CustomSemanticsAction(label: strings.editFewerRows): ?onFewerRows,
              CustomSemanticsAction(label: strings.editHide): ?onHide,
            }
          : null,
      child: GestureDetector(
        // A tap on a plate is not a tap on the page's blank space.
        behavior: HitTestBehavior.translucent,
        onTap: on ? () => onPlateTap?.call() : null,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            if (grow != null)
              Positioned.fill(
                child: CustomPaint(
                  painter: _PlateGrowPainter(
                    grow: grow!,
                    // Physical insets of the full plate and of the content, from the reading direction.
                    left: rtl ? end - homeEditPlateOutset(end) : start - homeEditPlateOutset(start),
                    right: rtl ? start - homeEditPlateOutset(start) : end - homeEditPlateOutset(end),
                    contentLeft: rtl ? end : start,
                    contentRight: rtl ? start : end,
                    vertical: vertical,
                    color: plateColor,
                    shadows: shadows,
                  ),
                ),
              )
            else
              PositionedDirectional(
                start: start - homeEditPlateOutset(start),
                end: end - homeEditPlateOutset(end),
                top: -vertical,
                bottom: -vertical,
                child: on
                    ? Opacity(
                        opacity: e,
                        child: DecoratedBox(
                          decoration: ShapeDecoration(
                            color: plateColor,
                            shape: RoundedSuperellipseBorder(
                              borderRadius: BorderRadius.circular(HomeEditMetrics.plateCorner),
                            ),
                            shadows: shadows,
                          ),
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
            IgnorePointer(
              ignoring: e > 0.5 && !childTaps,
              child: HomeEditPlateScope(
                progress: e,
                outsetStart: homeEditPlateOutset(start),
                outsetEnd: homeEditPlateOutset(end),
                child: child,
              ),
            ),
            PositionedDirectional(
              top: -vertical,
              end: end - homeEditPlateOutset(end),
              child: on
                  ? Opacity(
                      opacity: e,
                      child: IgnorePointer(
                        ignoring: e < 0.5,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            ?dragHandle,
                            // A tooltip alone is no label on HarmonyOS' reader: say it.
                            KeyedSubtree(
                              key: hideKey,
                              child: Semantics(
                                container: true,
                                button: true,
                                label: strings.hideSectionCd(name),
                                onTap: onHide,
                                excludeSemantics: true,
                                child: IconButton(
                                  tooltip: strings.hideSectionCd(name),
                                  onPressed: onHide,
                                  icon: Icon(YoinSymbols.visibilityOff, color: colors.onSurfaceVariant),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
            if (on && resizeHandle != null)
              PositionedDirectional(
                bottom: -vertical,
                end: end - homeEditPlateOutset(end),
                child: resizeHandle!,
              ),
          ],
        ),
      ),
    );
  }
}

/// A plate drawn somewhere other than its full rest (Android plateFrame):
/// [from] in the section content's own px (the latched R_press), [t] of the
/// way to the full plate, at [alpha]; or, with no [from], the press charge's
/// pre-show: R_press around [origin] at [charge], sized against the content
/// as it is laid out.
@immutable
final class HomeEditPlateGrow {
  const HomeEditPlateGrow(Rect this.from, this.t, this.alpha) : origin = Offset.zero, charge = 0;

  const HomeEditPlateGrow.press(this.origin, this.charge, this.alpha) : from = null, t = 0;

  final Rect? from;
  final double t;
  final double alpha;
  final Offset origin;
  final double charge;

  @override
  bool operator ==(Object other) =>
      other is HomeEditPlateGrow &&
      other.from == from &&
      other.t == t &&
      other.alpha == alpha &&
      other.origin == origin &&
      other.charge == charge;

  @override
  int get hashCode => Object.hash(from, t, alpha, origin, charge);
}

/// The growing plate: a painted round rect (no clip), with the lift's shadow under it.
class _PlateGrowPainter extends CustomPainter {
  _PlateGrowPainter({
    required this.grow,
    required this.left,
    required this.right,
    required this.contentLeft,
    required this.contentRight,
    required this.vertical,
    required this.color,
    required this.shadows,
  });

  final HomeEditPlateGrow grow;
  final double left;
  final double right;
  final double contentLeft;
  final double contentRight;
  final double vertical;
  final Color color;
  final List<BoxShadow>? shadows;

  @override
  void paint(Canvas canvas, Size size) {
    final alpha = grow.alpha.clamp(0.0, 1.0);
    if (alpha <= 0) return;
    final full = Rect.fromLTRB(left, -vertical, size.width - right, size.height + vertical);
    final content = Size(math.max(0, size.width - contentLeft - contentRight), size.height);
    final from = grow.from ?? pressRect(grow.origin, content, grow.charge);
    final rect = Rect.lerp(from.shift(Offset(contentLeft, 0)), full, grow.t)!;
    // The plate's own shape (a rounded superellipse), painted.
    const radius = Radius.circular(HomeEditMetrics.plateCorner);
    for (final shadow in shadows ?? const <BoxShadow>[]) {
      final shade = rect.shift(shadow.offset).inflate(shadow.spreadRadius);
      canvas.drawRSuperellipse(RSuperellipse.fromRectAndRadius(shade, radius), shadow.toPaint());
    }
    canvas.drawRSuperellipse(
      RSuperellipse.fromRectAndRadius(rect, radius),
      Paint()..color = color.withValues(alpha: color.a * alpha),
    );
  }

  @override
  bool shouldRepaint(_PlateGrowPainter old) =>
      old.grow != grow ||
      old.left != left ||
      old.right != right ||
      old.contentLeft != contentLeft ||
      old.contentRight != contentRight ||
      old.vertical != vertical ||
      old.color != color ||
      !listEquals(old.shadows, shadows);
}

/// An empty section's stand-in while editing: its name and placeholder line.
class HomeEditPlaceholder extends StatelessWidget {
  const HomeEditPlaceholder({super.key, required this.section});

  final HomeSection section;

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: HomeEditMetrics.placeholderHeight),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            strings.sectionTitle(section),
            style: gsf(HomeSectionMetrics.titleSize, weight: 600).copyWith(color: colors.onSurface),
          ),
          const SizedBox(height: HomeEditMetrics.placeholderGap),
          Text(strings.sectionPlaceholder(section), style: Theme.of(context).textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
        ],
      ),
    );
  }
}

/// The feed's end while editing (HomeEditTray.kt): "Hidden" (with an empty
/// line when nothing is), one row per hidden section — the whole row and its
/// + put it back where it was; a section that arrived since the last session
/// carries "New" — then "Reset Home".
class HomeEditTray extends ConsumerWidget {
  const HomeEditTray({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final layout = ref.watch(homeLayoutProvider);
    final hidden = hiddenSections(layout);
    final newBadges = ref.watch(homeEditProvider.select((s) => s.hints.newBadges));
    final editing = ref.watch(homeEditProvider.select((s) => s.editing));
    final edit = ref.read(homeEditProvider.notifier);
    // Leaving, the tray fades out as it was; only input stops.
    return IgnorePointer(
      ignoring: !editing,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Text fades into the status-bar seam, as the sections' does (HomeEditTray.kt seamFade).
          SeamFade(
            fontSize: HomeSectionMetrics.titleSize,
            child: Text(
              strings.editHidden,
              style: gsf(HomeSectionMetrics.titleSize, weight: 600).copyWith(color: colors.onSurface),
            ),
          ),
          const SizedBox(height: HomeEditMetrics.trayGap),
          if (hidden.isEmpty)
            SeamText(strings.editHiddenEmpty, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant))
          else
            for (final section in hidden)
              Padding(
                key: ValueKey(section),
                padding: const EdgeInsets.only(bottom: HomeEditMetrics.trayRowGap),
                child: HomeEditTrayRow(
                  section: section,
                  newBadge: newBadges.contains(section),
                  onShow: () => edit.show(section),
                ),
              ),
          const SizedBox(height: HomeEditMetrics.trayGap),
          Center(
            child: TextButton(
              // Nothing to reset on the default arrangement (HomeEditController.canReset).
              onPressed: layout.isDefault ? null : edit.reset,
              style: TextButton.styleFrom(
                minimumSize: const Size(0, HomeEditMetrics.footerButtonHeight),
                padding: const EdgeInsets.symmetric(horizontal: HomeEditMetrics.footerButtonPadding),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(YoinSymbols.refresh, size: HomeEditMetrics.footerIconSize),
                  const SizedBox(width: HomeEditMetrics.footerIconGap),
                  Text(strings.editReset),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// One hidden section in the tray: a panel row, its name (with "New" when it
/// arrived since the last session) over its supporting line, and a tonal +.
class HomeEditTrayRow extends StatelessWidget {
  const HomeEditTrayRow({super.key, required this.section, required this.newBadge, required this.onShow});

  final HomeSection section;
  final bool newBadge;
  final VoidCallback onShow;

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final title = strings.sectionTitle(section);
    final shape = RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel));
    final fontScale = math.max(1.0, MediaQuery.textScalerOf(context).scale(1));
    // Painted, not clipped: the panel is a shape decoration and the ripple is bounded by it.
    return Semantics(
      button: true,
      onTapHint: strings.showSectionAction(title),
      child: Material(
        color: colors.surfaceContainerHigh,
        shape: shape,
        child: InkWell(
          customBorder: shape,
          onTap: onShow,
          child: SizedBox(
            height: HomeEditMetrics.trayRowHeight * fontScale,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: HomeEditMetrics.trayRowPadding),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Flexible(
                              child: SeamText(
                                title,
                                style: text.titleMedium!.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
                              ),
                            ),
                            if (newBadge) ...[
                              const SizedBox(width: HomeEditMetrics.newChipGap),
                              const HomeEditNewChip(),
                            ],
                          ],
                        ),
                        // A line too long for the row keeps moving (MarqueeText), never cut.
                        SeamFade(
                          fontSize: text.bodySmall!.fontSize!,
                          child: MarqueeText(
                            strings.sectionSupporting(section),
                            style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: HomeEditMetrics.trayRowContentGap),
                  SizedBox.square(
                    dimension: HomeEditMetrics.showButtonSize,
                    child: IconButton.filledTonal(
                      padding: EdgeInsets.zero,
                      tooltip: strings.showSectionCd(title),
                      onPressed: onShow,
                      icon: const Icon(YoinSymbols.add),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// "New": a section that arrived since the last edit session (Q6a), on the
/// tray row and on the footer's "Edit Home".
class HomeEditNewChip extends StatelessWidget {
  const HomeEditNewChip({super.key});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(color: colors.tertiaryContainer, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: HomeEditMetrics.newChipPaddingH,
          vertical: HomeEditMetrics.newChipPaddingV,
        ),
        child: Text(
          HomeStrings.of(context).editNew,
          maxLines: 1,
          softWrap: false,
          style: Theme.of(context).textTheme.labelSmall!.copyWith(color: colors.onTertiaryContainer),
        ),
      ),
    );
  }
}

/// The feed's own way into edit mode, at its end (Q6b): a quiet "Edit Home",
/// badged "New" while a new section waits in the tray.
class HomeEditFooter extends ConsumerWidget {
  const HomeEditFooter({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final newBadge = ref.watch(homeEditFooterNewProvider);
    return Center(
      child: TextButton(
        onPressed: ref.read(homeEditProvider.notifier).enter,
        style: TextButton.styleFrom(
          foregroundColor: colors.onSurfaceVariant,
          minimumSize: const Size(0, HomeEditMetrics.footerButtonHeight),
          padding: const EdgeInsets.symmetric(horizontal: HomeEditMetrics.footerButtonPadding),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(YoinSymbols.edit, size: HomeEditMetrics.footerIconSize),
            const SizedBox(width: HomeEditMetrics.footerIconGap),
            Text(HomeStrings.of(context).editTitle, style: Theme.of(context).textTheme.labelLarge),
            if (newBadge) ...[const SizedBox(width: HomeEditMetrics.footerIconGap), const HomeEditNewChip()],
          ],
        ),
      ),
    );
  }
}

/// "Home is empty": every section hidden, outside edit mode.
class HomeEmptyLayoutCard extends ConsumerWidget {
  const HomeEmptyLayoutCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final strings = HomeStrings.of(context);
    return ElasticPress(
      onTap: ref.read(homeEditProvider.notifier).enter,
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.surfaceContainerLow,
          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(HomeEditMetrics.plateCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(HomeEmptyCardMetrics.padding),
          child: Text(
            strings.editAllHiddenTitle,
            style: Theme.of(context).textTheme.titleMedium!.copyWith(color: colors.onSurface),
          ),
        ),
      ),
    );
  }
}

/// What a section's content knows of its plate while editing: the edit
/// progress and the plate's horizontal reach past the content on each side.
class HomeEditPlateScope extends InheritedWidget {
  const HomeEditPlateScope({
    super.key,
    required this.progress,
    required this.outsetStart,
    required this.outsetEnd,
    required super.child,
  });

  final double progress;
  final double outsetStart;
  final double outsetEnd;

  static HomeEditPlateScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<HomeEditPlateScope>();

  @override
  bool updateShouldNotify(HomeEditPlateScope old) =>
      old.progress != progress || old.outsetStart != outsetStart || old.outsetEnd != outsetEnd;
}

/// A full-bleed shelf cut back to its plate while editing (Android
/// homeEditShelfClip, plate V1): past the plate the shelf fades out, and over
/// [HomeEditMetrics.shelfFade] inside the plate's edges it eases in
/// (smoothstep), by as much as edit mode has come in. [escapeStart] /
/// [escapeEnd] are how far the shelf reaches past its section's content (the
/// page margins it bleeds over).
///
/// No clip and no mask layer (CornerMask's way, which HarmonyOS needs): what
/// lies behind the shelf — the page's ground beyond the plate, the plate
/// within it — is painted over it. Outside edit mode nothing is painted.
class HomeEditShelfClip extends StatelessWidget {
  const HomeEditShelfClip({super.key, required this.escapeStart, required this.escapeEnd, required this.child});

  final double escapeStart;
  final double escapeEnd;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scope = HomeEditPlateScope.maybeOf(context);
    if (scope == null) return child;
    final colors = Theme.of(context).colorScheme;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return _ShelfMask(
      progress: scope.progress,
      // Physical left / right reach, from the reading direction's start / end.
      left: (rtl ? escapeEnd : escapeStart) - (rtl ? scope.outsetEnd : scope.outsetStart),
      right: (rtl ? escapeStart : escapeEnd) - (rtl ? scope.outsetStart : scope.outsetEnd),
      page: homePageGround(colors),
      pageHeight: MediaQuery.sizeOf(context).height,
      plate: colors.surfaceContainerHigh,
      child: child,
    );
  }
}

/// Home's ground, top to bottom of the window (home_screen.dart's page gradient).
List<Color> homePageGround(ColorScheme colors) => [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow];

/// The colour of a vertical [stops]-even gradient at [t] (0 top … 1 bottom).
Color homePageGroundAt(List<Color> ground, double t) {
  final x = t.clamp(0.0, 1.0) * (ground.length - 1);
  final i = x.floor().clamp(0, ground.length - 2);
  return Color.lerp(ground[i], ground[i + 1], x - i)!;
}

class _ShelfMask extends SingleChildRenderObjectWidget {
  const _ShelfMask({
    required this.progress,
    required this.left,
    required this.right,
    required this.page,
    required this.pageHeight,
    required this.plate,
    super.child,
  });

  final double progress;
  final double left;
  final double right;
  final List<Color> page;
  final double pageHeight;
  final Color plate;

  @override
  _RenderShelfMask createRenderObject(BuildContext context) => _RenderShelfMask()..set(this);

  @override
  void updateRenderObject(BuildContext context, _RenderShelfMask render) => render.set(this);
}

class _RenderShelfMask extends RenderProxyBox {
  double _progress = 0;
  double _left = 0;
  double _right = 0;
  List<Color> _page = const [];
  double _pageHeight = 1;
  Color _plate = const Color(0x00000000);

  void set(_ShelfMask w) {
    _progress = w.progress;
    _left = w.left;
    _right = w.right;
    _page = w.page;
    _pageHeight = w.pageHeight;
    _plate = w.plate;
    markNeedsPaint();
  }

  static const _rampSteps = 6;

  @override
  void paint(PaintingContext context, Offset offset) {
    super.paint(context, offset);
    final m = homeEditSmoothstep(_progress);
    if (m <= 0.001 || size.width <= 0 || _page.length < 2) return;
    final canvas = context.canvas;
    // The page's ground around the shelf's middle (a shelf is short next to the window).
    final global = localToGlobal(Offset.zero);
    final ground = homePageGroundAt(_page, (global.dy + size.height / 2) / math.max(_pageHeight, 1));
    final behindPlate = Color.alphaBlend(_plate.withValues(alpha: _plate.a * _progress.clamp(0.0, 1.0)), ground);
    final plateLeft = _left, plateRight = size.width - _right;
    final ramp = math.min(HomeEditMetrics.shelfFade, math.max(0.0, (plateRight - plateLeft) / 2));
    Paint fill(Color c, double a) => Paint()..color = c.withValues(alpha: c.a * a.clamp(0.0, 1.0));
    // Beyond the plate: the page's ground, as far as edit mode has come in.
    if (plateLeft > 0) {
      canvas.drawRect(
        Rect.fromLTRB(offset.dx, offset.dy, offset.dx + plateLeft, offset.dy + size.height),
        fill(ground, m),
      );
    }
    if (plateRight < size.width) {
      canvas.drawRect(
        Rect.fromLTRB(offset.dx + plateRight, offset.dy, offset.dx + size.width, offset.dy + size.height),
        fill(ground, m),
      );
    }
    if (ramp <= 0) return;
    // Inside each plate edge: the plate, easing off over the ramp.
    for (var i = 0; i < _rampSteps; i++) {
      final t0 = i / _rampSteps, t1 = (i + 1) / _rampSteps;
      final a = m * (1 - homeEditSmoothstep((t0 + t1) / 2));
      if (a <= 0.001) continue;
      final paint = fill(behindPlate, a);
      canvas
        ..drawRect(
          Rect.fromLTRB(
            offset.dx + plateLeft + ramp * t0,
            offset.dy,
            offset.dx + plateLeft + ramp * t1,
            offset.dy + size.height,
          ),
          paint,
        )
        ..drawRect(
          Rect.fromLTRB(
            offset.dx + plateRight - ramp * t1,
            offset.dy,
            offset.dx + plateRight - ramp * t0,
            offset.dy + size.height,
          ),
          paint,
        );
    }
  }
}
