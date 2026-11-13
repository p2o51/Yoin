import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../shell/detail_chrome.dart';
import '../ui/component/meta_line.dart';
import 'album_detail_view_model.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_strings.dart';
import 'detail_track_rows.dart';
import 'flowing_titles.dart';
import 'reshape_scaffold.dart';

/// The Album page (PushPage; Android AlbumDetailScreen, Compact): page wash
/// from the cover, header, hero cover + flowing titles that pull up into
/// the track list. No Play button on the page: Play / Shuffle live in the
/// bar's detail pose through [DetailChromeRegistration].
class AlbumDetailScreen extends ConsumerWidget {
  const AlbumDetailScreen({super.key, required this.albumId});

  final MediaId albumId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(albumDetailProvider(albumId));
    final source = ref.watch(activeSourceProvider).value;
    final strings = DetailStrings.of(context);
    final album = state is AlbumDetailContent ? state.album : null;
    final cover = album?.coverArt;
    final coverUrl = cover == null ? null : source?.resolveCoverUrl(cover);
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: DetailPalette(
        coverUrl: coverUrl,
        child: _AlbumChrome(
          state: state,
          child: ReshapeDetailScaffold(
            title: album == null ? '' : albumTitleWithoutVersion(album.name, album.version),
            titleTrailing: album?.version == null
                ? null
                : AlbumVersionTag(album!.version!, style: Theme.of(context).textTheme.labelMedium),
            meta: [
              if (album?.artist?.trim().isNotEmpty ?? false) MetaPlain(album!.artist!),
              if (album != null) MetaKind(strings.albumKind),
              if (album?.year != null) MetaPlain('${album!.year}'),
            ],
            stateView: switch (state) {
              AlbumDetailLoading() => const DetailLoadingView(),
              AlbumDetailError(:final notFound) => DetailErrorView(
                message: notFound ? strings.albumNotFound : strings.albumLoadError,
                onRetry: () => ref.read(albumDetailProvider(albumId).notifier).retry(),
              ),
              AlbumDetailContent() => null,
            },
            content: state is AlbumDetailContent ? _content(context, ref, state, coverUrl) : null,
          ),
        ),
      ),
    );
  }

  ReshapeContent _content(BuildContext context, WidgetRef ref, AlbumDetailContent state, String? coverUrl) {
    final strings = DetailStrings.of(context);
    final tracks = state.tracks;
    void playFrom(int index) =>
        playFromDetail(DetailPlayer.widget(ref), tracks, startIndex: index, context: albumPlaybackContext(state.album));
    final multiDisc = state.isMultiDisc;
    final canFavorite =
        ref.read(activeSourceProvider).value?.capabilities.contains(Capability.favorites) ?? false;
    final list = <Widget>[
      Padding(
        padding: const EdgeInsetsDirectional.only(start: 8, top: 2, bottom: 10),
        child: TrackCountLine(
          count: state.trackCount,
          totalSec: state.totalDurationSec,
          showDuration: tracks.isNotEmpty,
        ),
      ),
    ];
    int? disc;
    for (final (i, track) in tracks.indexed) {
      if (multiDisc && track.discNumber != null && track.discNumber != disc) {
        list.add(DiscHeading(track.discNumber!, first: disc == null, key: ValueKey('disc-${track.discNumber}')));
        disc = track.discNumber;
      }
      list.add(
        AlbumTrackRow(
          key: ValueKey('${track.id}#$i'),
          track: track,
          index: i,
          featArtist: state.featArtistOf(track),
          showHeart: canFavorite,
          onTap: () => playFrom(i),
        ),
      );
    }
    return ReshapeContent(
      coverUrl: coverUrl,
      fallbackIcon: YoinSymbols.album,
      listChildren: list,
      heroBelow: (links) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (tracks.isNotEmpty) ...[
            TrackCountLine(count: state.trackCount, totalSec: state.totalDurationSec),
            const SizedBox(height: 6),
          ],
          FlowingTitles(
            enabled: links,
            emptyText: albumTitleWithoutVersion(state.album.name, state.album.version),
            titles: [
              for (final t in tracks)
                FlowingTitle(
                  t.title ?? '',
                  feat: switch (state.featArtistOf(t)) {
                    final a? => strings.albumFeat(a),
                    null => null,
                  },
                ),
            ],
            onTap: playFrom,
          ),
        ],
      ),
    );
  }
}

/// Publishes the page's Play / Shuffle to the bar (no-ops until tracks load),
/// coloured with the cover scheme.
class _AlbumChrome extends ConsumerWidget {
  const _AlbumChrome({required this.state, required this.child});

  final AlbumDetailState state;
  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = this.state;
    final palette = DetailPaletteScope.maybeOf(context)?.target;
    // Present in every state (the bar keeps its detail pose); Play no-ops until tracks arrive.
    final loaded = state is AlbumDetailContent && state.tracks.isNotEmpty ? state : null;
    final actions = DetailPlayActions(
      onPlay: () {
        if (loaded != null) {
          playFromDetail(DetailPlayer.widget(ref), loaded.tracks, context: albumPlaybackContext(loaded.album));
        }
      },
      onShuffle: () {
        if (loaded != null) {
          playFromDetail(
            DetailPlayer.widget(ref),
            loaded.tracks,
            shuffle: true,
            context: albumPlaybackContext(loaded.album),
          );
        }
      },
      playContainer: palette?.accent,
      playContent: palette?.onAccent,
    );
    return DetailChromeRegistration(actions: actions, child: child);
  }
}
