import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../l10n/strings.dart';
import '../settings/subsonic_setup_screen.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'library_view_model.dart';

/// Library root. Framework proof for now: albums A–Z from the active source,
/// paged; the full Library (filters, sort, search, artists, playlists) is a
/// later feature task.
class LibraryScreen extends ConsumerWidget {
  const LibraryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(libraryViewModelProvider);
    final textTheme = Theme.of(context).textTheme;
    return SafeArea(
      bottom: false,
      child: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
            sliver: SliverToBoxAdapter(
              child: Text(YoinStrings.of(context).barLabelLibrary, style: textTheme.headlineLarge),
            ),
          ),
          ...switch (state) {
            LibraryLoading() => [const SliverFillRemaining(child: Center(child: CircularProgressIndicator()))],
            LibraryNoAccount() => [const SliverFillRemaining(hasScrollBody: false, child: _ConnectPrompt())],
            LibraryError(:final error) => [SliverFillRemaining(hasScrollBody: false, child: _ErrorPrompt(error: error))],
            LibraryContent() => [_AlbumGrid(state: state)],
          },
          const SliverToBoxAdapter(child: SizedBox(height: LibraryTokens.bottomGroupPadding)),
        ],
      ),
    );
  }
}

class _AlbumGrid extends ConsumerWidget {
  const _AlbumGrid({required this.state});

  final LibraryContent state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final width = YoinWindowInfo.of(context).width;
    const side = 16.0;
    final columns = ((width - side * 2 + LibraryTokens.gridGutterH) / (120 + LibraryTokens.gridGutterH)).floor().clamp(2, 8);
    final source = ref.watch(activeSourceProvider).value;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: side),
      sliver: SliverGrid.builder(
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: columns,
          crossAxisSpacing: LibraryTokens.gridGutterH,
          mainAxisSpacing: LibraryTokens.gridGutterV,
          childAspectRatio: 0.72,
        ),
        itemCount: state.albums.length,
        itemBuilder: (context, index) {
          if (index >= state.albums.length - columns * 2 && state.canLoadMore) {
            WidgetsBinding.instance.addPostFrameCallback(
              (_) => ref.read(libraryViewModelProvider.notifier).loadMore(),
            );
          }
          return _AlbumTile(album: state.albums[index], source: source);
        },
      ),
    );
  }
}

class _AlbumTile extends StatelessWidget {
  const _AlbumTile({required this.album, required this.source});

  final Album album;
  final MusicSource? source;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final cover = album.coverArt;
    final url = cover == null ? null : source?.resolveCoverUrl(cover, size: 400);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AspectRatio(
          aspectRatio: 1,
          child: ClipRSuperellipse(
            borderRadius: BorderRadius.circular(ShapeTokens.cover),
            child: ColoredBox(
              color: colors.surfaceContainerHigh,
              child: url == null
                  ? null
                  : Image.network(
                      url,
                      fit: BoxFit.cover,
                      cacheWidth: (160 * dpr).round(),
                      errorBuilder: (_, _, _) => const SizedBox.expand(),
                    ),
            ),
          ),
        ),
        const SizedBox(height: LibraryTokens.coverToTitle),
        Text(album.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: text.titleSmall),
        if (album.artist != null)
          Text(
            album.artist!,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
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
            onPressed: () =>
                Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const SubsonicSetupScreen())),
          ),
        ],
      ),
    );
  }
}

class _ErrorPrompt extends ConsumerWidget {
  const _ErrorPrompt({required this.error});

  final Object error;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = YoinStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            error is SourceNetworkException ? strings.setupUnreachable : strings.setupServerError,
            style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          IconButton.filledTonal(
            icon: const Icon(YoinSymbols.refresh),
            onPressed: () => ref.read(libraryViewModelProvider.notifier).retry(),
          ),
        ],
      ),
    );
  }
}
