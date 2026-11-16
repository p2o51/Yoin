import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/offline/offline_model.dart';
import '../data/source/music_source.dart';
import '../library/playlist_writes.dart';
import '../ui/component/error_strings.dart';
import '../offline/offline_toggle.dart';
import '../settings/backup/playlist_backup_strings.dart';
import '../shell/detail_chrome.dart';
import '../ui/component/add_to_playlist_sheet.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/user_message.dart';
import '../ui/component/yoin_dialog.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_share.dart';
import 'detail_strings.dart';
import 'detail_track_menu.dart';
import 'detail_track_rows.dart';
import 'flowing_titles.dart';
import 'playlist_backup_action.dart';
import 'playlist_detail_view_model.dart';
import 'playlist_row_presence.dart';
import 'playlist_sleeves.dart';
import 'reshape_scaffold.dart';

/// Rows and sleeves share one cover size so the image cache is shared.
const _rowCoverSize = 320;

/// The Playlist page (PushPage; Android PlaylistDetailScreen, Compact): the
/// Album shell with sleeves behind the hero cover, a Length / Owner block
/// and the flowing titles; the list rows lead with album thumbnails. A row's
/// sheet offers Remove from playlist where the account can edit it; the row
/// leaves at once (and comes back if the service refuses).
class PlaylistDetailScreen extends ConsumerStatefulWidget {
  const PlaylistDetailScreen({super.key, required this.playlistId});

  final MediaId playlistId;

  @override
  ConsumerState<PlaylistDetailScreen> createState() => _PlaylistDetailScreenState();
}

class _PlaylistDetailScreenState extends ConsumerState<PlaylistDetailScreen> {
  /// The list's rows coming and going (a removal, a refusal bringing one back).
  late final _rows = RowPresenceBook(
    onChanged: () {
      if (mounted) setState(() {});
    },
  );

  MediaId get playlistId => widget.playlistId;

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(playlistDetailProvider(playlistId));
    final source = ref.watch(activeSourceProvider).value;
    final strings = DetailStrings.of(context);
    final content = state is PlaylistDetailContent ? state : null;
    final hero = content?.heroCover;
    final heroUrl = hero == null ? null : source?.resolveCoverUrl(hero);
    final playlist = content?.playlist;
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: DetailPalette(
        coverUrl: heroUrl,
        child: _PlaylistChrome(
          content: content,
          child: ReshapeDetailScaffold(
            title: playlist?.name ?? '',
            headerEndPadding: DetailHeaderMetrics.paddingEnd,
            meta: [
              if (playlist?.owner?.trim().isNotEmpty ?? false) MetaPlain(playlist!.owner!),
              if (playlist != null) MetaKind(strings.playlistKind),
            ],
            stateView: switch (state) {
              PlaylistDetailLoading() => const DetailLoadingView(),
              PlaylistDetailError(:final notFound, :final cause, :final diagnostics) => DetailErrorView(
                message: notFound ? strings.playlistNotFound : userMessageText(context, cause, strings.playlistLoadError),
                diagnostics: notFound ? null : diagnostics,
                onRetry: () => ref.read(playlistDetailProvider(playlistId).notifier).retry(),
              ),
              PlaylistDetailContent() => null,
            },
            content: content == null ? null : _content(context, content, heroUrl),
          ),
        ),
      ),
    );
  }

  ReshapeContent _content(BuildContext context, PlaylistDetailContent state, String? heroUrl) {
    final strings = DetailStrings.of(context);
    final source = ref.read(activeSourceProvider).value;
    String? rowUrl(Track t) {
      final cover = t.coverArt ?? (t.albumId == null ? null : CoverSourceRelative(t.albumId!.rawId));
      return cover == null ? null : source?.resolveCoverUrl(cover, size: _rowCoverSize);
    }

    final playContext = playlistPlaybackContext(state.playlist, state.contextCover);
    void playFrom(int index) =>
        playFromDetail(DetailPlayer.widget(ref), state.tracks, startIndex: index, context: playContext);
    final sleeveUrls = [
      for (final c in playlistSleeveCovers(state.playlist)) source?.resolveCoverUrl(c, size: _rowCoverSize),
    ];
    final emptyStyle = Theme.of(
      context,
    ).textTheme.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    return ReshapeContent(
      coverUrl: heroUrl,
      fallbackIcon: YoinSymbols.playlist,
      backdrop: sleeveUrls.isEmpty
          ? null
          : (cover, leaving) =>
                PlaylistSleeves(urls: sleeveUrls, cover: cover, leaving: leaving, corner: playlistSleeveCorner),
      // Keyed by the entry (a song can be in the playlist twice): a row removed
      // from the playlist fades out and the rows under it move up. The count
      // and the empty line trade places when the last song goes (or one comes back).
      listChildren: _rows.layOut([
        if (state.entries.isNotEmpty)
          PresenceRow(
            const ValueKey('playlist-count'),
            Padding(
              padding: const EdgeInsetsDirectional.only(start: 8, top: 2, bottom: 10),
              child: TrackCountLine(count: state.trackCount, totalSec: state.totalDurationSec),
            ),
          )
        else
          PresenceRow(
            const ValueKey('playlist-empty'),
            Padding(
              padding: const EdgeInsetsDirectional.only(start: 8, top: 8),
              child: Text(strings.playlistEmpty, style: emptyStyle),
            ),
          ),
        for (final (i, e) in state.entries.indexed)
          PresenceRow(
            ValueKey(e.rowKey),
            PlaylistTrackRow(
              track: e.track,
              coverUrl: rowUrl(e.track),
              onTap: () => playFrom(i),
              onLongPress: () => openTrackMenu(
                context,
                e.track,
                // The sheet is about this row: a song can be in the playlist twice.
                onRemoveFromPlaylist: state.canRemoveSongs
                    ? () => ref
                          .read(playlistDetailProvider(playlistId).notifier)
                          .removeTrack(e.rowKey, playlistEditLines(context))
                    : null,
              ),
            ),
          ),
      ]),
      heroBelow: (links) => state.entries.isEmpty
          ? Center(child: Text(strings.playlistEmpty, style: emptyStyle))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _HeroMeta(state: state),
                const SizedBox(height: 28),
                FlowingTitles(
                  enabled: links,
                  titles: [for (final t in state.tracks) FlowingTitle(t.title ?? '')],
                  onTap: playFrom,
                ),
              ],
            ),
    );
  }
}

/// Length | Owner (+ description), column spacing 14 (PlaylistHeroMeta).
class _HeroMeta extends StatelessWidget {
  const _HeroMeta({required this.state});

  final PlaylistDetailContent state;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final caption = text.labelMedium?.copyWith(color: colors.onSurfaceVariant);
    const tabular = [FontFeature.tabularFigures()];
    final total = state.totalDurationSec;
    final h = total ~/ 3600, m = (total % 3600) ~/ 60;
    // Songs without durations leave the line out rather than show "–" under a count that is there.
    final duration = total > 0
        ? (h > 0 ? strings.durationHoursMinutes(h, m) : strings.durationMinutes(m))
        : (state.trackCount == 0 ? '–' : null);
    final owner = state.playlist.owner?.trim();
    final comment = state.playlist.comment?.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(strings.playlistLength, style: caption),
                Text(
                  strings.playlistTracks(state.trackCount),
                  style: text.bodyLarge?.copyWith(color: colors.onSurface, fontFeatures: tabular),
                ),
                if (duration != null)
                  Text(
                    duration,
                    style: text.bodyMedium?.copyWith(color: colors.onSurfaceVariant, fontFeatures: tabular),
                  ),
              ],
            ),
            if (owner != null && owner.isNotEmpty) ...[
              const SizedBox(width: 16),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(strings.playlistOwner, style: caption),
                    Text(owner, maxLines: 1, overflow: TextOverflow.ellipsis, style: text.bodyLarge),
                  ],
                ),
              ),
            ],
          ],
        ),
        if (comment != null && comment.isNotEmpty) ...[
          const SizedBox(height: 14),
          Text(strings.playlistDescription, style: caption),
          Text(comment, maxLines: 3, overflow: TextOverflow.ellipsis, style: text.bodyMedium),
        ],
      ],
    );
  }
}

class _PlaylistChrome extends ConsumerWidget {
  const _PlaylistChrome({required this.content, required this.child});

  final PlaylistDetailContent? content;
  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final palette = DetailPaletteScope.maybeOf(context)?.target;
    final loaded = content != null && content!.tracks.isNotEmpty ? content : null;
    void play({required bool shuffle}) {
      if (loaded == null) return;
      playFromDetail(
        DetailPlayer.widget(ref),
        loaded.tracks,
        shuffle: shuffle,
        context: playlistPlaybackContext(loaded.playlist, loaded.contextCover),
      );
    }

    return DetailChromeRegistration(
      actions: DetailPlayActions(
        onPlay: () => play(shuffle: false),
        onShuffle: () => play(shuffle: true),
        menuItems: content == null ? const [] : playlistMenuItems(context, ref, content!),
        playContainer: palette?.accent,
        playContent: palette?.onAccent,
      ),
      child: child,
    );
  }
}

/// The Playlist page's ▾ rows (PlaylistDetailScreen overflow + promotable):
/// Download (an account that downloads; OfflineToggle), Cloud backup, then
/// Rename and Delete, for a playlist this account owns on a service that can
/// change it, then Share (the playlist's public page, or a share link the
/// server makes).
List<DetailMenuAction> playlistMenuItems(BuildContext context, WidgetRef ref, PlaylistDetailContent state) {
  final detailStrings = DetailStrings.of(context);
  final link = ref.watch(detailWebLinkProvider((WebLinkKind.playlist, state.playlist.id))).value;
  final container = ProviderScope.containerOf(context, listen: false);
  final share = DetailMenuAction(
    icon: YoinSymbols.share,
    label: detailStrings.share,
    onTap: () => DetailShare.share(
      container,
      kind: WebLinkKind.playlist,
      id: state.playlist.id,
      title: state.playlist.name.trim().isEmpty ? detailStrings.sharePlaylist : state.playlist.name,
      webLink: link,
      linkFailedMessage: detailStrings.shareLinkFailed,
    ),
  );
  final offline = watchOfflineToggle(
    ref,
    context,
    collection: OfflineCollection.playlist(state.playlist, cover: state.contextCover),
    tracks: () async => state.tracks,
    onMessage: (text) {
      if (context.mounted) showDetailMessage(context, text);
    },
  );
  final always = [
    if (offline != null) DetailMenuAction(icon: offline.icon, label: offline.label, onTap: offline.onTap),
    // Cloud backup (PlaylistBackupService.backUp): read whole and saved; the snackbar's View opens it.
    DetailMenuAction(
      icon: YoinSymbols.cloud,
      label: PlaylistBackupStrings.of(context).detailMenu,
      onTap: () => backUpPlaylist(context, ref, state.playlist.id),
    ),
  ];
  final abilities = playlistAbilities(ref.watch(activeSourceProvider).value);
  if (!abilities.canEdit || !state.playlist.canWrite) return [...always, share];
  final strings = ComponentStrings.of(context);
  final vm = ref.read(playlistDetailProvider(state.playlist.id).notifier);
  return [
    ...always,
    DetailMenuAction(
      icon: YoinSymbols.edit,
      label: strings.playlistRename,
      onTap: () async {
        final name = await showPlaylistNameDialog(
          context,
          title: strings.playlistRenameTitle,
          label: strings.playlistName,
          confirmLabel: strings.playlistRenameConfirm,
          cancelLabel: strings.playlistCancelRename,
          initial: state.playlist.name,
        );
        if (name == null || !context.mounted) return;
        // Shows at once; the snackbar says if the service refused (the old name comes back).
        vm.rename(name, playlistEditLines(context));
      },
    ),
    DetailMenuAction(
      icon: YoinSymbols.delete,
      label: strings.playlistDeleteMenu,
      onTap: () async {
        final confirmed = await showYoinDialog<bool>(
          context: context,
          builder: (dialog) => AlertDialog(
            title: Text(strings.playlistDeleteTitle),
            content: Text(strings.playlistDeleteBody(state.playlist.name)),
            actions: [
              TextButton(onPressed: () => Navigator.of(dialog).pop(false), child: Text(strings.playlistCancelDelete)),
              TextButton(onPressed: () => Navigator.of(dialog).pop(true), child: Text(strings.playlistDelete)),
            ],
          ),
        );
        if (confirmed != true || !context.mounted) return;
        // The page closes at once, the way its back button closes it; the
        // playlist leaves the Library meanwhile and comes back if the service
        // refuses. The snackbar says how it went, whichever page is in front.
        if (vm.delete((deleted: strings.playlistDeleted, failed: strings.playlistErrorDelete))) {
          Navigator.of(context).maybePop();
        }
      },
    ),
    share,
  ];
}

/// The page's edit lines in the app's language, for an answer that may come
/// after the page has closed.
PlaylistEditLines playlistEditLines(BuildContext context) =>
    PlaylistEditLines(ComponentStrings.of(context), ErrorStrings.of(context));
