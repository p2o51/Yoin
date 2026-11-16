import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../l10n/strings.dart';
import '../nowplaying/haptics.dart';
import '../nowplaying/nowplaying_strings.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/expressive/expressive_shapes.dart';
import '../ui/component/expressive/expressive_split_button.dart';
import '../ui/component/expressive/expressive_surface_button.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/desktop_input.dart';
import 'bar_edit_pose.dart';
import 'bar_geometry.dart';
import 'detail_chrome.dart';
import 'shell_state.dart';
import 'yoin_section.dart';

/// The window's one floating bar (YoinButtonGroup / FloatingBottomBar).
///
/// Poses, all from [resolveBarGeometry] so surface and slots agree each frame:
/// - idle (nothing playing): Home and Library as two labelled halves;
/// - nav: [Home][now-playing pill][Library], the selected button 1.5× wide;
/// - detail (a detail page registered [DetailPlayActions]): [Play split][pill];
/// - edit (Home being edited, [BarEditPose]): the idle path with Home →
///   [Undo|Add] and Library → [Done], the pill folded.
/// Widths spring on the Standard role (default spatial for selection / idle /
/// pose, fast spatial for press echoes); colours on the effects spring.
/// Portrait bar below 600 wide; at 600+ the bar is centred, capped at 600
/// and 60 tall (BarTokens.centered*).
/// The pill's narrowest layout (cover + gaps + a sliver of text).
const double _pillMinLayout = 96;

class YoinBottomBar extends StatefulWidget {
  const YoinBottomBar({
    super.key,
    required this.selected,
    required this.onSelect,
    this.hasTrack = false,
    this.detailActions,
    this.pillBuilder,
    this.navOnly = false,
    this.availableWidth,
    this.paneOpen = false,
    this.onLibraryLongPress,
    this.editPose,
    this.form,
  });

  /// The form to lay out in (portrait or centred); null: the window's. A form
  /// cross-fade keeps the outgoing bar in the form it had.
  final ShellChromeForm? form;

  /// Long-press on Library: its search (YoinNavHost onLibraryLongClick). A
  /// search hint rises over the button while it is held. Null: no long-press.
  final VoidCallback? onLibraryLongPress;

  /// Home is being edited (or was, while the pose springs back): the bar's
  /// destinations become [Undo|Add] and [Done].
  final BarEditPose? editPose;

  /// The Wide window's detail column is open: with page actions the bar
  /// takes the merged pose and widens to [BarTokens.mergedMaxWidth].
  final bool paneOpen;

  /// The width of the box the bar sits in, when narrower than the window.
  final double? availableWidth;

  final YoinSection selected;
  final ValueChanged<YoinSection> onSelect;

  /// A track exists: the bar takes the nav pose and shows the pill.
  final bool hasTrack;

  /// Non-null on a detail page: the bar takes the detail pose.
  final DetailPlayActions? detailActions;

  /// The Now Playing side panel is open: the pill folds away and the bar
  /// keeps only its two destinations.
  final bool navOnly;

  /// Builds the now-playing pill; it reports its press for the echo.
  final Widget Function(BuildContext context, ValueChanged<bool> onPressChanged)? pillBuilder;

  @override
  State<YoinBottomBar> createState() => _YoinBottomBarState();
}

class _YoinBottomBarState extends State<YoinBottomBar> with TickerProviderStateMixin {
  /// 1 = Home selected, 0 = Library selected (aspect: spatial; colours: effects).
  late final SpringValue _selection = SpringValue(
    vsync: this,
    initial: widget.selected == YoinSection.home ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _selectionColor = SpringValue(
    vsync: this,
    initial: widget.selected == YoinSection.home ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _idle = SpringValue(
    vsync: this,
    initial: widget.hasTrack ? 0 : 1,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _detail = SpringValue(
    vsync: this,
    initial: widget.detailActions == null ? 0 : 1,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _pane = SpringValue(
    vsync: this,
    initial: widget.paneOpen && widget.detailActions != null ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _navOnly = SpringValue(
    vsync: this,
    initial: widget.navOnly ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _homePress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);
  late final SpringValue _libraryPress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);
  late final SpringValue _pillPress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);

  /// The edit pose P (Home's edit progress spring, so the bar and the header move as one).
  late final SpringValue _edit = SpringValue(
    vsync: this,
    initial: widget.editPose?.editing ?? false ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );

  /// The left slot's three layers (Undo, Add, the dimmed Undo) cross-fade on the fast effects spring.
  late final List<SpringValue> _slotAlphas = [
    for (final slot in BarEditLeftSlot.values)
      SpringValue(
        vsync: this,
        initial: (widget.editPose?.leftSlot ?? BarEditLeftSlot.undoDisabled) == slot ? 1 : 0,
        spring: YoinSprings.fastEffects,
      ),
  ];

  /// While editing a press squares the button off to the small corner (the haptic's visual twin).
  late final SpringValue _homeCorner = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _libraryCorner = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  /// The last edit pose, kept while the bar springs back to nav.
  BarEditPose? _lastPose;

  /// The last detail actions, kept while the pose springs back to nav.
  DetailPlayActions? _lastActions;

  /// Long-press Library's search hint: its scale (spatial) and alpha (effects).
  late final SpringValue _hintScale = SpringValue(
    vsync: this,
    initial: BarTokens.searchHintEnterScale,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _hintAlpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  bool _hintShown = false;
  Timer? _hintTimer;

  /// The hint shows 240 ms into a press on Library and leaves 120 ms after
  /// it (a long-press shows it at once), as Android's one reveal / settle.
  void _libraryHeld(bool down) {
    if (widget.onLibraryLongPress == null) return;
    _hintTimer?.cancel();
    _hintTimer = Timer(
      Duration(milliseconds: down ? BarTokens.searchHintDelayMs : BarTokens.searchHintSettleMs),
      () => _showHint(down),
    );
  }

  void _showHint(bool show) {
    if (!mounted || show == _hintShown) return;
    _hintShown = show;
    if (MediaQuery.disableAnimationsOf(context)) {
      _hintScale.snapTo(1);
      _hintAlpha.snapTo(show ? 1 : 0);
      return;
    }
    if (show) {
      // Each reveal scales in from small; the leave shrinks only a little.
      if (_hintAlpha.value < 0.01) _hintScale.snapTo(BarTokens.searchHintEnterScale);
      _hintScale.animateTo(1);
      _hintAlpha.animateTo(1);
    } else {
      _hintScale.animateTo(BarTokens.searchHintExitScale);
      _hintAlpha.animateTo(0);
    }
  }

  @override
  void initState() {
    super.initState();
    _lastActions = widget.detailActions;
    _lastPose = widget.editPose;
  }

  @override
  void didUpdateWidget(YoinBottomBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    final home = widget.selected == YoinSection.home ? 1.0 : 0.0;
    _selection.animateTo(home);
    _selectionColor.animateTo(home);
    _idle.animateTo(widget.hasTrack ? 0 : 1);
    _detail.animateTo(widget.detailActions == null ? 0 : 1);
    final pane = widget.paneOpen && widget.detailActions != null ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _navOnly.snapTo(widget.navOnly ? 1 : 0);
      _pane.snapTo(pane);
    } else {
      _navOnly.animateTo(widget.navOnly ? 1 : 0);
      _pane.animateTo(pane);
    }
    if (widget.detailActions != null) _lastActions = widget.detailActions;
    final pose = widget.editPose;
    if (pose != null) _lastPose = pose;
    final editing = pose?.editing ?? false;
    if (MediaQuery.disableAnimationsOf(context)) {
      _edit.snapTo(editing ? 1 : 0);
    } else {
      _edit.animateTo(editing ? 1 : 0);
    }
    final slot = (pose ?? _lastPose)?.leftSlot ?? BarEditLeftSlot.undoDisabled;
    for (final v in BarEditLeftSlot.values) {
      _slotAlphas[v.index].animateTo(v == slot ? 1 : 0);
    }
  }

  @override
  void dispose() {
    for (final s in [
      _selection,
      _selectionColor,
      _idle,
      _detail,
      _pane,
      _navOnly,
      _homePress,
      _libraryPress,
      _pillPress,
      _hintScale,
      _hintAlpha,
      _edit,
      ..._slotAlphas,
      _homeCorner,
      _libraryCorner,
    ]) {
      s.dispose();
    }
    _hintTimer?.cancel();
    super.dispose();
  }

  String _leftSlotCd(YoinStrings strings, BarEditLeftSlot slot) => switch (slot) {
    BarEditLeftSlot.undo || BarEditLeftSlot.undoDisabled => strings.barCdUndo,
    BarEditLeftSlot.add => strings.barCdShowHidden,
  };

  /// The edit pose's left-slot content, stacked and cross-faded: Undo, Add and
  /// the dimmed Undo (Undo is a text label, as on Android).
  Widget _leftSlotLayers(BuildContext context, YoinStrings strings, double editSwap, double labelAlpha) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge;
    Widget layer(BarEditLeftSlot slot, Widget child) {
      final a = editSwap * _slotAlphas[slot.index].value.clamp(0.0, 1.0);
      if (a <= 0.002) return const SizedBox.shrink();
      return Opacity(opacity: a, child: child);
    }

    return Stack(
      alignment: Alignment.center,
      children: [
        layer(
          BarEditLeftSlot.undo,
          Text(strings.barLabelUndo, maxLines: 1, softWrap: false, style: style?.copyWith(color: colors.onSurface)),
        ),
        layer(
          BarEditLeftSlot.add,
          _BarIconLabel(
            icon: YoinSymbols.add,
            label: strings.barLabelAdd,
            labelAlpha: labelAlpha,
            color: colors.onSurface,
          ),
        ),
        layer(
          BarEditLeftSlot.undoDisabled,
          Text(
            strings.barLabelUndo,
            maxLines: 1,
            softWrap: false,
            style: style?.copyWith(color: colors.onSurface.withValues(alpha: BarEditPose.disabledAlpha)),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = YoinStrings.of(context);
    final npStrings = NowPlayingStrings.of(context);
    final window = YoinWindowInfo.of(context);
    final centered = (widget.form ?? window.chromeForm) == ShellChromeForm.centeredBar;
    final height = centered ? BarTokens.centeredHeight : BarTokens.height;
    final buttonHeight = centered ? BarTokens.centeredButtonHeight : BarTokens.buttonHeight;
    final rowPadding = (height - buttonHeight) / 2;
    // The form follows the window; the width also fits the box the bar is
    // laid in (the content beside the Now Playing side panel).
    final room = math.min(window.width, widget.availableWidth ?? window.width);
    final barWidth = centered
        ? (room - 2 * BarTokens.centeredHorizontalMargin).clamp(
            0.0,
            widget.paneOpen ? BarTokens.mergedMaxWidth : BarTokens.centeredMaxWidth,
          )
        : room - 2 * BarTokens.horizontalMargin;
    final innerWidth = (barWidth - 2 * rowPadding).clamp(0.0, double.infinity);
    // The centred bar promotes Shuffle out of the ▾ menu into its own button.
    final promoted = centered && _lastActions?.onShuffle != null ? 1 : 0;

    // The surface wraps the slots (its width is the row's): the full bar
    // width normally, hugging the destinations beside the side panel.
    final bar = Container(
      height: height,
      padding: EdgeInsets.all(rowPadding),
      decoration: ShapeDecoration(
        color: colors.surfaceContainerHigh,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(BarTokens.corner)),
      ),
      child: AnimatedBuilder(
        animation: Listenable.merge([
          _selection.animation,
          _selectionColor.animation,
          _idle.animation,
          _detail.animation,
          _pane.animation,
          _navOnly.animation,
          _homePress.animation,
          _libraryPress.animation,
          _pillPress.animation,
          _hintScale.animation,
          _hintAlpha.animation,
          _edit.animation,
          for (final a in _slotAlphas) a.animation,
          _homeCorner.animation,
          _libraryCorner.animation,
        ]),
        builder: (context, _) {
          final g = resolveBarGeometry(
            BarGeometryInput(
              innerWidth: innerWidth,
              buttonHeight: buttonHeight,
              homeSelection: _selection.value,
              idle: _idle.value,
              homePress: _homePress.value,
              libraryPress: _libraryPress.value,
              pillPress: _pillPress.value,
              detailPose: _detail.value,
              centered: centered,
              promotedCount: promoted,
              navOnly: _navOnly.value,
              pane: _pane.value,
              mergedCount: _lastActions?.onShuffle != null ? 1 : 0,
              edit: _edit.value,
            ),
          );
          final edit = _edit.value.clamp(0.0, 1.0);
          final editSwap = barEditSwap(edit);
          final pose = _lastPose;
          final editing = widget.editPose?.editing ?? false;
          final slot = pose?.leftSlot ?? BarEditLeftSlot.undoDisabled;
          final colorSelection = _selectionColor.value.clamp(0.0, 1.0);
          final detail = _detail.value.clamp(0.0, 1.0);
          final actions = _lastActions;
          final homeContainer = Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, colorSelection)!;
          final libraryContainer = Color.lerp(
            colors.surfaceContainerHighest,
            colors.primaryContainer,
            1 - colorSelection,
          )!;
          final libraryContent = Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, 1 - colorSelection)!;
          // The left slot's press morphs only when it does something (Undo, Add).
          final slotActive = editing && slot != BarEditLeftSlot.undoDisabled;
          Widget homeButton() => _BarButton(
            icon: colorSelection > 0.5 ? YoinSymbols.homeFilled : YoinSymbols.home,
            label: strings.barLabelHome,
            semantics: editing ? _leftSlotCd(strings, slot) : npStrings.barCdHome,
            enabled: !editing || slot != BarEditLeftSlot.undoDisabled,
            labelAlpha: g.labelAlpha,
            selection: colorSelection,
            container: Color.lerp(homeContainer, colors.surfaceContainerHighest, editSwap),
            editSwap: editSwap,
            editLayer: editSwap > 0.01 ? _leftSlotLayers(context, strings, editSwap, g.labelAlpha) : null,
            pressCorner: _homeCorner.value,
            onPress: (down) {
              _homePress.animateTo(down ? 1 : 0);
              _homeCorner.animateTo(down && slotActive ? 1 : 0);
            },
            onTap: () {
              if (editing) {
                // Edit haptics belong to the controller alone.
                widget.editPose?.onLeftSlot();
                return;
              }
              YoinHaptics.click();
              widget.onSelect(YoinSection.home);
            },
          );
          final onLibraryLongPress = widget.onLibraryLongPress;
          Widget libraryButton() => Listener(
            // The finger itself, not the tap: the hint stays while a long-press is held.
            onPointerDown: (_) => _libraryHeld(true),
            onPointerUp: (_) => _libraryHeld(false),
            onPointerCancel: (_) => _libraryHeld(false),
            child: _BarButton(
              icon: colorSelection < 0.5 ? YoinSymbols.libraryFilled : YoinSymbols.library,
              label: strings.barLabelLibrary,
              semantics: editing ? strings.barCdDone : npStrings.barCdLibrary,
              labelAlpha: g.labelAlpha,
              selection: 1 - colorSelection,
              ripple: false,
              container: Color.lerp(libraryContainer, colors.primary, edit),
              content: Color.lerp(libraryContent, colors.onPrimary, edit),
              editSwap: editSwap,
              editLayer: editSwap > 0.01
                  ? _BarIconLabel(
                      icon: YoinSymbols.check,
                      label: strings.barLabelDone,
                      labelAlpha: g.labelAlpha,
                      color: Color.lerp(libraryContent, colors.onPrimary, edit)!,
                    )
                  : null,
              pressCorner: _libraryCorner.value,
              onPress: (down) {
                _libraryPress.animateTo(down ? 1 : 0);
                _libraryCorner.animateTo(down && editing ? 1 : 0);
              },
              onTap: () {
                if (editing) {
                  widget.editPose?.onDone();
                  return;
                }
                YoinHaptics.click();
                widget.onSelect(YoinSection.library);
              },
              // Long-press Library opens its search; while Home is edited this slot is Done.
              onLongPress: onLibraryLongPress == null || editing
                  ? null
                  : () {
                      _hintTimer?.cancel();
                      _showHint(true);
                      YoinHaptics.contextClick();
                      onLibraryLongPress();
                    },
            ),
          );
          final merged = g.pane;
          // The search hint rides above whichever slot hosts Library: the
          // bar's end in the nav pose, beside Home when merged.
          final hint = _hintAlpha.value <= 0.001
              ? null
              : Positioned.fill(
                  child: LayoutBuilder(
                    builder: (context, box) {
                      final navX = box.maxWidth - (BarTokens.searchHintEndInset - rowPadding) - BarTokens.searchHintBox;
                      final mergedX =
                          g.homeWidth + BarTokens.itemGap + g.libraryWidth / 2 - BarTokens.searchHintBox / 2;
                      return Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Positioned(
                            left: lerpDouble(navX, mergedX, merged.clamp(0.0, 1.0)),
                            top: BarTokens.searchHintLift - rowPadding,
                            child: IgnorePointer(
                              child: LibrarySearchHint(
                                scale: _hintScale.value,
                                alpha: _hintAlpha.value.clamp(0.0, 1.0),
                                semantics: npStrings.barCdSearch,
                              ),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                );
          final row = Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: g.splitWidth,
                height: buttonHeight,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (g.navAlpha > 0)
                      IgnorePointer(
                        ignoring: detail > 0.5 || merged > 0.5,
                        child: Opacity(opacity: g.navAlpha, child: homeButton()),
                      ),
                    // MERGED: the shell's two destinations, side by side.
                    if (merged > 0)
                      IgnorePointer(
                        ignoring: merged < 0.5,
                        child: Opacity(
                          opacity: merged,
                          child: ClipRect(
                            child: OverflowBox(
                              alignment: AlignmentDirectional.centerStart,
                              minWidth: g.homeWidth + BarTokens.itemGap + g.libraryWidth,
                              maxWidth: g.homeWidth + BarTokens.itemGap + g.libraryWidth,
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                children: [
                                  SizedBox(width: g.homeWidth, child: homeButton()),
                                  const SizedBox(width: BarTokens.itemGap),
                                  SizedBox(width: g.libraryWidth, child: libraryButton()),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    if (g.splitAlpha > 0 && actions != null)
                      IgnorePointer(
                        ignoring: detail < 0.5 || merged > 0.5,
                        child: Opacity(
                          opacity: g.splitAlpha,
                          // Laid out at its resting width while the slot morphs; the slot clips it.
                          child: ClipRect(
                            child: OverflowBox(
                              alignment: AlignmentDirectional.centerStart,
                              minWidth: g.splitDetailWidth,
                              maxWidth: g.splitDetailWidth,
                              child: _PlaySplit(actions: actions, height: buttonHeight, shuffleInMenu: promoted == 0),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: BarTokens.itemGap),
              // EXTRAS (centred detail pose): Shuffle as its own round button, each with its trailing gap.
              if (g.extrasSlotWidth > 0.5 && actions?.onShuffle != null)
                SizedBox(
                  width: g.extrasSlotWidth,
                  height: buttonHeight,
                  child: ClipRect(
                    child: OverflowBox(
                      alignment: AlignmentDirectional.centerStart,
                      minWidth: buttonHeight + BarTokens.itemGap,
                      maxWidth: buttonHeight + BarTokens.itemGap,
                      child: Opacity(
                        opacity: g.splitAlpha,
                        child: IgnorePointer(
                          ignoring: detail < 0.5,
                          child: Row(
                            children: [
                              _BarExtraButton(
                                icon: YoinSymbols.shuffle,
                                label: npStrings.playMenuShuffle,
                                size: buttonHeight,
                                onTap: () {
                                  YoinHaptics.click();
                                  actions!.onShuffle!();
                                },
                              ),
                              const SizedBox(width: BarTokens.itemGap),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              SizedBox(
                width: g.pillWidth,
                height: buttonHeight,
                child: g.composePill && widget.pillBuilder != null && g.pillWidth > 0
                    ? Opacity(
                        opacity: g.pillAlpha,
                        // While the morph squeezes it (and it is still transparent), the pill keeps
                        // a minimum layout width so its row never overflows; the slot clips it.
                        child: ClipRect(
                          child: OverflowBox(
                            alignment: AlignmentDirectional.centerStart,
                            minWidth: g.pillWidth < _pillMinLayout ? _pillMinLayout : g.pillWidth,
                            maxWidth: g.pillWidth < _pillMinLayout ? _pillMinLayout : g.pillWidth,
                            // Fading out on the way into the edit pose: no press, tap or haptic (Android enabled = !editing).
                            child: IgnorePointer(
                              ignoring: widget.editPose?.editing ?? false,
                              child: widget.pillBuilder!(context, (down) => _pillPress.animateTo(down ? 1 : 0)),
                            ),
                          ),
                        ),
                      )
                    : null,
              ),
              if (g.librarySlotWidth > 0.5)
                SizedBox(
                  width: g.librarySlotWidth,
                  height: buttonHeight,
                  child: ClipRect(
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        if (g.navAlpha > 0)
                          OverflowBox(
                            alignment: AlignmentDirectional.centerEnd,
                            minWidth: g.libraryWidth,
                            maxWidth: g.libraryWidth,
                            child: IgnorePointer(
                              ignoring: g.navAlpha < 0.5,
                              child: Opacity(opacity: g.navAlpha, child: libraryButton()),
                            ),
                          ),
                        // MERGED: the column page's Play split and Shuffle on its side.
                        if (merged > 0 && actions != null)
                          OverflowBox(
                            alignment: AlignmentDirectional.centerEnd,
                            minWidth: g.rightMergedWidth,
                            maxWidth: g.rightMergedWidth,
                            child: IgnorePointer(
                              ignoring: merged < 0.5,
                              child: Opacity(
                                opacity: merged,
                                child: Row(
                                  crossAxisAlignment: CrossAxisAlignment.stretch,
                                  children: [
                                    const SizedBox(width: BarTokens.itemGap),
                                    SizedBox(
                                      width: BarTokens.splitWidth,
                                      child: _PlaySplit(
                                        actions: actions,
                                        height: buttonHeight,
                                        shuffleInMenu: actions.onShuffle == null,
                                      ),
                                    ),
                                    if (actions.onShuffle != null) ...[
                                      const SizedBox(width: BarTokens.itemGap),
                                      _BarExtraButton(
                                        icon: YoinSymbols.shuffle,
                                        label: npStrings.playMenuShuffle,
                                        size: buttonHeight,
                                        onTap: () {
                                          YoinHaptics.click();
                                          actions.onShuffle!();
                                        },
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
            ],
          );
          // Always the Stack, so the row keeps its state as the hint comes and goes.
          return Stack(clipBehavior: Clip.none, fit: StackFit.passthrough, children: [row, ?hint]);
        },
      ),
    );

    return Padding(
      padding: centered
          ? EdgeInsets.only(
              bottom: window.layoutMode == LayoutMode.wide
                  ? BarTokens.centeredBottomMarginWide
                  : BarTokens.centeredBottomMargin,
            )
          : const EdgeInsets.symmetric(
              horizontal: BarTokens.horizontalMargin,
              vertical: BarTokens.portraitVerticalMargin,
            ),
      child: bar,
    );
  }
}

/// A Home / Library button: stadium (FilledIconButton's filledShape / the
/// 28 dp extraLarge corner at 48 dp), the 24 dp glyph, container
/// lerp(surfaceContainerHighest → primaryContainer, selection); the label
/// fades in over the idle tail. In the edit pose its content cross-fades to
/// [editLayer] over [editSwap], and a press while editing squares its
/// corners off toward the small corner ([pressCorner]).
class _BarButton extends StatelessWidget {
  const _BarButton({
    required this.icon,
    required this.label,
    required this.semantics,
    required this.labelAlpha,
    required this.selection,
    required this.onPress,
    required this.onTap,
    this.onLongPress,
    this.ripple = true,
    this.enabled = true,
    this.container,
    this.content,
    this.editSwap = 0,
    this.editLayer,
    this.pressCorner = 0,
  });

  final IconData icon;
  final String label;
  final String semantics;
  final double labelAlpha;

  /// 0 = resting, 1 = selected.
  final double selection;
  final ValueChanged<bool> onPress;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;

  /// Home is a FilledIconButton (ripple); Library a Surface with
  /// `indication = null` (YoinButtonGroup LibraryButton): no ripple.
  final bool ripple;

  /// False for the edit pose's dimmed Undo (the reader hears it disabled).
  final bool enabled;

  /// Edit-pose colours (null: the selection's).
  final Color? container;
  final Color? content;

  /// 0 = the destination, 1 = the edit pose's content.
  final double editSwap;
  final Widget? editLayer;

  /// 0 = stadium, 1 = the pressed small corner.
  final double pressCorner;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final fill = container ?? Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, selection)!;
    final ink = content ?? Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, selection)!;
    final corner = pressCorner.clamp(0.0, 1.0);
    final shape = corner <= 0.001
        ? const StadiumBorder()
        : ShapeBorder.lerp(
            const StadiumBorder(),
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(BarTokens.editPressedCorner)),
            corner,
          )!;
    final destination = _BarIconLabel(icon: icon, label: label, labelAlpha: labelAlpha, color: ink);
    return Semantics(
      button: true,
      enabled: enabled,
      selected: editSwap < 0.5 && selection > 0.5,
      label: semantics,
      excludeSemantics: true,
      child: YoinFocusRing(
        child: Material(
          color: fill,
          shape: shape,
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            onLongPress: onLongPress,
            splashFactory: ripple ? null : NoSplash.splashFactory,
            highlightColor: ripple ? null : Colors.transparent,
            onTapDown: (_) => onPress(true),
            onTapUp: (_) => onPress(false),
            onTapCancel: () => onPress(false),
            child: editLayer == null
                ? destination
                // Both stacked in one button, so the swap never re-lays out the slot.
                : Stack(
                    alignment: Alignment.center,
                    children: [
                      if (editSwap < 0.99) Opacity(opacity: 1 - editSwap, child: destination),
                      editLayer!,
                    ],
                  ),
          ),
        ),
      ),
    );
  }
}

/// Long-press Library's hint (YoinButtonGroup LibrarySearchShortcutHint): a
/// round surfaceContainerHighest chip with the search glyph in primary,
/// floating over the bar with a soft shadow. Drawn, not clipped (HarmonyOS
/// paints black wedges at clipped corners over other content).
class LibrarySearchHint extends StatelessWidget {
  const LibrarySearchHint({super.key, required this.scale, required this.alpha, required this.semantics});

  final double scale;
  final double alpha;
  final String semantics;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      label: semantics,
      child: Opacity(
        opacity: alpha,
        child: Transform.scale(
          scale: scale,
          child: SizedBox.square(
            dimension: BarTokens.searchHintBox,
            child: Center(
              child: DecoratedBox(
                decoration: ShapeDecoration(
                  color: colors.surfaceContainerHighest,
                  shape: const CircleBorder(),
                  shadows: kElevationToShadow[BarTokens.searchHintElevation.toInt()],
                ),
                child: SizedBox.square(
                  dimension: BarTokens.searchHintSurface,
                  child: Icon(YoinSymbols.search, size: BarTokens.searchHintIcon, color: colors.primary),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A bar button's glyph with its idle-pose label, which shows only while [labelAlpha] does.
class _BarIconLabel extends StatelessWidget {
  const _BarIconLabel({required this.icon, required this.label, required this.labelAlpha, required this.color});

  final IconData icon;
  final String label;
  final double labelAlpha;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisAlignment: MainAxisAlignment.center,
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, size: BarTokens.iconSize, color: color),
      if (labelAlpha > 0.01) ...[
        SizedBox(width: BarTokens.iconLabelGap * labelAlpha),
        Flexible(
          child: Opacity(
            opacity: labelAlpha,
            child: Text(
              label,
              maxLines: 1,
              softWrap: false,
              overflow: TextOverflow.fade,
              style: Theme.of(context).textTheme.labelLarge?.copyWith(color: color),
            ),
          ),
        ),
      ],
    ],
  );
}

/// The detail pose's Play split button (PlaySplitButton.kt): [▶ Play][▾],
/// the real M3 split (SplitButtonDefaults geometry, shape morphs and checked
/// circle via [ExpressiveSplitButton]), as tall as the bar's buttons and
/// [BarTokens.splitWidth] wide, in the page's colours (springing as the cover
/// scheme resolves). ▾ opens the Play menu and folds over while it is open.
/// The menu is Shuffle play + the page's own rows
/// ([DetailPlayActions.menuItems]: Play next, Add to queue, Go to artist…).
class _PlaySplit extends StatefulWidget {
  const _PlaySplit({required this.actions, required this.height, this.shuffleInMenu = true});

  final DetailPlayActions actions;
  final double height;

  /// False when the bar shows Shuffle as its own button (centred detail
  /// pose): an action pulled out of the menu never stays in it too. The
  /// page's rows stay in the menu there (v1 promotes only Shuffle); with none,
  /// the menu is empty and ▾ is left out.
  final bool shuffleInMenu;

  @override
  State<_PlaySplit> createState() => _PlaySplitState();
}

class _PlaySplitState extends State<_PlaySplit> with TickerProviderStateMixin {
  late final SpringValue _color = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  bool _menuOpen = false;

  /// The ▾ glyph's context: the menu anchors to the trailing half around it.
  BuildContext? _anchorContext;
  Color? _fromContainer;
  Color? _fromContent;
  Color? _toContainer;
  Color? _toContent;

  @override
  void didUpdateWidget(_PlaySplit oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.actions.playContainer != widget.actions.playContainer ||
        oldWidget.actions.playContent != widget.actions.playContent) {
      final t = _color.value.clamp(0.0, 1.0);
      _fromContainer = Color.lerp(_fromContainer, _toContainer, t);
      _fromContent = Color.lerp(_fromContent, _toContent, t);
      _color.snapTo(0);
      _color.animateTo(1);
    }
  }

  /// The ▾ menu (PlaySplitButton.kt: YoinDropdownMenu). It is a route on
  /// the app Navigator (the bar sits above it), so system back closes the
  /// menu instead of popping the detail page under it.
  Future<void> _openMenu(VoidCallback? onShuffle) async {
    final navigatorContext = appNavigatorKey.currentContext;
    final glyphContext = _anchorContext;
    if (_menuOpen || navigatorContext == null || glyphContext == null || !glyphContext.mounted) return;
    final strings = NowPlayingStrings.of(context);
    // The trailing half: the glyph's rect widened by its padding, as tall as the split.
    final glyph = globalRectOf(glyphContext);
    final split = globalRectOf(context);
    final anchor = Rect.fromLTRB(
      glyph.left - _splitTrailingPadding,
      split.top,
      glyph.right + _splitTrailingPadding,
      split.bottom,
    );
    setState(() => _menuOpen = true);
    final pageItems = widget.actions.menuItems;
    // Shuffle is row −1; the page's rows follow by index.
    final chosen = await showYoinDropdownMenu<int>(
      context: navigatorContext,
      anchor: anchor,
      items: [
        if (onShuffle != null) YoinMenuItem(value: -1, label: strings.playMenuShuffle, icon: YoinSymbols.shuffle),
        for (final (i, item) in pageItems.indexed) YoinMenuItem(value: i, label: item.label, icon: item.icon),
      ],
    );
    if (!mounted) return;
    setState(() => _menuOpen = false);
    if (chosen == null) return;
    if (chosen < 0) {
      onShuffle?.call();
    } else if (chosen < pageItems.length) {
      pageItems[chosen].onTap();
    }
  }

  @override
  void dispose() {
    _color.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    _toContainer = widget.actions.playContainer ?? colors.primary;
    _toContent = widget.actions.playContent ?? colors.onPrimary;
    _fromContainer ??= _toContainer;
    _fromContent ??= _toContent;
    final onShuffle = widget.shuffleInMenu ? widget.actions.onShuffle : null;
    return AnimatedBuilder(
      animation: _color.animation,
      builder: (context, _) {
        final t = _color.value.clamp(0.0, 1.0);
        final container = Color.lerp(_fromContainer, _toContainer, t)!;
        final content = Color.lerp(_fromContent, _toContent, t)!;
        return ExpressiveSplitButton(
          height: widget.height,
          containerColor: container,
          contentColor: content,
          // compact = true (YoinButtonGroup): 16 / 14 dp content padding.
          leadingPadding: _splitLeadingPadding,
          trailingPadding: _splitTrailingPadding,
          onLeadingTap: () {
            YoinHaptics.click();
            widget.actions.onPlay();
          },
          leading: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(YoinSymbols.playFilled, size: M3eSplitButtonTokens.leadingIconSize, color: content),
              const SizedBox(width: _buttonIconSpacing),
              Flexible(
                child: Text(
                  strings.playLabel,
                  maxLines: 1,
                  overflow: TextOverflow.clip,
                  softWrap: false,
                  style: Theme.of(context).textTheme.titleMedium!.copyWith(color: content),
                ),
              ),
            ],
          ),
          onTrailingTap: onShuffle == null && widget.actions.menuItems.isEmpty
              ? null
              // TrailingButton(onCheckedChange): no haptic of its own on Android.
              : () => _openMenu(onShuffle),
          trailingChecked: _menuOpen,
          trailingSemanticLabel: strings.playCdMore,
          trailing: Builder(
            builder: (anchorContext) {
              _anchorContext = anchorContext;
              return ExpandChevron(expanded: _menuOpen, color: content);
            },
          ),
        );
      },
    );
  }
}

/// A promoted page action as a round tonal button (YoinButtonGroup
/// BarExtraButton: FilledTonalIconButton, surfaceContainerHighest /
/// onSurfaceVariant, 22 dp glyph, the bar's button height square).
class _BarExtraButton extends StatelessWidget {
  const _BarExtraButton({required this.icon, required this.label, required this.size, required this.onTap});

  final IconData icon;
  final String label;
  final double size;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox.square(
      dimension: size,
      child: ExpressiveSurfaceButton(
        shapes: const InteractionShapes.fixed(CornerRadii.full),
        height: size,
        color: colors.surfaceContainerHighest,
        contentColor: colors.onSurfaceVariant,
        semanticLabel: label,
        onTap: onTap,
        child: Icon(icon, size: _extraIconSize, color: colors.onSurfaceVariant),
      ),
    );
  }
}

const double _extraIconSize = 22;

/// PlaySplitButton(compact = true): the halves' horizontal content padding.
const double _splitLeadingPadding = 16;
const double _splitTrailingPadding = 14;

/// ButtonDefaults.IconSpacing.
const double _buttonIconSpacing = 8;
