/// Settings on the demo phone (Android ui/tips TipVolumeLevelingScene.kt,
/// TipAiScene.kt, TipSettingsScene.kt): the real Settings rows and page
/// bodies with choices of their own — the demo never changes the real ones.
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../landing/landing_bubble.dart';
import '../landing/landing_mascot.dart';
import '../landing/landing_strings.dart';
import '../playback/replay_gain.dart';
import '../settings/ai_settings_screen.dart';
import '../settings/settings_components.dart';
import '../settings/settings_metrics.dart';
import '../settings/settings_rows.dart';
import '../settings/settings_strings.dart';
import '../settings/sync/cloud_sync_strings.dart';
import '../settings/volume_leveling_screen.dart';
import '../theme/tokens.dart';
import 'demo_phone.dart';
import 'tip_scene_parts.dart';
import 'tip_scenes_library.dart' show DemoSceneState;

/// A Subsonic account's Volume leveling page, the real page body; the dot picks By track, then By album.
class VolumeLevelingDemo extends StatefulWidget {
  const VolumeLevelingDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<VolumeLevelingDemo> createState() => _VolumeLevelingDemoState();
}

class _VolumeLevelingDemoState extends State<VolumeLevelingDemo> with DemoSceneState {
  ReplayGainMode _mode = ReplayGainMode.off;

  Future<void> _script(DemoFinger f) async {
    put(() => _mode = ReplayGainMode.off);
    await f.wait(700);
    await f.moveTo('leveling-track');
    await f.wait(250);
    await f.tap(() => put(() => _mode = ReplayGainMode.track));
    await f.wait(900);
    await f.moveTo('leveling-album');
    await f.wait(250);
    await f.tap(() => put(() => _mode = ReplayGainMode.album));
    await f.lift();
    await f.wait(2400);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: DemoSettingsPage(
      title: SettingsStrings.of(context).volumeLevelingTitle,
      children: [
        VolumeLevelingPageBody(
          selected: _mode,
          onSelect: (mode) => put(() => _mode = mode),
          rowWrapper: (mode, row) => DemoTarget('leveling-${mode.name}', child: row),
        ),
      ],
    ),
  );
}

/// Settings › AI features, the real tier cards and language button: the dot picks Best.
class AiServicesDemo extends StatefulWidget {
  const AiServicesDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<AiServicesDemo> createState() => _AiServicesDemoState();
}

class _AiServicesDemoState extends State<AiServicesDemo> with DemoSceneState {
  AiTier _tier = AiTier.good;
  String _language = AiLanguages.defaultTarget;

  Future<void> _script(DemoFinger f) async {
    put(() {
      _tier = AiTier.good;
      _language = AiLanguages.defaultTarget;
    });
    await f.wait(700);
    await f.moveTo('ai-tier-best');
    await f.wait(250);
    await f.tap(() => put(() => _tier = AiTier.best));
    await f.wait(900);
    await f.moveTo('ai-tier-good');
    await f.wait(250);
    await f.tap(() => put(() => _tier = AiTier.good));
    await f.lift();
    await f.wait(2000);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DemoSettingsPage(
        title: strings.aiTitlePage,
        children: [
          SettingsGroupLabel(title: strings.aiTiersLabel),
          const SizedBox(height: SettingsMetrics.groupLabelGap),
          Row(
            children: [
              for (final tier in AiTier.values) ...[
                if (tier != AiTier.values.first) const SizedBox(width: SettingsMetrics.aiTierGap),
                Expanded(
                  child: DemoTarget(
                    'ai-tier-${tier.name}',
                    child: AiTierCard(
                      label: strings.aiTier(tier),
                      selected: _tier == tier,
                      onTap: () => put(() => _tier = tier),
                    ),
                  ),
                ),
              ],
            ],
          ),
          const SizedBox(height: SettingsMetrics.aiBlockGap),
          // The key is there to be seen: no field to type in on the tip page.
          InputDecorator(
            decoration: InputDecoration(labelText: strings.aiKeyLabel, border: const OutlineInputBorder()),
            child: Text('AIza••••••••', style: TextStyle(color: colors.onSurfaceVariant)),
          ),
          const SizedBox(height: SettingsMetrics.aiBlockGap),
          AiLanguageButton(language: _language, onPick: (language) => put(() => _language = language)),
        ],
      ),
    );
  }
}

/// Settings › Sync and storage › Cloud sync: the row opens the page, whose
/// button starts the Google sign-in (in the phone it only waits).
class CloudSyncDemo extends StatefulWidget {
  const CloudSyncDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<CloudSyncDemo> createState() => _CloudSyncDemoState();
}

class _CloudSyncDemoState extends State<CloudSyncDemo> with DemoSceneState {
  bool _page = false;
  bool _waiting = false;

  Future<void> _script(DemoFinger f) async {
    put(() {
      _page = false;
      _waiting = false;
    });
    await f.wait(600);
    await f.moveTo('syncRow');
    await f.tap(() => put(() => _page = true));
    await f.wait(1000);
    await f.moveTo('turnOn');
    await f.tap(() => put(() => _waiting = true));
    await f.lift();
    await f.wait(2200);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final s = CloudSyncStrings.of(context);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: Stack(
        children: [
          DemoSettingsPage(
            title: strings.title,
            children: [
              SettingsGroup(
                title: s.sectionSyncStorage,
                children: [
                  DemoTarget(
                    'syncRow',
                    child: SettingsItem(
                      icon: YoinSymbols.devices,
                      title: s.title,
                      summary: s.lead,
                      onTap: () => put(() => _page = true),
                    ),
                  ),
                ],
              ),
            ],
          ),
          DemoPushed(
            shown: _page,
            child: DemoSettingsPage(
              title: s.title,
              children: [
                SettingsGroup(
                  title: s.sectionWhatYouGet,
                  children: [
                    SettingsItem(icon: YoinSymbols.editNote, title: s.benefitNotes),
                    SettingsItem(icon: YoinSymbols.settings, title: s.benefitSettings),
                    SettingsItem(icon: YoinSymbols.cloudOffline, title: s.benefitOffline),
                  ],
                ),
                const SizedBox(height: SettingsMetrics.sectionGap),
                DemoTarget(
                  'turnOn',
                  child: FilledButton(
                    onPressed: _waiting ? null : () => put(() => _waiting = true),
                    child: Text(_waiting ? s.waitingTurnOn : s.turnOn),
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

/// Settings › Help and about › Welcome guide: the first-run guide's mascot comes back and says hello.
class WelcomeGuideDemo extends StatefulWidget {
  const WelcomeGuideDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<WelcomeGuideDemo> createState() => _WelcomeGuideDemoState();
}

class _WelcomeGuideDemoState extends State<WelcomeGuideDemo> with DemoSceneState, TickerProviderStateMixin {
  late final LandingMascotState _mascot;
  final BubbleTailState _tail = BubbleTailState();
  bool _guide = false;

  @override
  void initState() {
    super.initState();
    _mascot = LandingMascotState(this, reduced: false, haptics: false);
  }

  @override
  void dispose() {
    _mascot.dispose();
    _tail.dispose();
    super.dispose();
  }

  void _open() {
    put(() => _guide = true);
    if (!MediaQuery.disableAnimationsOf(context)) _mascot.wave(ticks: false);
  }

  Future<void> _script(DemoFinger f) async {
    put(() => _guide = false);
    await f.wait(600);
    await f.moveTo('welcome');
    await f.tap(_open);
    await f.lift();
    await f.wait(2800);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final landing = LandingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: Stack(
        children: [
          DemoSettingsPage(
            title: strings.title,
            children: [
              SettingsGroup(
                title: strings.sectionHelpAbout,
                children: [
                  SettingsItem(icon: YoinSymbols.sparkle, title: strings.aboutTips),
                  DemoTarget(
                    'welcome',
                    child: SettingsItem(icon: YoinSymbols.refresh, title: landing.welcomeGuide, onTap: _open),
                  ),
                  SettingsItem(icon: YoinSymbols.info, title: strings.aboutName),
                ],
              ),
            ],
          ),
          DemoSpring(
            target: _guide ? 1 : 0,
            builder: (context, p) => p <= 0.001 && !_guide
                ? const SizedBox.shrink()
                : Opacity(
                    opacity: p.clamp(0.0, 1.0),
                    child: ColoredBox(
                      color: colors.surfaceContainerLow,
                      child: Padding(
                        padding: const EdgeInsets.all(TipSceneTokens.pageTop),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            LandingBubble(
                              title: landing.helloTitle,
                              tail: _tail,
                              background: colors.surfaceContainerHighest,
                              content: colors.onSurface,
                            ),
                            const SizedBox(height: TipSceneTokens.titleGap),
                            SizedBox(
                              height: TipSceneTokens.mascot,
                              child: Transform.scale(
                                scale: 0.8 + 0.2 * p,
                                child: Center(
                                  child: AspectRatio(
                                    aspectRatio: mascotAspect,
                                    child: LandingMascot(state: _mascot),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}
