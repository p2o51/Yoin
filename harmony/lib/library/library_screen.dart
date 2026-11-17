import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../l10n/strings.dart';
import '../playback/playback_controller.dart';
import '../settings/account_reconnect.dart';
import '../settings/settings_screen.dart';
import '../shell/desktop_shortcuts.dart';
import '../shell/shell_state.dart';
import '../shell/yoin_section.dart';
import '../theme/tokens.dart';
import '../ui/component/seam/chrome_seam.dart';
import '../ui/component/error_diagnostics.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/user_message.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/component/yoin_pull_to_refresh.dart';
import 'library_filter_row.dart';
import 'library_grid_anchor.dart';
import 'library_search.dart';
import 'library_search_view.dart';
import 'library_strings.dart';
import 'library_view_model.dart';
import 'library_views.dart';
import 'new_playlist_fab.dart';
import 'playlist_writes.dart';

/// Library root (RootSection; Android LibraryScreen, Compact): search pill
/// + settings, the chip row, and the selected view under pull to refresh.
/// Root back is never intercepted.
class LibraryScreen extends ConsumerStatefulWidget {
  const LibraryScreen({super.key});

  @override
  ConsumerState<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends ConsumerState<LibraryScreen> {
  /// One scroll controller per view, above the switcher, so returning to a
  /// view lands where the user left it. The grids' keep their anchor with
  /// them: a view that comes back at another width finds its place.
  final _scrolls = {
    for (final tab in LibraryTab.values)
      tab: switch (tab) {
        LibraryTab.all || LibraryTab.artists || LibraryTab.albums => LibraryGridScrollController(),
        LibraryTab.songs || LibraryTab.playlists || LibraryTab.favorites => ScrollController(),
      },
  };

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && ref.read(shellSectionProvider) == YoinSection.library) {
        ref.read(libraryViewModelProvider.notifier).onShown();
      }
    });
  }

  @override
  void dispose() {
    for (final c in _scrolls.values) {
      c.dispose();
    }
    super.dispose();
  }

  void _openSettings() => openSettings(context);

  /// The search pill: Ctrl+K opens the search from it, like a tap.
  final GlobalKey _pillKey = GlobalKey(debugLabel: 'library search pill');

  void _openSearchFromPill() {
    final box = _pillKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize || !box.attached) return;
    openLibrarySearch(context, _SearchHeader.padding.deflateRect(box.localToGlobal(Offset.zero) & box.size));
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(libraryViewModelProvider);
    final vm = ref.read(libraryViewModelProvider.notifier);
    ref.listen(shellSectionProvider, (_, section) {
      if (section == YoinSection.library) {
        vm.onShown();
      } else {
        vm.onHidden();
      }
    });
    ref.listen(librarySearchRequestsProvider, (_, _) => _openSearchFromPill());
    ref.listen(libraryViewModelProvider.select((s) => s is LibraryContent ? s.notice : null), (previous, notice) {
      if (notice == null || notice.serial == previous?.serial) return;
      final strings = LibraryStrings.of(context);
      // The failure's own line first (connectivity, the server's words), else the view's.
      ref
          .read(playbackControllerProvider.notifier)
          .showMessage(userMessageText(context, notice.error, _tabError(strings, notice.tab)));
    });
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      // ExpressivePageBackground, no accent on Library.
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _SearchHeader(key: _pillKey, onSettings: _openSettings),
            Expanded(
              child: SpringCrossFade(
                childKey: state.runtimeType,
                child: switch (state) {
                  LibraryLoading() => const Center(child: YoinLoadingIndicator()),
                  LibraryNoAccount() => const _ConnectPrompt(),
                  LibraryError(:final error, :final diagnostics) => _ErrorPrompt(
                    error: error,
                    diagnostics: diagnostics,
                    onSettings: _openSettings,
                  ),
                  LibraryContent() => _Content(state: state, scrolls: _scrolls),
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String _tabError(LibraryStrings s, LibraryTab tab) => switch (tab) {
  LibraryTab.all => s.errorLoadLibrary,
  LibraryTab.artists => s.errorLoadArtists,
  LibraryTab.albums => s.errorLoadAlbums,
  LibraryTab.songs => s.errorLoadSongs,
  LibraryTab.playlists => s.errorLoadPlaylists,
  LibraryTab.favorites => s.errorLoadFavorites,
};

/// The search pill (56, full radius, surfaceContainerHighest) with the gear at its end.
class _SearchHeader extends StatelessWidget {
  const _SearchHeader({super.key, required this.onSettings});

  static const padding = EdgeInsets.fromLTRB(16, 8, 16, 8);

  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: padding,
      child: Builder(
        builder: (pillContext) => Material(
          color: colors.surfaceContainerHighest,
          shape: const StadiumBorder(),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () {
              final box = pillContext.findRenderObject()! as RenderBox;
              openLibrarySearch(context, box.localToGlobal(Offset.zero) & box.size);
            },
            child: SizedBox(
              height: 56,
              child: Row(
                children: [
                  const SizedBox(width: 16),
                  Icon(YoinSymbols.search, color: colors.onSurfaceVariant),
                  const SizedBox(width: 16),
                  Expanded(
                    // A query kept from a result opened in the detail column
                    // shows here, so the search is visibly one tap away.
                    child: Consumer(
                      builder: (context, ref, _) {
                        final kept = ref.watch(librarySearchProvider.select((st) => st.query.trim()));
                        return Text(
                          kept.isEmpty ? strings.searchPlaceholder : kept,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: text.bodyLarge?.copyWith(
                            color: kept.isEmpty ? colors.onSurfaceVariant : colors.onSurface,
                          ),
                        );
                      },
                    ),
                  ),
                  IconButton(
                    tooltip: strings.cdSettings,
                    icon: Icon(YoinSymbols.settings, color: colors.onSurfaceVariant),
                    onPressed: onSettings,
                  ),
                  const SizedBox(width: 4),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Content extends ConsumerWidget {
  const _Content({required this.state, required this.scrolls});

  final LibraryContent state;
  final Map<LibraryTab, ScrollController> scrolls;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final vm = ref.read(libraryViewModelProvider.notifier);
    final strings = LibraryStrings.of(context);
    final tab = state.selected;
    final canAddPlaylists = playlistAbilities(ref.watch(activeSourceProvider).value).canAdd;
    final colors = Theme.of(context).colorScheme;
    return Column(
      children: [
        LibraryFilterRow(
          tabs: state.availableTabs,
          selected: tab,
          onSelect: vm.selectTab,
          byYou: state.playlistsByYou,
          onByYouChange: vm.selectPlaylistsByYou,
        ),
        const SizedBox(height: 4),
        Expanded(
          // Items dissolve (artwork) and fade (text) into the chips above
          // instead of being cut at the views' top edge, in the user's style.
          child: ChromeSeam(
            background: SeamBackground([colors.surfaceContainer, colors.surface, colors.surfaceContainerLow]),
            // Artwork near the bar opens into the bottom field, in every tab.
            field: true,
            child: YoinPullToRefresh(
              refreshing: state.isRefreshing,
              onRefresh: vm.refresh,
              child: SpringCrossFade(
                childKey: tab,
                child: switch (tab) {
                  LibraryTab.all => LibraryGridView(
                    tab: tab,
                    view: state.sorted.all,
                    sort: state.sortOf(tab),
                    controller: scrolls[tab]! as LibraryGridScrollController,
                    emptyText: strings.emptyAll,
                    loading: !state.allSettled,
                  ),
                  LibraryTab.artists || LibraryTab.albums => LibraryGridView(
                    tab: tab,
                    view: state.sorted.of(tab),
                    sort: state.sortOf(tab),
                    controller: scrolls[tab]! as LibraryGridScrollController,
                    emptyText: tab == LibraryTab.artists ? strings.emptyArtists : strings.emptyAlbums,
                  ),
                  LibraryTab.playlists => Stack(
                    fit: StackFit.expand,
                    children: [
                      LibraryPlaylistsView(
                        view: state.shownPlaylists,
                        sort: state.sortOf(tab),
                        controller: scrolls[tab]!,
                      ),
                      if (canAddPlaylists && state.shownPlaylists != null)
                        PositionedDirectional(
                          end: PlaylistFabTokens.endMargin,
                          bottom: libraryBottomPadding(context),
                          child: NewPlaylistFab(controller: scrolls[tab]!),
                        ),
                    ],
                  ),
                  LibraryTab.songs => LibrarySongsView(
                    songs: state.songs,
                    more: state.songsMore,
                    controller: scrolls[tab]!,
                  ),
                  LibraryTab.favorites => LibraryFavoritesView(favorites: state.favorites, controller: scrolls[tab]!),
                },
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ConnectPrompt extends StatelessWidget {
  const _ConnectPrompt();

  @override
  Widget build(BuildContext context) {
    final strings = YoinStrings.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('Subsonic', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          FilledButton.tonalIcon(
            style: FilledButton.styleFrom(shape: const StadiumBorder(), minimumSize: const Size(0, 56)),
            icon: const Icon(YoinSymbols.add),
            label: Text(strings.setupConnect),
            onPressed: () => openAddAccount(context),
          ),
        ],
      ),
    );
  }
}

/// The page error: message (bodyLarge, error) + Retry / Settings, then
/// Copy details (LibraryScreen.kt Error).
class _ErrorPrompt extends ConsumerWidget {
  const _ErrorPrompt({required this.error, required this.diagnostics, required this.onSettings});

  final Object error;
  final String diagnostics;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final message = error is AccountNeedsReconnectError
        ? strings.accountReconnectNeeded
        : userMessageText(context, error, strings.errorLoadLibrary);
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.error),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextButton(
                  onPressed: () => ref.read(libraryViewModelProvider.notifier).retry(),
                  child: Text(strings.errorRetry),
                ),
                const SizedBox(width: 12),
                TextButton(onPressed: onSettings, child: Text(strings.errorSettings)),
              ],
            ),
            const SizedBox(height: 8),
            CopyDiagnosticsButton(report: diagnostics),
          ],
        ),
      ),
    );
  }
}
