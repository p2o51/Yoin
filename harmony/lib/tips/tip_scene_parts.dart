/// The pieces the tip scenes are built from (Android ui/tips TipLibraryScenes
/// DemoAlbumPage / DemoLibraryPage, TipNowPlayingScenes DemoNowPlaying /
/// DemoSongSheet): the app's real components over made-up content, with no
/// service behind them. A press on the phone answers the way the app does,
/// but only inside the phone: nothing here plays, saves or opens anything.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../library/library_cells.dart';
import '../library/library_filter_row.dart';
import '../library/library_sort.dart';
import '../library/library_strings.dart';
import '../nowplaying/now_playing_pill.dart';
import '../offline/offline_strings.dart';
import '../nowplaying/overlay_controller.dart';
import '../nowplaying/player_snapshot.dart';
import '../nowplaying/playhead_clock.dart';
import '../nowplaying/transport.dart';
import '../playback/play_mode.dart';
import '../playback/playhead.dart';
import '../settings/settings_colors.dart';
import '../settings/settings_components.dart';
import '../settings/settings_metrics.dart';
import '../shell/yoin_bottom_bar.dart';
import '../shell/yoin_section.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/cover_art.dart';
import '../ui/component/song_list_item.dart';
import '../ui/component/track_menu_sheet.dart';
import 'demo_data.dart';
import 'demo_phone.dart';

/// A number that springs to [target] and builds with where it is.
class DemoSpring extends StatefulWidget {
  const DemoSpring({super.key, required this.target, required this.builder, this.spring});

  final double target;
  final SpringDescription? spring;
  final Widget Function(BuildContext context, double value) builder;

  @override
  State<DemoSpring> createState() => _DemoSpringState();
}

class _DemoSpringState extends State<DemoSpring> with TickerProviderStateMixin {
  late final SpringValue _v = SpringValue(
    vsync: this,
    initial: widget.target,
    spring: widget.spring ?? YoinSprings.defaultSpatial,
  );

  @override
  void didUpdateWidget(DemoSpring old) {
    super.didUpdateWidget(old);
    if (MediaQuery.disableAnimationsOf(context)) {
      _v.snapTo(widget.target);
    } else {
      _v.animateTo(widget.target);
    }
  }

  @override
  void dispose() {
    _v.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      AnimatedBuilder(animation: _v.animation, builder: (context, _) => widget.builder(context, _v.value));
}

/// The player the phone's components read: [track] in a one-song queue,
/// playing or not, on a clock of its own; a Now Playing that never opens
/// (the pill's tap stays inside the phone).
class DemoPlayerScope extends StatefulWidget {
  const DemoPlayerScope({
    super.key,
    required this.track,
    required this.playing,
    required this.builder,
    this.playMode = PlayMode.repeatAll,
    this.positionMs = TipSceneTokens.demoPositionMs,
  });

  final Track? track;
  final bool playing;
  final PlayMode playMode;
  final int positionMs;

  /// The phone's content, with the scope (its clock, [DemoPlayerScopeState.seek]).
  final Widget Function(BuildContext context, DemoPlayerScopeState player) builder;

  @override
  State<DemoPlayerScope> createState() => DemoPlayerScopeState();
}

class DemoPlayerScopeState extends State<DemoPlayerScope> with TickerProviderStateMixin {
  final PlayheadClock clock = PlayheadClock();
  late final NowPlayingOverlayController _overlay = NowPlayingOverlayController(vsync: this);

  @override
  void initState() {
    super.initState();
    _sample(widget.positionMs);
  }

  @override
  void didUpdateWidget(DemoPlayerScope oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.playing != widget.playing ||
        oldWidget.track != widget.track ||
        oldWidget.positionMs != widget.positionMs) {
      _sample(oldWidget.positionMs == widget.positionMs ? clock.positionMs() : widget.positionMs);
    }
  }

  void _sample(int positionMs) => clock.update(
    Playhead(
      entryId: widget.track?.id.toString(),
      positionMs: positionMs,
      sampledAtMs: clock.nowMs,
      isPlaying: widget.playing && widget.track != null,
      durationMs: _durationMs,
    ),
  );

  int? get _durationMs => widget.track?.durationSec == null ? null : widget.track!.durationSec! * 1000;

  /// Jumps the song to [positionMs].
  void seek(int positionMs) => _sample(positionMs);

  @override
  void dispose() {
    clock.dispose();
    _overlay.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final track = widget.track;
    final snapshot = track == null
        ? PlayerSnapshot.idle
        : PlayerSnapshot(
            queue: [track],
            index: 0,
            isPlaying: widget.playing,
            playWhenReady: widget.playing,
            durationMs: _durationMs,
            playMode: widget.playMode,
          );
    return ProviderScope(
      overrides: [playerSnapshotProvider.overrideWithValue(snapshot), playheadClockProvider.overrideWithValue(clock)],
      child: NowPlayingOverlayScope(
        controller: _overlay,
        child: Builder(builder: (context) => widget.builder(context, this)),
      ),
    );
  }
}

/// A bottom sheet inside the phone: the scrim, and the panel rising on the
/// spatial spring with its handle. A tap on the scrim puts it away.
class DemoSheet extends StatelessWidget {
  const DemoSheet({super.key, required this.open, required this.onDismiss, required this.child});

  final bool open;
  final VoidCallback onDismiss;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DemoSpring(
      target: open ? 1 : 0,
      builder: (context, p) {
        final shown = p.clamp(0.0, 1.0);
        if (shown <= 0.001 && !open) return const SizedBox.shrink();
        return Stack(
          children: [
            Positioned.fill(
              child: GestureDetector(
                onTap: onDismiss,
                child: ColoredBox(color: colors.scrim.withValues(alpha: 0.32 * shown)),
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: FractionalTranslation(
                translation: Offset(0, 1 - p),
                child: DecoratedBox(
                  decoration: ShapeDecoration(
                    color: colors.surfaceContainerLow,
                    shape: const RoundedRectangleBorder(
                      borderRadius: BorderRadius.vertical(top: Radius.circular(TipSceneTokens.sheetCorner)),
                    ),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: TipSceneTokens.handlePadding),
                        child: Container(
                          width: TipSceneTokens.handleWidth,
                          height: TipSceneTokens.handleHeight,
                          decoration: ShapeDecoration(
                            color: colors.onSurfaceVariant.withValues(alpha: 0.4),
                            shape: const StadiumBorder(),
                          ),
                        ),
                      ),
                      child,
                    ],
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// The song sheet in the phone: the real body ([TrackMenuContent]) with the
/// rows a Subsonic song has. Its taps stay in the phone.
class DemoSongSheet extends StatelessWidget {
  const DemoSongSheet({
    super.key,
    required this.open,
    required this.onDismiss,
    required this.track,
    this.nowPlaying = false,
    this.downloaded,
    this.onDownload,
    this.footer,
  });

  final bool open;
  final VoidCallback onDismiss;
  final Track track;

  /// The playing song's sheet: Favorite and Add to playlist on top, Go to album below.
  final bool nowPlaying;

  /// Non-null: the Download row, saying Download or Remove download.
  final bool? downloaded;
  final VoidCallback? onDownload;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final strings = ComponentStrings.of(context);
    void none() {}
    final favorite = TrackMenuQuickAction(
      id: TrackMenuActionId.favorite,
      label: strings.favorite,
      icon: YoinSymbols.favorite,
      keepsOpen: true,
      onTap: none,
    );
    final quick = nowPlaying
        ? [
            favorite,
            TrackMenuQuickAction(
              id: TrackMenuActionId.addToPlaylist,
              label: strings.addToPlaylist,
              icon: YoinSymbols.playlist,
              keepsOpen: false,
              onTap: none,
            ),
          ]
        : [
            TrackMenuQuickAction(
              id: TrackMenuActionId.playNext,
              label: strings.playNext,
              icon: YoinSymbols.skipNext,
              keepsOpen: true,
              onTap: none,
            ),
            TrackMenuQuickAction(
              id: TrackMenuActionId.addToQueue,
              label: strings.addToQueue,
              icon: YoinSymbols.queue,
              keepsOpen: true,
              onTap: none,
            ),
            favorite,
          ];
    final offline = OfflineStrings.of(context);
    final downloadRow = switch (downloaded) {
      null => null,
      true => TrackMenuRow(offline.removeDownload, YoinSymbols.downloadDone, onDownload ?? none),
      false => TrackMenuRow(offline.download, YoinSymbols.download, onDownload ?? none),
    };
    final groups = [
      [if (!nowPlaying) TrackMenuRow(strings.addToPlaylist, YoinSymbols.playlist, none), ?downloadRow],
      [
        if (nowPlaying) TrackMenuRow(strings.goToAlbum, YoinSymbols.album, none),
        TrackMenuRow(strings.goToArtist, YoinSymbols.artist, none),
      ],
    ];
    return DemoSheet(
      open: open,
      onDismiss: onDismiss,
      child: TrackMenuContent(
        track: track,
        quick: quick,
        groups: groups,
        onQuick: (action) {
          if (!action.keepsOpen) onDismiss();
        },
        onRow: (row) {
          if (row == downloadRow) {
            row.onTap();
          } else {
            onDismiss();
          }
        },
        rowWrapper: (row, child) => row == downloadRow ? DemoTarget('offline', child: child) : child,
        footer: footer,
      ),
    );
  }
}

/// An album page in the phone: its cover and name, then its songs as the
/// real song rows; each row is a demo target `row<i>`.
class DemoAlbumPage extends StatelessWidget {
  const DemoAlbumPage({super.key, required this.album, required this.onLongPressRow});

  final DemoAlbum album;
  final ValueChanged<int> onLongPressRow;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return ColoredBox(
      color: colors.surface,
      child: ListView(
        physics: const NeverScrollableScrollPhysics(),
        padding: const EdgeInsets.only(top: TipSceneTokens.pageTop),
        children: [
          Center(
            child: CoverArt(cover: album.coverRef, size: TipSceneTokens.albumCover, radius: ShapeTokens.hero),
          ),
          const SizedBox(height: TipSceneTokens.albumTitleGap),
          Text(
            album.name,
            textAlign: TextAlign.center,
            style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
          ),
          Text(
            album.artist,
            textAlign: TextAlign.center,
            style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
          const SizedBox(height: TipSceneTokens.albumRowsGap),
          for (var i = 0; i < 6; i++)
            DemoTarget(
              'row$i',
              child: SongListItem(
                title: demoTrackTitles[i],
                coverUrl: album.coverUrl,
                artist: album.artist,
                durationSec: 180 + i * 23,
                onTap: () {},
                onLongPress: () => onLongPressRow(i),
              ),
            ),
        ],
      ),
    );
  }
}

/// Library in the phone: its chips, the albums grid (the real cells) and
/// the real bar with the mini player.
class DemoLibraryPage extends StatelessWidget {
  const DemoLibraryPage({
    super.key,
    this.body,
    this.nowPlaying,
    this.onMiniPlayerLongPress,
    this.pillOverride,
    this.scrollable = false,
  });

  /// The grid scrolls (a pull to refresh needs it to).
  final bool scrollable;

  /// In place of the grid (a refresh wraps it).
  final Widget Function(Widget grid)? body;

  /// The bar shows the mini player with this song.
  final Track? nowPlaying;
  final VoidCallback? onMiniPlayerLongPress;

  /// Builds the pill in place of the real one.
  final Widget Function(BuildContext context, ValueChanged<bool> onPress)? pillOverride;

  @override
  Widget build(BuildContext context) {
    final strings = LibraryStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    // Library's own cell sum (libraryCellExtent), three columns as on a phone.
    const columns = 3;
    final cellWidth =
        (TipTokens.screenWidth - 2 * TipSceneTokens.pagePaddingH - LibraryTokens.gridGutterH * (columns - 1)) / columns;
    final grid = GridView.builder(
      physics: scrollable ? const AlwaysScrollableScrollPhysics() : const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(
        TipSceneTokens.pagePaddingH,
        TipSceneTokens.gridTop,
        TipSceneTokens.pagePaddingH,
        TipSceneTokens.gridBottom,
      ),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: columns,
        crossAxisSpacing: LibraryTokens.gridGutterH,
        mainAxisSpacing: LibraryTokens.gridGutterV,
        mainAxisExtent: libraryCellExtent(cellWidth, MediaQuery.textScalerOf(context)),
      ),
      itemCount: demoLibraryAlbums.length,
      itemBuilder: (context, i) => AlbumGridCell(album: demoLibraryAlbums[i], onTap: () {}),
    );
    return ColoredBox(
      color: colors.surface,
      child: Stack(
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: TipSceneTokens.pageTop),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: TipSceneTokens.pagePaddingH),
                child: Text(
                  strings.title,
                  style: Theme.of(context).textTheme.headlineMedium!.copyWith(color: colors.onSurface),
                ),
              ),
              const SizedBox(height: TipSceneTokens.titleGap),
              LibraryFilterRow(
                tabs: const [LibraryTab.all, LibraryTab.albums, LibraryTab.artists, LibraryTab.songs],
                selected: LibraryTab.albums,
                onSelect: (_) {},
              ),
              Expanded(child: DemoTarget('libraryBody', child: body?.call(grid) ?? grid)),
            ],
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: TipSceneTokens.barBottom,
            child: DemoTarget(
              'bar',
              child: YoinBottomBar(
                selected: YoinSection.library,
                onSelect: (_) {},
                hasTrack: nowPlaying != null,
                pillBuilder:
                    pillOverride ??
                    (context, onPress) => NowPlayingPill(
                      onPressChanged: onPress,
                      onLongPress: onMiniPlayerLongPress == null ? null : (_) => onMiniPlayerLongPress!(),
                    ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Now Playing in the phone: the cover (a demo target `cover`), the song,
/// then the real transport with its play-mode button and wave bar.
class DemoNowPlaying extends StatelessWidget {
  const DemoNowPlaying({
    super.key,
    required this.track,
    required this.clock,
    required this.playing,
    this.playMode = PlayMode.repeatAll,
    this.onSetPlayMode,
    this.onCoverTap,
    this.onCoverLongPress,
    this.focus = 0,
    this.noteAnchorsMs = const [],
    this.below,
  });

  final Track track;
  final PlayheadClock clock;
  final bool playing;
  final PlayMode playMode;
  final ValueChanged<PlayMode>? onSetPlayMode;
  final VoidCallback? onCoverTap;
  final VoidCallback? onCoverLongPress;

  /// 0 at rest, 1 in focus: the cover grows and the rest steps away.
  final double focus;
  final List<int> noteAnchorsMs;

  /// What sits under the transport (the Write pill, notes).
  final Widget? below;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final f = focus.clamp(0.0, 1.0);
    final cover = TipSceneTokens.npCover + (TipSceneTokens.npFocusCover - TipSceneTokens.npCover) * f;
    final away = (1 - f).clamp(0.0, 1.0);
    return ColoredBox(
      color: colors.surface,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
        child: Column(
          children: [
            SizedBox(height: TipSceneTokens.npTop * (1 - f) + TipSceneTokens.npFocusTop * f),
            DemoTarget(
              'cover',
              child: GestureDetector(
                onTap: onCoverTap,
                onLongPress: onCoverLongPress,
                child: CoverArt(cover: track.coverArt, size: cover, radius: ShapeTokens.nowPlayingCover),
              ),
            ),
            SizedBox(height: TipSceneTokens.npTitleGap),
            Opacity(
              opacity: away,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    track.title ?? '',
                    maxLines: 1,
                    style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
                  ),
                  Text(
                    track.artist ?? '',
                    maxLines: 1,
                    style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            const SizedBox(height: TipSceneTokens.npTransportGap),
            DemoTarget(
              'transport',
              child: _DemoTransport(
                clock: clock,
                playing: playing,
                durationMs: (track.durationSec ?? 0) * 1000,
                playMode: playMode,
                onSetPlayMode: onSetPlayMode ?? (_) {},
                noteAnchorsMs: noteAnchorsMs,
              ),
            ),
            if (below != null)
              Expanded(
                child: Opacity(opacity: away, child: below),
              ),
          ],
        ),
      ),
    );
  }
}

class _DemoTransport extends StatefulWidget {
  const _DemoTransport({
    required this.clock,
    required this.playing,
    required this.durationMs,
    required this.playMode,
    required this.onSetPlayMode,
    required this.noteAnchorsMs,
  });

  final PlayheadClock clock;
  final bool playing;
  final int durationMs;
  final PlayMode playMode;
  final ValueChanged<PlayMode> onSetPlayMode;
  final List<int> noteAnchorsMs;

  @override
  State<_DemoTransport> createState() => _DemoTransportState();
}

class _DemoTransportState extends State<_DemoTransport> {
  final ValueNotifier<TransportKey> _pressed = ValueNotifier(TransportKey.none);

  @override
  void dispose() {
    _pressed.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TransportControls(
    isPlaying: widget.playing,
    isWavePlaying: widget.playing,
    playMode: widget.playMode,
    clock: widget.clock,
    durationMs: widget.durationMs,
    labelsUnder: true,
    waveVisible: true,
    onPlayPause: () {},
    onNext: () {},
    onPrevious: () {},
    onSetPlayMode: widget.onSetPlayMode,
    onSeek: (_) {},
    pressed: _pressed,
    noteAnchorsMs: widget.noteAnchorsMs,
  );
}

/// A Settings page in the phone: the page colour, the large title, then [children].
class DemoSettingsPage extends StatelessWidget {
  const DemoSettingsPage({super.key, required this.title, required this.children, this.scroll});

  final String title;
  final List<Widget> children;
  final ScrollController? scroll;

  @override
  Widget build(BuildContext context) => ColoredBox(
    color: settingsPageColor(Theme.of(context).colorScheme),
    child: ListView(
      controller: scroll,
      physics: scroll == null ? const NeverScrollableScrollPhysics() : null,
      padding: const EdgeInsets.fromLTRB(
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageTop,
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageBottom,
      ),
      children: [
        SettingsHeadline(title: title),
        ...children,
      ],
    ),
  );
}

/// [child] sliding in from the end over the page under it, on the spatial
/// spring, as a pushed page arrives.
class DemoPushed extends StatelessWidget {
  const DemoPushed({super.key, required this.shown, required this.child});

  final bool shown;
  final Widget child;

  @override
  Widget build(BuildContext context) => DemoSpring(
    target: shown ? 1 : 0,
    builder: (context, p) {
      if (p <= 0.001 && !shown) return const SizedBox.shrink();
      return FractionalTranslation(translation: Offset(1 - p, 0), child: child);
    },
  );
}
