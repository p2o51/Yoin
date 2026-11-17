import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import 'focus_idle.dart';
import 'lyrics_selection.dart';
import 'nowplaying_strings.dart';

/// The lyric tools' geometry (LyricsActionBar.kt, NowPlayingScreen.kt; not in
/// tokens.dart yet — the Kotlin side has them as private vals).
abstract final class LyricToolsMetrics {
  /// A key in the bottom accessory.
  static const double keySize = 52;

  /// Enlarged phone at rest: the tools ride the end of the text tab row (EnlargedTabToolSize).
  static const double enlargedTabKey = 44;

  /// 16:9 expanded page: the tools ride the tab row (ShortTabToolSize).
  static const double shortTabKey = 40;

  /// The key's corner, as a share of its size.
  static const double cornerShare = 0.31;

  /// Between two keys (LyricsKeyGap).
  static const double keyGap = 8;

  /// M3 ButtonGroupDefaults.ExpandedRatio: what a pressed key gains of its width.
  static const double expandedRatio = 0.15;

  /// The incoming bar's scale as the tools ⇄ select bar cross-fade.
  static const double swapScaleFrom = 0.92;

  /// The phone's Expanded accessory slot the tools stand in (ExpandedAccessoryHeight).
  static const double expandedAccessory = 68;

  /// Auto-immersive (design.md "自动沉浸", owner 2026-10-05: 5 s read as sluggish).
  static const int idleMs = 3000;
}

/// Whether the lyric tools may step away (lyricToolsIdleEligible): playing
/// synced lyrics that follow the playhead, on the Lyrics page, with the tools
/// on screen — and not while lines are being picked.
bool lyricToolsIdleEligible({
  required bool isPlaying,
  required bool hasSyncedLyrics,
  required bool autoScroll,
  required bool lyricsPageSelected,
  required bool toolsOnScreen,
  required bool selectingLines,
}) => isPlaying && hasSyncedLyrics && autoScroll && lyricsPageSelected && toolsOnScreen && !selectingLines;

/// Each key's width with its press (0 at rest, 1 held, overshoot allowed):
/// the pressed key gains [LyricToolsMetrics.expandedRatio] of its width from
/// its neighbours (both halves for a middle key, all from the one beside an
/// end key). The row's total stays put; no width goes below zero
/// (lyricsKeyWidths).
List<double> lyricsKeyWidths(int count, double key, double Function(int index) press) {
  final widths = List<double>.filled(count, key);
  if (count > 1) {
    for (var i = 0; i < count; i++) {
      final growth = press(i) * LyricToolsMetrics.expandedRatio * key;
      if (growth == 0) continue;
      widths[i] += growth;
      if (i == 0) {
        widths[1] -= growth;
      } else if (i == count - 1) {
        widths[i - 1] -= growth;
      } else {
        widths[i - 1] -= growth / 2;
        widths[i + 1] -= growth / 2;
      }
    }
  }
  return [for (final w in widths) w < 0 ? 0 : w];
}

/// What the tools do; set by the Now Playing screen each build.
@immutable
final class LyricToolsActions {
  const LyricToolsActions({
    required this.hasLyrics,
    required this.hasSyncedLyrics,
    required this.busy,
    required this.onSearch,
    required this.onEdit,
    this.translating = false,
    this.onTranslate = _noop,
    required this.onCopy,
    required this.onShare,
    this.onBeforeSelect,
  });

  static const none = LyricToolsActions(
    hasLyrics: false,
    hasSyncedLyrics: false,
    busy: false,
    onSearch: _noopFrom,
    onEdit: _noop,
    onCopy: _noop,
    onShare: _noop,
  );

  static void _noop() {}
  static void _noopFrom(Rect? from) {}

  final bool hasLyrics;
  final bool hasSyncedLyrics;

  /// A lyrics action (search apply, editor apply) is running: the keys wait.
  final bool busy;

  /// A translation is being looked up: the Translate key stays lit (taps wait).
  final bool translating;

  /// Search, with the key's global rect: the search surface grows out of it.
  final void Function(Rect? from) onSearch;

  /// Translate: a translation looked up (the lyrics' source, a switch, Gemini), or the layer shown / hidden.
  final VoidCallback onTranslate;

  /// The LRC editor (the ✓ with no lines yet, and select mode's Edit).
  final VoidCallback onEdit;
  final VoidCallback onCopy;
  final VoidCallback onShare;

  /// Runs before select mode opens (the enlarged phone's tab-row tools expand the stage first).
  final VoidCallback? onBeforeSelect;
}

/// The lyric tools' shared state for one player: select mode, the
/// auto-immersive idle count and the follow flag the Recenter key reads.
class LyricToolsController extends ChangeNotifier {
  LyricToolsController({required this.autoFollow}) {
    selection.addListener(notifyListeners);
  }

  final ValueNotifier<bool> autoFollow;
  final LyricsSelection selection = LyricsSelection();
  final FocusIdle idle = FocusIdle(idleMs: LyricToolsMetrics.idleMs);
  LyricToolsActions _actions = LyricToolsActions.none;

  LyricToolsActions get actions => _actions;

  set actions(LyricToolsActions value) {
    final changed =
        value.hasLyrics != _actions.hasLyrics ||
        value.hasSyncedLyrics != _actions.hasSyncedLyrics ||
        value.busy != _actions.busy ||
        value.translating != _actions.translating;
    _actions = value;
    if (changed) notifyListeners();
  }

  /// The ✓: select mode, or the LRC editor while there are no lines to pick.
  void select() {
    if (!_actions.hasLyrics) {
      _actions.onEdit();
      return;
    }
    _actions.onBeforeSelect?.call();
    selection.enter();
  }

  /// Back while picking leaves the mode (and nothing else).
  bool handleBack() {
    if (!selection.active) return false;
    selection.exit();
    return true;
  }

  @override
  void dispose() {
    selection
      ..removeListener(notifyListeners)
      ..dispose();
    idle.dispose();
    super.dispose();
  }
}

/// Hands the player's [LyricToolsController] to the tools and the full lyric list.
class LyricToolsScope extends InheritedWidget {
  const LyricToolsScope({super.key, required this.controller, required super.child});

  final LyricToolsController controller;

  static LyricToolsController? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<LyricToolsScope>()?.controller;

  @override
  bool updateShouldNotify(LyricToolsScope old) => old.controller != controller;
}

/// The lyric tools, or — in select mode — the selection actions, in the same
/// slot (LyricsTools): cross-faded on the effects spring, the incoming one
/// growing from 0.92 on the standard spatial spring. Idle, they fade away on
/// the effects spring; with [inPlaceGuard] (where nothing sinks into their
/// place) the first tap on them while away only wakes them
/// (lyricToolsInPlaceWakeGuard; the player's touch tracker wakes them).
class LyricToolsBar extends StatefulWidget {
  const LyricToolsBar({super.key, this.iconSize = LyricToolsMetrics.keySize, this.inPlaceGuard = false});

  final double iconSize;
  final bool inPlaceGuard;

  @override
  State<LyricToolsBar> createState() => _LyricToolsBarState();
}

class _LyricToolsBarState extends State<LyricToolsBar> with TickerProviderStateMixin {
  LyricToolsController? _controller;
  late final SpringValue _swap = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _swapScale = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);
  late final SpringValue _shown = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  bool _selecting = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final next = LyricToolsScope.maybeOf(context);
    if (next == _controller) return;
    _controller?.removeListener(_onChange);
    _controller?.idle.removeListener(_onIdle);
    _controller = next;
    next?.addListener(_onChange);
    next?.idle.addListener(_onIdle);
    _selecting = next?.selection.active ?? false;
    _swap.snapTo(_selecting ? 1 : 0);
    _shown.snapTo(next?.idle.idle ?? false ? 0 : 1);
  }

  bool get _reduced => MediaQuery.disableAnimationsOf(context);

  void _onChange() {
    if (!mounted) return;
    final selecting = _controller?.selection.active ?? false;
    if (selecting != _selecting) {
      _selecting = selecting;
      if (_reduced) {
        _swap.snapTo(selecting ? 1 : 0);
        _swapScale.snapTo(1);
      } else {
        _swap.animateTo(selecting ? 1 : 0);
        _swapScale
          ..snapTo(LyricToolsMetrics.swapScaleFrom)
          ..animateTo(1);
      }
    }
    setState(() {});
  }

  void _onIdle() {
    if (!mounted) return;
    final target = (_controller?.idle.idle ?? false) ? 0.0 : 1.0;
    if (_reduced) {
      _shown.snapTo(target);
    } else {
      _shown.animateTo(target);
    }
    if (widget.inPlaceGuard) setState(() {});
  }

  @override
  void dispose() {
    _controller?.removeListener(_onChange);
    _controller?.idle.removeListener(_onIdle);
    _swap.dispose();
    _swapScale.dispose();
    _shown.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller == null) return SizedBox(height: widget.iconSize);
    final selection = controller.selection;
    final actions = controller.actions;
    final tools = ValueListenableBuilder<bool>(
      valueListenable: controller.autoFollow,
      builder: (context, following, _) => LyricsActionBar(
        iconSize: widget.iconSize,
        busy: actions.busy,
        translating: actions.translating,
        canTranslate: actions.hasLyrics,
        onTranslate: actions.onTranslate,
        canSelect: actions.hasLyrics,
        canRecenter: !following && actions.hasSyncedLyrics,
        onSearch: actions.onSearch,
        onSelect: controller.select,
        onRecenter: () => controller.autoFollow.value = true,
      ),
    );
    final bar = LyricsSelectionBar(
      iconSize: widget.iconSize,
      selectedCount: selection.selected.length,
      onClose: selection.exit,
      onCopy: actions.onCopy,
      onShare: actions.onShare,
      onEdit: () {
        selection.exit();
        actions.onEdit();
      },
    );
    final swapped = AnimatedBuilder(
      animation: Listenable.merge([_swap.animation, _swapScale.animation]),
      builder: (context, _) {
        final t = _swap.value.clamp(0.0, 1.0);
        final s = _swapScale.value;
        Widget side(Widget child, double alpha, bool incoming, bool active) => IgnorePointer(
          ignoring: !active,
          child: ExcludeSemantics(
            excluding: !active,
            child: Opacity(
              opacity: alpha,
              child: Transform.scale(scale: incoming ? s : 1, alignment: Alignment.centerLeft, child: child),
            ),
          ),
        );
        return Stack(
          alignment: AlignmentDirectional.centerStart,
          children: [
            if (t < 0.999) side(tools, 1 - t, !_selecting, !_selecting),
            if (t > 0.001) side(bar, t, _selecting, _selecting),
          ],
        );
      },
    );
    return AnimatedBuilder(
      animation: _shown.animation,
      child: swapped,
      builder: (context, child) => AbsorbPointer(
        absorbing: widget.inPlaceGuard && controller.idle.idle,
        child: Opacity(opacity: _shown.value.clamp(0.0, 1.0), child: child),
      ),
    );
  }
}

/// The lyric tools (LyricsActionBar): Search, Translate, ✓ Select,
/// Recenter. Translate stays lit while its translation runs and ignores
/// taps until it ends.
class LyricsActionBar extends StatelessWidget {
  const LyricsActionBar({
    super.key,
    required this.busy,
    this.translating = false,
    this.canTranslate = false,
    this.onTranslate,
    required this.canSelect,
    required this.canRecenter,
    required this.onSearch,
    required this.onSelect,
    required this.onRecenter,
    this.iconSize = LyricToolsMetrics.keySize,
  });

  final bool busy;
  final bool translating;
  final bool canTranslate;
  final VoidCallback? onTranslate;
  final bool canSelect;
  final bool canRecenter;

  /// Search, with the key's global rect (the surface grows out of the key).
  final void Function(Rect? from) onSearch;
  final VoidCallback onSelect;
  final VoidCallback onRecenter;
  final double iconSize;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return LyricsKeyRow(
      size: iconSize,
      keys: [
        LyricsKey(
          key: const ValueKey('lyrics-tool-search'),
          icon: YoinSymbols.search,
          label: strings.cdSearchLyrics,
          enabled: !busy,
          onTap: () => onSearch(null),
          onTapFrom: onSearch,
        ),
        LyricsKey(
          key: const ValueKey('lyrics-tool-translate'),
          icon: YoinSymbols.translate,
          label: strings.cdTranslateLyrics,
          enabled: (!busy || translating) && canTranslate,
          onTap: () {
            if (!busy) onTranslate?.call();
          },
        ),
        LyricsKey(
          key: const ValueKey('lyrics-tool-select'),
          icon: YoinSymbols.check,
          label: canSelect ? strings.cdSelectLyrics : strings.cdAddLyrics,
          enabled: !busy,
          onTap: onSelect,
        ),
        LyricsKey(
          key: const ValueKey('lyrics-tool-recenter'),
          icon: YoinSymbols.recenter,
          label: strings.cdReturnCurrentLine,
          enabled: !busy && canRecenter,
          onTap: onRecenter,
        ),
      ],
    );
  }
}

/// Select mode's keys in the tools' slot (LyricsSelectionBar): leave
/// (emphasized), copy, share as an image, edit the lyrics.
class LyricsSelectionBar extends StatelessWidget {
  const LyricsSelectionBar({
    super.key,
    required this.selectedCount,
    required this.onClose,
    required this.onCopy,
    required this.onShare,
    required this.onEdit,
    this.iconSize = LyricToolsMetrics.keySize,
  });

  final int selectedCount;
  final VoidCallback onClose;
  final VoidCallback onCopy;
  final VoidCallback onShare;
  final VoidCallback onEdit;
  final double iconSize;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final picked = selectedCount > 0;
    return LyricsKeyRow(
      size: iconSize,
      keys: [
        LyricsKey(
          key: const ValueKey('lyrics-select-close'),
          icon: YoinSymbols.close,
          label: strings.cdDoneSelecting,
          enabled: true,
          emphasized: true,
          onTap: onClose,
        ),
        LyricsKey(
          key: const ValueKey('lyrics-select-copy'),
          icon: YoinSymbols.copy,
          label: strings.cdCopyLyrics,
          enabled: picked,
          onTap: onCopy,
        ),
        LyricsKey(
          key: const ValueKey('lyrics-select-share'),
          icon: YoinSymbols.share,
          label: strings.cdShareLyricsImage,
          enabled: picked,
          onTap: onShare,
        ),
        LyricsKey(
          key: const ValueKey('lyrics-select-edit'),
          icon: YoinSymbols.edit,
          label: strings.cdEditLyrics,
          enabled: true,
          onTap: onEdit,
        ),
      ],
    );
  }
}

/// One lyric key's look and action.
class LyricsKey {
  const LyricsKey({
    required this.key,
    required this.icon,
    required this.label,
    required this.enabled,
    required this.onTap,
    this.onTapFrom,
    this.emphasized = false,
  });

  final Key key;
  final IconData icon;
  final String label;
  final bool enabled;
  final VoidCallback onTap;

  /// Instead of [onTap] when set: the tap with the key's global rect (a surface that grows out of it).
  final void Function(Rect from)? onTapFrom;

  /// The key that leaves a mode reads stronger than the actions beside it.
  final bool emphasized;
}

/// The lyric keys' connected group (LyricsKeyRow): [size]-square keys 8 apart,
/// the pressed one widening into its neighbours on the standard spatial
/// spring (M3 Expressive ButtonGroup's press, same expanded ratio).
class LyricsKeyRow extends StatefulWidget {
  const LyricsKeyRow({super.key, required this.keys, this.size = LyricToolsMetrics.keySize});

  final List<LyricsKey> keys;
  final double size;

  @override
  State<LyricsKeyRow> createState() => _LyricsKeyRowState();
}

class _LyricsKeyRowState extends State<LyricsKeyRow> with TickerProviderStateMixin {
  final List<SpringValue> _press = [];

  SpringValue _pressAt(int i) {
    while (_press.length <= i) {
      _press.add(SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial));
    }
    return _press[i];
  }

  void _setPressed(int i, bool pressed) {
    final spring = _pressAt(i);
    if (MediaQuery.disableAnimationsOf(context)) {
      spring.snapTo(pressed ? 1 : 0);
    } else {
      spring.animateTo(pressed ? 1 : 0);
    }
  }

  @override
  void dispose() {
    for (final p in _press) {
      p.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final keys = widget.keys;
    final springs = [for (var i = 0; i < keys.length; i++) _pressAt(i)];
    final total = widget.size * keys.length + LyricToolsMetrics.keyGap * (keys.length - 1).clamp(0, 99);
    return SizedBox(
      height: widget.size,
      width: total,
      child: AnimatedBuilder(
        animation: Listenable.merge([for (final s in springs) s.animation]),
        builder: (context, _) {
          final widths = lyricsKeyWidths(keys.length, widget.size, (i) => springs[i].value);
          return Row(
            children: [
              for (var i = 0; i < keys.length; i++) ...[
                if (i > 0) const SizedBox(width: LyricToolsMetrics.keyGap),
                SizedBox(
                  width: widths[i],
                  height: widget.size,
                  child: Listener(
                    onPointerDown: keys[i].enabled ? (_) => _setPressed(i, true) : null,
                    onPointerUp: (_) => _setPressed(i, false),
                    onPointerCancel: (_) => _setPressed(i, false),
                    child: _LyricsKeyButton(spec: keys[i], size: widget.size),
                  ),
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}

/// A filled tonal icon key: primaryContainer (primary when emphasized),
/// corner 0.31 of its size; M3's disabled colours when off.
class _LyricsKeyButton extends StatelessWidget {
  const _LyricsKeyButton({required this.spec, required this.size});

  final LyricsKey spec;
  final double size;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final container = !spec.enabled
        ? colors.onSurface.withValues(alpha: 0.1)
        : (spec.emphasized ? colors.primary : colors.primaryContainer);
    final content = !spec.enabled
        ? colors.onSurface.withValues(alpha: 0.38)
        : (spec.emphasized ? colors.onPrimary : colors.onPrimaryContainer);
    return Semantics(
      key: spec.key,
      button: true,
      enabled: spec.enabled,
      label: spec.label,
      excludeSemantics: true,
      child: Material(
        color: container,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(size * LyricToolsMetrics.cornerShare)),
        clipBehavior: Clip.antiAlias,
        child: Builder(
          builder: (context) => InkWell(
            onTap: !spec.enabled
                ? null
                : () {
                    final from = spec.onTapFrom;
                    final box = context.findRenderObject();
                    if (from != null && box is RenderBox && box.hasSize) {
                      from(box.localToGlobal(Offset.zero) & box.size);
                    } else {
                      spec.onTap();
                    }
                  },
            child: Center(child: Icon(spec.icon, color: content, size: 24)),
          ),
        ),
      ),
    );
  }
}

/// Hands [builder] the idle tools' step-away (0 here → 1 away) on the default
/// spatial spring, for a slot that collapses when they idle (the wide
/// layout's right column: the lyrics grow into it).
class LyricToolsAwayBuilder extends StatefulWidget {
  const LyricToolsAwayBuilder({super.key, required this.builder});

  final Widget Function(BuildContext context, double away) builder;

  @override
  State<LyricToolsAwayBuilder> createState() => _LyricToolsAwayBuilderState();
}

class _LyricToolsAwayBuilderState extends State<LyricToolsAwayBuilder> with TickerProviderStateMixin {
  late final SpringValue _away = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  FocusIdle? _idle;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final next = LyricToolsScope.maybeOf(context)?.idle;
    if (next == _idle) return;
    _idle?.removeListener(_onIdle);
    _idle = next;
    _idle?.addListener(_onIdle);
    _away.snapTo(next?.idle ?? false ? 1 : 0);
  }

  void _onIdle() {
    if (!mounted) return;
    final target = (_idle?.idle ?? false) ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _away.snapTo(target);
    } else {
      _away.animateTo(target);
    }
  }

  @override
  void dispose() {
    _idle?.removeListener(_onIdle);
    _away.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _away.animation,
    builder: (context, _) => widget.builder(context, _away.value.clamp(0.0, 1.0)),
  );
}

/// While the tools are away, the first tap in the bottom [band] of this area
/// only wakes them (lyricToolsWakeGuard): the slot they left collapsed and
/// the title sank into it, so a blind tap aimed at a tool would land on the
/// title. The tap is absorbed at hit test (the player's touch tracker wakes
/// the tools); drags still reach the gestures around it.
class LyricToolsWakeGuard extends StatelessWidget {
  const LyricToolsWakeGuard({super.key, required this.enabled, required this.band, required this.child});

  final bool enabled;
  final double band;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final idle = LyricToolsScope.maybeOf(context)?.idle;
    if (!enabled || idle == null) return child;
    return Stack(
      fit: StackFit.passthrough,
      children: [
        child,
        ListenableBuilder(
          listenable: idle,
          builder: (context, _) => !idle.idle
              ? const SizedBox.shrink()
              : Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  height: band,
                  child: GestureDetector(
                    key: const ValueKey('lyrics-tools-wake-guard'),
                    behavior: HitTestBehavior.opaque,
                    onTap: () {},
                  ),
                ),
        ),
      ],
    );
  }
}
