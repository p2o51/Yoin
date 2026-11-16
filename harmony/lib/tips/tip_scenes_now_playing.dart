/// Now Playing on the demo phone (Android ui/tips TipNowPlayingScenes.kt):
/// the real transport, play-mode button, wave bar, note lines, lyric list
/// and lyric tools, over the demo song.
library;

import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart' show MediaId, Track;
import '../nowplaying/devices/devices_pill.dart';
import '../nowplaying/devices/devices_sheet.dart';
import '../nowplaying/devices/devices_strings.dart';
import '../nowplaying/devices/devices_view_model.dart';
import '../nowplaying/lyric_tools.dart';
import '../nowplaying/lyrics_share.dart';
import '../nowplaying/lyrics_view.dart';
import '../nowplaying/note_line.dart';
import '../nowplaying/now_playing_screen.dart'
    show
        NowPlayingPostureSwap,
        NowPlayingWritePill,
        TabletopEyebrow,
        TabletopExpandLyricsKey,
        tabletopCoverSize,
        tabletopSplit;
import '../nowplaying/now_playing_view_model.dart' show LyricsReady;
import '../nowplaying/nowplaying_strings.dart';
import '../nowplaying/play_mode_cycle.dart' show playModeSplitHoldMs;
import '../nowplaying/tabletop_strings.dart';
import '../nowplaying/transport.dart';
import '../playback/play_mode.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/cover_art.dart';
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
      screenWidth: TipSceneTokens.tabletopScreenWidth,
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
      screenWidth: TipSceneTokens.tabletopScreenWidth,
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

/// Now Playing › Devices (Android TipDevicesScene.kt, here without Spotify):
/// the Devices pill opens the real sheet, which looks for speakers again; a
/// DLNA speaker on the same Wi-Fi turns up and the dot moves the music to it.
/// Nothing leaves the phone.
class DevicesDemo extends StatefulWidget {
  const DevicesDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<DevicesDemo> createState() => _DevicesDemoState();
}

class _DevicesDemoState extends State<DevicesDemo> with DemoSceneState {
  static const _speakerId = 'tip-dlna';

  bool _open = false;
  bool _searching = false;
  bool _found = false;
  bool _busy = false;
  bool _onSpeaker = false;

  Timer? _search;

  @override
  void dispose() {
    _search?.cancel();
    super.dispose();
  }

  /// A refresh (the user's) reads for a moment and finds the speaker.
  void _refresh() {
    put(() => _searching = true);
    _search?.cancel();
    _search = Timer(const Duration(milliseconds: TipSceneTokens.refreshMs), () {
      put(() {
        _searching = false;
        _found = true;
      });
    });
  }

  /// Opening the sheet searches the Wi-Fi again (as the real one does).
  void _openSheet() {
    put(() => _open = true);
    _refresh();
  }

  Future<void> _script(DemoFinger f) async {
    _search?.cancel();
    put(() {
      _open = false;
      _searching = false;
      _found = false;
      _busy = false;
      _onSpeaker = false;
    });
    await f.wait(700);
    await f.moveToKey(const ValueKey('np-pill-devices'));
    await f.tap(_openSheet);
    await f.lift();
    // The search finds the speaker on the Wi-Fi.
    await f.wait(TipSceneTokens.refreshMs + 600);
    await f.moveToKey(const ValueKey('device-$_speakerId'));
    await f.tap(() => put(() => _busy = true));
    await f.lift();
    await f.wait(800);
    put(() {
      _busy = false;
      _onSpeaker = true;
    });
    await f.wait(2200);
    put(() => _open = false);
    await f.wait(500);
  }

  DevicesSheetState get _sheet => DevicesSheetState(
    providerId: MediaId.providerSubsonic,
    loading: _searching,
    busyDeviceId: _busy ? _speakerId : null,
    devices: [
      YoinDevice(
        id: YoinDevice.localId,
        kind: DeviceKind.local,
        name: '',
        isActive: !_onSpeaker,
        status: _onSpeaker ? DeviceStatus.switchBack : DeviceStatus.none,
      ),
      const YoinDevice(
        id: YoinDevice.systemCastId,
        kind: DeviceKind.systemCast,
        name: '',
        isActive: false,
        status: DeviceStatus.notCasting,
      ),
      if (_found)
        YoinDevice(
          id: _speakerId,
          kind: DeviceKind.dlna,
          name: demoSpeakerName,
          isActive: _onSpeaker,
          status: _onSpeaker ? DeviceStatus.casting : DeviceStatus.dlna,
        ),
    ],
  );

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(2, demoAlbums[2]);
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
              below: Align(
                alignment: AlignmentDirectional.topStart,
                child: Padding(
                  padding: const EdgeInsets.only(top: TipSceneTokens.titleGap),
                  child: NowPlayingDevicesPill(label: DevicesStrings.of(context).pillDevices, onTap: _openSheet),
                ),
              ),
            ),
            DemoSheet(
              open: _open,
              onDismiss: () => put(() => _open = false),
              // The real sheet's height is the window's to give; the phone's sheet sits in a bounded one.
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: TipSceneTokens.devicesSheetMax),
                child: DevicesSheetBody(
                  state: _sheet,
                  onRefresh: _refresh,
                  onSelect: (device) => put(() {
                    _busy = false;
                    _onSpeaker = device.id == _speakerId;
                  }),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Tabletop (Android NowPlayingScreen TabletopPlayingContent; the fold
/// posture from yoin/posture): Now Playing is open, the phone folds halfway
/// on its middle and the lower half leans back flat, and Now Playing swaps
/// into the kickstand layout with the real posture swap — the cover, where
/// it plays from, the song and a peek of the lyrics up top; the real
/// transport with its expand-lyrics key and the Queue and Devices pills on
/// the deck. The dot lets the lyrics fill the top half and brings the cover
/// back, then the phone unfolds. Nothing plays.
class TabletopDemo extends StatefulWidget {
  const TabletopDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<TabletopDemo> createState() => _TabletopDemoState();
}

class _TabletopDemoState extends State<TabletopDemo> with DemoSceneState, TickerProviderStateMixin {
  /// 0 flat → 1 half folded: the deck leans back.
  late final SpringValue _fold = SpringValue(vsync: this, initial: 0, spring: YoinSprings.slowSpatial);

  /// 0 the song leads the display → 1 the lyrics fill it (the real pane's emphasis).
  late final SpringValue _emphasis = SpringValue(vsync: this, initial: 0, spring: YoinSprings.slowSpatial);
  final ValueNotifier<bool> _autoFollow = ValueNotifier(true);
  final ValueNotifier<TransportKey> _pressed = ValueNotifier(TransportKey.none);
  bool _tabletop = false;
  bool _expanded = false;

  @override
  void dispose() {
    _fold.dispose();
    _emphasis.dispose();
    _autoFollow.dispose();
    _pressed.dispose();
    super.dispose();
  }

  void _spring(SpringValue value, double target) {
    if (MediaQuery.disableAnimationsOf(context)) {
      value.snapTo(target);
    } else {
      value.animateTo(target);
    }
  }

  void _setExpanded(bool expanded) {
    put(() => _expanded = expanded);
    _spring(_emphasis, expanded ? 1 : 0);
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _tabletop = false;
      _expanded = false;
    });
    _fold.snapTo(0);
    _emphasis.snapTo(0);
    await f.wait(1200);
    // Half folded: the kickstand layout, the deck leaning back.
    put(() => _tabletop = true);
    _spring(_fold, 1);
    await f.wait(1600);
    await f.moveToKey(const ValueKey('np-tabletop-expand-lyrics'));
    await f.tap(() => _setExpanded(true));
    await f.wait(2200);
    await f.tap(() => _setExpanded(false));
    await f.lift();
    await f.wait(1400);
    // Opened flat again: the deck stands up, then Now Playing is the phone's own.
    _spring(_fold, 0);
    await f.wait(TipSceneTokens.unfoldMs);
    put(() => _tabletop = false);
    await f.wait(1400);
  }

  @override
  Widget build(BuildContext context) {
    final track = demoTrack(0, demoAlbums[0]);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      screenWidth: TipSceneTokens.tabletopScreenWidth,
      child: DemoPlayerScope(
        track: track,
        playing: widget.playing,
        positionMs: TipSceneTokens.lyricsStartMs,
        builder: (context, player) => NowPlayingPostureSwap(
          tabletop: _tabletop,
          child: _tabletop
              ? AnimatedBuilder(
                  animation: _fold.animation,
                  builder: (context, _) => _DemoTabletop(
                    track: track,
                    player: player,
                    playing: widget.playing,
                    fold: _fold.value.clamp(0.0, 1.0),
                    emphasis: _emphasis,
                    expanded: _expanded,
                    onExpand: () => _setExpanded(!_expanded),
                    autoFollow: _autoFollow,
                    pressed: _pressed,
                  ),
                )
              : DemoNowPlaying(track: track, clock: player.clock, playing: widget.playing),
        ),
      ),
    );
  }
}

/// The kickstand layout in the phone (the real pane's geometry, with no
/// hinge reading: a 50 / 50 split): the display up top, the deck below,
/// leaning back by [fold] about the crease.
class _DemoTabletop extends StatelessWidget {
  const _DemoTabletop({
    required this.track,
    required this.player,
    required this.playing,
    required this.fold,
    required this.emphasis,
    required this.expanded,
    required this.onExpand,
    required this.autoFollow,
    required this.pressed,
  });

  final Track track;
  final DemoPlayerScopeState player;
  final bool playing;
  final double fold;
  final SpringValue emphasis;
  final bool expanded;
  final VoidCallback onExpand;
  final ValueNotifier<bool> autoFollow;
  final ValueNotifier<TransportKey> pressed;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final tabletopStrings = TabletopStrings.of(context);
    final mq = MediaQuery.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final split = tabletopSplit(constraints.maxHeight, null);
        final cover = tabletopCoverSize(constraints.maxWidth, split.display);
        final controlSize = fitTransportControlSize(
          rowWidth: constraints.maxWidth - 2 * TabletopTokens.deckPaddingH,
          controlSize: TabletopTokens.controlSize,
          playKeyWidth: transportPlayKeyMaxWidth(context),
          hasExtraKey: true,
        );
        final display = Stack(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: TabletopTokens.displayPaddingH,
                vertical: TabletopTokens.displayPaddingV,
              ),
              child: Row(
                children: [
                  Padding(
                    padding: const EdgeInsets.all(TabletopTokens.coverAir),
                    child: CoverArt(cover: track.coverArt, size: cover, radius: ShapeTokens.nowPlayingCover),
                  ),
                  const SizedBox(width: TabletopTokens.coverToText),
                  Expanded(
                    child: AnimatedBuilder(
                      animation: emphasis.animation,
                      builder: (context, _) {
                        final e = emphasis.value;
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            TabletopEyebrow(kind: strings.fromAlbum, name: track.album),
                            const SizedBox(height: TabletopTokens.eyebrowGap),
                            Text(
                              track.title ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              textScaler: TextScaler.noScaling,
                              style: text.headlineSmall!.copyWith(
                                fontSize: TabletopTokens.titleSize - TabletopTokens.titleShrink * e,
                                color: colors.onSurface,
                              ),
                            ),
                            const SizedBox(height: TabletopTokens.titleToArtist),
                            Text(
                              track.artist ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              textScaler: TextScaler.noScaling,
                              style: text.titleMedium!.copyWith(
                                fontSize: TabletopTokens.artistSize - TabletopTokens.artistShrink * e,
                                color: colors.onSurfaceVariant,
                              ),
                            ),
                            const SizedBox(height: TabletopTokens.artistToLyrics),
                            Expanded(
                              child: LayoutBuilder(
                                builder: (context, box) => Center(
                                  child: SizedBox(
                                    height: lerpDouble(
                                      math.min(TabletopTokens.collapsedLyrics, box.maxHeight),
                                      box.maxHeight,
                                      e,
                                    ),
                                    child: MediaQuery(
                                      data: mq.copyWith(
                                        textScaler: TextScaler.linear(
                                          mq.textScaler.scale(1) *
                                              (TabletopTokens.lyricScale + TabletopTokens.lyricScaleGrowth * e),
                                        ),
                                      ),
                                      child: LyricsView(
                                        mode: LyricsListMode.full,
                                        lyrics: const LyricsReady(demoLyrics),
                                        songKey: 'tip-tabletop',
                                        clock: player.clock,
                                        onSeek: player.seek,
                                        autoFollow: autoFollow,
                                        horizontalInset: -NowPlayingTokens.columnPadding,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
            PositionedDirectional(
              top: TabletopTokens.closeInset,
              end: TabletopTokens.closeInset,
              child: IconButton(
                tooltip: tabletopStrings.cdClose,
                onPressed: () {},
                icon: Icon(YoinSymbols.chevronDown, color: colors.onSurfaceVariant),
              ),
            ),
          ],
        );
        final deck = Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: TabletopTokens.deckPaddingH - NowPlayingTokens.columnPadding,
            vertical: TabletopTokens.deckPaddingV,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.end,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TransportControls(
                isPlaying: playing,
                isWavePlaying: playing,
                playMode: PlayMode.repeatAll,
                clock: player.clock,
                durationMs: (track.durationSec ?? 0) * 1000,
                labelsUnder: false,
                waveVisible: true,
                controlSize: controlSize,
                beforePlayMode: TabletopExpandLyricsKey(
                  expanded: expanded,
                  size: controlSize,
                  label: expanded ? tabletopStrings.cdCollapseLyrics : tabletopStrings.cdExpandLyrics,
                  onTap: onExpand,
                ),
                onPlayPause: () {},
                onNext: () {},
                onPrevious: () {},
                onSetPlayMode: (_) {},
                onSeek: (fraction) => player.seek((fraction * (track.durationSec ?? 0) * 1000).round()),
                pressed: pressed,
              ),
              const SizedBox(height: TabletopTokens.controlsToPills),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                child: SizedBox(
                  height: TabletopTokens.pillHeight,
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        NowPlayingPillButton(
                          icon: YoinSymbols.queue,
                          label: strings.pillQueue,
                          onTap: () {},
                          background: colors.primary,
                          foreground: colors.onPrimary,
                        ),
                        const SizedBox(width: NowPlayingTokens.pillGap),
                        NowPlayingDevicesPill(label: DevicesStrings.of(context).pillDevices, onTap: () {}),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
        return ColoredBox(
          color: colors.surface,
          child: Column(
            children: [
              SizedBox(height: split.display, child: display),
              Expanded(
                // Behind the leaning deck: the table's side of the phone.
                child: ColoredBox(
                  color: Color.lerp(colors.surface, colors.surfaceContainerHighest, fold)!,
                  child: Transform(
                    alignment: Alignment.topCenter,
                    transform: Matrix4.identity()
                      ..setEntry(3, 2, TipSceneTokens.foldPerspective)
                      ..rotateX(TipSceneTokens.foldTilt * fold),
                    child: DecoratedBox(
                      // The crease, darker the further the deck leans.
                      decoration: BoxDecoration(
                        color: colors.surface,
                        border: Border(
                          top: BorderSide(
                            color: colors.outlineVariant.withValues(alpha: fold),
                            width: TipSceneTokens.creaseWidth,
                          ),
                        ),
                      ),
                      child: deck,
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
