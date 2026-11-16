import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../home/home_edit.dart';
import '../home/home_screen.dart';
import '../library/library_screen.dart';
import '../memories/memories_motion.dart';
import '../memories/memories_reveal_scope.dart';
import '../memories/memories_screen.dart';
import '../nowplaying/overlay_controller.dart';
import '../theme/motion.dart';
import '../ui/adaptive/edge_split.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/navigation/pane/detail_pane.dart';
import 'bar_scroll_hide.dart';
import 'chrome_back.dart';
import 'shell_back_order.dart';
import 'shell_state.dart';
import 'yoin_section.dart';

/// The shell (RootSection): Home and Library. The bar lives in AppChrome
/// above the Navigator and writes [shellSectionProvider]; switching
/// crossfades the pages on the effects spring (design.md 关键转场动画:
/// 切换主页 ↔ Library), both kept alive so scroll positions survive.
class ShellScreen extends ConsumerStatefulWidget {
  const ShellScreen({super.key});

  @override
  ConsumerState<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends ConsumerState<ShellScreen> with TickerProviderStateMixin {
  /// 1 = Home showing, 0 = Library showing.
  late final SpringValue _homeVisibility = SpringValue(
    vsync: this,
    initial: ref.read(shellSectionProvider) == YoinSection.home ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  /// Memories' q (ShellOverlayUp over Home): Home's pull, the deck, the
  /// bar's hide and system back all feed this one controller.
  late final MemoriesRevealController _memories = MemoriesRevealController(vsync: this);
  late final MemoriesRevealHandle _memoriesHandle = ref.read(memoriesRevealHandleProvider);

  /// The window's back order: Memories, the column and the Now Playing side
  /// panel close newest first (ShellBackResolver).
  late final ShellBackLedger _backOrder = ref.read(shellBackLedgerProvider);
  late final ChromeBackEntry _memoriesBack = ChromeBackEntry(
    _memoriesHandle.back,
    owns: () => _backOrder.owns(ShellBackOwner.memories),
    priority: ChromeBackPriority.memories,
  );

  /// 1 = the shell is the top page; 0 = a page pushed over it (from Memories) has the bar.
  late final SpringValue _uncovered = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);

  /// The Wide window's detail column (adaptive-principles §3).
  late final DetailPaneController _pane = DetailPaneController(vsync: this);

  /// Home's scroll-hide of the bar: only over the plain feed, with nothing
  /// opened over it (YoinNavHost barScrollHideEnabled).
  late final ShellBarScrollHide _barScrollHide = ref.read(shellBarScrollHideProvider);

  /// The shell route's [ModalRoute.secondaryAnimation]: forward or done while
  /// a page is pushed over the shell (a sheet or menu doesn't drive it).
  Animation<double>? _coverAnimation;

  bool get _pageOverShell {
    final status = _coverAnimation?.status ?? AnimationStatus.dismissed;
    return status == AnimationStatus.forward || status == AnimationStatus.completed;
  }

  /// A page opened over the shell, the column, Memories, edit mode or Library
  /// all keep the bar; the moment one comes, the bar rises back into place.
  void _syncBarScrollHide() {
    if (!mounted) return;
    _barScrollHide.shellAllows =
        ref.read(shellSectionProvider) == YoinSection.home &&
        !ref.read(homeEditProvider).editing &&
        !_memories.ownsBack &&
        !_pane.hasEntries &&
        !_pageOverShell;
  }

  /// Arbitrated by the back order (principle 8): beside a side panel or
  /// Memories, system back is the column's only while it is the newest of
  /// them, whatever order the entries were registered in.
  late final ChromeBackEntry _paneBack = ChromeBackEntry(
    _pane.handleBack,
    owns: () => _backOrder.owns(ShellBackOwner.detailColumn),
    priority: ChromeBackPriority.detailColumn,
  );

  @override
  void initState() {
    super.initState();
    _memoriesHandle.attach(_memories);
    _memoriesHandle.attachCover(_uncovered.animation);
    _memories.addListener(_syncMemoriesBack);
    DetailPaneController.active = _pane;
    _pane.addListener(_syncPaneBack);
    _backOrder.bindShell(
      homeSelected: () => ref.read(shellSectionProvider) == YoinSection.home,
      memoriesOpen: () => _memories.ownsBack,
      detailColumnOpen: () => _pane.hasEntries,
    );
    // A page shown in the column — also into one already open — is the
    // newest thing entered: back leaves it first.
    _pane.onPageShown = _backOrder.columnPageShown;
  }

  void _syncPaneBack() {
    _backOrder.syncShell();
    _syncBarScrollHide();
    final navigator = appNavigatorKey.currentState;
    if (_pane.hasEntries && navigator != null) {
      _paneBack.attach(navigator);
    } else if (!_pane.hasEntries && _paneBack.isAttached) {
      Future.microtask(() {
        if (!_pane.hasEntries) _paneBack.detach();
      });
    }
  }

  /// Back belongs to Memories exactly while it is open or opening.
  void _syncMemoriesBack() {
    _backOrder.syncShell();
    _syncBarScrollHide();
    final navigator = appNavigatorKey.currentState;
    if (_memories.ownsBack && navigator != null) {
      _memoriesBack.attach(navigator);
    } else if (!_memories.ownsBack && _memoriesBack.isAttached) {
      // Not synchronously: a close can come from inside the route's pop-entry loop.
      Future.microtask(() {
        if (!_memories.ownsBack) _memoriesBack.detach();
      });
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final cover = ModalRoute.of(context)?.secondaryAnimation;
    if (!identical(cover, _coverAnimation)) {
      _coverAnimation?.removeStatusListener(_onCoverStatus);
      _coverAnimation = cover?..addStatusListener(_onCoverStatus);
      WidgetsBinding.instance.addPostFrameCallback((_) => _syncBarScrollHide());
    }
  }

  void _onCoverStatus(AnimationStatus _) => _syncBarScrollHide();

  @override
  void dispose() {
    _coverAnimation?.removeStatusListener(_onCoverStatus);
    _backOrder.unbindShell();
    _pane.onPageShown = null;
    _pane.removeListener(_syncPaneBack);
    _paneBack.dispose();
    _pane.dispose();
    _memories.removeListener(_syncMemoriesBack);
    _memoriesHandle.detach(_memories);
    _memoriesBack.dispose();
    _memories.dispose();
    _uncovered.dispose();
    _homeVisibility.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(shellSectionProvider, (_, section) {
      _homeVisibility.animateTo(section == YoinSection.home ? 1 : 0);
      // Memories counts in the back order only over Home.
      _backOrder.syncShell();
      _syncBarScrollHide();
    });
    ref.listen(homeEditProvider.select((e) => e.editing), (_, _) => _syncBarScrollHide());
    // A page over the shell takes the bar back from Memories (and gives it back when it pops).
    final top = ModalRoute.isCurrentOf(context) ?? true;
    _pane.shellIsTop = top;
    // Narrowed out of Wide with the column open: its pages become pushed
    // pages. The window, not the width beside a Now Playing side panel: the
    // panel never takes the column away (adaptive-principles §4).
    final window = NowPlayingHostWindowScope.of(context);
    if (_pane.holdsColumn && (window.layoutMode != LayoutMode.wide || window.isCompactHeight)) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final nav = appNavigatorKey.currentState;
        if (nav != null && mounted) _pane.handOver(nav);
      });
    }
    if (MediaQuery.disableAnimationsOf(context)) {
      _uncovered.snapTo(top ? 1 : 0);
    } else {
      _uncovered.animateTo(top ? 1 : 0);
    }
    return Scaffold(
      body: DetailPaneHost(
        controller: _pane,
        shell: AnimatedBuilder(
          animation: _homeVisibility.animation,
          builder: (context, _) {
            final home = _homeVisibility.value.clamp(0.0, 1.0);
            return Stack(
              fit: StackFit.expand,
              children: [
                // A landscape handset: the pages start past the edge capsules
                // (84) and stop short of a cutout on the other edge, their wash
                // running under the band to the screen edge. Memories is not
                // shifted: the capsules hide there.
                _Page(
                  visibility: 1 - home,
                  child: const ShellChromeBand(background: _ShellWash(), child: LibraryScreen()),
                ),
                _Page(
                  visibility: home,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      MemoriesHomeBehind(
                        reveal: _memories,
                        child: const ShellChromeBand(background: _ShellWash(), child: HomeScreen()),
                      ),
                      MemoriesLayer(reveal: _memories),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _Page extends StatelessWidget {
  const _Page({required this.visibility, required this.child});

  final double visibility;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      ignoring: visibility < 0.5,
      child: Opacity(
        opacity: visibility,
        // Keep the subtree mounted (state, scroll) but skip painting when hidden.
        child: TickerMode(enabled: visibility > 0, child: child),
      ),
    );
  }
}

/// The shell pages' wash (ExpressivePageBackground: surfaceContainer →
/// surface → surfaceContainerLow, top to bottom), under the edge band so the
/// page meets the screen edge without a seam.
class _ShellWash extends StatelessWidget {
  const _ShellWash();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
        ),
      ),
    );
  }
}
