import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/offline/offline_model.dart';
import '../library/playlist_writes.dart';
import '../offline/offline_toggle.dart';
import '../settings/backup/playlist_backup_strings.dart';
import '../shell/detail_chrome.dart';
import '../ui/component/add_to_playlist_sheet.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/yoin_dialog.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_strings.dart';
import 'detail_track_menu.dart';
import 'detail_track_rows.dart';
import 'flowing_titles.dart';
import 'playlist_backup_action.dart';
import 'playlist_detail_view_model.dart';
import 'playlist_sleeves.dart';
import 'reshape_scaffold.dart';

/// Rows and sleeves share one cover size so the image cache is shared.
const _rowCoverSize = 320;

/// The Playlist page (PushPage; Android PlaylistDetailScreen, Compact): the
/// Album shell with sleeves behind the hero cover, a Length / Owner block
/// and the flowing titles; the list rows lead with album thumbnails.
class PlaylistDetailScreen extends ConsumerWidget {
  const PlaylistDetailScreen({super.key, required this.playlistId});

  final MediaId playlistId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
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
              PlaylistDetailError(:final notFound) => DetailErrorView(
                message: notFound ? strings.playlistNotFound : strings.playlistLoadError,
                onRetry: () => ref.read(playlistDetailProvider(playlistId).notifier).retry(),
              ),
              PlaylistDetailContent() => null,
            },
            content: content == null ? null : _content(context, ref, content, heroUrl),
          ),
        ),
      ),
    );
  }

  ReshapeContent _content(BuildContext context, WidgetRef ref, PlaylistDetailContent state, String? heroUrl) {
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
      listChildren: [
        Padding(
          padding: const EdgeInsetsDirectional.only(start: 8, top: 2, bottom: 10),
          child: TrackCountLine(
            count: state.trackCount,
            totalSec: state.totalDurationSec,
            showDuration: state.tracks.isNotEmpty,
          ),
        ),
        if (state.entries.isEmpty)
          Padding(
            padding: const EdgeInsetsDirectional.only(start: 8, top: 8),
            child: Text(strings.playlistEmpty, style: emptyStyle),
          ),
        for (final (i, e) in state.entries.indexed)
          PlaylistTrackRow(
            key: ValueKey(e.rowKey),
            track: e.track,
            coverUrl: rowUrl(e.track),
            onTap: () => playFrom(i),
            onLongPress: () => openTrackMenu(context, e.track),
          ),
      ],
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
    final duration = total <= 0 ? '–' : (h > 0 ? strings.durationHoursMinutes(h, m) : strings.durationMinutes(m));
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

/// The Playlist page's ▾ rows (PlaylistDetailScreen overflow): Download
/// (an account that downloads; OfflineToggle), Cloud backup, then Rename and
/// Delete, for a playlist this account owns on a service that can change it.
List<DetailMenuAction> playlistMenuItems(BuildContext context, WidgetRef ref, PlaylistDetailContent state) {
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
  if (!abilities.canEdit || !state.playlist.canWrite) return always;
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
        if (name == null || name == state.playlist.name) return;
        final ok = await vm.rename(name);
        if (context.mounted) showDetailMessage(context, ok ? strings.playlistRenamed : strings.playlistErrorRename);
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
        if (confirmed != true) return;
        final ok = await vm.delete();
        if (!context.mounted) return;
        showDetailMessage(context, ok ? strings.playlistDeleted : strings.playlistErrorDelete);
        // Gone: leave the page the way its back button does.
        if (ok) Navigator.of(context).maybePop();
      },
    ),
  ];
}
