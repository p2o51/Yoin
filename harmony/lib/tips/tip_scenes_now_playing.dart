/// Now Playing on the demo phone (Android ui/tips TipNowPlayingScenes.kt):
/// the real transport, play-mode button, wave bar, note lines, lyric list
/// and lyric tools, over the demo song.
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/lyric_tools.dart';
import '../nowplaying/lyrics_share.dart';
import '../nowplaying/lyrics_view.dart';
import '../nowplaying/note_line.dart';
import '../nowplaying/now_playing_screen.dart' show NowPlayingWritePill;
import '../nowplaying/now_playing_view_model.dart' show LyricsReady;
import '../nowplaying/nowplaying_strings.dart';
import '../nowplaying/play_mode_cycle.dart' show playModeSplitHoldMs;
import '../nowplaying/transport.dart';
import '../playback/play_mode.dart';
import '../theme/tokens.dart';
import '../ui/component/meta_line.dart' show formatTrackDuration;
import '../ui/component/track_menu_sheet.dart';
import 'demo_data.dart';
import 'demo_phone.dart';
import 'tip_scene_parts.dart';
import 'tip_scenes_library.dart' show DemoSceneState;

/// Long-press the cover → the song sheet; with [keepScreenOn], switch Keep screen on in it.
class CoverMenuDemo extends StatefulWidget {
  const CoverMenuDemo({super.key, required this.playing, required this.keepScreenOn});
  final bool playing;
  final bool keepScreenOn;

  @override
  State<CoverMenuDemo> createState() => _CoverMenuDemoState();
}

class _CoverMenuDemoState extends State<CoverMenuDemo> with DemoSceneState {
  bool _open = false;
  bool _awake = false;

  Future<void> _script(DemoFinger f) async {
    put(() {
      _open = false;
      _awake = false;
    });
    await f.wait(300);
    await f.moveTo('cover', fy: 0.45);
    await f.hold(() => put(() => _open = true));
    if (widget.keepScreenOn) {
      await f.wait(500);
      await f.moveTo('keep', fx: 0.86);
      await f.tap(() => put(() => _awake = !_awake));
      await f.wait(1200);
    } else {
      await f.lift();
      await f.wait(1800);
    }
    await f.moveTo('cover', fy: 0.3);
    await f.tap(() => put(() => _open = false));
    await f.wait(500);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(0, demoAlbums[0]);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      screenWidth: TipTokens.tallScreenWidth,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        builder: (context, player) => Stack(
          children: [
            DemoNowPlaying(
              track: track,
              clock: player.clock,
              playing: widget.playing,
              onCoverLongPress: () => put(() => _open = true),
            ),
            DemoSongSheet(
              open: _open,
              onDismiss: () => put(() => _open = false),
              track: track,
              nowPlaying: true,
              footer: DemoTarget(
                'keep',
                child: KeepScreenOnRow(on: _awake, onChanged: (on) => put(() => _awake = on)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Tap the cover → Focus: the song and the rest step away and the cover
/// grows; tap it again and the rest pose comes back.
class FocusCoverDemo extends StatefulWidget {
  const FocusCoverDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<FocusCoverDemo> createState() => _FocusCoverDemoState();
}

class _FocusCoverDemoState extends State<FocusCoverDemo> with DemoSceneState {
  bool _focused = false;

  Future<void> _script(DemoFinger f) async {
    put(() => _focused = false);
    await f.wait(600);
    await f.moveTo('cover', fy: 0.45);
    await f.tap(() => put(() => _focused = true));
    await f.lift();
    await f.wait(2400);
    await f.moveTo('cover', fy: 0.45);
    await f.tap(() => put(() => _focused = false));
    await f.lift();
    await f.wait(1400);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(0, demoAlbums[3]);
    return DemoPhone(
      playing: widget.playing,
      tall: true,
      script: _script,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        builder: (context, player) => DemoSpring(
          target: _focused ? 1 : 0,
          builder: (context, focus) => DemoNowPlaying(
            track: track,
            clock: player.clock,
            playing: widget.playing,
            focus: focus,
            onCoverTap: () => put(() => _focused = !_focused),
          ),
        ),
      ),
    );
  }
}

/// The real play-mode button in its place in the transport: tap through the modes, hold to split.
class PlayModesDemo extends StatefulWidget {
  const PlayModesDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<PlayModesDemo> createState() => _PlayModesDemoState();
}

class _PlayModesDemoState extends State<PlayModesDemo> with DemoSceneState {
  PlayMode _mode = PlayMode.repeatAll;

  bool _isButton(Widget w) => w is PlayModeButton;

  Future<void> _script(DemoFinger f) async {
    put(() => _mode = PlayMode.repeatAll);
    await f.wait(300);
    await f.moveToWhere(_isButton);
    await f.tap(() => put(() => _mode = _mode.next()));
    await f.wait(600);
    await f.tap(() => put(() => _mode = _mode.next()));
    await f.wait(600);
    await f.hold(() => f.stateOf<PlayModeButtonState>()?.showSplit());
    await f.wait(250);
    await f.moveToWhere(_isButton, fx: 0.27);
    await f.tap(() {
      put(() => _mode = _mode.withShuffle(!_mode.shuffled));
      f.stateOf<PlayModeButtonState>()?.showSplit();
    });
    await f.wait(500);
    await f.moveToWhere(_isButton, fx: 0.73);
    await f.tap(() {
      put(() => _mode = _mode.nextRepeat());
      f.stateOf<PlayModeButtonState>()?.showSplit();
    });
    await f.lift();
    await f.wait(playModeSplitHoldMs + 600);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(1, demoAlbums[2]);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      screenWidth: TipTokens.tallScreenWidth,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        playMode: _mode,
        builder: (context, player) => DemoNowPlaying(
          track: track,
          clock: player.clock,
          playing: widget.playing,
          playMode: _mode,
          onSetPlayMode: (mode) => put(() => _mode = mode),
        ),
      ),
    );
  }
}

/// Write keeps the second it was tapped at: the note is stamped with it and
/// the wave bar marks it; tapping the note goes back to that second.
class NoteMomentsDemo extends StatefulWidget {
  const NoteMomentsDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<NoteMomentsDemo> createState() => _NoteMomentsDemoState();
}

class _NoteMomentsDemoState extends State<NoteMomentsDemo> with DemoSceneState {
  final List<int> _anchors = [];
  int _active = -1;
  DemoPlayerScopeState? _player;

  void _write() {
    final at = _player?.clock.positionMs() ?? TipSceneTokens.demoPositionMs;
    put(() {
      _anchors.add(at);
      _active = _anchors.length - 1;
    });
  }

  void _goTo(int index) {
    _player?.seek(_anchors[index]);
    put(() => _active = index);
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _anchors.clear();
      _active = -1;
    });
    _player?.seek(TipSceneTokens.demoPositionMs);
    await f.wait(900);
    await f.moveToWhere((w) => w is NowPlayingWritePill);
    await f.tap(_write);
    await f.lift();
    await f.wait(2600);
    await f.moveTo('note0');
    await f.tap(() => _goTo(0));
    await f.lift();
    await f.wait(1800);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(4, demoAlbums[4]);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      screenWidth: TipTokens.tallScreenWidth,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        builder: (context, player) {
          _player = player;
          return DemoNowPlaying(
            track: track,
            clock: player.clock,
            playing: widget.playing,
            noteAnchorsMs: List.of(_anchors),
            below: ListView(
              physics: const NeverScrollableScrollPhysics(),
              padding: EdgeInsets.zero,
              children: [
                const SizedBox(height: TipSceneTokens.titleGap),
                Align(
                  alignment: AlignmentDirectional.centerEnd,
                  child: NowPlayingWritePill(onTap: _write),
                ),
                for (final (i, at) in _anchors.indexed)
                  DemoTarget(
                    'note$i',
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => _goTo(i),
                      child: NoteLine(
                        stamp: formatTrackDuration(at ~/ 1000),
                        text: demoMemoryExcerpt,
                        active: _active == i,
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

/// The expanded lyrics with the real lyric tools under them; the demo song's
/// lines are synced, and Translate shows the line's translation under it.
class _DemoLyrics extends StatelessWidget {
  const _DemoLyrics({required this.controller, required this.player, required this.translated});

  final LyricToolsController controller;
  final DemoPlayerScopeState player;
  final bool translated;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return LyricToolsScope(
      controller: controller,
      child: ColoredBox(
        color: colors.surface,
        child: Column(
          children: [
            const SizedBox(height: TipSceneTokens.pageTop),
            Expanded(
              child: LyricsView(
                mode: LyricsListMode.full,
                lyrics: const LyricsReady(demoLyrics),
                songKey: 'tip-lyrics',
                clock: player.clock,
                onSeek: player.seek,
                autoFollow: controller.autoFollow,
                showTranslation: translated,
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(bottom: TipSceneTokens.barBottom),
              child: DemoTarget('lyricTools', child: const LyricToolsBar()),
            ),
          ],
        ),
      ),
    );
  }
}

/// Lyric tools: Translate shows the lines' translations, Search finds lyrics
/// from the sources and applies one.
class LyricsToolsDemo extends StatefulWidget {
  const LyricsToolsDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<LyricsToolsDemo> createState() => _LyricsToolsDemoState();
}

class _LyricsToolsDemoState extends State<LyricsToolsDemo> with DemoSceneState {
  final LyricToolsController _tools = LyricToolsController(autoFollow: ValueNotifier(true));
  bool _translated = false;
  bool _searching = false;
  DemoPlayerScopeState? _player;

  @override
  void initState() {
    super.initState();
    _tools.actions = _actions();
  }

  LyricToolsActions _actions() => LyricToolsActions(
    hasLyrics: true,
    hasSyncedLyrics: true,
    busy: false,
    onSearch: (_) => put(() => _searching = true),
    onTranslate: () => put(() => _translated = !_translated),
    onEdit: () {},
    onCopy: () {},
    onShare: () {},
  );

  @override
  void dispose() {
    _tools.autoFollow.dispose();
    _tools.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _translated = false;
      _searching = false;
    });
    _player?.seek(TipSceneTokens.lyricsStartMs);
    await f.wait(800);
    await f.moveToKey(const ValueKey('lyrics-tool-translate'));
    await f.tap(() => put(() => _translated = true));
    await f.lift();
    await f.wait(2000);
    await f.moveToKey(const ValueKey('lyrics-tool-search'));
    await f.tap(() => put(() => _searching = true));
    await f.wait(900);
    await f.moveTo('lyricResult0');
    await f.tap(() => put(() => _searching = false));
    await f.lift();
    await f.wait(1400);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(0, demoAlbums[0]);
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      screenWidth: TipTokens.tallScreenWidth,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        positionMs: TipSceneTokens.lyricsStartMs,
        builder: (context, player) {
          _player = player;
          return Stack(
            children: [
              _DemoLyrics(controller: _tools, player: player, translated: _translated),
              DemoSheet(
                open: _searching,
                onDismiss: () => put(() => _searching = false),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(
                    TipSceneTokens.pagePaddingH,
                    0,
                    TipSceneTokens.pagePaddingH,
                    TipSceneTokens.pageTop,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Container(
                        height: TipSceneTokens.searchField,
                        decoration: ShapeDecoration(color: colors.surfaceContainerHigh, shape: const StadiumBorder()),
                        padding: const EdgeInsets.symmetric(horizontal: TipSceneTokens.pagePaddingH),
                        child: Row(
                          children: [
                            Icon(YoinSymbols.search, color: colors.onSurfaceVariant),
                            const SizedBox(width: TipSceneTokens.titleGap),
                            Expanded(
                              child: Text(
                                '${track.title} ${track.artist}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.bodyLarge!.copyWith(color: colors.onSurface),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: TipSceneTokens.titleGap),
                      for (final (i, source) in const ['qq', 'netease', 'lrclib'].indexed)
                        DemoTarget(
                          'lyricResult$i',
                          child: ListTile(
                            onTap: () => put(() => _searching = false),
                            title: Text(track.title ?? ''),
                            subtitle: Text('${track.artist} · ${strings.lyricsProviderLabel(source)}'),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

/// Select: ✓ turns the lines into picks; two lines picked, Share makes the
/// real share card of them; the close key leaves.
class LyricsSelectDemo extends StatefulWidget {
  const LyricsSelectDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<LyricsSelectDemo> createState() => _LyricsSelectDemoState();
}

class _LyricsSelectDemoState extends State<LyricsSelectDemo> with DemoSceneState {
  final LyricToolsController _tools = LyricToolsController(autoFollow: ValueNotifier(true));
  bool _sharing = false;
  DemoPlayerScopeState? _player;

  @override
  void initState() {
    super.initState();
    _tools.actions = LyricToolsActions(
      hasLyrics: true,
      hasSyncedLyrics: true,
      busy: false,
      onSearch: (_) {},
      onEdit: () {},
      onCopy: () {},
      onShare: () => put(() => _sharing = true),
    );
  }

  @override
  void dispose() {
    _tools.autoFollow.dispose();
    _tools.dispose();
    super.dispose();
  }

  Future<void> _pick(DemoFinger f, int line) async {
    await f.moveToText(demoLyrics.rows[line].text);
    await f.tap(() => _tools.selection.toggle(line));
    await f.wait(350);
  }

  Future<void> _script(DemoFinger f) async {
    put(() => _sharing = false);
    _tools.selection.exit();
    _player?.seek(TipSceneTokens.lyricsStartMs);
    await f.wait(700);
    await f.moveToKey(const ValueKey('lyrics-tool-select'));
    await f.tap(_tools.select);
    await f.wait(500);
    await _pick(f, 1);
    await _pick(f, 2);
    await f.moveToKey(const ValueKey('lyrics-select-share'));
    await f.tap(() => put(() => _sharing = true));
    await f.lift();
    await f.wait(2200);
    await f.moveToPoint(0.5, 0.1);
    await f.tap(() => put(() => _sharing = false));
    await f.wait(400);
    await f.moveToKey(const ValueKey('lyrics-select-close'));
    await f.tap(_tools.selection.exit);
    await f.wait(600);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(0, demoAlbums[0]);
    final picked = _tools.selection.selected.toList()..sort();
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      screenWidth: TipTokens.tallScreenWidth,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        positionMs: TipSceneTokens.lyricsStartMs,
        builder: (context, player) {
          _player = player;
          return Stack(
            children: [
              _DemoLyrics(controller: _tools, player: player, translated: false),
              DemoSheet(
                open: _sharing,
                onDismiss: () => put(() => _sharing = false),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(
                    TipSceneTokens.pagePaddingH,
                    0,
                    TipSceneTokens.pagePaddingH,
                    TipSceneTokens.pageTop,
                  ),
                  child: LyricsShareCard(
                    lines: [
                      for (final i in picked.isEmpty ? const [1, 2] : picked) demoLyrics.rows[i],
                    ],
                    passageStarts: const {0},
                    showTranslation: false,
                    songTitle: track.title ?? '',
                    artist: track.artist ?? '',
                    cover: track.coverArt,
                    colors: lyricsCardColors(LyricsCardTone.values.first, Theme.of(context).colorScheme),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
