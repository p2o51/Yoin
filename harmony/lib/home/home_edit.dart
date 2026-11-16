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
/// Not ported yet: the wiggle, the bar's edit pose (Undo and Done sit in the
/// header instead) and the shelves' clip to their plate.
library;

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../nowplaying/haptics.dart';
import '../shell/shell_state.dart';
import '../shell/yoin_section.dart';
import '../theme/theme.dart';
import 'home_metrics.dart';
import 'home_section.dart';
import 'home_strings.dart';
import 'widgets/elastic_press.dart';

/// Undo keeps this many steps.
const int homeEditUndoLimit = 20;

@immutable
final class HomeEditState {
  const HomeEditState({this.editing = false, this.undo = const []});

  final bool editing;

  /// Layouts before each step, oldest first.
  final List<HomeLayout> undo;

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
    return const HomeEditState();
  }

  void enter() {
    if (state.editing) return;
    YoinHaptics.longPress();
    state = const HomeEditState(editing: true);
  }

  void exit() {
    if (!state.editing) return;
    state = const HomeEditState();
  }

  void _apply(HomeLayout next) {
    final layouts = ref.read(homeLayoutProvider.notifier);
    final current = ref.read(homeLayoutProvider);
    if (identical(next, current)) return;
    final undo = [...state.undo, current];
    state = HomeEditState(editing: state.editing, undo: undo.length > homeEditUndoLimit ? undo.sublist(undo.length - homeEditUndoLimit) : undo);
    layouts.set(next);
  }

  void hide(HomeSection section) {
    YoinHaptics.click();
    _apply(withSectionEnabled(ref.read(homeLayoutProvider), section, false));
  }

  void show(HomeSection section) {
    YoinHaptics.click();
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
    _apply(resetLayout(ref.read(homeLayoutProvider)));
  }

  void undo() {
    if (!state.canUndo) return;
    YoinHaptics.click();
    final previous = state.undo.last;
    state = HomeEditState(editing: state.editing, undo: state.undo.sublist(0, state.undo.length - 1));
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
  });

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
        onTap: on ? () {} : null,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
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
                          color: colors.surfaceContainerHigh,
                          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(HomeEditMetrics.plateCorner)),
                          shadows: lift <= 0
                              ? null
                              : [
                                  BoxShadow(
                                    color: colors.shadow.withValues(alpha: HomeEditMetrics.liftShadowAlpha * lift),
                                    blurRadius: HomeEditMetrics.liftShadow * 2 * lift,
                                    offset: Offset(0, HomeEditMetrics.liftShadow / 2 * lift),
                                  ),
                                ],
                        ),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
            IgnorePointer(ignoring: e > 0.5 && !childTaps, child: child),
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
                            Semantics(
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

/// The feed's end while editing: the "Hidden" tray (a + per hidden section
/// puts it back) and "Reset Home".
class HomeEditTray extends ConsumerWidget {
  const HomeEditTray({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final hidden = hiddenSections(ref.watch(homeLayoutProvider));
    final edit = ref.read(homeEditProvider.notifier);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(strings.editHidden, style: gsf(HomeSectionMetrics.titleSize, weight: 600).copyWith(color: colors.onSurface)),
        const SizedBox(height: HomeEditMetrics.trayGap),
        if (hidden.isEmpty)
          Text(strings.editHiddenEmpty, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant))
        else
          for (final section in hidden)
            Padding(
              key: ValueKey(section),
              padding: const EdgeInsets.only(bottom: HomeEditMetrics.trayRowGap),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(strings.sectionTitle(section), style: text.titleMedium!.copyWith(color: colors.onSurface)),
                        Text(strings.sectionSupporting(section), style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant)),
                      ],
                    ),
                  ),
                  IconButton.filledTonal(
                    tooltip: strings.showSectionCd(strings.sectionTitle(section)),
                    onPressed: () => edit.show(section),
                    icon: const Icon(YoinSymbols.add),
                  ),
                ],
              ),
            ),
        const SizedBox(height: HomeEditMetrics.trayGap),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: TextButton(onPressed: edit.reset, child: Text(strings.editReset)),
        ),
      ],
    );
  }
}

/// The feed's last line in normal mode: "Edit Home", always there.
class HomeEditFooter extends ConsumerWidget {
  const HomeEditFooter({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => Center(
        child: TextButton.icon(
          onPressed: ref.read(homeEditProvider.notifier).enter,
          icon: const Icon(YoinSymbols.edit),
          label: Text(HomeStrings.of(context).editTitle),
        ),
      );
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
          child: Text(strings.editAllHiddenTitle, style: Theme.of(context).textTheme.titleMedium!.copyWith(color: colors.onSurface)),
        ),
      ),
    );
  }
}

/// The header's edit pose: Undo (dim when there is nothing to undo) and Done.
class HomeEditActions extends ConsumerWidget {
  const HomeEditActions({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final edit = ref.watch(homeEditProvider);
    final controller = ref.read(homeEditProvider.notifier);
    final strings = HomeStrings.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // A tooltip alone is no label on HarmonyOS' reader: say it.
        Semantics(
          container: true,
          button: true,
          enabled: edit.canUndo,
          label: strings.undo,
          onTap: edit.canUndo ? controller.undo : null,
          excludeSemantics: true,
          child: IconButton(
            tooltip: strings.undo,
            onPressed: edit.canUndo ? controller.undo : null,
            icon: const Icon(YoinSymbols.undo),
          ),
        ),
        const SizedBox(width: HomeEditMetrics.actionGap),
        FilledButton(onPressed: controller.exit, child: Text(strings.done)),
      ],
    );
  }
}
