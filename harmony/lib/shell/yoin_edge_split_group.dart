import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../l10n/strings.dart';
import '../nowplaying/haptics.dart';
import '../nowplaying/now_playing_pill.dart';
import '../nowplaying/nowplaying_strings.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/edge_split.dart';
import '../ui/component/desktop_input.dart';
import '../ui/component/expressive/expressive_split_button.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import 'bar_edit_pose.dart';
import 'detail_chrome.dart';
import 'edge_split_strings.dart';
import 'shell_state.dart';
import 'yoin_bottom_bar.dart' show LibrarySearchHint;
import 'yoin_section.dart';

typedef _E = EdgeSplitTokens;

/// The Button Group for a short window (YoinEdgeSplitGroup.kt,
/// ShellChromeForm.EdgeSplit, 断点交接 §2.2): the portrait bar stood upright
/// and split in two around the camera cutout, both capsules on the cutout's
/// edge inside its band.
///
///  - UPPER: [Home] over [Library] (the selected one takes the height the
///    other's 56 and the gap leave), or in the detail pose the upright Play
///    split, plus Shuffle when the edge has no cutout and the capsule gains
///    the slot ([EdgeSplitSegments.roomy]).
///  - LOWER: the upright now-playing pill. Now Playing opens from and closes
///    back into its cover.
///
/// Nothing playing, or Home being edited: the lower capsule steps out over
/// its edge (the portrait bar's idle pose retires its pill the same way);
/// the detail pose keeps it. In the edit pose Home becomes [Undo|Add] and
/// Library Done. Poses spring on the Standard role, colours on the effects
/// spring, as the portrait bar's do.
class YoinEdgeSplitGroup extends StatefulWidget {
  const YoinEdgeSplitGroup({
    super.key,
    required this.selected,
    required this.onSelect,
    this.idle = false,
    this.detailActions,
    this.editPose,
    this.onLibraryLongPress,
    this.onPillLongPress,
    this.pill,
    this.segmentsOverride,
    this.sideOverride,
  });

  final YoinSection selected;
  final ValueChanged<YoinSection> onSelect;

  /// Nothing playing (and no playback error to tell): the pill capsule steps out.
  final bool idle;

  /// Non-null on a detail page: the upper capsule takes the upright Play split.
  final DetailPlayActions? detailActions;

  /// Home is being edited: [Undo|Add] and Done.
  final BarEditPose? editPose;

  /// Long-press Library: its search; a hint opens toward the content.
  final VoidCallback? onLibraryLongPress;

  /// Long-press the pill on a song: its sheet.
  final ValueChanged<Track>? onPillLongPress;

  /// The lower capsule's content in place of the live upright pill (tests, Tips demos).
  final Widget? pill;

  /// Explicit geometry (tests, previews); the live window otherwise.
  final EdgeSplitSegments? segmentsOverride;
  final EdgeSplitSide? sideOverride;

  @override
  State<YoinEdgeSplitGroup> createState() => _YoinEdgeSplitGroupState();
}

class _YoinEdgeSplitGroupState extends State<YoinEdgeSplitGroup> with TickerProviderStateMixin {
  /// 1 = Home selected (heights and colours, as Android's one homeSelection).
  late final SpringValue _selection = SpringValue(
    vsync: this,
    initial: widget.selected == YoinSection.home ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _idle = SpringValue(
    vsync: this,
    initial: widget.idle ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );

  /// The nav ⇄ detail pose (chromeProgress).
  late final SpringValue _detail = SpringValue(
    vsync: this,
    initial: widget.detailActions == null ? 0 : 1,
    spring: YoinSprings.standardDefaultSpatial,
  );

  /// Home's edit progress P (the header and the bar move as one).
  late final SpringValue _edit = SpringValue(
    vsync: this,
    initial: widget.editPose?.editing ?? false ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final List<SpringValue> _slotAlphas = [
    for (final slot in BarEditLeftSlot.values)
      SpringValue(
        vsync: this,
        initial: (widget.editPose?.leftSlot ?? BarEditLeftSlot.undoDisabled) == slot ? 1 : 0,
        spring: YoinSprings.fastEffects,
      ),
  ];

  /// While editing a press squares the button off to the small corner.
  late final SpringValue _homeCorner = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _libraryCorner = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  /// Long-press Library's hint: scale (spatial) and alpha (effects).
  late final SpringValue _hintScale = SpringValue(
    vsync: this,
    initial: BarTokens.searchHintEnterScale,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _hintAlpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  bool _hintShown = false;
  Timer? _hintTimer;

  BarEditPose? _lastPose;
  DetailPlayActions? _lastActions;

  bool get _reduced => MediaQuery.disableAnimationsOf(context);

  @override
  void initState() {
    super.initState();
    _lastPose = widget.editPose;
    _lastActions = widget.detailActions;
  }

  void _to(SpringValue v, double target) => _reduced ? v.snapTo(target) : v.animateTo(target);

  @override
  void didUpdateWidget(YoinEdgeSplitGroup oldWidget) {
    super.didUpdateWidget(oldWidget);
    _to(_selection, widget.selected == YoinSection.home ? 1 : 0);
    _to(_idle, widget.idle ? 1 : 0);
    _to(_detail, widget.detailActions == null ? 0 : 1);
    if (widget.detailActions != null) _lastActions = widget.detailActions;
    final pose = widget.editPose;
    if (pose != null) _lastPose = pose;
    final editing = pose?.editing ?? false;
    _to(_edit, editing ? 1 : 0);
    // Library is Done while editing: entering edit clears a showing hint.
    if (editing) _showHint(false);
    final slot = (pose ?? _lastPose)?.leftSlot ?? BarEditLeftSlot.undoDisabled;
    for (final v in BarEditLeftSlot.values) {
      _slotAlphas[v.index].animateTo(v == slot ? 1 : 0);
    }
  }

  @override
  void dispose() {
    for (final s in [
      _selection,
      _idle,
      _detail,
      _edit,
      ..._slotAlphas,
      _homeCorner,
      _libraryCorner,
      _hintScale,
      _hintAlpha,
    ]) {
      s.dispose();
    }
    _hintTimer?.cancel();
    super.dispose();
  }

  /// The hint shows on the long-press and leaves 1.4 s later (EdgeLibraryHintHoldMs).
  void _showHint(bool show) {
    _hintTimer?.cancel();
    if (!mounted || show == _hintShown) return;
    _hintShown = show;
    if (_reduced) {
      _hintScale.snapTo(1);
      _hintAlpha.snapTo(show ? 1 : 0);
    } else if (show) {
      if (_hintAlpha.value < 0.01) _hintScale.snapTo(BarTokens.searchHintEnterScale);
      _hintScale.animateTo(1);
      _hintAlpha.animateTo(1);
    } else {
      _hintScale.animateTo(BarTokens.searchHintExitScale);
      _hintAlpha.animateTo(0);
    }
    if (show) _hintTimer = Timer(const Duration(milliseconds: _E.searchHintHoldMs), () => _showHint(false));
  }

  @override
  Widget build(BuildContext context) {
    final padding = MediaQuery.viewPaddingOf(context);
    final cutouts = DisplayPostureScope.cutoutsOf(context);
    final side = widget.sideOverride ?? cutouts.side;
    // A navigation inset on the group's own edge steps it inward.
    final edgeShift = side == EdgeSplitSide.right ? padding.right : padding.left;
    return LayoutBuilder(
      builder: (context, box) {
        final segments = widget.segmentsOverride ?? edgeSplitSegmentsOf(context, box.maxHeight);
        final x = edgeSplitGroupX(windowWidth: box.maxWidth, side: side, edgeShift: edgeShift);
        return AnimatedBuilder(
          animation: Listenable.merge([
            _selection.animation,
            _idle.animation,
            _detail.animation,
            _edit.animation,
            for (final a in _slotAlphas) a.animation,
            _homeCorner.animation,
            _libraryCorner.animation,
            _hintScale.animation,
            _hintAlpha.animation,
          ]),
          builder: (context, _) {
            final morph = _detail.value.clamp(0.0, 1.0);
            final edit = _edit.value.clamp(0.0, 1.0);
            final idleLike = math.max(_idle.value.clamp(0.0, 1.0), edit);
            final lowerShown = idleLike < 0.995 || morph > 0.005;
            final hide = morph > 0.005 ? 0.0 : idleLike;
            final upperInner = math.max(0.0, segments.upperHeight - 2 * _E.capsulePadding);
            final lowerInner = math.max(0.0, segments.lowerHeight - 2 * _E.capsulePadding);
            // The hint opens toward the content, away from the edge, level with the capsule's inner foot.
            final hintLeft = side == EdgeSplitSide.right
                ? x + _E.capsulePadding + _E.buttonWidth - (_E.groupWidth + _E.searchHintGap) - BarTokens.searchHintBox
                : x + _E.capsulePadding + _E.groupWidth + _E.searchHintGap;
            final hintTop = segments.upperBottom - _E.capsulePadding - BarTokens.searchHintBox;
            return Stack(
              clipBehavior: Clip.none,
              children: [
                _Capsule(
                  key: const ValueKey('edge.upper'),
                  x: x,
                  top: segments.upperTop,
                  height: segments.upperHeight,
                  child: _upper(context, upperInner, segments.roomy, morph, edit),
                ),
                if (lowerShown)
                  Positioned(
                    key: const ValueKey('edge.lower'),
                    left: x,
                    top: segments.lowerTop,
                    width: _E.groupWidth,
                    height: segments.lowerHeight,
                    child: Opacity(
                      opacity: (1 - hide).clamp(0.0, 1.0),
                      child: Transform.translate(
                        // Steps out over its own edge.
                        offset: Offset(
                          side == EdgeSplitSide.right ? (box.maxWidth - x) * hide : -(_E.groupWidth + x) * hide,
                          0,
                        ),
                        child: _CapsuleSurface(
                          child: SizedBox(
                            width: _E.buttonWidth,
                            height: lowerInner,
                            child:
                                widget.pill ??
                                NowPlayingPillVertical(
                                  onLongPress: widget.onPillLongPress,
                                  // Stepping out but still composed on the way into edit: no press, tap or haptic.
                                  enabled: !(widget.editPose?.editing ?? false),
                                ),
                          ),
                        ),
                      ),
                    ),
                  ),
                if (_hintAlpha.value > 0.001 && morph < 0.99)
                  Positioned(
                    left: hintLeft,
                    top: hintTop,
                    child: IgnorePointer(
                      child: LibrarySearchHint(
                        scale: _hintScale.value,
                        alpha: _hintAlpha.value.clamp(0.0, 1.0),
                        semantics: NowPlayingStrings.of(context).barCdSearch,
                      ),
                    ),
                  ),
              ],
            );
          },
        );
      },
    );
  }

  /// The upper capsule: the nav column fading out (and shrinking to 0.94)
  /// as the upright Play split fades in (growing from 0.94).
  Widget _upper(BuildContext context, double inner, bool roomy, double morph, double edit) {
    final navAlpha = (1 - morph / _E.morphFadeSpan).clamp(0.0, 1.0);
    final splitAlpha = ((morph - _E.morphSplitStart) / _E.morphFadeSpan).clamp(0.0, 1.0);
    final actions = _lastActions;
    final shuffleSlot = roomy ? _E.splitShuffleHeight + _E.splitGap * 2 : 0.0;
    return SizedBox(
      width: _E.buttonWidth,
      height: inner,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          if (morph < 0.99)
            IgnorePointer(
              ignoring: morph > 0.5,
              child: Opacity(
                opacity: navAlpha,
                child: Transform.scale(scale: 1 - _E.morphScale * morph, child: _navColumn(context, inner, edit)),
              ),
            ),
          if (morph > 0.01 && actions != null)
            IgnorePointer(
              ignoring: morph < 0.5,
              child: Opacity(
                opacity: splitAlpha,
                child: Transform.scale(
                  scale: 1 - _E.morphScale + _E.morphScale * morph,
                  child: ClipRect(
                    child: PlaySplitButtonVertical(
                      actions: actions,
                      playHeight: math.max(_E.buttonWidth, inner - _E.splitGap - _E.splitMenuHeight - shuffleSlot),
                      showShuffleButton: roomy,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _navColumn(BuildContext context, double inner, double edit) {
    final colors = Theme.of(context).colorScheme;
    final strings = EdgeSplitStrings.of(context);
    final barStrings = YoinStrings.of(context);
    final selection = _selection.value.clamp(0.0, 1.0);
    final editing = widget.editPose?.editing ?? false;
    final editSwap = barEditSwap(edit);
    final slot = _lastPose?.leftSlot ?? BarEditLeftSlot.undoDisabled;
    // The selected button takes whatever the unselected one and the gap leave.
    final selectedHeight = math.max(_E.navRestingHeight, inner - _E.navGap - _E.navRestingHeight);
    final homeHeight = lerpDouble(_E.navRestingHeight, selectedHeight, selection)!;
    final libraryHeight = lerpDouble(_E.navRestingHeight, selectedHeight, 1 - selection)!;
    final homeContainer = Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, selection)!;
    final homeContent = Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, selection)!;
    final libraryContainer = Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, 1 - selection)!;
    final libraryContent = Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, 1 - selection)!;
    final slotActive = editing && slot != BarEditLeftSlot.undoDisabled;
    final onLibraryLongPress = widget.onLibraryLongPress;

    Widget layered({required IconData icon, required Color ink, Widget? editLayer}) => Stack(
      alignment: Alignment.center,
      children: [
        if (editSwap < 0.99)
          Opacity(
            opacity: 1 - editSwap,
            child: Icon(icon, size: _E.navIconSize, color: ink),
          ),
        ?editLayer,
      ],
    );

    return Column(
      children: [
        _EdgeNavButton(
          key: const ValueKey('edge.home'),
          height: homeHeight,
          container: Color.lerp(homeContainer, colors.surfaceContainerHighest, editSwap)!,
          semantics: editing ? _leftSlotCd(barStrings, slot) : strings.cdHome,
          enabled: !editing || slot != BarEditLeftSlot.undoDisabled,
          selected: !editing && widget.selected == YoinSection.home,
          pressCorner: _homeCorner.value,
          onPress: (down) => _homeCorner.animateTo(down && slotActive ? 1 : 0),
          onTap: () {
            if (editing) {
              // Edit haptics belong to the controller alone.
              widget.editPose?.onLeftSlot();
              return;
            }
            YoinHaptics.click();
            widget.onSelect(YoinSection.home);
          },
          child: layered(
            icon: widget.selected == YoinSection.home ? YoinSymbols.homeFilled : YoinSymbols.home,
            ink: homeContent,
            editLayer: editSwap > 0.01 ? _leftSlotLayers(context, barStrings, editSwap) : null,
          ),
        ),
        const SizedBox(height: _E.navGap),
        _EdgeNavButton(
          key: const ValueKey('edge.library'),
          height: libraryHeight,
          ripple: false,
          container: Color.lerp(libraryContainer, colors.primary, edit)!,
          semantics: editing ? strings.cdDone : strings.cdLibrary,
          selected: !editing && widget.selected == YoinSection.library,
          pressCorner: _libraryCorner.value,
          onPress: (down) => _libraryCorner.animateTo(down && editing ? 1 : 0),
          onTap: () {
            if (editing) {
              widget.editPose?.onDone();
              return;
            }
            YoinHaptics.click();
            widget.onSelect(YoinSection.library);
          },
          onLongPress: onLibraryLongPress == null || editing
              ? null
              : () {
                  _showHint(true);
                  YoinHaptics.contextClick();
                  onLibraryLongPress();
                },
          child: layered(
            icon: widget.selected == YoinSection.library ? YoinSymbols.libraryFilled : YoinSymbols.library,
            ink: Color.lerp(libraryContent, colors.onPrimary, edit)!,
            editLayer: editSwap > 0.01
                ? Opacity(
                    opacity: editSwap,
                    child: Icon(
                      YoinSymbols.check,
                      size: _E.navIconSize,
                      color: Color.lerp(libraryContent, colors.onPrimary, edit),
                    ),
                  )
                : null,
          ),
        ),
      ],
    );
  }

  String _leftSlotCd(YoinStrings strings, BarEditLeftSlot slot) => switch (slot) {
    BarEditLeftSlot.undo || BarEditLeftSlot.undoDisabled => strings.barCdUndo,
    BarEditLeftSlot.add => strings.barCdShowHidden,
  };

  /// The edit pose's left slot: Undo (a word), Add (its glyph; the capsule
  /// has no room for labels), the dimmed Undo, cross-faded on the slot's alphas.
  Widget _leftSlotLayers(BuildContext context, YoinStrings strings, double editSwap) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge;
    Widget layer(BarEditLeftSlot slot, Widget child) {
      final a = editSwap * _slotAlphas[slot.index].value.clamp(0.0, 1.0);
      if (a <= 0.002) return const SizedBox.shrink();
      return Opacity(opacity: a, child: child);
    }

    return ExcludeSemantics(
      child: Stack(
        alignment: Alignment.center,
        children: [
          layer(
            BarEditLeftSlot.undo,
            Text(strings.barLabelUndo, maxLines: 1, softWrap: false, style: style?.copyWith(color: colors.onSurface)),
          ),
          layer(BarEditLeftSlot.add, Icon(YoinSymbols.add, size: _E.navIconSize, color: colors.onSurface)),
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
      ),
    );
  }
}

/// One capsule at its place: the bar's container colour, round ends, the
/// content inset by the capsule padding.
class _Capsule extends StatelessWidget {
  const _Capsule({super.key, required this.x, required this.top, required this.height, required this.child});

  final double x;
  final double top;
  final double height;
  final Widget child;

  @override
  Widget build(BuildContext context) => Positioned(
    left: x,
    top: top,
    width: _E.groupWidth,
    height: height,
    child: _CapsuleSurface(child: child),
  );
}

class _CapsuleSurface extends StatelessWidget {
  const _CapsuleSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Material(
    color: Theme.of(context).colorScheme.surfaceContainerHigh,
    shape: const StadiumBorder(),
    child: Padding(
      padding: const EdgeInsets.all(_E.capsulePadding),
      child: Align(alignment: Alignment.topCenter, child: child),
    ),
  );
}

/// Home / Library standing upright: 52 wide, [height] tall, 26 corners
/// (FilledIconButton for Home, a ripple-less Surface for Library); a press
/// while editing squares it off toward the small corner ([pressCorner]).
class _EdgeNavButton extends StatelessWidget {
  const _EdgeNavButton({
    super.key,
    required this.height,
    required this.container,
    required this.semantics,
    required this.onTap,
    required this.onPress,
    required this.child,
    this.onLongPress,
    this.ripple = true,
    this.enabled = true,
    this.selected = false,
    this.pressCorner = 0,
  });

  final double height;
  final Color container;
  final String semantics;
  final VoidCallback onTap;
  final ValueChanged<bool> onPress;
  final VoidCallback? onLongPress;
  final Widget child;
  final bool ripple;
  final bool enabled;
  final bool selected;
  final double pressCorner;

  @override
  Widget build(BuildContext context) {
    final corner = lerpDouble(_E.navCorner, BarTokens.editPressedCorner, pressCorner.clamp(0.0, 1.0))!;
    return Semantics(
      button: true,
      enabled: enabled,
      selected: selected,
      label: semantics,
      excludeSemantics: true,
      child: YoinFocusRing(
        child: SizedBox(
          width: _E.buttonWidth,
          height: height,
          child: Material(
            color: container,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(corner)),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onTap,
              onLongPress: onLongPress,
              splashFactory: ripple ? null : NoSplash.splashFactory,
              highlightColor: ripple ? null : Colors.transparent,
              onTapDown: (_) => onPress(true),
              onTapUp: (_) => onPress(false),
              onTapCancel: () => onPress(false),
              child: Center(child: child),
            ),
          ),
        ),
      ),
    );
  }
}

/// The Play split turned upright for the edge capsule (PlaySplitButton.kt
/// PlaySplitButtonVertical): Play 52 × [playHeight] with its round end on top
/// (26/26/8/8), ▾ 52 × 46 under it (8/8/26/26), 2 apart, in the page's
/// colours (springing as the cover scheme resolves). M3's split button has
/// exactly two parts: a roomy capsule's third action is a segment of its own
/// ([showShuffleButton], a tonal 40 tall Shuffle) and Shuffle leaves the ▾
/// menu. ▾ opens the Play menu (YoinDropdownMenu): Shuffle play, then the
/// page's rows.
class PlaySplitButtonVertical extends StatefulWidget {
  const PlaySplitButtonVertical({
    super.key,
    required this.actions,
    required this.playHeight,
    this.showShuffleButton = false,
  });

  final DetailPlayActions actions;
  final double playHeight;
  final bool showShuffleButton;

  @override
  State<PlaySplitButtonVertical> createState() => _PlaySplitButtonVerticalState();
}

class _PlaySplitButtonVerticalState extends State<PlaySplitButtonVertical> with TickerProviderStateMixin {
  late final SpringValue _color = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  bool _menuOpen = false;
  Color? _fromContainer;
  Color? _fromContent;
  Color? _toContainer;
  Color? _toContent;

  @override
  void didUpdateWidget(PlaySplitButtonVertical oldWidget) {
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

  @override
  void dispose() {
    _color.dispose();
    super.dispose();
  }

  /// The ▾ menu, a route on the app Navigator (the bar sits above it), so
  /// system back closes the menu instead of popping the page under it.
  Future<void> _openMenu(BuildContext anchorContext) async {
    final navigatorContext = appNavigatorKey.currentContext;
    if (_menuOpen || navigatorContext == null || !anchorContext.mounted) return;
    final strings = NowPlayingStrings.of(context);
    final onShuffle = widget.showShuffleButton ? null : widget.actions.onShuffle;
    final pageItems = widget.actions.menuItems;
    setState(() => _menuOpen = true);
    final chosen = await showYoinDropdownMenu<int>(
      context: navigatorContext,
      anchor: globalRectOf(anchorContext),
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
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = EdgeSplitStrings.of(context);
    _toContainer = widget.actions.playContainer ?? colors.primary;
    _toContent = widget.actions.playContent ?? colors.onPrimary;
    _fromContainer ??= _toContainer;
    _fromContent ??= _toContent;
    final menuEmpty =
        (widget.showShuffleButton || widget.actions.onShuffle == null) && widget.actions.menuItems.isEmpty;
    const outer = Radius.circular(_E.splitOuterCorner);
    const inner = Radius.circular(_E.splitInnerCorner);
    return AnimatedBuilder(
      animation: _color.animation,
      builder: (context, _) {
        final t = _color.value.clamp(0.0, 1.0);
        final container = Color.lerp(_fromContainer, _toContainer, t)!;
        final content = Color.lerp(_fromContent, _toContent, t)!;
        return SizedBox(
          width: _E.buttonWidth,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _Segment(
                key: const ValueKey('edge.play'),
                height: widget.playHeight,
                color: container,
                radius: const BorderRadius.vertical(top: outer, bottom: inner),
                semantics: strings.playCd,
                onTap: () {
                  YoinHaptics.click();
                  widget.actions.onPlay();
                },
                child: Icon(YoinSymbols.playFilled, size: _E.splitPlayIcon, color: content),
              ),
              const SizedBox(height: _E.splitGap),
              Builder(
                builder: (anchorContext) => _Segment(
                  key: const ValueKey('edge.more'),
                  height: _E.splitMenuHeight,
                  color: container,
                  radius: const BorderRadius.vertical(top: inner, bottom: outer),
                  semantics: strings.playCdMore,
                  onTap: menuEmpty
                      ? null
                      : () {
                          YoinHaptics.contextClick();
                          _openMenu(anchorContext);
                        },
                  child: ExpandChevron(expanded: _menuOpen, size: _E.splitMenuIcon, color: content),
                ),
              ),
              if (widget.showShuffleButton && widget.actions.onShuffle != null) ...[
                const SizedBox(height: _E.splitGap * 2),
                _Segment(
                  key: const ValueKey('edge.shuffle'),
                  height: _E.splitShuffleHeight,
                  color: colors.surfaceContainerHighest,
                  radius: BorderRadius.circular(_E.splitShuffleHeight / 2),
                  semantics: strings.playCdShuffle,
                  onTap: () {
                    YoinHaptics.click();
                    widget.actions.onShuffle!();
                  },
                  child: Icon(YoinSymbols.shuffle, size: _E.splitShuffleIcon, color: colors.onSurfaceVariant),
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}

/// One upright split segment: a filled button with its own corners.
class _Segment extends StatelessWidget {
  const _Segment({
    super.key,
    required this.height,
    required this.color,
    required this.radius,
    required this.semantics,
    required this.onTap,
    required this.child,
  });

  final double height;
  final Color color;
  final BorderRadius radius;
  final String semantics;
  final VoidCallback? onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    enabled: onTap != null,
    label: semantics,
    excludeSemantics: true,
    child: YoinFocusRing(
      child: SizedBox(
        width: _E.buttonWidth,
        height: height,
        child: Material(
          color: color,
          shape: RoundedRectangleBorder(borderRadius: radius),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: Center(child: child),
          ),
        ),
      ),
    ),
  );
}
