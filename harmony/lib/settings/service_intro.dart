import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../ui/adaptive/yoin_window_info.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_rows.dart';

/// The setup page's pitch and the account limit's copy, English and
/// Simplified Chinese, copied from Android res/values{,-zh-rCN}/strings_settings.xml
/// (key in the comment).
class ServiceIntroStrings {
  const ServiceIntroStrings._(this._zh);

  /// For tests and non-widget callers.
  const ServiceIntroStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  static ServiceIntroStrings of(BuildContext context) =>
      ServiceIntroStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get sectionWhatYouGet => _zh ? '支持功能' : 'What you get'; // settings_setup_section_what_you_get

  String get subsonicPlayTitle => _zh ? '播放' : 'Playback'; // settings_intro_subsonic_play_title
  String get subsonicPlayBody =>
      _zh ? '直接从你的服务器串流播放' : 'Stream directly from your server'; // settings_intro_subsonic_play_body
  String get subsonicFavoritesTitle =>
      _zh ? '收藏与评分' : 'Favorites and ratings'; // settings_intro_subsonic_favorites_title
  String get subsonicFavoritesBody =>
      _zh ? '与你的服务器保持同步' : 'Synced with your server'; // settings_intro_subsonic_favorites_body
  String get subsonicPlaylistsTitle => _zh ? '播放列表' : 'Playlists'; // settings_intro_subsonic_playlists_title
  String get subsonicPlaylistsBody =>
      _zh ? '浏览与编辑你的播放列表' : 'Browse and edit your playlists'; // settings_intro_subsonic_playlists_body
  String get subsonicCastTitle => _zh ? '投播与缓存' : 'Cast and cache'; // settings_intro_subsonic_cast_title
  String get subsonicCastBody =>
      _zh ? '投播到音箱，自动缓存近期播放' : 'Cast to speakers and cache recent plays'; // settings_intro_subsonic_cast_body

  String get appleSearchTitle => _zh ? '资料库与目录' : 'Library and catalog'; // settings_intro_apple_search_title
  String get appleSearchBody => _zh
      ? '浏览个人资料库，搜索 Apple Music 目录'
      : 'Browse your library and search the Apple Music catalog'; // settings_intro_apple_search_body
  String get applePlayTitle => _zh ? '播放' : 'Playback'; // settings_intro_apple_play_title
  String get applePlayBody =>
      _zh ? '在 Yoin 内直接播放目录歌曲' : 'Play catalog songs directly in Yoin'; // settings_intro_apple_play_body
  String get appleBadge => _zh ? '预览' : 'Preview'; // settings_intro_apple_badge

  /// settings_accounts_count: "n of max" beside the Accounts label.
  String accountsCount(int count, int max) => _zh ? '$count / $max' : '$count of $max';

  String get accountLimit => _zh ? '已达账号上限' : 'Account limit reached'; // settings_setup_account_limit
  String get limitSubsonic => _zh ? '已达账号上限' : 'Account limit reached'; // settings_setup_limit_subsonic
}

/// A service Yoin can add (Android SetupService, the ones this port ships).
enum SetupService { subsonic, appleMusic }

/// One "What you get" row (ServiceIntro.Highlight).
@immutable
final class ServiceHighlight {
  const ServiceHighlight(this.icon, this.title, this.body);
  final IconData icon;
  final String title;
  final String body;
}

/// [service]'s highlights, as ServiceIntro.kt pitches it: a person's pitch,
/// not a support matrix.
List<ServiceHighlight> serviceHighlights(ServiceIntroStrings s, SetupService service) => switch (service) {
  SetupService.subsonic => [
    ServiceHighlight(YoinSymbols.equalizer, s.subsonicPlayTitle, s.subsonicPlayBody),
    ServiceHighlight(YoinSymbols.favorite, s.subsonicFavoritesTitle, s.subsonicFavoritesBody),
    ServiceHighlight(YoinSymbols.playlist, s.subsonicPlaylistsTitle, s.subsonicPlaylistsBody),
    ServiceHighlight(YoinSymbols.cast, s.subsonicCastTitle, s.subsonicCastBody),
  ],
  SetupService.appleMusic => [
    ServiceHighlight(YoinSymbols.search, s.appleSearchTitle, s.appleSearchBody),
    ServiceHighlight(YoinSymbols.playCircle, s.applePlayTitle, s.applePlayBody),
  ],
};

/// "What you get" (HighlightsGroup): one row per highlight, its icon, title
/// and line. Shown while adding an account, never on an account's own page.
class ServiceHighlightsGroup extends StatelessWidget {
  const ServiceHighlightsGroup({super.key, required this.service});

  final SetupService service;

  @override
  Widget build(BuildContext context) {
    final strings = ServiceIntroStrings.of(context);
    return SettingsGroup(
      title: strings.sectionWhatYouGet,
      children: [
        for (final h in serviceHighlights(strings, service))
          SettingsItem(key: ValueKey(h.title), icon: h.icon, title: h.title, summary: h.body),
      ],
    );
  }
}

/// Whether the setup page splits into two columns: a landscape handset
/// (Android SetupLandscape, so the keyboard never scrolls the service away)
/// or a wide window.
bool setupUsesColumns(YoinWindowInfo info) => info.isCompactHeight || info.layoutMode == LayoutMode.wide;

/// The setup page in two columns (ServiceSetupScreen.kt, compact height):
/// [lead] — the hero and, while adding, what you'll need — stays put on the
/// left; [side] — the form first, then "What you get" (adding) or the
/// account's settings (managing) — scrolls on the right under the seam.
class SetupColumns extends StatelessWidget {
  const SetupColumns({
    super.key,
    required this.lead,
    required this.side,
    required this.leadController,
    required this.sideController,
  });

  final List<Widget> lead;
  final List<Widget> side;
  final ScrollController leadController;
  final ScrollController sideController;

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.paddingOf(context).bottom;
    List<Widget> spaced(List<Widget> items, double gap) => [
      for (var i = 0; i < items.length; i++) ...[if (i > 0) SizedBox(height: gap), items[i]],
    ];
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          flex: SettingsMetrics.setupLeadFlex,
          child: SingleChildScrollView(
            controller: leadController,
            padding: EdgeInsetsDirectional.only(
              start: SettingsMetrics.pageHorizontal,
              top: SettingsMetrics.setupColumnTop,
              bottom: SettingsMetrics.pageBottom + bottom,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: spaced(lead, SettingsMetrics.setupLeadGap),
            ),
          ),
        ),
        const SizedBox(width: SettingsMetrics.setupColumnsGap),
        Expanded(
          flex: SettingsMetrics.setupSideFlex,
          child: SettingsSeam(
            controller: sideController,
            child: ListView(
              controller: sideController,
              clipBehavior: Clip.hardEdge,
              padding: EdgeInsetsDirectional.only(
                end: SettingsMetrics.pageHorizontal,
                top: SettingsMetrics.setupColumnTop,
                bottom: SettingsMetrics.pageBottom + bottom,
              ),
              children: spaced(side, SettingsMetrics.sectionGap),
            ),
          ),
        ),
      ],
    );
  }
}
