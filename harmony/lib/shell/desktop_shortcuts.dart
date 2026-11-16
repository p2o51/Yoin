/// The app's keyboard shortcuts on PC windows (MateBook 2in1, later
/// Windows), mirroring the Mac app's Controls menu (YoinMacApp.swift) with
/// Ctrl for ⌘. One Shortcuts/Actions layer around the chrome (AppChrome:
/// above the Navigator and Now Playing), so every page and overlay sees the
/// same keys. Every shortcut steps aside while a text field has focus (the
/// key reaches the field), and while its target can't act (no song: Space
/// still activates the focused button).
///
/// | Keys | Does |
/// | --- | --- |
/// | Space | play / pause |
/// | Ctrl+→ / Ctrl+← | next / previous |
/// | Ctrl+D | heart / unheart the playing song |
/// | Ctrl+Shift+P | open / close Now Playing |
/// | Ctrl+L | Now Playing panel ⇄ full window (opens it first) |
/// | Ctrl+F | Now Playing focus (opens it first; where the cover can focus) |
/// | Ctrl+K | Library search |
/// | Ctrl+, | Settings |
/// | Esc | back (the system back's path: the Navigator's maybePop); in a text field it leaves the field first (the Library search closes) |
/// | ← / → | seek −5 s / +5 s while Now Playing is open |
/// | Shift+F10, Menu key | the focused item's menu (its right click) |
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../library/library_search_view.dart' show librarySearchRouteName;
import '../nowplaying/overlay_controller.dart';
import '../nowplaying/player_snapshot.dart';
import '../playback/playback_controller.dart';
import '../playback/playhead.dart';
import '../settings/settings_screen.dart';
import '../theme/tokens.dart';
import '../ui/component/desktop_input.dart';
import 'shell_state.dart';
import 'yoin_section.dart';

class PlayPauseIntent extends Intent {
  const PlayPauseIntent();
}

class NextTrackIntent extends Intent {
  const NextTrackIntent();
}

class PreviousTrackIntent extends Intent {
  const PreviousTrackIntent();
}

class ToggleFavoriteIntent extends Intent {
  const ToggleFavoriteIntent();
}

class ToggleNowPlayingIntent extends Intent {
  const ToggleNowPlayingIntent();
}

class ToggleFullWindowIntent extends Intent {
  const ToggleFullWindowIntent();
}

class ToggleFocusIntent extends Intent {
  const ToggleFocusIntent();
}

class OpenSearchIntent extends Intent {
  const OpenSearchIntent();
}

class OpenSettingsIntent extends Intent {
  const OpenSettingsIntent();
}

class YoinBackIntent extends Intent {
  const YoinBackIntent();
}

class SeekByIntent extends Intent {
  const SeekByIntent(this.deltaMs);
  final int deltaMs;
}

/// The key map (Ctrl for the Mac app's ⌘).
const Map<ShortcutActivator, Intent> yoinShortcuts = {
  SingleActivator(LogicalKeyboardKey.space, includeRepeats: false): PlayPauseIntent(),
  SingleActivator(LogicalKeyboardKey.arrowRight, control: true): NextTrackIntent(),
  SingleActivator(LogicalKeyboardKey.arrowLeft, control: true): PreviousTrackIntent(),
  SingleActivator(LogicalKeyboardKey.keyD, control: true, includeRepeats: false): ToggleFavoriteIntent(),
  SingleActivator(LogicalKeyboardKey.keyP, control: true, shift: true, includeRepeats: false):
      ToggleNowPlayingIntent(),
  SingleActivator(LogicalKeyboardKey.keyL, control: true, includeRepeats: false): ToggleFullWindowIntent(),
  SingleActivator(LogicalKeyboardKey.keyF, control: true, includeRepeats: false): ToggleFocusIntent(),
  SingleActivator(LogicalKeyboardKey.keyK, control: true, includeRepeats: false): OpenSearchIntent(),
  SingleActivator(LogicalKeyboardKey.comma, control: true, includeRepeats: false): OpenSettingsIntent(),
  SingleActivator(LogicalKeyboardKey.escape, includeRepeats: false): YoinBackIntent(),
  SingleActivator(LogicalKeyboardKey.arrowLeft): SeekByIntent(-DesktopInputTokens.seekStepMs),
  SingleActivator(LogicalKeyboardKey.arrowRight): SeekByIntent(DesktopInputTokens.seekStepMs),
  ...contextMenuShortcuts,
};

/// Ctrl+K asks the Library to open its search from its own pill (the
/// search route morphs out of the pill's rect). Library listens.
class LibrarySearchRequests extends Notifier<int> {
  @override
  int build() => 0;

  void request() => state++;
}

final librarySearchRequestsProvider = NotifierProvider<LibrarySearchRequests, int>(LibrarySearchRequests.new);

/// Library's search from anywhere in the shell (Ctrl+K, a long-press on
/// Library in the bar; Android LibraryViewModel.openSearchShortcut): Library
/// comes up, then opens its search from its pill once it has laid out.
void openLibrarySearchShortcut(ProviderContainer container) {
  container.read(shellSectionProvider.notifier).select(YoinSection.library);
  WidgetsBinding.instance.addPostFrameCallback((_) => container.read(librarySearchRequestsProvider.notifier).request());
}

/// Whether the keyboard is typing into a text field now (every shortcut steps aside).
bool isTypingIntoTextField() {
  final context = FocusManager.instance.primaryFocus?.context;
  if (context == null || !context.mounted) return false;
  return context.widget is EditableText || context.findAncestorStateOfType<EditableTextState>() != null;
}

/// Wraps the chrome: the shortcuts, and a focus anchor so keys arrive even
/// before anything was focused (HarmonyOS starts with no primary focus).
class YoinShortcuts extends ConsumerStatefulWidget {
  const YoinShortcuts({
    super.key,
    required this.controller,
    required this.openNowPlaying,
    required this.navigator,
    required this.child,
  });

  final NowPlayingOverlayController controller;

  /// Opens Now Playing the way the pill does (waits for a rebuilding queue).
  final VoidCallback openNowPlaying;

  /// The app's Navigator (Esc, Settings).
  final GlobalKey<NavigatorState> navigator;

  final Widget child;

  @override
  ConsumerState<YoinShortcuts> createState() => _YoinShortcutsState();
}

class _YoinShortcutsState extends ConsumerState<YoinShortcuts> {
  final FocusNode _anchor = FocusNode(debugLabel: 'YoinShortcuts', skipTraversal: true);

  @override
  void initState() {
    super.initState();
    FocusManager.instance.addListener(_ensureFocus);
    WidgetsBinding.instance.addPostFrameCallback((_) => _ensureFocus());
  }

  @override
  void dispose() {
    FocusManager.instance.removeListener(_ensureFocus);
    _anchor.dispose();
    super.dispose();
  }

  /// No primary focus = no key events at all: hold it on the anchor (an
  /// ancestor of every route), until a route or a field takes it.
  void _ensureFocus() {
    if (!mounted) return;
    final primary = FocusManager.instance.primaryFocus;
    if (primary == null || primary == FocusManager.instance.rootScope) _anchor.requestFocus();
  }

  NowPlayingOverlayController get _np => widget.controller;
  PlaybackController get _playback => ref.read(playbackControllerProvider.notifier);
  bool get _hasTrack => ref.read(playerSnapshotProvider).hasTrack;

  /// Opens Now Playing if needed, then runs [then] once it is up.
  void _withNowPlaying(VoidCallback then) {
    if (_np.expanded) {
      then();
      return;
    }
    widget.openNowPlaying();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _np.expanded) then();
    });
  }

  /// The pages under a full-window player are hidden: leave it (the side panel stays).
  void _clearNowPlayingForPage() {
    if (_np.expanded && !_np.panelOpen) _np.close();
  }

  void _seekBy(int delta) {
    final head = ref.read(playheadProvider);
    final now = head.positionAt(ref.read(playbackClockProvider).wallMs());
    _playback.seekTo(now + delta);
  }

  void _openSettings() {
    final navigator = widget.navigator.currentState;
    if (navigator == null) return;
    _clearNowPlayingForPage();
    // popUntil with an always-true predicate pops nothing: it reads the top route.
    String? top;
    navigator.popUntil((route) {
      top = route.settings.name;
      return true;
    });
    if (top == settingsRouteName) return;
    openSettings(navigator.context);
  }

  /// Esc: out of a text field first (the Library search closes outright, as
  /// on Android and the Mac), otherwise the system back's path.
  void _back() {
    if (isTypingIntoTextField()) {
      final field = FocusManager.instance.primaryFocus!;
      final route = ModalRoute.of(field.context!);
      if (route?.settings.name == librarySearchRouteName) {
        route!.navigator?.maybePop();
      } else {
        field.unfocus();
      }
      return;
    }
    widget.navigator.currentState?.maybePop();
  }

  void _openSearch() {
    _clearNowPlayingForPage();
    if (mounted) openLibrarySearchShortcut(ref.container);
  }

  @override
  Widget build(BuildContext context) {
    final actions = <Type, Action<Intent>>{
      PlayPauseIntent: _KeyAction<PlayPauseIntent>((_) => _playback.togglePlayPause(), when: () => _hasTrack),
      NextTrackIntent: _KeyAction<NextTrackIntent>((_) => _playback.next(), when: () => _hasTrack),
      PreviousTrackIntent: _KeyAction<PreviousTrackIntent>((_) => _playback.previous(), when: () => _hasTrack),
      ToggleFavoriteIntent: _KeyAction<ToggleFavoriteIntent>((_) => _playback.toggleFavorite(), when: () => _hasTrack),
      ToggleNowPlayingIntent: _KeyAction<ToggleNowPlayingIntent>(
        (_) => _np.expanded ? _np.close() : widget.openNowPlaying(),
        when: () => _np.expanded || _hasTrack,
      ),
      ToggleFullWindowIntent: _KeyAction<ToggleFullWindowIntent>(
        (_) => _withNowPlaying(_np.toggleFullscreen),
        when: () => _np.expanded || _hasTrack,
      ),
      // Tolerant: focus exists only where the cover can grow; the
      // controller ignores the call elsewhere.
      ToggleFocusIntent: _KeyAction<ToggleFocusIntent>(
        (_) => _withNowPlaying(_np.toggleFocus),
        when: () => _np.expanded || _hasTrack,
      ),
      OpenSearchIntent: _KeyAction<OpenSearchIntent>((_) => _openSearch()),
      OpenSettingsIntent: _KeyAction<OpenSettingsIntent>((_) => _openSettings()),
      YoinBackIntent: _KeyAction<YoinBackIntent>((_) => _back(), whileTyping: true),
      SeekByIntent: _KeyAction<SeekByIntent>((i) => _seekBy(i.deltaMs), when: () => _np.expanded && _hasTrack),
      // Tab never leaves the Flutter view: at the end of the order it wraps
      // instead of handing the key to ArkUI, whose own focus box then drew a
      // grey band over the window (device QA n_12/n_13).
      NextFocusIntent: _TraversalAction<NextFocusIntent>((n) => n.nextFocus()),
      PreviousFocusIntent: _TraversalAction<PreviousFocusIntent>((n) => n.previousFocus()),
    };
    return Shortcuts(
      shortcuts: yoinShortcuts,
      child: Actions(
        actions: actions,
        child: Focus(
          focusNode: _anchor,
          autofocus: true,
          includeSemantics: false,
          // The keyboard focus ring, drawn once for whatever has focus.
          child: YoinFocusRingLayer(ignore: _anchor, child: widget.child),
        ),
      ),
    );
  }
}

/// A shortcut's action: off while typing or while [when] says it can't act,
/// so the key goes on to the field / the focused control.
class _KeyAction<T extends Intent> extends Action<T> {
  _KeyAction(this._invoke, {bool Function()? when, this.whileTyping = false}) : _when = when;

  final void Function(T intent) _invoke;
  final bool Function()? _when;

  /// Esc acts in a text field too (it leaves the field).
  final bool whileTyping;

  @override
  bool isEnabled(T intent) => (whileTyping || !isTypingIntoTextField()) && (_when?.call() ?? true);

  @override
  Object? invoke(T intent) {
    _invoke(intent);
    return null;
  }
}

/// Tab / Shift+Tab: move focus and always keep the key, wrapping at the ends
/// (the framework hands an unmoved Tab to the platform).
class _TraversalAction<T extends Intent> extends Action<T> {
  _TraversalAction(this._move);

  final bool Function(FocusNode node) _move;

  @override
  Object? invoke(T intent) {
    final node = FocusManager.instance.primaryFocus;
    if (node == null) return null;
    if (!_move(node)) {
      // At the end: start over from the top of the nearest scope.
      final scope = node.nearestScope;
      if (scope != null) {
        final order = scope.traversalDescendants.where((n) => n.canRequestFocus && !n.skipTraversal).toList();
        if (order.isNotEmpty) (intent is PreviousFocusIntent ? order.last : order.first).requestFocus();
      }
    }
    return null;
  }
}
