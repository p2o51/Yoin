import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/source/music_source.dart';
import '../l10n/strings.dart';
import '../settings/settings_screen.dart';
import '../shell/shell_state.dart';
import '../shell/yoin_section.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/component/yoin_pull_to_refresh.dart';
import 'library_filter_row.dart';
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
  /// view lands where the user left it.
  final _scrolls = {for (final tab in LibraryTab.values) tab: ScrollController()};

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
    ref.listen(libraryViewModelProvider.select((s) => s is LibraryContent ? s.notice : null), (previous, notice) {
      if (notice == null || notice.serial == previous?.serial) return;
      final strings = LibraryStrings.of(context);
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(_tabError(strings, notice.tab))));
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
            _SearchHeader(onSettings: _openSettings),
            Expanded(
              child: SpringCrossFade(
                childKey: state.runtimeType,
                child: switch (state) {
                  LibraryLoading() => const Center(child: YoinLoadingIndicator()),
                  LibraryNoAccount() => const _ConnectPrompt(),
                  LibraryError(:final error) => _ErrorPrompt(error: error, onSettings: _openSettings),
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
  const _SearchHeader({required this.onSettings});

  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
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
                    child: Text(
                      strings.searchPlaceholder,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: text.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
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
    return Column(
      children: [
        LibraryFilterRow(tabs: state.availableTabs, selected: tab, onSelect: vm.selectTab),
        const SizedBox(height: 4),
        Expanded(
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
                  controller: scrolls[tab]!,
                  emptyText: strings.emptyAll,
                  loading: !state.allSettled,
                ),
                LibraryTab.artists || LibraryTab.albums => LibraryGridView(
                  tab: tab,
                  view: state.sorted.of(tab),
                  sort: state.sortOf(tab),
                  controller: scrolls[tab]!,
                  emptyText: tab == LibraryTab.artists ? strings.emptyArtists : strings.emptyAlbums,
                ),
                LibraryTab.playlists => Stack(
                  fit: StackFit.expand,
                  children: [
                    LibraryPlaylistsView(
                      view: state.sorted.playlists,
                      sort: state.sortOf(tab),
                      controller: scrolls[tab]!,
                    ),
                    if (canAddPlaylists && state.sorted.playlists != null)
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

/// The page error: message (bodyLarge, error) + Retry / Settings.
class _ErrorPrompt extends ConsumerWidget {
  const _ErrorPrompt({required this.error, required this.onSettings});

  final Object error;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = LibraryStrings.of(context);
    final yoin = YoinStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final message = switch (error) {
      SourceNetworkException() => yoin.setupUnreachable,
      SourceServerException() || SourceAuthException() => yoin.setupServerError,
      _ => strings.errorLoadLibrary,
    };
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
          ],
        ),
      ),
    );
  }
}
