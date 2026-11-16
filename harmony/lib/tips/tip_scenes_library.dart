/// Library and an album page on the demo phone (Android ui/tips
/// TipLibraryScenes.kt): Library is the real grid cells under the real bar;
/// the album page is the real song rows; the song sheet is the real body.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../continueon/continue_on_card.dart';
import '../continueon/continue_on_controller.dart';
import '../continueon/continue_on_cursor.dart';
import '../data/model/model.dart' show MediaId;
import '../detail/detail_track_rows.dart' show PlaylistTrackRow, TrackCountLine;
import '../detail/playback_projection.dart' show trackPlayStateProvider;
import '../detail/playlist_row_presence.dart';
import '../library/library_search.dart';
import '../library/library_search_view.dart' show librarySearchFilterLabel;
import '../library/library_strings.dart';
import '../nowplaying/now_playing_pill.dart';
import '../shell/yoin_section.dart';
import '../theme/tokens.dart';
import '../ui/component/cover_art.dart';
import '../ui/component/expressive_segment_chip.dart';
import '../ui/component/yoin_pull_to_refresh.dart';
import 'demo_data.dart';
import 'demo_phone.dart';
import 'tip_scene_parts.dart';
import 'tip_scenes_home.dart' show DemoHomeFeed;

/// A scene's state, set only while it is on screen.
mixin DemoSceneState<T extends StatefulWidget> on State<T> {
  void put(VoidCallback change) {
    if (mounted) setState(change);
  }
}

/// Long-press a song on an album → its song sheet.
class SongMenuDemo extends StatefulWidget {
  const SongMenuDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<SongMenuDemo> createState() => _SongMenuDemoState();
}

class _SongMenuDemoState extends State<SongMenuDemo> with DemoSceneState {
  bool _open = false;
  int _row = 2;

  Future<void> _script(DemoFinger f) async {
    put(() => _open = false);
    await f.wait(300);
    await f.moveTo('row2', fx: 0.45);
    await f.hold(
      () => put(() {
        _row = 2;
        _open = true;
      }),
    );
    await f.lift();
    await f.wait(1800);
    await f.moveToPoint(0.5, 0.12);
    await f.tap(() => put(() => _open = false));
    await f.wait(500);
  }

  @override
  Widget build(BuildContext context) {
    final album = demoAlbums[1];
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: Stack(
        children: [
          DemoAlbumPage(
            album: album,
            onLongPressRow: (i) => put(() {
              _row = i;
              _open = true;
            }),
          ),
          DemoSongSheet(open: _open, onDismiss: () => put(() => _open = false), track: demoTrack(_row, album)),
        ],
      ),
    );
  }
}

/// Long-press Library in the bar → its search.
class LibrarySearchDemo extends StatefulWidget {
  const LibrarySearchDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<LibrarySearchDemo> createState() => _LibrarySearchDemoState();
}

class _LibrarySearchDemoState extends State<LibrarySearchDemo> with DemoSceneState {
  bool _searching = false;

  Future<void> _script(DemoFinger f) async {
    put(() => _searching = false);
    await f.wait(300);
    // Library is the bar's right half (nothing playing: the two labelled halves).
    await f.moveTo('bar', fx: 0.75);
    await f.hold(() => put(() => _searching = true));
    await f.lift();
    await f.wait(1800);
    await f.moveTo('searchBack');
    await f.tap(() => put(() => _searching = false));
    await f.wait(500);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: Stack(
      children: [
        DemoLibraryPage(onLibraryLongPress: () => put(() => _searching = true)),
        Positioned.fill(
          child: _DemoLibrarySearch(visible: _searching, onClose: () => put(() => _searching = false)),
        ),
      ],
    ),
  );
}

/// Library's full-screen search as it opens (its own route in the app, so
/// the phone draws its look): the back arrow and the placeholder, then the
/// real type chips.
class _DemoLibrarySearch extends StatefulWidget {
  const _DemoLibrarySearch({required this.visible, required this.onClose});

  final bool visible;
  final VoidCallback onClose;

  @override
  State<_DemoLibrarySearch> createState() => _DemoLibrarySearchState();
}

class _DemoLibrarySearchState extends State<_DemoLibrarySearch> {
  LibrarySearchFilter _filter = LibrarySearchFilter.all;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = LibraryStrings.of(context);
    return DemoSpring(
      target: widget.visible ? 1 : 0,
      builder: (context, p) {
        if (p <= 0.001 && !widget.visible) return const SizedBox.shrink();
        return IgnorePointer(
          ignoring: !widget.visible,
          child: Opacity(
            opacity: p.clamp(0.0, 1.0),
            child: Transform.scale(
              scale: TipSceneTokens.searchEnterScale + (1 - TipSceneTokens.searchEnterScale) * p,
              child: ColoredBox(
                color: colors.surfaceContainerHigh,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    SizedBox(
                      height: TipSceneTokens.searchField + TipSceneTokens.searchRowPadding,
                      child: Row(
                        children: [
                          const SizedBox(width: TipSceneTokens.searchRowInset),
                          DemoTarget(
                            'searchBack',
                            child: IconButton(icon: const Icon(YoinSymbols.back), onPressed: widget.onClose),
                          ),
                          Expanded(
                            child: Text(
                              strings.searchPlaceholder,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
                            ),
                          ),
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: SearchTokens.filterRowPaddingV),
                      child: SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        padding: const EdgeInsets.symmetric(horizontal: TipSceneTokens.pagePaddingH),
                        child: Row(
                          children: [
                            for (final (i, f) in LibrarySearchFilter.values.indexed) ...[
                              if (i > 0) const SizedBox(width: LibraryTokens.chipGap),
                              ExpressiveSegmentChip(
                                label: librarySearchFilterLabel(strings, f),
                                selected: _filter == f,
                                onTap: () => setState(() => _filter = f),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Subsonic downloads from a song's sheet: long-press a song, tap Download,
/// and the next time the sheet says Remove download.
class DownloadsDemo extends StatefulWidget {
  const DownloadsDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<DownloadsDemo> createState() => _DownloadsDemoState();
}

class _DownloadsDemoState extends State<DownloadsDemo> with DemoSceneState {
  bool _open = false;
  bool _downloaded = false;

  Future<void> _script(DemoFinger f) async {
    put(() {
      _open = false;
      _downloaded = false;
    });
    await f.wait(300);
    await f.moveTo('row2', fx: 0.45);
    await f.hold(() => put(() => _open = true));
    // Off the song the moment the sheet is up, so the dot never sits on its buttons.
    await f.moveTo('offline', fx: 0.4);
    await f.wait(250);
    await f.tap();
    // The row answers first; the sheet goes down once the dot has left it.
    put(() => _downloaded = true);
    await f.lift();
    put(() => _open = false);
    await f.wait(900);
    await f.moveTo('row2', fx: 0.45);
    await f.hold(() => put(() => _open = true));
    await f.moveTo('offline', fx: 0.4);
    await f.wait(300);
    await f.lift();
    await f.wait(1800);
    // Close on the scrim above the sheet, not on the sheet's header.
    await f.moveToPoint(0.5, 0.12);
    await f.tap(() => put(() => _open = false));
    f.hide();
    await f.wait(500);
  }

  @override
  Widget build(BuildContext context) {
    final album = demoAlbums[1];
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: Stack(
        children: [
          DemoAlbumPage(album: album, onLongPressRow: (_) => put(() => _open = true)),
          DemoSongSheet(
            open: _open,
            onDismiss: () => put(() => _open = false),
            track: demoTrack(2, album),
            downloaded: _downloaded,
            onDownload: () => put(() => _downloaded = !_downloaded),
          ),
        ],
      ),
    );
  }
}

/// Long-press the mini player in the bar → the playing song's sheet.
class MiniPlayerMenuDemo extends StatefulWidget {
  const MiniPlayerMenuDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<MiniPlayerMenuDemo> createState() => _MiniPlayerMenuDemoState();
}

class _MiniPlayerMenuDemoState extends State<MiniPlayerMenuDemo> with DemoSceneState {
  bool _open = false;

  Future<void> _script(DemoFinger f) async {
    put(() => _open = false);
    await f.wait(300);
    await f.moveToWhere((w) => w is NowPlayingPill);
    await f.hold(() => put(() => _open = true));
    // Gone the frame the sheet starts up, so it never sits on a row.
    f.hide();
    await f.wait(1800);
    await f.moveToPoint(0.5, 0.12);
    await f.tap(() => put(() => _open = false));
    await f.lift();
    await f.wait(500);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(0, demoAlbums[0]);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        builder: (context, _) => Stack(
          children: [
            DemoLibraryPage(nowPlaying: track, onMiniPlayerLongPress: () => put(() => _open = true)),
            DemoSongSheet(open: _open, onDismiss: () => put(() => _open = false), track: track, nowPlaying: true),
          ],
        ),
      ),
    );
  }
}

/// Pull Library down from the top: the view is read again while the real
/// pull indicator turns. The phone has no service behind it, so a pull —
/// the dot's, or the user's — reads for a moment and is done.
class LibraryRefreshDemo extends StatefulWidget {
  const LibraryRefreshDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<LibraryRefreshDemo> createState() => _LibraryRefreshDemoState();
}

class _LibraryRefreshDemoState extends State<LibraryRefreshDemo> with DemoSceneState {
  bool _refreshing = false;
  Timer? _done;

  void _refresh() {
    put(() => _refreshing = true);
    _done?.cancel();
    _done = Timer(const Duration(milliseconds: TipSceneTokens.refreshMs), () => put(() => _refreshing = false));
  }

  @override
  void dispose() {
    _done?.cancel();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    put(() => _refreshing = false);
    await f.wait(300);
    await f.moveTo('libraryBody', fy: 0.3);
    final to = f.at('libraryBody', fy: 0.55);
    await f.drag(to.dx, to.dy, 700);
    _refresh();
    await f.lift();
    await f.wait(TipSceneTokens.refreshMs + 1200);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: DemoLibraryPage(
      scrollable: true,
      body: (grid) => YoinPullToRefresh(refreshing: _refreshing, onRefresh: _refresh, child: grid),
    ),
  );
}

/// The keys the keyboard demo presses.
enum _DemoKey { space, next, home, library, search }

/// Keyboard shortcuts: Space pauses and plays the mini player, Ctrl + →
/// moves it to the next song, Ctrl + 1 / Ctrl + 2 go to Home and Library,
/// Ctrl + K opens search — a keyboard's keys as keycaps under the phone's
/// screen; the user can press them too.
class KeyboardShortcutsDemo extends StatefulWidget {
  const KeyboardShortcutsDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<KeyboardShortcutsDemo> createState() => _KeyboardShortcutsDemoState();
}

class _KeyboardShortcutsDemoState extends State<KeyboardShortcutsDemo> with DemoSceneState {
  bool _paused = false;
  int _song = 0;
  bool _searching = false;
  YoinSection _section = YoinSection.home;
  _DemoKey? _pressed;

  void _press(_DemoKey key) => put(() {
    switch (key) {
      case _DemoKey.space:
        _paused = !_paused;
      case _DemoKey.next:
        _song = (_song + 1) % demoTrackTitles.length;
      // Going to a section leaves the search, as the shell's Ctrl + 1 / 2 do.
      case _DemoKey.home:
        _searching = false;
        _section = YoinSection.home;
      case _DemoKey.library:
        _searching = false;
        _section = YoinSection.library;
      case _DemoKey.search:
        _searching = !_searching;
    }
  });

  Future<void> _key(DemoFinger f, _DemoKey key) async {
    await f.moveTo('key-${key.name}');
    await f.tap(() {
      put(() => _pressed = key);
      _press(key);
    });
    put(() => _pressed = null);
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _paused = false;
      _song = 0;
      _searching = false;
      _section = YoinSection.home;
      _pressed = null;
    });
    await f.wait(500);
    await _key(f, _DemoKey.space);
    await f.wait(900);
    await _key(f, _DemoKey.space);
    await f.wait(700);
    await _key(f, _DemoKey.next);
    await f.wait(900);
    await _key(f, _DemoKey.library);
    await f.wait(1100);
    await _key(f, _DemoKey.search);
    await f.lift();
    await f.wait(1600);
    await _key(f, _DemoKey.home);
    await f.lift();
    await f.wait(1100);
  }

  @override
  Widget build(BuildContext context) {
    final zh = Localizations.maybeLocaleOf(context)?.languageCode == 'zh';
    final track = demoTrack(_song, demoAlbums[_song % demoAlbums.length]);
    final colors = Theme.of(context).colorScheme;
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing && !_paused,
        builder: (context, _) => Column(
          children: [
            Expanded(
              child: Stack(
                children: [
                  DemoLibraryPage(nowPlaying: track, section: _section, home: const DemoHomeFeed()),
                  Positioned.fill(child: _DemoSearchBar(visible: _searching)),
                ],
              ),
            ),
            ColoredBox(
              color: colors.surfaceContainerHighest,
              child: Padding(
                padding: const EdgeInsets.all(TipSceneTokens.keyTrayPadding),
                child: Row(
                  children: [
                    for (final key in _DemoKey.values) ...[
                      if (key != _DemoKey.space) const SizedBox(width: TipSceneTokens.keyGap),
                      Expanded(
                        flex: key == _DemoKey.space ? 2 : 1,
                        child: DemoTarget(
                          'key-${key.name}',
                          child: _Keycap(
                            label: switch (key) {
                              _DemoKey.space => zh ? '空格' : 'Space',
                              _DemoKey.next => 'Ctrl →',
                              _DemoKey.home => 'Ctrl 1',
                              _DemoKey.library => 'Ctrl 2',
                              _DemoKey.search => 'Ctrl K',
                            },
                            pressed: _pressed == key,
                            onPress: () => _press(key),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A keyboard key: a raised cap that sinks while pressed (spatial spring).
class _Keycap extends StatelessWidget {
  const _Keycap({required this.label, required this.pressed, required this.onPress});

  final String label;
  final bool pressed;
  final VoidCallback onPress;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return GestureDetector(
      onTap: onPress,
      child: DemoSpring(
        target: pressed ? 1 : 0,
        builder: (context, p) => SizedBox(
          height: TipSceneTokens.keyHeight,
          child: Stack(
            children: [
              Positioned.fill(
                top: TipSceneTokens.keyTravel,
                child: DecoratedBox(
                  decoration: ShapeDecoration(
                    color: colors.outlineVariant,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(TipSceneTokens.keyCorner)),
                  ),
                ),
              ),
              Positioned.fill(
                bottom: TipSceneTokens.keyTravel * (1 - p.clamp(0.0, 1.0)),
                top: TipSceneTokens.keyTravel * p.clamp(0.0, 1.0),
                child: DecoratedBox(
                  decoration: ShapeDecoration(
                    color: Color.lerp(colors.surfaceBright, colors.primaryContainer, p.clamp(0.0, 1.0)),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(TipSceneTokens.keyCorner)),
                  ),
                  child: Center(
                    // Five keys on a phone's width: a chord's label shrinks to its cap rather than wrapping.
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        label,
                        maxLines: 1,
                        style: Theme.of(context).textTheme.titleMedium!.copyWith(color: colors.onSurface),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Library's search field coming down over the page.
class _DemoSearchBar extends StatelessWidget {
  const _DemoSearchBar({required this.visible});

  final bool visible;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DemoSpring(
      target: visible ? 1 : 0,
      builder: (context, p) {
        if (p <= 0.001 && !visible) return const SizedBox.shrink();
        return SizedBox.expand(
          child: Opacity(
            opacity: p.clamp(0.0, 1.0),
            child: ColoredBox(
              color: colors.surface,
              child: Align(
                alignment: Alignment.topCenter,
                child: Padding(
                  padding: EdgeInsets.fromLTRB(
                    TipSceneTokens.pagePaddingH,
                    TipSceneTokens.pageTop + (1 - p) * TipSceneTokens.searchDrop,
                    TipSceneTokens.pagePaddingH,
                    0,
                  ),
                  child: Container(
                    height: TipSceneTokens.searchField,
                    decoration: ShapeDecoration(color: colors.surfaceContainerHigh, shape: const StadiumBorder()),
                    padding: const EdgeInsets.symmetric(horizontal: TipSceneTokens.pagePaddingH),
                    child: Row(
                      children: [
                        Icon(YoinSymbols.search, color: colors.onSurfaceVariant),
                        const SizedBox(width: TipSceneTokens.titleGap),
                        Expanded(
                          child: Text(
                            LibraryStrings.of(context).searchPlaceholder,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: Theme.of(context).textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Continue On (Android TipLibraryScenes.kt ContinueOnDemo): another device
/// hands its song over, the real card comes up over Library, and "Continue
/// here" takes it (the other device pauses). Nothing plays.
class ContinueOnDemo extends StatefulWidget {
  const ContinueOnDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<ContinueOnDemo> createState() => _ContinueOnDemoState();
}

class _ContinueOnDemoState extends State<ContinueOnDemo> with DemoSceneState {
  static const _continueKey = ValueKey('tip-continue-here');

  bool _card = false;
  bool _starting = false;

  ContinueOnPrompt get _prompt {
    final album = demoAlbums[2];
    return ContinueOnPrompt(
      cursor: ContinueOnCursor(
        provider: MediaId.providerSubsonic,
        accountKey: null,
        trackId: 'tip-continue',
        title: demoTrackTitles[0],
        artist: album.artist,
        album: album.name,
        cover: null,
        queueIds: const ['tip-continue'],
        index: 0,
        positionMs: TipSceneTokens.continuePositionMs,
        capturedAtMs: 0,
        playing: true,
        repeatMode: 0,
        shuffle: false,
        context: null,
        deviceName: demoOtherDeviceName,
        pause: null,
      ),
      account: const ContinueOnAccountFound('tip-account', 'Home server'),
      coverUrl: album.coverUrl,
      stage: _starting ? ContinueOnStage.starting : ContinueOnStage.asking,
    );
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _card = false;
      _starting = false;
    });
    await f.wait(700);
    put(() => _card = true);
    await f.wait(1400);
    await f.moveToKey(_continueKey);
    await f.tap(() => put(() => _starting = true));
    await f.lift();
    await f.wait(700);
    put(() => _card = false);
    await f.wait(1600);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: Stack(
      children: [
        const DemoLibraryPage(),
        ContinueOnCard(
          prompt: _card ? _prompt : null,
          continueKey: _continueKey,
          onContinue: () => put(() => _starting = true),
          onDismiss: () => put(() => _card = false),
        ),
      ],
    ),
  );
}

/// Remove from playlist: long-press a song on a playlist page, tap the
/// sheet's last row, and the real row leaves as on the page (the playlist
/// list's own presence: it fades while the rows under it move up). Next
/// time round it opens back in its place.
class RemoveFromPlaylistDemo extends StatefulWidget {
  const RemoveFromPlaylistDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<RemoveFromPlaylistDemo> createState() => _RemoveFromPlaylistDemoState();
}

class _RemoveFromPlaylistDemoState extends State<RemoveFromPlaylistDemo> with DemoSceneState {
  late final RowPresenceBook _rows = RowPresenceBook(onChanged: () => put(() {}));
  final Set<int> _removed = {};
  bool _open = false;
  int _row = 2;

  void _openSheet(int row) => put(() {
    _row = row;
    _open = true;
  });

  Future<void> _script(DemoFinger f) async {
    put(() {
      _open = false;
      _removed.clear();
    });
    await f.wait(900);
    await f.moveTo('row2', fx: 0.45);
    await f.hold(() => _openSheet(2));
    // Once the sheet is up, onto the row at its foot (read where it rests, not where it rises from).
    await f.wait(250);
    await f.moveTo('remove', fx: 0.4);
    await f.wait(400);
    await f.tap(() => put(() => _open = false));
    put(() => _removed.add(2));
    await f.lift();
    await f.wait(2200);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final entries = [
      for (final (i, a) in demoPlaylistAlbums.indexed)
        if (!_removed.contains(i)) (i, demoTrack(i, demoAlbums[a]), demoAlbums[a]),
    ];
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      // Nothing in the phone plays: the rows don't ask the app's player.
      child: ProviderScope(
        overrides: [trackPlayStateProvider.overrideWith((ref, id) => (current: false, playing: false))],
        child: Stack(
          children: [
            ColoredBox(
              color: colors.surface,
              child: ListView(
                physics: const NeverScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(
                  TipSceneTokens.pagePaddingH,
                  TipSceneTokens.pageTop,
                  TipSceneTokens.pagePaddingH,
                  0,
                ),
                children: [
                  Center(
                    child: CoverArt(
                      cover: demoAlbums[demoPlaylistAlbums.first].coverRef,
                      size: TipSceneTokens.playlistCover,
                      radius: ShapeTokens.hero,
                    ),
                  ),
                  const SizedBox(height: TipSceneTokens.albumTitleGap),
                  Text(
                    demoPlaylistName,
                    textAlign: TextAlign.center,
                    style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
                  ),
                  const SizedBox(height: TipSceneTokens.albumRowsGap),
                  // The playlist page's own list: the count, then each entry keyed as on the page.
                  ..._rows.layOut([
                    PresenceRow(
                      const ValueKey('playlist-count'),
                      Padding(
                        padding: const EdgeInsetsDirectional.only(start: 8, top: 2, bottom: 10),
                        child: TrackCountLine(
                          count: entries.length,
                          totalSec: entries.fold(0, (sum, e) => sum + (e.$2.durationSec ?? 0)),
                        ),
                      ),
                    ),
                    for (final (i, track, album) in entries)
                      PresenceRow(
                        ValueKey('entry-$i'),
                        DemoTarget(
                          'row$i',
                          child: PlaylistTrackRow(
                            track: track,
                            coverUrl: album.coverUrl,
                            onTap: () {},
                            onLongPress: () => _openSheet(i),
                          ),
                        ),
                      ),
                  ]),
                ],
              ),
            ),
            DemoSongSheet(
              open: _open,
              onDismiss: () => put(() => _open = false),
              track: demoTrack(_row, demoAlbums[demoPlaylistAlbums[_row]]),
              downloaded: false,
              onRemoveFromPlaylist: () => put(() => _removed.add(_row)),
            ),
          ],
        ),
      ),
    );
  }
}
