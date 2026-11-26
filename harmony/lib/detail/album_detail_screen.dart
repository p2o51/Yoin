import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/release/release_marks.dart';
import '../data/offline/offline_model.dart';
import '../data/source/music_source.dart';
import '../library/add_to_playlist_flow.dart';
import '../library/playlist_writes.dart';
import '../offline/offline_toggle.dart';
import '../shell/detail_chrome.dart';
import '../theme/tokens.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/user_message.dart';
import '../ui/navigation/pane/detail_pane.dart';
import 'album_detail_view_model.dart';
import 'album_hero_marks.dart';
import 'album_release_date.dart';
import 'album_scrapbook_page.dart';
import 'detail_layout.dart';
import 'detail_listening.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_routes.dart';
import 'detail_share.dart';
import 'detail_strings.dart';
import 'detail_track_menu.dart';
import 'detail_track_rows.dart';
import 'flowing_titles.dart';
import 'release_kind_labels.dart';
import 'pull_up_reveal.dart';
import 'reshape_scaffold.dart';
import 'scrapbook_focus.dart';

/// The Album page (PushPage; Android AlbumDetailScreen, Compact): page wash
/// from the cover, header, hero cover + flowing titles that pull up into
/// the track list. No Play button on the page: Play / Shuffle live in the
/// bar's detail pose through [DetailChromeRegistration].
class AlbumDetailScreen extends ConsumerStatefulWidget {
  const AlbumDetailScreen({super.key, required this.albumId});

  final MediaId albumId;

  @override
  ConsumerState<AlbumDetailScreen> createState() => _AlbumDetailScreenState();
}

class _AlbumDetailScreenState extends ConsumerState<AlbumDetailScreen> {
  /// "View in Scrapbook": the song page 2 brings into view; cleared once it has.
  final _scrapbookFocus = ValueNotifier<ScrapbookFocus?>(null);

  MediaId get albumId => widget.albumId;

  @override
  void dispose() {
    _scrapbookFocus.dispose();
    super.dispose();
  }

  /// Page 2, with [songId] brought into view there ([context] is under the pager).
  void _viewInScrapbook(BuildContext context, ScrapbookFocus focus) {
    // The sheet's row runs after the sheet has gone: the page (or that row) may have gone too.
    if (!mounted || !context.mounted) return;
    _scrapbookFocus.value = focus;
    ReshapePager.read(context)?.openPage(1);
  }

  @override
  Widget build(BuildContext context) {
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
            // The name alone: the version is its own tag, Apple Music's " - EP" / " - Single" the kind below.
            title: album == null
                ? ''
                : ReleaseMarks.nameWithoutKindSuffix(
                    albumTitleWithoutVersion(album.name, album.version),
                    album.releaseType,
                  ),
            titleTrailing: album?.version == null
                ? null
                : AlbumVersionTag(album!.version!, style: Theme.of(context).textTheme.labelMedium),
            titleExplicit: album?.isExplicit ?? false,
            meta: [
              if (album?.artist?.trim().isNotEmpty ?? false) MetaPlain(album!.artist!),
              // The real kind (EP / Single / Compilation) as the service says; Album when unknown.
              if (album != null)
                MetaKind(
                  album.releaseType == null
                      ? strings.albumKind
                      : releaseKindLabel(strings, ReleaseMarks.headerKind(album.releaseType)),
                ),
              // The date at the service's precision in place of the year ("22.6.12", "22.6", "2022").
              if (album != null)
                if (albumHeaderDate(album.parsedReleaseDate, album.year) case final date?) MetaPlain(date),
            ],
            stateView: switch (state) {
              AlbumDetailLoading() => const DetailLoadingView(),
              AlbumDetailError(:final notFound, :final cause, :final diagnostics) => DetailErrorView(
                message: notFound ? strings.albumNotFound : userMessageText(context, cause, strings.albumLoadError),
                diagnostics: notFound ? null : diagnostics,
                onRetry: () => ref.read(albumDetailProvider(albumId).notifier).retry(),
              ),
              AlbumDetailContent() => null,
            },
            content: state is AlbumDetailContent ? _content(context, state, coverUrl) : null,
          ),
        ),
      ),
    );
  }

  ReshapeContent _content(BuildContext context, AlbumDetailContent state, String? coverUrl) {
    final strings = DetailStrings.of(context);
    final tracks = state.tracks;
    void playFrom(int index) =>
        playFromDetail(DetailPlayer.widget(ref), tracks, startIndex: index, context: albumPlaybackContext(state.album));
    final multiDisc = state.isMultiDisc;
    final canFavorite = ref.read(activeSourceProvider).value?.capabilities.contains(Capability.favorites) ?? false;
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
        Builder(
          key: ValueKey('${track.id}#$i'),
          builder: (rowContext) => AlbumTrackRow(
            track: track,
            index: i,
            featArtist: state.featArtistOf(track),
            showHeart: canFavorite,
            onTap: () => playFrom(i),
            onLongPress: () => openTrackMenu(
              rowContext,
              track,
              onAlbumPage: true,
              onViewInScrapbook: (id) => _viewInScrapbook(rowContext, ScrapbookFocus(id, DateTime.now())),
            ),
          ),
        ),
      );
    }
    // The release date closes the list, as precisely as the service dates it: just the date, no label.
    if (state.album.parsedReleaseDate case final date? when tracks.isNotEmpty) {
      list.add(AlbumReleaseDateLine(date, key: const ValueKey('album-release-date')));
    }
    final albumName = albumTitleWithoutVersion(state.album.name, state.album.version);
    final titles = [
      for (final t in tracks)
        FlowingTitle(
          t.title ?? '',
          feat: switch (state.featArtistOf(t)) {
            final a? => strings.albumFeat(a),
            null => null,
          },
        ),
    ];
    // In the detail column the page's sides are not screen edges: the blocks hug the cover whole
    // instead of bleeding off the sides (albumBarGeometry pageEdgesAreScreenEdges).
    final inColumn = DetailPane.isInColumn(context);
    return ReshapeContent(
      coverUrl: coverUrl,
      fallbackIcon: YoinSymbols.album,
      listChildren: list,
      // The album's family mark behind the cover (owner 2026-11-06: one mark, three readings).
      backdrop: (cover, leaving) => inColumn
          ? Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned.fromRect(
                  rect: albumHuggingMark(cover),
                  child: AlbumButterfly(leaving: leaving, hugging: true),
                ),
              ],
            )
          : AlbumButterfly(leaving: leaving),
      // Short portrait, landscape handset, the Medium overview and the Wide identity column.
      // The cover forms the stamp everywhere but Wide; no butterfly on Short or tablets
      // (owner 2026-11-07, 2026-11-14) — only the landscape hero keeps it, hugging the cover.
      overviews: ReshapeOverviews(
        hasShort: true,
        barLayouts: const {DetailLayout.compact, DetailLayout.short, DetailLayout.landscape, DetailLayout.medium},
        meta: (layout, interactive) => AlbumHeroMetaBlocks(
          albumId: state.album.id,
          albumName: albumName,
          coverUrl: coverUrl,
          stacked: layout == DetailLayout.short,
          interactive: interactive,
        ),
        titles: (layout, links) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (tracks.isNotEmpty) ...[
              TrackCountLine(count: state.trackCount, totalSec: state.totalDurationSec),
              const SizedBox(height: 6),
            ],
            FlowingTitles(
              enabled: links,
              emptyText: albumName,
              style: layout == DetailLayout.landscape ? Theme.of(context).textTheme.headlineSmall : null,
              titles: titles,
              onTap: playFrom,
            ),
          ],
        ),
        huggingMark: (leaving) => AlbumButterfly(leaving: leaving, hugging: true),
        // View in Scrapbook from elsewhere, or Now Playing's Manage in Scrapbook: this page opened for it.
        attached: Builder(
          builder: (takerContext) => ScrapbookFocusTaker(
            songIds: [albumId, for (final t in tracks) t.id],
            onTake: (focus) => _viewInScrapbook(takerContext, focus),
          ),
        ),
      ),
      // Page 2: the scrapbook of what the listener left on this album.
      secondPage: (context, page) =>
          AlbumScrapbookPage(album: state.album, coverUrl: coverUrl, page: page, focus: _scrapbookFocus),
      heroBelow: (links) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // View in Scrapbook from a song sheet elsewhere, or Now Playing's
          // Manage in Scrapbook: this page opened for it.
          Builder(
            builder: (takerContext) => ScrapbookFocusTaker(
              songIds: [albumId, for (final t in tracks) t.id],
              onTake: (focus) => _viewInScrapbook(takerContext, focus),
            ),
          ),
          // Score emblem + comment pen, the cover's width (AlbumHeroDetails, Compact).
          LayoutBuilder(
            builder: (context, constraints) => Padding(
              padding: const EdgeInsets.only(bottom: AlbumHeroTokens.metaBottom),
              child: AlbumHeroMetaRow(
                // The hero column is inset 16 each side; the cover is sized off the page width.
                width: PullUpMath.heroCoverSide(
                  constraints.maxWidth + 32,
                  fraction: DetailTokens.albumCoverWidthFraction,
                  max: DetailTokens.albumCoverMax,
                ),
                trackCount: state.trackCount,
                albumId: state.album.id,
                albumName: albumTitleWithoutVersion(state.album.name, state.album.version),
                coverUrl: coverUrl,
              ),
            ),
          ),
          if (tracks.isNotEmpty) ...[
            // Total count + runtime, then when it was last played (owner 2026-10-29).
            AlbumHeroCountLine(albumId: state.album.id, count: state.trackCount, totalSec: state.totalDurationSec),
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
      menuItems: loaded == null ? const [] : albumMenuItems(context, ref, loaded),
      playContainer: palette?.accent,
      playContent: palette?.onAccent,
    );
    return DetailChromeRegistration(actions: actions, child: child);
  }
}

/// The Album page's ▾ rows after Shuffle play (AlbumDetailActivity
/// rememberDetailMenu + promotable, which phones show as menu rows): Play
/// next, Add to queue, Add to playlist (the whole album; an account that
/// files songs), Download (an account that downloads; OfflineToggle), Open in
/// Apple Music (once the album's public page is known), then Go to artist
/// when the album names one, and Share.
List<DetailMenuAction> albumMenuItems(BuildContext context, WidgetRef ref, AlbumDetailContent state) {
  final strings = DetailStrings.of(context);
  void message(String text) {
    if (context.mounted) showDetailMessage(context, text);
  }

  void queue({required bool next}) {
    final player = DetailPlayer.widget(ref);
    queueFromDetail(player, () async => state.tracks, next: next).then((count) {
      if (count > 0) message(next ? strings.menuPlayNextCount(count) : strings.menuQueued(count));
    });
  }

  final album = state.album;
  final artistId = album.artistId;
  final offline = watchOfflineToggle(
    ref,
    context,
    collection: OfflineCollection.album(album),
    tracks: () async => state.tracks,
    onMessage: message,
  );
  final canAdd = playlistAbilities(ref.watch(activeSourceProvider).value).canAdd && state.tracks.isNotEmpty;
  final link = ref.watch(detailWebLinkProvider((WebLinkKind.album, album.id))).value;
  final openIn = openInLabel(link, strings);
  final container = ProviderScope.containerOf(context, listen: false);
  return [
    DetailMenuAction(icon: YoinSymbols.skipNext, label: strings.menuPlayNext, onTap: () => queue(next: true)),
    DetailMenuAction(icon: YoinSymbols.queue, label: strings.menuAddToQueue, onTap: () => queue(next: false)),
    if (canAdd)
      DetailMenuAction(
        icon: YoinSymbols.playlist,
        label: strings.menuAddToPlaylist,
        onTap: () {
          if (context.mounted) addToPlaylistFlow(context, state.tracks, onMessage: message);
        },
      ),
    if (offline != null) DetailMenuAction(icon: offline.icon, label: offline.label, onTap: offline.onTap),
    if ((link, openIn) case (final url?, final label?))
      DetailMenuAction(icon: YoinSymbols.launch, label: label, onTap: () => openDetailLink(container, url)),
    if (artistId != null)
      DetailMenuAction(
        icon: YoinSymbols.artist,
        label: strings.albumGoToArtist,
        promotable: true,
        onTap: () => DetailRoutes.openArtist(context, artistId, name: album.artist),
      ),
    DetailMenuAction(
      icon: YoinSymbols.share,
      label: strings.share,
      promotable: true,
      onTap: () => DetailShare.share(
        container,
        kind: WebLinkKind.album,
        id: album.id,
        title: switch (album.artist?.trim()) {
          final artist? when artist.isNotEmpty => strings.shareAlbumTitle(album.name, artist),
          _ => album.name.trim().isEmpty ? strings.shareAlbum : album.name,
        },
        webLink: link,
        linkFailedMessage: strings.shareLinkFailed,
      ),
    ),
  ];
}

/// The hero's track-count line with Last Play after it ("Yesterday 12:02",
/// AlbumHeroLastPlay): caption-less, in the line's full ink, 12 after the
/// runtime (tap: the scrapbook). Never played, the line is the count alone.
class AlbumHeroCountLine extends ConsumerWidget {
  const AlbumHeroCountLine({super.key, required this.albumId, required this.count, required this.totalSec});

  final MediaId albumId;
  final int count;
  final int totalSec;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final at = ref.watch(albumLastPlayedProvider(albumId));
    final label = at == null ? null : lastPlayLabels(at, DetailStrings.of(context));
    final line = TrackCountLine(
      count: count,
      totalSec: totalSec,
      trailing: label == null
          ? null
          : MetaKind('${label.$1} ${label.$2}', color: Theme.of(context).colorScheme.onSurface),
    );
    // Tapping it opens the scrapbook page, where Last Play reads in full.
    final pager = ReshapePager.maybeOf(context);
    if (label == null || pager == null) return line;
    return GestureDetector(behavior: HitTestBehavior.opaque, onTap: () => pager.openPage(1), child: line);
  }
}
