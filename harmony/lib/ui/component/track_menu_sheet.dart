import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../playback/keep_screen_on.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../app/providers.dart';
import '../../data/model/model.dart';
import '../../data/source/music_source.dart';
import '../../nowplaying/haptics.dart';
import '../../nowplaying/nowplaying_strings.dart';
import '../../playback/playback_controller.dart';
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

enum TrackMenuActionId { playNext, addToQueue, favorite, addToPlaylist }

/// The sheet's layout (TrackMenu.kt TrackMenuSheet): which one-tap actions sit
/// in the top row and which fold into the list.
@immutable
final class TrackMenuLayout {
  const TrackMenuLayout(this.quick, this.folded);
  final List<TrackMenuQuickAction> quick;

  /// Actions that went back to the list because the row would hold fewer than two.
  final List<TrackMenuQuickAction> folded;
}

/// Play next / Add to queue / Favorite on top; for the playing song
/// ([nowPlaying], queueing it again makes no sense) Favorite / Add to
/// playlist. One button alone is not a row: it goes back to the list (a lone
/// Favorite leaves, as on Android). Null actions are what the song or
/// provider can't do.
TrackMenuLayout trackMenuLayout({
  required bool nowPlaying,
  TrackMenuQuickAction? playNext,
  TrackMenuQuickAction? addToQueue,
  TrackMenuQuickAction? favorite,
  TrackMenuQuickAction? addToPlaylist,
}) {
  final candidates = nowPlaying ? [?favorite, ?addToPlaylist] : [?playNext, ?addToQueue, ?favorite];
  if (candidates.length >= 2) return TrackMenuLayout(candidates, const []);
  return TrackMenuLayout(const [], [
    for (final a in candidates)
      if (a.id != TrackMenuActionId.favorite) a,
  ]);
}

/// A song's long-press sheet, rising from the bottom (owner 2026-10-17: a
/// bottom sheet, never a dropdown; TrackMenu.kt TrackMenuSheet): the song
/// (cover, title, artist, format), a top row of one-tap actions, then Add to
/// playlist, then Go to album / Go to artist. Rows the song or provider can't
/// do are left out ([onGoToAlbum] / [onGoToArtist] null leaves those out: an
/// album row has no Go to album — it is that album). [onAddToPlaylist] null
/// leaves out Add to playlist. [onMessage] shows the queue confirmations and
/// failures (the caller's snackbar).
Future<void> showTrackMenuSheet(
  BuildContext context, {
  required Track track,
  bool nowPlaying = false,
  ValueChanged<MediaId>? onGoToAlbum,
  ValueChanged<MediaId>? onGoToArtist,
  ValueChanged<List<Track>>? onAddToPlaylist,
  required ValueChanged<String> onMessage,
}) {
  YoinHaptics.longPress();
  return showModalBottomSheet<void>(
    context: context,
    useSafeArea: false,
    isScrollControlled: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    builder: (_) => TrackMenuSheet(
      track: track,
      nowPlaying: nowPlaying,
      onGoToAlbum: onGoToAlbum,
      onGoToArtist: onGoToArtist,
      onAddToPlaylist: onAddToPlaylist,
      onMessage: onMessage,
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
    this.onAddToPlaylist,
  });

  final Track track;
  final bool nowPlaying;
  final ValueChanged<MediaId>? onGoToAlbum;
  final ValueChanged<MediaId>? onGoToArtist;
  final ValueChanged<List<Track>>? onAddToPlaylist;
  final ValueChanged<String> onMessage;

  @override
  ConsumerState<TrackMenuSheet> createState() => _TrackMenuSheetState();
}

class _TrackMenuSheetState extends ConsumerState<TrackMenuSheet> {
  /// A tap shows its state at once until the overrides catch up.
  bool? _pendingFavorite;

  Track get _track => widget.track;

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
    final colors = Theme.of(context).colorScheme;
    final source = ref.watch(activeSourceProvider).value;
    final caps = source?.capabilities ?? const <Capability>{};
    final resolved = ref.watch(favoriteOverridesProvider)[_track.id] ?? _track.isStarred;
    final favorite = _pendingFavorite ?? resolved;
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
    );

    final playlistRows = [
      for (final a in layout.folded) TrackMenuRow(a.label, a.icon, a.onTap),
      if (widget.onAddToPlaylist case final add? when !widget.nowPlaying)
        TrackMenuRow(strings.addToPlaylist, YoinSymbols.playlist, () => add([_track])),
    ];
    final goRows = [
      if ((widget.onGoToAlbum, _track.albumId) case (final go?, final id?))
        TrackMenuRow(strings.goToAlbum, YoinSymbols.album, () => go(id)),
      if ((widget.onGoToArtist, _track.artistId) case (final go?, final id?))
        TrackMenuRow(strings.goToArtist, YoinSymbols.artist, () => go(id)),
    ];
    final navBottom = MediaQuery.paddingOf(context).bottom;
    final tags = audioFormatTags(_track.audioFormat);

    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(SheetTokens.trackMenuPaddingH, 0, SheetTokens.trackMenuPaddingH, 16 + navBottom),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Header(track: _track, tags: tags),
          if (layout.quick.isNotEmpty) ...[
            const SizedBox(height: SheetTokens.trackMenuGroupGap),
            _QuickRow(
              actions: layout.quick,
              onTap: (action) {
                if (action.keepsOpen) {
                  YoinHaptics.tick();
                  action.onTap();
                } else {
                  YoinHaptics.contextClick();
                  _close(action.onTap);
                }
              },
            ),
          ],
          for (final rows in [playlistRows, goRows])
            if (rows.isNotEmpty) ...[
              const SizedBox(height: SheetTokens.trackMenuGroupGap),
              for (var i = 0; i < rows.length; i++) ...[
                if (i > 0) const SizedBox(height: SheetTokens.trackMenuRowGap),
                Material(
                  color: colors.surfaceContainerHigh,
                  shape: _segmentShape(i, rows.length),
                  clipBehavior: Clip.antiAlias,
                  child: InkWell(
                    onTap: () {
                      YoinHaptics.contextClick();
                      _close(rows[i].onTap);
                    },
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
              ],
            ],
          if (widget.nowPlaying) ...[const SizedBox(height: SheetTokens.trackMenuGroupGap), const _KeepScreenOnRow()],
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
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final on = ref.watch(keepScreenOnPreferenceProvider);
    void toggle(bool value) {
      YoinHaptics.tick();
      ref.read(keepScreenOnPreferenceProvider.notifier).set(value);
    }

    return Material(
      color: colors.surfaceContainerHigh,
      shape: _segmentShape(0, 1),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => toggle(!on),
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
                Switch(value: on, onChanged: toggle),
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
