/// The app's keyboard shortcuts on PC windows (MateBook 2in1, later
/// Windows): Android's twelve (ShellShortcuts.kt, iOS's Controls and Go
/// menus with Ctrl for ⌘ and Alt for ⌥) plus the Mac app's extras. One
/// Shortcuts/Actions layer around the chrome (AppChrome: above the Navigator
/// and Now Playing), so every page and overlay sees the same keys. Every
/// shortcut steps aside while a text field has focus (the key reaches the
/// field), and while its target can't act (no song: Space still activates
/// the focused button — a dialog's buttons share this window here, unlike
/// Android's). A chord takes its key once: holding it doesn't repeat it.
/// HarmonyOS has no system shortcut list (Android's Meta + /), so the keys
/// are listed only here and in the tip.
///
/// | Keys | Does |
/// | --- | --- |
/// | Space | play / pause |
/// | Ctrl+→ / Ctrl+← | next / previous |
/// | Ctrl+Alt+S | shuffle on / off |
/// | Ctrl+D | heart / unheart the playing song (a service that keeps favorites) |
/// | Ctrl+Shift+P | open / close Now Playing (over the shell, Memories and Home edit mode close first) |
/// | Ctrl+L | Now Playing panel ⇄ full window (opens it first) |
/// | Ctrl+F | Now Playing focus (opens it first; where the cover can focus) |
/// | Ctrl+K | Library search |
/// | Ctrl+, | Settings |
/// | Ctrl+1 / Ctrl+2 | Home / Library (back to the shell; Memories closes) |
/// | Ctrl+3 | All Memories (not while Home is being edited) |
/// | Ctrl+[ | back, when something is open to close (never leaves the app) |
/// | Esc | back (the system back's path: the Navigator's maybePop); in a text field it leaves the field first (the Library search closes) |
/// | ← / → | seek −5 s / +5 s while Now Playing is open |
/// | Shift+F10, Menu key | the focused item's menu (its right click) |
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart' show activeSourceProvider;
import '../data/source/music_source.dart' show Capability;
import '../home/home_edit.dart';
import '../library/library_search_view.dart' show librarySearchRouteName;
import '../memories/memories_reveal_scope.dart';
import '../nowplaying/overlay_controller.dart';
import '../nowplaying/player_snapshot.dart';
import '../playback/playback_controller.dart';
import '../playback/playhead.dart';
import '../settings/settings_screen.dart';
import '../theme/tokens.dart';
import '../ui/component/desktop_input.dart';
import 'chrome_back.dart';
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

class ToggleShuffleIntent extends Intent {
  const ToggleShuffleIntent();
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

/// Ctrl+[ (Android ShellShortcut.Back): back only when something is open to close.
class GoBackIntent extends Intent {
  const GoBackIntent();
}

/// Ctrl+1 / Ctrl+2 / Ctrl+3: the bar's destinations and All Memories (iOS's Go menu).
enum GoDestination { home, library, allMemories }

class GoToIntent extends Intent {
  const GoToIntent(this.destination);
  final GoDestination destination;
}

class SeekByIntent extends Intent {
  const SeekByIntent(this.deltaMs);
  final int deltaMs;
}

/// The key map (Ctrl for the Mac app's ⌘).
const Map<ShortcutActivator, Intent> yoinShortcuts = {
  SingleActivator(LogicalKeyboardKey.space, includeRepeats: false): PlayPauseIntent(),
  SingleActivator(LogicalKeyboardKey.arrowRight, control: true, includeRepeats: false): NextTrackIntent(),
  SingleActivator(LogicalKeyboardKey.arrowLeft, control: true, includeRepeats: false): PreviousTrackIntent(),
  SingleActivator(LogicalKeyboardKey.keyS, control: true, alt: true, includeRepeats: false): ToggleShuffleIntent(),
  SingleActivator(LogicalKeyboardKey.keyD, control: true, includeRepeats: false): ToggleFavoriteIntent(),
  SingleActivator(LogicalKeyboardKey.keyP, control: true, shift: true, includeRepeats: false):
      ToggleNowPlayingIntent(),
  SingleActivator(LogicalKeyboardKey.keyL, control: true, includeRepeats: false): ToggleFullWindowIntent(),
  SingleActivator(LogicalKeyboardKey.keyF, control: true, includeRepeats: false): ToggleFocusIntent(),
  SingleActivator(LogicalKeyboardKey.keyK, control: true, includeRepeats: false): OpenSearchIntent(),
  SingleActivator(LogicalKeyboardKey.comma, control: true, includeRepeats: false): OpenSettingsIntent(),
  SingleActivator(LogicalKeyboardKey.digit1, control: true, includeRepeats: false): GoToIntent(GoDestination.home),
  SingleActivator(LogicalKeyboardKey.digit2, control: true, includeRepeats: false): GoToIntent(GoDestination.library),
  SingleActivator(LogicalKeyboardKey.digit3, control: true, includeRepeats: false):
      GoToIntent(GoDestination.allMemories),
  SingleActivator(LogicalKeyboardKey.bracketLeft, control: true, includeRepeats: false): GoBackIntent(),
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
  MemoriesRevealHandle get _memories => ref.read(memoriesRevealHandleProvider);
  bool get _homeEditing => ref.read(homeEditProvider).editing;

  /// Like needs a service that keeps favorites (Android ServiceFeatureCatalog.supportsFavorites).
  bool get _canLike {
    final track = ref.read(playbackControllerProvider).current;
    final source = ref.read(activeSourceProvider).value;
    return track != null &&
        source != null &&
        source.provider == track.id.provider &&
        source.capabilities.contains(Capability.favorites);
  }

  /// Something in the window would take a back now (a pushed page, a sheet,
  /// Now Playing, edit mode, Memories, the column): at the root Ctrl+[ never
  /// sends the app to the background.
  bool get _canGoBack => (widget.navigator.currentState?.canPop() ?? false) || ChromeBackEntry.anyTakesBack;

  void _toggleShuffle() {
    final now = ref.read(playbackControllerProvider);
    _playback.setPlayMode(shuffle: !now.shuffle, repeat: now.repeat);
  }

  /// Memories leaves with Home's feed (Android dismissMemoriesIfActive).
  void _closeMemories() {
    final reveal = _memories.controller;
    if (reveal != null && reveal.ownsBack) reveal.close();
  }

  /// The Go keys land on the shell itself: a full-window player and the
  /// pages pushed over the shell (the Library search among them) give way.
  /// On Android they belong to the shell window alone; here every page
  /// shares the keys, so Home means going back to it.
  void _backToShell() {
    _clearNowPlayingForPage();
    widget.navigator.currentState?.popUntil((route) => route.isFirst);
  }

  void _goTo(GoDestination destination) {
    final sections = ref.read(shellSectionProvider.notifier);
    switch (destination) {
      case GoDestination.home:
        _backToShell();
        sections.select(YoinSection.home);
        _closeMemories();
      case GoDestination.library:
        _backToShell();
        ref.read(homeEditProvider.notifier).exit();
        sections.select(YoinSection.library);
        _closeMemories();
      case GoDestination.allMemories:
        _backToShell();
        sections.select(YoinSection.home);
        _memories.open();
    }
  }

  /// Opens Now Playing as the shell's own way in does (Android
  /// openNowPlayingFromShell): over the shell, Memories closes and Home
  /// leaves edit mode first (the bar's pill isn't there in either), so
  /// closing the player lands on the feed. Over a pushed page they wait
  /// under it.
  void _openNowPlaying() {
    if (!(widget.navigator.currentState?.canPop() ?? false)) {
      _closeMemories();
      ref.read(homeEditProvider.notifier).exit();
    }
    widget.openNowPlaying();
  }

  /// Opens Now Playing if needed, then runs [then] once it is up.
  void _withNowPlaying(VoidCallback then) {
    if (_np.expanded) {
      then();
      return;
    }
    _openNowPlaying();
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
    // As the shell's own way in (Android navigateToSettingsFromShell).
    _closeMemories();
    ref.read(homeEditProvider.notifier).exit();
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
    ref.read(homeEditProvider.notifier).exit();
    _closeMemories();
    if (mounted) openLibrarySearchShortcut(ref.container);
  }

  @override
  Widget build(BuildContext context) {
    final actions = <Type, Action<Intent>>{
      PlayPauseIntent: _KeyAction<PlayPauseIntent>((_) => _playback.togglePlayPause(), when: () => _hasTrack),
      NextTrackIntent: _KeyAction<NextTrackIntent>((_) => _playback.next(), when: () => _hasTrack),
      PreviousTrackIntent: _KeyAction<PreviousTrackIntent>((_) => _playback.previous(), when: () => _hasTrack),
      ToggleShuffleIntent: _KeyAction<ToggleShuffleIntent>((_) => _toggleShuffle(), when: () => _hasTrack),
      ToggleFavoriteIntent: _KeyAction<ToggleFavoriteIntent>(
        (_) => _playback.toggleFavorite(),
        when: () => _hasTrack && _canLike,
      ),
      ToggleNowPlayingIntent: _KeyAction<ToggleNowPlayingIntent>(
        (_) => _np.expanded ? _np.close() : _openNowPlaying(),
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
      GoBackIntent: _KeyAction<GoBackIntent>((_) => widget.navigator.currentState?.maybePop(), when: () => _canGoBack),
      // Memories never opens over edit mode (as Home's own way in).
      GoToIntent: _GoToAction(_goTo, editing: () => _homeEditing),
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

/// Ctrl+1/2/3: off while typing; All Memories also while Home is being edited.
class _GoToAction extends _KeyAction<GoToIntent> {
  _GoToAction(void Function(GoDestination) go, {required this.editing}) : super((i) => go(i.destination));

  final bool Function() editing;

  @override
  bool isEnabled(GoToIntent intent) =>
      super.isEnabled(intent) && !(intent.destination == GoDestination.allMemories && editing());
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
