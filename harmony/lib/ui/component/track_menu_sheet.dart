import 'dart:math' as math;

import 'package:flutter/material.dart';
import '../../shell/bar_suppression.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../playback/keep_screen_on.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../app/providers.dart';
import '../../data/model/model.dart';
import '../../data/offline/offline_model.dart';
import '../../data/source/music_source.dart';
import '../../detail/detail_share.dart';
import '../../detail/detail_strings.dart';
import '../../detail/scrapbook_focus.dart';
import '../../offline/offline_providers.dart';
import '../../offline/offline_toggle.dart';
import '../../nowplaying/haptics.dart';
import '../../nowplaying/nowplaying_strings.dart';
import '../../playback/playback_controller.dart';
import '../../playback/song_links.dart';
import '../../theme/tokens.dart';
import 'audio_format_tags.dart';
import 'component_strings.dart';
import 'cover_art.dart';
import 'expressive/expressive_shapes.dart';
import 'expressive/expressive_surface_button.dart';

/// One row of the sheet: a symbol and a label; [onTap] runs as the sheet goes down.
@immutable
final class TrackMenuRow {
  const TrackMenuRow(this.label, this.icon, this.onTap);
  final String label;
  final IconData icon;
  final VoidCallback onTap;
}

/// One button of the sheet's top row (TrackMenu.kt TrackMenuQuickAction): an
/// action on the song itself that lands in one tap. [keepsOpen]: the sheet
/// stays up after it (a toggle, a queue add with its own message). [checked]
/// draws the button selected (Favorite on).
@immutable
final class TrackMenuQuickAction {
  const TrackMenuQuickAction({
    required this.id,
    required this.label,
    required this.icon,
    required this.keepsOpen,
    required this.onTap,
    this.checked = false,
  });

  final TrackMenuActionId id;
  final String label;
  final IconData icon;
  final bool keepsOpen;
  final bool checked;
  final VoidCallback onTap;
}

enum TrackMenuActionId { playNext, addToQueue, favorite, addToPlaylist, share }

/// The sheet's layout (TrackMenu.kt TrackMenuSheet): which one-tap actions sit
/// in the top row and which fold into the list.
@immutable
final class TrackMenuLayout {
  const TrackMenuLayout(this.quick, this.folded);
  final List<TrackMenuQuickAction> quick;

  /// Actions that went back to the list because the row would hold fewer than two.
  final List<TrackMenuQuickAction> folded;
}

/// Play next / Add to queue / Favorite / Share on top (owner 2026-11-03); for
/// the playing song ([nowPlaying], queueing it again makes no sense)
/// Favorite / Add to playlist / Share. One button alone is not a row: it goes
/// back to the list (a lone Favorite leaves, as on Android). Null actions are
/// what the song or provider can't do.
TrackMenuLayout trackMenuLayout({
  required bool nowPlaying,
  TrackMenuQuickAction? playNext,
  TrackMenuQuickAction? addToQueue,
  TrackMenuQuickAction? favorite,
  TrackMenuQuickAction? addToPlaylist,
  TrackMenuQuickAction? share,
}) {
  final candidates = nowPlaying ? [?favorite, ?addToPlaylist, ?share] : [?playNext, ?addToQueue, ?favorite, ?share];
  if (candidates.length >= 2) return TrackMenuLayout(candidates, const []);
  return TrackMenuLayout(const [], [
    for (final a in candidates)
      if (a.id != TrackMenuActionId.favorite) a,
  ]);
}

/// "Go to artist", or one "Go to `name`" per credited artist with a page of
/// their own when the service names several (OpenSubsonic `artists`;
/// TrackMenu.kt goToArtistRows). Empty without [go].
List<TrackMenuRow> goToArtistRows(Track track, ValueChanged<MediaId>? go, ComponentStrings strings) {
  if (go == null) return const [];
  final credits = [
    for (final c in track.artistCredits)
      if (c.id != null) c,
  ];
  if (credits.length > 1) {
    return [
      for (final c in credits) TrackMenuRow(strings.goToNamedArtist(c.name), YoinSymbols.artist, () => go(c.id!)),
    ];
  }
  final id = track.artistId;
  return id == null ? const [] : [TrackMenuRow(strings.goToArtist, YoinSymbols.artist, () => go(id))];
}

/// A song's long-press sheet, rising from the bottom (owner 2026-10-17: a
/// bottom sheet, never a dropdown; TrackMenu.kt TrackMenuSheet): the song
/// (cover, title, artist, format), a top row of one-tap actions, then Add to
/// playlist and Download (an account that downloads), then Go to album / View
/// in Scrapbook / Go to artist (one row per credited artist when the service
/// names several) / Open in Apple Music. Rows the song or provider can't do
/// are left out ([onGoToAlbum] / [onGoToArtist] null leaves those out: an
/// album row has no Go to album — it is that album). View in Scrapbook moves
/// the album page to its scrapbook at this song ([onViewInScrapbook], on the
/// album page), or opens the album there ([onGoToAlbum] with a
/// [ScrapbookFocusRequest]). [onAddToPlaylist] null leaves out Add to
/// playlist. [onRemoveFromPlaylist] is the playlist page's own: last and on
/// its own, it takes this row's entry out of its playlist (null leaves it
/// out). [onMessage] shows the queue confirmations and failures (the
/// caller's snackbar).
Future<void> showTrackMenuSheet(
  BuildContext context, {
  required Track track,
  bool nowPlaying = false,
  ValueChanged<MediaId>? onGoToAlbum,
  ValueChanged<MediaId>? onGoToArtist,
  ValueChanged<MediaId>? onViewInScrapbook,
  ValueChanged<List<Track>>? onAddToPlaylist,
  VoidCallback? onRemoveFromPlaylist,
  required ValueChanged<String> onMessage,
}) {
  YoinHaptics.longPress();
  return showModalBottomSheet<void>(
    context: context,
    useSafeArea: false,
    isScrollControlled: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    // The window's bar sits above the Navigator: a sheet sends it away.
    builder: (_) => ChromeBarSuppression(
      child: TrackMenuSheet(
        track: track,
        nowPlaying: nowPlaying,
        onGoToAlbum: onGoToAlbum,
        onGoToArtist: onGoToArtist,
        onViewInScrapbook: onViewInScrapbook,
        onAddToPlaylist: onAddToPlaylist,
        onRemoveFromPlaylist: onRemoveFromPlaylist,
        onMessage: onMessage,
      ),
    ),
  );
}

/// The sheet's content; [showTrackMenuSheet] puts it in a modal sheet.
class TrackMenuSheet extends ConsumerStatefulWidget {
  const TrackMenuSheet({
    super.key,
    required this.track,
    required this.onMessage,
    this.nowPlaying = false,
    this.onGoToAlbum,
    this.onGoToArtist,
    this.onViewInScrapbook,
    this.onAddToPlaylist,
    this.onRemoveFromPlaylist,
  });

  final Track track;
  final bool nowPlaying;
  final ValueChanged<MediaId>? onGoToAlbum;
  final ValueChanged<MediaId>? onGoToArtist;

  /// The album page's own View in Scrapbook: page 2 at this song.
  final ValueChanged<MediaId>? onViewInScrapbook;
  final ValueChanged<List<Track>>? onAddToPlaylist;

  /// The playlist page's "Remove from playlist" for this row's entry (TrackMenu.kt onRemoveFromPlaylist).
  final VoidCallback? onRemoveFromPlaylist;
  final ValueChanged<String> onMessage;

  @override
  ConsumerState<TrackMenuSheet> createState() => _TrackMenuSheetState();
}

class _TrackMenuSheetState extends ConsumerState<TrackMenuSheet> {
  /// A tap shows its state at once until the overrides catch up.
  bool? _pendingFavorite;

  /// Its album and artist as its service names them, for a song that came
  /// without them (TrackMenu.kt songLinks: Home's cards, Apple Music's
  /// playlist and library songs), so Go to album / artist are there too.
  Track? _linked;

  Track get _track => _linked ?? widget.track;

  @override
  void initState() {
    super.initState();
    _askLinks();
  }

  @override
  void didUpdateWidget(TrackMenuSheet oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (identical(oldWidget.track, widget.track)) return;
    _linked = null;
    _askLinks();
  }

  void _askLinks() {
    final track = widget.track;
    if (SongLinks.of(track).complete || (widget.onGoToAlbum == null && widget.onGoToArtist == null)) return;
    () async {
      try {
        // The account's source, once it is up (the menu can open as the app starts).
        await ref.read(activeSourceProvider.future);
        if (!mounted) return;
        final links = await ref.read(songLinksResolverProvider).linksFor(track);
        if (mounted && identical(widget.track, track)) setState(() => _linked = links.applyTo(track));
      } catch (_) {
        // No source: the rows stay as the song came.
      }
    }();
  }

  /// Play next / Add to queue (the detail ▾ rows for one song): the count is
  /// the queue's new entries, so only a real success is confirmed.
  Future<void> _queue({required bool next}) async {
    final strings = ComponentStrings.of(context);
    final container = ProviderScope.containerOf(context, listen: false);
    final controller = container.read(playbackControllerProvider.notifier);
    final before = container.read(playbackControllerProvider).entryIds.toSet();
    try {
      await controller.addToQueue([_track], next: next);
    } catch (error) {
      debugPrint('YoinTrackMenu: queue failed: $error');
      return;
    }
    final added = math.min(
      container.read(playbackControllerProvider).entryIds.where((id) => !before.contains(id)).length,
      1,
    );
    if (added > 0) widget.onMessage(next ? strings.playNextCount(added) : strings.queued(added));
  }

  Future<void> _toggleFavorite(bool current) async {
    final strings = ComponentStrings.of(context);
    final next = !current;
    setState(() => _pendingFavorite = next);
    final ok = await ref
        .read(favoriteOverridesProvider.notifier)
        .setFavorite(_track.id, target: next, previous: current);
    if (mounted) setState(() => _pendingFavorite = null);
    if (!ok) widget.onMessage(next ? strings.couldntSaveFavorite : strings.couldntRemoveFavorite);
  }

  void _close(VoidCallback then) {
    Navigator.of(context).pop();
    then();
  }

  @override
  Widget build(BuildContext context) {
    final strings = ComponentStrings.of(context);
    final source = ref.watch(activeSourceProvider).value;
    final caps = source?.capabilities ?? const <Capability>{};
    final resolved = ref.watch(favoriteOverridesProvider)[_track.id] ?? _track.isStarred;
    final favorite = _pendingFavorite ?? resolved;
    final detailStrings = DetailStrings.of(context);
    // Rows run after the sheet has gone: they hold the container, not this state.
    final container = ref.container;
    // The song's public page (Apple Music): Share carries it, Open in opens it.
    final link = ref.watch(detailWebLinkProvider((WebLinkKind.track, _track.id))).value;
    final openIn = openInLabel(link, detailStrings);
    final canFavorite =
        caps.contains(Capability.favorites) &&
        _track.id.rawId.trim().isNotEmpty &&
        source?.provider == _track.id.provider;

    final layout = trackMenuLayout(
      nowPlaying: widget.nowPlaying,
      playNext: TrackMenuQuickAction(
        id: TrackMenuActionId.playNext,
        label: strings.playNext,
        icon: YoinSymbols.skipNext,
        keepsOpen: true,
        onTap: () => _queue(next: true),
      ),
      addToQueue: TrackMenuQuickAction(
        id: TrackMenuActionId.addToQueue,
        label: strings.addToQueue,
        icon: YoinSymbols.queue,
        keepsOpen: true,
        onTap: () => _queue(next: false),
      ),
      favorite: canFavorite
          ? TrackMenuQuickAction(
              id: TrackMenuActionId.favorite,
              label: favorite ? strings.favorited : strings.favorite,
              icon: favorite ? YoinSymbols.favoriteFilled : YoinSymbols.favorite,
              keepsOpen: true,
              checked: favorite,
              onTap: () => _toggleFavorite(favorite),
            )
          : null,
      addToPlaylist: widget.onAddToPlaylist == null
          ? null
          : TrackMenuQuickAction(
              id: TrackMenuActionId.addToPlaylist,
              label: strings.addToPlaylist,
              icon: YoinSymbols.playlist,
              keepsOpen: false,
              onTap: () => widget.onAddToPlaylist!([_track]),
            ),
      share: TrackMenuQuickAction(
        id: TrackMenuActionId.share,
        label: strings.share,
        icon: YoinSymbols.share,
        keepsOpen: false,
        onTap: () => DetailShare.share(
          container,
          kind: WebLinkKind.track,
          id: _track.id,
          title: trackShareTitle(_track),
          webLink: link,
          linkFailedMessage: detailStrings.shareLinkFailed,
        ),
      ),
    );

    // Download this song alone (TrackMenu.kt). A song kept as part of a
    // downloaded album or playlist has no row: that page's own Download says
    // it, and removes it.
    final offline = source?.provider == _track.id.provider && _track.id.rawId.trim().isNotEmpty
        ? watchOfflineToggle(
            ref,
            context,
            collection: OfflineCollection.song(_track),
            tracks: () async => [_track],
            onMessage: widget.onMessage,
          )
        : null;
    final keptByCollection =
        offline != null &&
        offline.status is OfflineNone &&
        ref.watch(offlineLibraryProvider.select((library) => library.isDownloaded(_track.id.toString())));

    final playlistRows = [
      for (final a in layout.folded) TrackMenuRow(a.label, a.icon, a.onTap),
      if (widget.onAddToPlaylist case final add? when !widget.nowPlaying)
        TrackMenuRow(strings.addToPlaylist, YoinSymbols.playlist, () => add([_track])),
      if (offline != null && !keptByCollection) TrackMenuRow(offline.label, offline.icon, offline.onTap),
    ];
    final goRows = [
      if ((widget.onGoToAlbum, _track.albumId) case (final go?, final id?))
        TrackMenuRow(strings.goToAlbum, YoinSymbols.album, () => go(id)),
      // The song's cards on its album's second page: in place on the album
      // page, or the album opened at page 2 from anywhere else.
      if (widget.onViewInScrapbook case final view?)
        TrackMenuRow(strings.viewInScrapbook, YoinSymbols.note, () => view(_track.id))
      else if ((widget.onGoToAlbum, _track.albumId) case (final go?, final id?))
        TrackMenuRow(strings.viewInScrapbook, YoinSymbols.note, () {
          ScrapbookFocusRequest.request(container, _track.id);
          go(id);
        }),
      ...goToArtistRows(_track, widget.onGoToArtist, strings),
      if ((link, openIn) case (final url?, final label?))
        TrackMenuRow(label, YoinSymbols.launch, () => openDetailLink(container, url)),
    ];
    // Last and on its own: the one row that takes something away.
    final editRows = [
      if (widget.onRemoveFromPlaylist case final remove?)
        TrackMenuRow(strings.removeFromPlaylist, YoinSymbols.delete, remove),
    ];
    return TrackMenuContent(
      track: _track,
      quick: layout.quick,
      groups: [playlistRows, goRows, editRows],
      onQuick: (action) {
        if (action.keepsOpen) {
          YoinHaptics.tick();
          action.onTap();
        } else {
          YoinHaptics.contextClick();
          _close(action.onTap);
        }
      },
      onRow: (row) {
        YoinHaptics.contextClick();
        _close(row.onTap);
      },
      footer: widget.nowPlaying ? const _KeepScreenOnRow() : null,
    );
  }
}

/// The song sheet's body (TrackMenu.kt TrackMenuSheetContent): the song, the
/// top row of one-tap actions, then each group of rows, then [footer]. What
/// a tap does is the caller's ([onQuick], [onRow]); the tip pages' demo
/// phone shows the same body with its own rows.
class TrackMenuContent extends StatelessWidget {
  const TrackMenuContent({
    super.key,
    required this.track,
    required this.quick,
    required this.groups,
    required this.onQuick,
    required this.onRow,
    this.footer,
    this.rowWrapper,
  });

  final Track track;
  final List<TrackMenuQuickAction> quick;
  final List<List<TrackMenuRow>> groups;
  final ValueChanged<TrackMenuQuickAction> onQuick;
  final ValueChanged<TrackMenuRow> onRow;
  final Widget? footer;

  /// Wraps each row (the demo names one for its touch).
  final Widget Function(TrackMenuRow row, Widget child)? rowWrapper;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final navBottom = MediaQuery.paddingOf(context).bottom;
    final tags = audioFormatTags(track.audioFormat);
    Widget wrap(TrackMenuRow row, Widget child) => rowWrapper?.call(row, child) ?? child;

    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(SheetTokens.trackMenuPaddingH, 0, SheetTokens.trackMenuPaddingH, 16 + navBottom),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Header(track: track, tags: tags),
          if (quick.isNotEmpty) ...[
            const SizedBox(height: SheetTokens.trackMenuGroupGap),
            _QuickRow(actions: quick, onTap: onQuick),
          ],
          for (final rows in groups)
            if (rows.isNotEmpty) ...[
              const SizedBox(height: SheetTokens.trackMenuGroupGap),
              for (var i = 0; i < rows.length; i++) ...[
                if (i > 0) const SizedBox(height: SheetTokens.trackMenuRowGap),
                wrap(
                  rows[i],
                  Material(
                    color: colors.surfaceContainerHigh,
                    shape: _segmentShape(i, rows.length),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () => onRow(rows[i]),
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(minHeight: SheetTokens.trackMenuRowHeight),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: SheetTokens.trackMenuRowPaddingH),
                          child: Row(
                            children: [
                              Icon(rows[i].icon, color: colors.onSurfaceVariant),
                              const SizedBox(width: SheetTokens.trackMenuIconGap),
                              Expanded(
                                child: Text(
                                  rows[i].label,
                                  style: Theme.of(context).textTheme.bodyLarge!.copyWith(color: colors.onSurface),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ],
          if (footer case final footer?) ...[const SizedBox(height: SheetTokens.trackMenuGroupGap), footer],
        ],
      ),
    );
  }
}

/// Now Playing's player setting (Android NowPlayingCoverMenu): keep the
/// screen on while the player is open and playing. A switch; the sheet stays.
class _KeepScreenOnRow extends ConsumerWidget {
  const _KeepScreenOnRow();

  @override
  Widget build(BuildContext context, WidgetRef ref) => KeepScreenOnRow(
    on: ref.watch(keepScreenOnPreferenceProvider),
    onChanged: (value) {
      YoinHaptics.tick();
      ref.read(keepScreenOnPreferenceProvider.notifier).set(value);
    },
  );
}

/// The Keep screen on row itself: a symbol, the label and a switch; a tap anywhere flips it.
class KeepScreenOnRow extends StatelessWidget {
  const KeepScreenOnRow({super.key, required this.on, required this.onChanged});

  final bool on;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Material(
      color: colors.surfaceContainerHigh,
      shape: _segmentShape(0, 1),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => onChanged(!on),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: SheetTokens.trackMenuRowHeight),
          child: Padding(
            padding: const EdgeInsets.only(left: SheetTokens.trackMenuRowPaddingH, right: 12),
            child: Row(
              children: [
                Icon(YoinSymbols.lightMode, color: colors.onSurfaceVariant),
                const SizedBox(width: SheetTokens.trackMenuIconGap),
                Expanded(
                  child: Text(
                    ComponentStrings.of(context).keepScreenOn,
                    style: Theme.of(context).textTheme.bodyLarge!.copyWith(color: colors.onSurface),
                  ),
                ),
                Switch(value: on, onChanged: onChanged),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A group of rows, segmented like Settings: outer corners round, inner corners tight.
ShapeBorder _segmentShape(int index, int count) {
  const outer = Radius.circular(SheetTokens.segmentOuterCorner);
  const inner = Radius.circular(SheetTokens.segmentInnerCorner);
  final top = index == 0 ? outer : inner;
  final bottom = index == count - 1 ? outer : inner;
  return RoundedRectangleBorder(
    borderRadius: BorderRadius.only(topLeft: top, topRight: top, bottomLeft: bottom, bottomRight: bottom),
  );
}

/// The song the sheet is about: its cover, its title and artist, its format.
class _Header extends StatelessWidget {
  const _Header({required this.track, required this.tags});
  final Track track;
  final List<String> tags;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final artist = track.artist?.trim();
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
      child: Row(
        children: [
          track.coverArt == null
              ? Container(
                  width: SheetTokens.trackMenuCover,
                  height: SheetTokens.trackMenuCover,
                  decoration: BoxDecoration(
                    color: colors.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(ShapeTokens.thumb),
                  ),
                  child: Icon(YoinSymbols.musicNote, color: colors.onSurfaceVariant),
                )
              : CoverArt(cover: track.coverArt, size: SheetTokens.trackMenuCover, radius: ShapeTokens.thumb),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  track.title ?? '',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: text.titleMedium!.copyWith(fontWeight: FontWeight.w600, color: colors.onSurface),
                ),
                if (artist != null && artist.isNotEmpty)
                  Text(
                    artist,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                  ),
                if (tags.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: AudioFormatTagRow(
                      tags: tags,
                      description: NowPlayingStrings.of(context).formatDescription(tags.join(' ')),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// ButtonGroupDefaults connected shapes: outer corners full, inner corners
/// 8 dp pressing to 4 dp; a checked button rounds into a pill.
InteractionShapes connectedButtonShapes(int index, int count) {
  const inner = M3eShapeTokens.cornerSmall;
  const pressedInner = M3eShapeTokens.cornerExtraSmall;
  const full = M3eShapeTokens.cornerFull;
  final first = index == 0;
  final last = index == count - 1;
  return InteractionShapes(
    rest: CornerRadii.horizontal(start: first ? full : inner, end: last ? full : inner),
    pressed: CornerRadii.horizontal(start: first ? full : pressedInner, end: last ? full : pressedInner),
    checked: CornerRadii.full,
  );
}

/// The top row: an MD3 Expressive connected button group. Outer corners
/// round, inner corners tight; a press squeezes the corners and a checked
/// button rounds into a pill in the tonal colour.
class _QuickRow extends StatelessWidget {
  const _QuickRow({required this.actions, required this.onTap});

  static Color _content(ColorScheme colors, TrackMenuQuickAction a) =>
      a.checked ? colors.onPrimaryContainer : colors.onSurface;
  final List<TrackMenuQuickAction> actions;
  final ValueChanged<TrackMenuQuickAction> onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final label = Theme.of(context).textTheme.labelMedium!;
    return Row(
      children: [
        for (var i = 0; i < actions.length; i++) ...[
          if (i > 0) const SizedBox(width: SheetTokens.trackMenuQuickGap),
          Expanded(
            child: ExpressiveSurfaceButton(
              shapes: connectedButtonShapes(i, actions.length),
              height: SheetTokens.trackMenuQuickHeight,
              checked: actions[i].checked,
              color: actions[i].checked ? colors.primaryContainer : colors.surfaceContainerHigh,
              contentColor: _content(colors, actions[i]),
              padding: const EdgeInsetsDirectional.symmetric(horizontal: 4),
              semanticLabel: actions[i].label,
              onTap: () => onTap(actions[i]),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(actions[i].icon, size: 24),
                  const SizedBox(height: 6),
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      actions[i].label,
                      maxLines: 1,
                      style: label.copyWith(color: _content(colors, actions[i])),
                      textAlign: TextAlign.center,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}
