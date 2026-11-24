/// Settings on the demo phone (Android ui/tips TipVolumeLevelingScene.kt,
/// TipAiScene.kt, TipSettingsScene.kt): the real Settings rows and page
/// bodies with choices of their own — the demo never changes the real ones.
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../data/applemusic/apple_music_name_region.dart';
import '../landing/landing_bubble.dart';
import '../landing/landing_mascot.dart';
import '../landing/landing_strings.dart';
import '../playback/replay_gain.dart';
import '../playback/spatial_audio.dart';
import '../settings/ai_settings_screen.dart';
import '../settings/ai_settings_view_model.dart';
import '../settings/apple_music_account_screen.dart' show AppleMusicNameRegionGroup;
import '../settings/appearance/appearance_strings.dart';
import '../settings/appearance/screen_corner_control.dart' show ScreenCornerBar;
import '../settings/appearance/screen_corners_screen.dart';
import '../settings/appearance/scroll_edge_screen.dart';
import '../settings/licenses.dart' show yoinVersionName;
import '../settings/settings_metrics.dart';
import '../settings/settings_rows.dart';
import '../settings/settings_strings.dart';
import '../settings/stream_quality.dart';
import '../settings/stream_quality_screen.dart';
import '../settings/sync/cloud_sync_strings.dart';
import '../settings/volume_leveling_screen.dart';
import '../theme/tokens.dart';
import '../ui/component/seam/seam_style.dart';
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

/// Apple Music song names (Android TipAppleMusicSongNamesScene.kt): the real
/// Song names group of an Apple Music account's page; the dot picks Original
/// language, then a storefront region (Japan). The demo keeps its selection
/// to itself.
class AppleMusicSongNamesDemo extends StatefulWidget {
  const AppleMusicSongNamesDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<AppleMusicSongNamesDemo> createState() => _AppleMusicSongNamesDemoState();
}

class _AppleMusicSongNamesDemoState extends State<AppleMusicSongNamesDemo> with DemoSceneState {
  AppleMusicNameRegion _region = AppleMusicNameRegion.off;

  Future<void> _script(DemoFinger f) async {
    put(() => _region = AppleMusicNameRegion.off);
    await f.wait(700);
    await f.moveTo('am-names-original');
    await f.wait(250);
    await f.tap(() => put(() => _region = AppleMusicNameRegion.original));
    await f.wait(950);
    await f.moveTo('am-names-region');
    await f.wait(250);
    await f.tap(() => put(() => _region = AppleMusicNameRegion.japan));
    await f.lift();
    await f.wait(2400);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: DemoSettingsPage(
      title: SettingsStrings.of(context).introAppleName,
      children: [
        AppleMusicNameRegionGroup(
          selected: _region,
          onSelect: (region) => put(() => _region = region),
          rowWrapper: (key, row) => DemoTarget('am-names-$key', child: row),
        ),
      ],
    ),
  );
}

/// Settings › AI features (Android TipAiScene.kt), the real page body: the
/// dot picks DeepSeek in the service row, then the Best tier, then goes down
/// to Try a song. The demo keeps its choices to itself: it never changes the
/// real ones, opens no page and no link, and its fields take no focus.
class AiServicesDemo extends StatefulWidget {
  const AiServicesDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<AiServicesDemo> createState() => _AiServicesDemoState();
}

class _AiServicesDemoState extends State<AiServicesDemo> with DemoSceneState implements AiSettingsActions {
  AiSettings _ai = const AiSettings();
  final _scroll = ScrollController();

  static const Duration _scrollTime = Duration(milliseconds: 900);

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _set(AiSettings Function(AiSettings) change) => put(() => _ai = change(_ai));

  Future<void> _scrollTo(double offset) async {
    if (!_scroll.hasClients) return;
    await _scroll.animateTo(offset, duration: _scrollTime, curve: Curves.easeInOut);
  }

  Future<void> _script(DemoFinger f) async {
    _set((_) => const AiSettings());
    if (_scroll.hasClients) _scroll.jumpTo(0);
    await f.wait(700);
    await f.moveTo('ai-service-${AiProvider.deepSeek.name}');
    await f.wait(250);
    await f.tap(() => setProvider(AiProvider.deepSeek));
    await f.wait(900);
    await f.moveTo('ai-tier-${AiTier.best.name}');
    await f.wait(250);
    await f.tap(() => setTier(AiTier.best));
    await f.lift();
    await f.wait(900);
    if (_scroll.hasClients) await _scrollTo(_scroll.position.maxScrollExtent);
    await f.moveTo('ai-try');
    await f.wait(250);
    await f.tap();
    await f.lift();
    await f.wait(1200);
    await _scrollTo(0);
    await f.wait(300);
  }

  @override
  void setProvider(AiProvider provider) => _set((s) => s.copyWith(provider: provider));

  @override
  void setTier(AiTier tier) => _set((s) => s.copyWith(tier: tier));

  @override
  void setKey(AiProvider provider, String key) => _set((s) => s.withKey(provider, key));

  @override
  void setCustomFormat(AiApiFormat format) => _set((s) => s.copyWith(customFormat: format));

  @override
  void setCustomBaseUrl(String url) => _set((s) => s.copyWith(customBaseUrl: url));

  @override
  void setCustomModel(String model) => _set((s) => s.copyWith(customModel: model));

  @override
  void setCustomThinking(AiThinking thinking) => _set((s) => s.copyWith(customThinking: thinking));

  @override
  void setSearchService(AiSearchService service) => _set((s) => s.copyWith(searchService: service));

  @override
  void setSearchKey(AiSearchService service, String key) => _set((s) => s.withSearchKey(service, key));

  @override
  void setTargetLanguage(String language) => _set((s) => s.copyWith(targetLanguage: language));

  @override
  void pullModels() {}

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    // The fields are there to be seen: no focus, so no keyboard comes up over the tip.
    child: ExcludeFocus(
      child: DemoSettingsPage(
        title: SettingsStrings.of(context).aiTitlePage,
        scroll: _scroll,
        children: [
          AiSettingsBody(
            settings: _ai,
            models: const AiModelList(),
            actions: this,
            onOpenTiers: () {},
            onOpenTry: () {},
            onOpenLink: (_) {},
            providerWrapper: (provider, child) => DemoTarget('ai-service-${provider.name}', child: child),
            tierWrapper: (tier, child) => DemoTarget('ai-tier-${tier.name}', child: child),
            tryWrapper: (child) => DemoTarget('ai-try', child: child),
          ),
        ],
      ),
    ),
  );
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

/// Settings, regrouped (Android TipSettingsScene.kt SettingsGroupsDemo): the
/// page's groups as they now stand — Features, Appearance, Sync and storage,
/// then Help and about — the real rows and labels, scrolled through once.
class SettingsRegroupedDemo extends StatefulWidget {
  const SettingsRegroupedDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<SettingsRegroupedDemo> createState() => _SettingsRegroupedDemoState();
}

class _SettingsRegroupedDemoState extends State<SettingsRegroupedDemo> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    if (_scroll.hasClients) _scroll.jumpTo(0);
    await f.wait(500);
    await f.moveToPoint(0.5, 0.75);
    // The finger carries the page: the list follows it to the end.
    final end = _scroll.hasClients ? _scroll.position.maxScrollExtent : 0.0;
    await f.drag(
      0.5,
      0.3,
      1400,
      onProgress: (p) {
        if (_scroll.hasClients) _scroll.jumpTo(end * p);
      },
    );
    await f.lift();
    await f.wait(1400);
    if (_scroll.hasClients) {
      await _scroll.animateTo(0, duration: const Duration(milliseconds: 900), curve: Curves.easeInOut);
    }
    await f.wait(400);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final appearance = AppearanceStrings.of(context);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DemoSettingsPage(
        title: strings.title,
        scroll: _scroll,
        children: [
          SettingsGroup(
            title: strings.sectionFeatures,
            children: [
              SettingsItem(icon: YoinSymbols.sparkle, title: strings.aiTitle),
              SettingsItem(icon: YoinSymbols.palette, title: appearance.sectionAppearance),
            ],
          ),
          const SizedBox(height: SettingsMetrics.sectionGap),
          SettingsGroup(
            title: CloudSyncStrings.of(context).sectionSyncStorage,
            children: [
              SettingsItem(icon: YoinSymbols.cloud, title: strings.syncBackupsTitle),
              SettingsItem(icon: YoinSymbols.storage, title: strings.storageTitle),
            ],
          ),
          const SizedBox(height: SettingsMetrics.sectionGap),
          SettingsGroup(
            title: strings.sectionHelpAbout,
            children: [
              SettingsItem(icon: YoinSymbols.comment, title: strings.helpFeedbackTitle),
              SettingsItem(
                icon: YoinSymbols.info,
                title: strings.aboutYoinTitle,
                summary: strings.aboutVersion(yoinVersionName),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Settings › Appearance › Scroll edge (Android TipLibraryScenes.kt
/// ScrollEdgeDemo shows the tide; here the page that changes it): the real
/// style cards with their live previews; the dot picks Dots, then Cookie
/// wave. The demo keeps its choice to itself: it never changes the real one.
class ScrollEdgeDemo extends StatefulWidget {
  const ScrollEdgeDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<ScrollEdgeDemo> createState() => _ScrollEdgeDemoState();
}

class _ScrollEdgeDemoState extends State<ScrollEdgeDemo> with DemoSceneState {
  final _scroll = ScrollController();
  final Map<SeamTopStyle, GlobalKey> _cards = {for (final style in SeamTopStyle.values) style: GlobalKey()};
  SeamTopStyle _style = SeamTopStyle.defaultStyle;

  static const Duration _scrollTime = Duration(milliseconds: 800);

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// Brings [style]'s card up into the phone.
  Future<void> _show(SeamTopStyle style) async {
    final card = _cards[style]!.currentContext;
    if (card == null || !card.mounted) return;
    await Scrollable.ensureVisible(card, alignment: 0.5, duration: _scrollTime, curve: Curves.easeInOut);
  }

  Future<void> _pick(DemoFinger f, SeamTopStyle style) async {
    await _show(style);
    await f.moveTo('edge-${style.name}', fy: 0.35);
    await f.wait(250);
    await f.tap(() => put(() => _style = style));
    await f.wait(1600);
  }

  Future<void> _script(DemoFinger f) async {
    put(() => _style = SeamTopStyle.defaultStyle);
    if (_scroll.hasClients) _scroll.jumpTo(0);
    await f.wait(900);
    await _pick(f, SeamTopStyle.dots);
    await _pick(f, SeamTopStyle.cookie);
    await f.lift();
    await f.wait(600);
    if (_scroll.hasClients) await _scroll.animateTo(0, duration: _scrollTime, curve: Curves.easeInOut);
    await f.wait(300);
  }

  @override
  Widget build(BuildContext context) {
    final strings = AppearanceStrings.of(context);
    final theme = Theme.of(context);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DemoSettingsPage(
        title: strings.scrollEdge,
        scroll: _scroll,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              ScrollEdgeTokens.textInset,
              0,
              ScrollEdgeTokens.textInset,
              ScrollEdgeTokens.introBottom,
            ),
            child: Text(
              strings.scrollEdgeIntro,
              style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          for (final (i, style) in SeamTopStyle.values.indexed) ...[
            if (i > 0) const SizedBox(height: ScrollEdgeTokens.cardGap),
            KeyedSubtree(
              key: _cards[style],
              child: DemoTarget(
                'edge-${style.name}',
                child: ScrollEdgeStyleCard(
                  key: ValueKey(style),
                  style: style,
                  selected: style == _style,
                  onTap: () => put(() => _style = style),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Settings › Appearance › Screen corners (Android TipCornersScene.kt): the
/// real page, one rounded rectangle over black; the dot slides the corner
/// bar down from the roundest until no black shows in this phone's own
/// corners. The demo keeps its value to itself: it never changes the real one.
class ScreenCornersDemo extends StatefulWidget {
  const ScreenCornersDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<ScreenCornersDemo> createState() => _ScreenCornersDemoState();
}

class _ScreenCornersDemoState extends State<ScreenCornersDemo> with DemoSceneState {
  double _value = TipSceneTokens.cornersStart;
  bool _following = true;

  /// Where on the corner bar the knob for [dp] sits, as a fraction of the
  /// bar's width (the knob's centre runs from half its height in).
  static double _knob(double dp) {
    const width = TipTokens.screenWidth - 2 * ScreenCornerTokens.controlsPaddingH;
    const knob = ScreenCornerTokens.barHeight;
    final f = (dp - ScreenCornerTokens.min) / (ScreenCornerTokens.max - ScreenCornerTokens.min);
    return (knob / 2 + (width - knob) * f) / width;
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _value = TipSceneTokens.cornersStart;
      _following = true;
    });
    final target = demoPhoneScreenCorner(
      MediaQuery.sizeOf(context),
    ).clamp(ScreenCornerTokens.min, ScreenCornerTokens.max).roundToDouble();
    await f.wait(600);
    await f.moveToWhere((w) => w is ScreenCornerBar, fx: _knob(TipSceneTokens.cornersStart));
    // The bar spans the screen less its side padding: a share of the bar is that share of it across the screen.
    const barShare = (TipTokens.screenWidth - 2 * ScreenCornerTokens.controlsPaddingH) / TipTokens.screenWidth;
    final toX = f.x.value + (_knob(target) - _knob(TipSceneTokens.cornersStart)) * barShare;
    await f.drag(
      toX,
      f.y.value,
      1600,
      onProgress: (p) {
        put(() {
          _following = false;
          _value = (TipSceneTokens.cornersStart + (target - TipSceneTokens.cornersStart) * p).roundToDouble();
        });
      },
    );
    await f.lift();
    await f.wait(2400);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: ScreenCornersPageContent(
      value: _value,
      following: _following,
      reported: null,
      examplesPlaying: widget.playing,
      onBack: () {},
      onValueChange: (v) => put(() {
        _following = false;
        _value = v;
      }),
      onInteractionChange: (_) {},
      onFollow: () => put(() {
        _following = true;
        _value = TipSceneTokens.cornersStart;
      }),
    ),
  );
}

/// A Subsonic account's Streaming quality page, its real groups: the dot
/// scrolls past Wi‑Fi and mobile data to Spatial audio and turns Yoin's
/// switch off and on again while the status line says what the listener
/// gets (headphones that spatialize, the system's switch on). The demo keeps
/// its choice to itself: it never changes the real one.
class SpatialAudioDemo extends StatefulWidget {
  const SpatialAudioDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<SpatialAudioDemo> createState() => _SpatialAudioDemoState();
}

class _SpatialAudioDemoState extends State<SpatialAudioDemo> with DemoSceneState {
  final ScrollController _scroll = ScrollController();
  SpatialAudioMode _mode = SpatialAudioMode.auto;

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    put(() => _mode = SpatialAudioMode.auto);
    if (_scroll.hasClients) _scroll.jumpTo(0);
    await f.wait(800);
    // Down past the two networks to the Spatial audio group.
    final end = _scroll.hasClients ? _scroll.position.maxScrollExtent : 0.0;
    await f.moveToPoint(0.5, 0.8);
    await f.drag(
      0.5,
      0.3,
      1100,
      onProgress: (p) {
        if (_scroll.hasClients) _scroll.jumpTo(end * p);
      },
    );
    await f.wait(600);
    await f.moveToKey(const ValueKey('spatial-switch'), fx: 0.88);
    await f.tap(() => put(() => _mode = SpatialAudioMode.off));
    await f.wait(1600);
    await f.tap(() => put(() => _mode = SpatialAudioMode.auto));
    await f.lift();
    await f.wait(2000);
    if (_scroll.hasClients) {
      await _scroll.animateTo(0, duration: const Duration(milliseconds: 900), curve: Curves.easeInOut);
    }
    await f.wait(400);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DemoSettingsPage(
        title: strings.streamQualityTitle,
        scroll: _scroll,
        children: [
          StreamQualityGroup(
            title: strings.streamQualityWifi,
            selected: StreamQualitySettings.defaultWifi,
            onSelect: (_) {},
          ),
          const SizedBox(height: SettingsMetrics.sectionGap),
          StreamQualityGroup(
            title: strings.streamQualityMobile,
            selected: StreamQualitySettings.defaultMobile,
            onSelect: (_) {},
          ),
          const SizedBox(height: SettingsMetrics.sectionGap),
          SpatialAudioGroup(
            // Headphones that spatialize, with the system's switch on for them.
            state: SpatialAudioState(
              mode: _mode,
              supported: true,
              outputSupported: true,
              enabled: true,
              canChoose: true,
            ),
            onModeChange: (mode) => put(() => _mode = mode),
            onOpenSystemSettings: () {},
          ),
        ],
      ),
    );
  }
}
