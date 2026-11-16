/// How a tip is done, as a small live phone built from the app's own
/// components (Android ui/tips/TipIllustration.kt; owner 2026-10-19: native
/// UI the user can press too, with nothing behind it). A touch dot acts the
/// gesture out on a loop and the phone answers the way the app does. It runs
/// only while [TipIllustration.playing]; reduced motion keeps the phone
/// still but still pressable. Decorative for accessibility: the text says it.
library;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../home/home_bubble_tips.dart';
import '../theme/tokens.dart';
import 'demo_phone.dart';
import 'tip_scenes_home.dart';
import 'tip_scenes_library.dart';
import 'tip_scenes_now_playing.dart';
import 'tip_scenes_settings.dart';

class TipIllustration extends StatelessWidget {
  const TipIllustration({super.key, required this.tip, required this.playing});

  final HomeBubbleTip tip;
  final bool playing;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return ExcludeSemantics(
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.surfaceContainerLow,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(TipTokens.illustrationCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: TipTokens.illustrationPaddingV),
          child: Center(child: tipDemo(tip, playing)),
        ),
      ),
    );
  }
}

/// The demo phone for [tip].
Widget tipDemo(HomeBubbleTip tip, bool playing) => switch (tip) {
  HomeBubbleTip.tabletop => TabletopDemo(playing: playing),
  HomeBubbleTip.spatialAudio => SpatialAudioDemo(playing: playing),
  HomeBubbleTip.lyricsTools => LyricsToolsDemo(playing: playing),
  HomeBubbleTip.lyricsSelect => LyricsSelectDemo(playing: playing),
  HomeBubbleTip.memoriesDiary => MemoriesDiaryDemo(playing: playing),
  HomeBubbleTip.cloudSync => CloudSyncDemo(playing: playing),
  HomeBubbleTip.welcomeGuide => WelcomeGuideDemo(playing: playing),
  HomeBubbleTip.keyboardShortcuts => KeyboardShortcutsDemo(playing: playing),
  HomeBubbleTip.volumeLeveling => VolumeLevelingDemo(playing: playing),
  HomeBubbleTip.homeScreenWidgets => HomeScreenWidgetsDemo(playing: playing),
  HomeBubbleTip.libraryRefresh => LibraryRefreshDemo(playing: playing),
  HomeBubbleTip.memoryLedger => MemoryLedgerDemo(playing: playing),
  HomeBubbleTip.aiServices => AiServicesDemo(playing: playing),
  HomeBubbleTip.settingsRegrouped => SettingsRegroupedDemo(playing: playing),
  HomeBubbleTip.continueOn => ContinueOnDemo(playing: playing),
  HomeBubbleTip.lyricHighlight => LyricHighlightDemo(playing: playing),
  HomeBubbleTip.screenCorners => ScreenCornersDemo(playing: playing),
  HomeBubbleTip.memoriesAllAccounts => MemoriesAllAccountsDemo(playing: playing),
  HomeBubbleTip.devices => DevicesDemo(playing: playing),
  HomeBubbleTip.accountQuickSwitch => AccountQuickSwitchDemo(playing: playing),
  HomeBubbleTip.miniPlayerMenu => MiniPlayerMenuDemo(playing: playing),
  HomeBubbleTip.focusCover => FocusCoverDemo(playing: playing),
  HomeBubbleTip.downloads => DownloadsDemo(playing: playing),
  HomeBubbleTip.editHome => EditHomeDemo(playing: playing),
  HomeBubbleTip.pullForMemories => PullForMemoriesDemo(playing: playing),
  HomeBubbleTip.coverMenu => CoverMenuDemo(playing: playing, keepScreenOn: false),
  HomeBubbleTip.songMenu => SongMenuDemo(playing: playing),
  HomeBubbleTip.playModes => PlayModesDemo(playing: playing),
  HomeBubbleTip.noteMoments => NoteMomentsDemo(playing: playing),
  HomeBubbleTip.librarySearch => LibrarySearchDemo(playing: playing),
  HomeBubbleTip.keepScreenOn => CoverMenuDemo(playing: playing, keepScreenOn: true),
  HomeBubbleTip.scrollEdge => ScrollEdgeDemo(playing: playing),
  HomeBubbleTip.name => NameDemo(playing: playing),
};

/// 余韻: the Yoin mark; each tap (the demo's, or the user's) sounds a note
/// whose rings keep spreading.
class NameDemo extends StatefulWidget {
  const NameDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<NameDemo> createState() => _NameDemoState();
}

class _NameDemoState extends State<NameDemo> with SingleTickerProviderStateMixin {
  final List<Duration> _notes = [];
  final ValueNotifier<Duration> _now = ValueNotifier(Duration.zero);
  late final Ticker _ticker;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_tick);
  }

  void _tick(Duration elapsed) {
    _now.value = elapsed;
    const life = Duration(milliseconds: TipSceneTokens.noteLifeMs + 2 * TipSceneTokens.noteEchoMs);
    _notes.removeWhere((start) => elapsed - start > life);
    if (_notes.isEmpty) _ticker.stop();
  }

  void _sound() {
    if (MediaQuery.disableAnimationsOf(context)) return;
    if (!_ticker.isActive) {
      _notes.clear();
      _now.value = Duration.zero;
      _ticker.start();
    }
    _notes.add(_now.value);
  }

  @override
  void dispose() {
    _ticker.dispose();
    _now.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    await f.moveTo('mark');
    await f.tap(_sound);
    await f.wait(1200);
    await f.tap(_sound);
    await f.lift();
    await f.wait(2000);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: ColoredBox(
        color: colors.surfaceContainer,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _sound,
          child: CustomPaint(
            painter: _RingsPainter(notes: _notes, now: _now, color: colors.primary),
            child: Center(
              child: DemoTarget(
                'mark',
                child: const SizedBox(width: TipSceneTokens.mark, child: DemoMark()),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _RingsPainter extends CustomPainter {
  _RingsPainter({required this.notes, required this.now, required this.color}) : super(repaint: now);

  final List<Duration> notes;
  final ValueNotifier<Duration> now;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final max = size.height * 0.6;
    for (final start in notes) {
      for (var k = 0; k < 3; k++) {
        final ms = (now.value - start).inMilliseconds - k * TipSceneTokens.noteEchoMs;
        final p = (ms / TipSceneTokens.noteLifeMs).clamp(0.0, 1.0);
        if (p <= 0 || p >= 1) continue;
        canvas.drawCircle(
          c,
          max * p,
          Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = (1 - p) * 6 + 1.5
            ..color = color.withValues(alpha: (1 - p) * 0.6),
        );
      }
    }
  }

  @override
  bool shouldRepaint(_RingsPainter old) => true;
}
