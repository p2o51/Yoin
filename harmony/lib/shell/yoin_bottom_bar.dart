import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../l10n/strings.dart';
import '../nowplaying/haptics.dart';
import '../nowplaying/nowplaying_strings.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'bar_geometry.dart';
import 'detail_chrome.dart';
import 'shell_state.dart';
import 'yoin_section.dart';

/// The window's one floating bar (YoinButtonGroup / FloatingBottomBar).
///
/// Poses, all from [resolveBarGeometry] so surface and slots agree each frame:
/// - idle (nothing playing): Home and Library as two labelled halves;
/// - nav: [Home][now-playing pill][Library], the selected button 1.5× wide;
/// - detail (a detail page registered [DetailPlayActions]): [Play split][pill].
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
  });

  final YoinSection selected;
  final ValueChanged<YoinSection> onSelect;

  /// A track exists: the bar takes the nav pose and shows the pill.
  final bool hasTrack;

  /// Non-null on a detail page: the bar takes the detail pose.
  final DetailPlayActions? detailActions;

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
  late final SpringValue _homePress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);
  late final SpringValue _libraryPress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);
  late final SpringValue _pillPress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);

  /// The last detail actions, kept while the pose springs back to nav.
  DetailPlayActions? _lastActions;

  @override
  void initState() {
    super.initState();
    _lastActions = widget.detailActions;
  }

  @override
  void didUpdateWidget(YoinBottomBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    final home = widget.selected == YoinSection.home ? 1.0 : 0.0;
    _selection.animateTo(home);
    _selectionColor.animateTo(home);
    _idle.animateTo(widget.hasTrack ? 0 : 1);
    _detail.animateTo(widget.detailActions == null ? 0 : 1);
    if (widget.detailActions != null) _lastActions = widget.detailActions;
  }

  @override
  void dispose() {
    for (final s in [_selection, _selectionColor, _idle, _detail, _homePress, _libraryPress, _pillPress]) {
      s.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = YoinStrings.of(context);
    final npStrings = NowPlayingStrings.of(context);
    final window = YoinWindowInfo.of(context);
    final centered = window.chromeForm == ShellChromeForm.centeredBar;
    final height = centered ? BarTokens.centeredHeight : BarTokens.height;
    final buttonHeight = centered ? BarTokens.centeredButtonHeight : BarTokens.buttonHeight;
    final rowPadding = (height - buttonHeight) / 2;
    final barWidth = centered
        ? (window.width - 2 * BarTokens.centeredHorizontalMargin).clamp(0.0, BarTokens.centeredMaxWidth)
        : window.width - 2 * BarTokens.horizontalMargin;
    final innerWidth = (barWidth - 2 * rowPadding).clamp(0.0, double.infinity);

    final bar = Container(
      width: barWidth,
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
          _homePress.animation,
          _libraryPress.animation,
          _pillPress.animation,
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
            ),
          );
          final colorSelection = _selectionColor.value.clamp(0.0, 1.0);
          final detail = _detail.value.clamp(0.0, 1.0);
          final actions = _lastActions;
          return Row(
            children: [
              SizedBox(
                width: g.splitWidth,
                height: buttonHeight,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (detail < 0.999)
                      IgnorePointer(
                        ignoring: detail > 0.5,
                        child: Opacity(
                          opacity: (1 - detail).clamp(0.0, 1.0),
                          child: _BarButton(
                            icon: colorSelection > 0.5 ? YoinSymbols.homeFilled : YoinSymbols.home,
                            label: strings.barLabelHome,
                            semantics: npStrings.barCdHome,
                            labelAlpha: g.labelAlpha,
                            selection: colorSelection,
                            onPress: (down) => _homePress.animateTo(down ? 1 : 0),
                            onTap: () {
                              YoinHaptics.click();
                              widget.onSelect(YoinSection.home);
                            },
                          ),
                        ),
                      ),
                    if (detail > 0.001 && actions != null)
                      IgnorePointer(
                        ignoring: detail < 0.5,
                        child: Opacity(
                          opacity: detail,
                          // Laid out at its own width while the slot morphs; the slot clips it.
                          child: ClipRect(
                            child: OverflowBox(
                              alignment: AlignmentDirectional.centerStart,
                              minWidth: BarTokens.splitWidth,
                              maxWidth: BarTokens.splitWidth,
                              child: _PlaySplit(actions: actions, height: buttonHeight),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: BarTokens.itemGap),
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
                            child: widget.pillBuilder!(context, (down) => _pillPress.animateTo(down ? 1 : 0)),
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
                    child: OverflowBox(
                      alignment: AlignmentDirectional.centerEnd,
                      minWidth: g.libraryWidth,
                      maxWidth: g.libraryWidth,
                      child: Opacity(
                        opacity: (1 - detail).clamp(0.0, 1.0),
                        child: _BarButton(
                          icon: colorSelection < 0.5 ? YoinSymbols.libraryFilled : YoinSymbols.library,
                          label: strings.barLabelLibrary,
                          semantics: npStrings.barCdLibrary,
                          labelAlpha: g.labelAlpha,
                          selection: 1 - colorSelection,
                          onPress: (down) => _libraryPress.animateTo(down ? 1 : 0),
                          onTap: () {
                            YoinHaptics.click();
                            widget.onSelect(YoinSection.library);
                          },
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          );
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

/// A Home / Library button: stadium, container lerp(surfaceContainerHighest
/// → primaryContainer, selection); the label fades in over the idle tail.
class _BarButton extends StatelessWidget {
  const _BarButton({
    required this.icon,
    required this.label,
    required this.semantics,
    required this.labelAlpha,
    required this.selection,
    required this.onPress,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final String semantics;
  final double labelAlpha;

  /// 0 = resting, 1 = selected.
  final double selection;
  final ValueChanged<bool> onPress;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final container = Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, selection)!;
    final content = Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, selection)!;
    return Semantics(
      button: true,
      selected: selection > 0.5,
      label: semantics,
      excludeSemantics: true,
      child: Material(
        color: container,
        shape: const StadiumBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          onTapDown: (_) => onPress(true),
          onTapUp: (_) => onPress(false),
          onTapCancel: () => onPress(false),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: BarTokens.iconSize, color: content),
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
                      style: Theme.of(context).textTheme.labelLarge?.copyWith(color: content),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// The detail pose's Play split button (PlaySplitButton.kt): [▶ Play][▾],
/// 2 dp apart, in the page's colours (springing as the cover scheme
/// resolves); ▾ opens a menu with Shuffle play and rotates 180° while open.
class _PlaySplit extends StatefulWidget {
  const _PlaySplit({required this.actions, required this.height});

  final DetailPlayActions actions;
  final double height;

  @override
  State<_PlaySplit> createState() => _PlaySplitState();
}

class _PlaySplitState extends State<_PlaySplit> with TickerProviderStateMixin {
  late final SpringValue _color = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  late final SpringValue _hinge = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial);
  bool _menuOpen = false;
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
  Future<void> _openMenu(BuildContext buttonContext, VoidCallback onShuffle) async {
    final navigatorContext = appNavigatorKey.currentContext;
    if (_menuOpen || navigatorContext == null) return;
    final strings = NowPlayingStrings.of(context);
    _menuOpen = true;
    _hinge.animateTo(1);
    final chosen = await showYoinDropdownMenu<_SplitMenuItem>(
      context: navigatorContext,
      anchor: globalRectOf(buttonContext),
      items: [
        YoinMenuItem(value: _SplitMenuItem.shuffle, label: strings.playMenuShuffle, icon: YoinSymbols.shuffle),
      ],
    );
    _menuOpen = false;
    if (!mounted) return;
    _hinge.animateTo(0);
    if (chosen == _SplitMenuItem.shuffle) onShuffle();
  }

  @override
  void dispose() {
    _color.dispose();
    _hinge.dispose();
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
    final onShuffle = widget.actions.onShuffle;
    return AnimatedBuilder(
      animation: Listenable.merge([_color.animation, _hinge.animation]),
      builder: (context, _) {
        final t = _color.value.clamp(0.0, 1.0);
        final container = Color.lerp(_fromContainer, _toContainer, t)!;
        final content = Color.lerp(_fromContent, _toContent, t)!;
        const inner = Radius.circular(BarTokens.splitInnerCorner);
        final outer = Radius.circular(widget.height / 2);
        return Row(
          children: [
            Expanded(
              child: Material(
                color: container,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.horizontal(left: outer, right: onShuffle == null ? outer : inner),
                ),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: () {
                    YoinHaptics.click();
                    widget.actions.onPlay();
                  },
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(YoinSymbols.playFilled, size: 20, color: content),
                        const SizedBox(width: 8),
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
                  ),
                ),
              ),
            ),
            if (onShuffle != null) ...[
              const SizedBox(width: BarTokens.splitGap),
              Builder(
                builder: (buttonContext) => Semantics(
                  button: true,
                  label: strings.playCdMore,
                  excludeSemantics: true,
                  child: Material(
                    color: container,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.horizontal(left: inner, right: outer),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () => _openMenu(buttonContext, onShuffle),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        child: Transform.rotate(
                          angle: 3.141592653589793 * _hinge.value,
                          child: Icon(YoinSymbols.chevronDown, size: 20, color: content),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ],
        );
      },
    );
  }
}

enum _SplitMenuItem { shuffle }
