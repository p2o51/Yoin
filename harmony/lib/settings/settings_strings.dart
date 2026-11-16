import 'package:flutter/widgets.dart';

import '../data/ai/ai_service.dart' show AboutFields;
import '../data/ai/ai_settings.dart';

/// Settings' UI strings, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings_settings.xml (key in the comment).
class SettingsStrings {
  const SettingsStrings._(this._zh);

  /// For tests and non-widget callers.
  const SettingsStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  static SettingsStrings of(BuildContext context) =>
      SettingsStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  // ── Settings ─────────────────────────────────────────────────────────
  String get title => _zh ? '设置' : 'Settings'; // settings_title
  String get sectionAccounts => _zh ? '账号' : 'Accounts'; // settings_section_accounts
  String get sectionFeatures => _zh ? '功能' : 'Features'; // settings_section_features

  // Settings › AI features (strings_settings settings_gemini_*, strings_ai ai_*).
  String get aiTitle => _zh ? 'AI 功能' : 'AI features'; // settings_gemini_title
  String get aiTitlePage => _zh ? 'AI 功能' : 'AI features'; // settings_gemini_title_page
  String get aiSummaryEmpty =>
      _zh ? '歌曲信息、问 Gemini、歌词翻译与 Memories 标题' : 'Song info, Ask Gemini, translation and Memory titles'; // settings_gemini_summary_empty
  String aiAnswerIn(String language) => _zh ? '回答语言：$language' : 'Answer in $language'; // settings_gemini_answer_in
  String get aiTiersLabel => _zh ? '档位' : 'Tier'; // ai_tiers_label
  String get aiKeyLabel => _zh ? 'API 密钥' : 'API key'; // ai_key_label

  /// ai_provider_gemini / _deepseek (not translatable) / _qwen / _custom.
  String aiProvider(AiProvider provider) => switch (provider) {
    AiProvider.gemini => 'Gemini',
    AiProvider.deepSeek => 'DeepSeek',
    AiProvider.qwen => _zh ? '千问' : 'Qwen',
    AiProvider.custom => _zh ? '自定义' : 'Custom',
  };

  /// ai_tier_good / ai_tier_best.
  String aiTier(AiTier tier) => switch (tier) {
    AiTier.good => _zh ? '好' : 'Good',
    AiTier.best => _zh ? '最佳' : 'Best',
  };
  String get aiCdTierDetails => _zh ? '档位区别' : 'How the tiers differ'; // ai_cd_tier_details
  String get aiFlip => _zh ? '换个角度' : 'Change view'; // ai_flip
  String get aiAxisSpeed => _zh ? '更快' : 'Faster'; // ai_axis_speed
  String get aiAxisCost => _zh ? '更省' : 'Cheaper'; // ai_axis_cost
  String get aiAxisAccuracy => _zh ? '更准' : 'More accurate'; // ai_axis_accuracy
  String get aiUntested => _zh ? '还没测' : 'Not tested'; // ai_untested
  String get aiGetKeyGemini => _zh ? '在 Google AI Studio 获取' : 'Get a key in Google AI Studio'; // ai_get_key_gemini
  String get aiGetKeyDeepSeek =>
      _zh ? '在 DeepSeek 开放平台获取' : 'Get a key on the DeepSeek platform'; // ai_get_key_deepseek
  String get aiGetKeyQwen => _zh ? '在阿里云百炼获取' : 'Get a key in Alibaba Cloud Model Studio'; // ai_get_key_qwen
  String get aiCustomFormat => _zh ? '接口格式' : 'Format'; // ai_custom_format
  String get aiFormatOpenAi => _zh ? 'OpenAI 兼容' : 'OpenAI compatible'; // ai_format_openai
  String get aiFormatAnthropic => 'Anthropic'; // ai_format_anthropic (not translatable)
  String get aiCustomUrl => _zh ? '地址' : 'Address'; // ai_custom_url
  String get aiCustomModel => _zh ? '模型' : 'Model'; // ai_custom_model
  String get aiCustomPull => _zh ? '拉取' : 'Fetch'; // ai_custom_pull
  String get aiCustomThinking => _zh ? '思考' : 'Thinking'; // ai_custom_thinking

  /// ai_thinking_off / _low / _medium / _high.
  String aiThinking(AiThinking thinking) => switch (thinking) {
    AiThinking.off => _zh ? '不思考' : 'Off',
    AiThinking.low => _zh ? '低' : 'Low',
    AiThinking.medium => _zh ? '中' : 'Medium',
    AiThinking.high => _zh ? '高' : 'High',
  };
  String get aiSearchLabel => _zh ? '搜索服务' : 'Search'; // ai_search_label
  String get aiSearchNone => _zh ? '不搜索' : 'No search'; // ai_search_none

  /// The search service's name (searchServiceName: DeepSeek, Exa, Tavily, ai_search_none).
  String aiSearchService(AiSearchService service) => switch (service) {
    AiSearchService.deepSeek => 'DeepSeek',
    AiSearchService.exa => 'Exa',
    AiSearchService.tavily => 'Tavily',
    AiSearchService.none => aiSearchNone,
  };
  String aiSearchKey(String name) => _zh ? '$name 密钥' : '$name key'; // ai_search_key
  String aiGetSearchKey(String name) => _zh ? '获取 $name 密钥' : 'Get a $name key'; // ai_get_search_key
  String aiModelsError(String error) => _zh ? '拉取失败：$error' : "Couldn't fetch models: $error"; // ai_models_error
  String get aiTry => _zh ? '拿一首歌试试' : 'Try a song'; // ai_try
  String aiTiersTitle(String provider) => _zh ? '$provider 档位' : '$provider tiers'; // ai_tiers_title
  String get aiRowModel => _zh ? '模型' : 'Model'; // ai_row_model
  String get aiRowSearch => _zh ? '搜索' : 'Search'; // ai_row_search
  String get aiRowSpeed => _zh ? '速度' : 'Speed'; // ai_row_speed
  String get aiRowAccuracy => _zh ? '准确' : 'Accuracy'; // ai_row_accuracy
  String get aiRowCost => _zh ? '每千首' : 'Per 1,000 songs'; // ai_row_cost
  String aiSeconds(String seconds) => _zh ? '$seconds 秒' : '$seconds s'; // ai_seconds
  String aiCost(String cost) => '\$$cost'; // ai_cost (not translatable)
  String aiCostEstimated(String cost) => _zh ? '\$$cost（估）' : '\$$cost (est.)'; // ai_cost_estimated
  String get aiSearchGoogle => _zh ? 'Google 搜索' : 'Google Search'; // ai_search_google
  String get aiSearchQwen => _zh ? '千问自带搜索' : "Qwen's own search"; // ai_search_qwen
  String get aiSearchDeepSeekOnce =>
      _zh ? 'DeepSeek 搜索 1 次，读全文' : 'DeepSeek Search once, full pages'; // ai_search_deepseek_once
  String get aiSearchDeepSeekTwice =>
      _zh ? 'DeepSeek 搜索 2 次，读全文' : 'DeepSeek Search twice, full pages'; // ai_search_deepseek_twice
  String get aiNoteGemini => _zh
      ? '每月前 5,000 次搜索免费，超出后每千首约 \$31。没开结算的免费密钥不能搜索，会报 429。'
      : "The first 5,000 searches each month are free; after that, about \$31 per 1,000 songs. A free key without "
            "billing can't search and gets error 429."; // ai_note_gemini
  String get aiNoteDeepSeek => _zh
      ? '国内可以直接用，不需要代理。搜索用同一个密钥，按次收费。'
      : 'Works in mainland China without a proxy. Search uses the same key and is charged per call.'; // ai_note_deepseek
  String get aiNoteQwen =>
      _zh ? '国内可以直接用，不需要代理。还没测过。' : 'Works in mainland China without a proxy. Not tested yet.'; // ai_note_qwen
  String get aiTiersSource => _zh
      ? '速度和准确来自 35 首歌的测试，花费按当前价格算。'
      : 'Speed and accuracy from a 35-song test; cost at current prices.'; // ai_tiers_source
  String get aiTrySong => _zh ? '歌名' : 'Song'; // ai_try_song
  String get aiTryArtist => _zh ? '艺人' : 'Artist'; // ai_try_artist
  String get aiTryAlbum => _zh ? '专辑' : 'Album'; // ai_try_album
  String get aiTryRun => _zh ? '试试' : 'Try'; // ai_try_run
  String get aiTryRunning => _zh ? '正在查…' : 'Searching…'; // ai_try_running
  String aiTryQueries(String queries) => _zh ? '搜了：$queries' : 'Searched: $queries'; // ai_try_queries
  String aiTryPages(int pages) => _zh ? '读了 $pages 个网页' : '$pages pages read'; // ai_try_pages
  String get aiTryBuiltin => _zh ? '模型自己搜索' : 'The model searched on its own'; // ai_try_builtin
  String get aiTryNoKey => _zh ? '先贴上密钥' : 'Paste a key first'; // ai_try_no_key
  String aiTryError(String message) => _zh ? '失败：$message' : 'Failed: $message'; // ai_try_error

  /// An About field's label (strings_nowplaying np_about_creation_time … np_about_review).
  String aboutField(String key) => switch (key) {
    AboutFields.creationTime => _zh ? '创作时间' : 'Creation Time',
    AboutFields.creationLocation => _zh ? '创作地点' : 'Creation Location',
    AboutFields.lyricist => _zh ? '作词' : 'Lyricist',
    AboutFields.composer => _zh ? '作曲' : 'Composer',
    AboutFields.producer => _zh ? '制作人' : 'Producer',
    _ => _zh ? '关于' : 'About',
  };
  String get accountsAdd => _zh ? '添加账号' : 'Add account'; // settings_accounts_add
  String get accountsAddShort => _zh ? '添加' : 'Add'; // settings_accounts_add_short
  String get accountsEmptyTitle => _zh ? '暂无账号' : 'No accounts'; // settings_accounts_empty_title
  String get accountsUse => _zh ? '使用此账号' : 'Use this account'; // settings_accounts_use
  String get accountsRemoveMenu => _zh ? '移除' : 'Remove'; // settings_accounts_remove_menu
  String get cdSwitchAccount => _zh ? '切换到此账号' : 'Switch to this account'; // settings_cd_switch_account
  String cdAccountOptionsCard(String name) =>
      _zh ? '$name 的选项' : 'Options for $name'; // settings_cd_account_options_card
  String get removeTitle => _zh ? '移除此账号？' : 'Remove this account?'; // settings_remove_title
  String removeBody(String name) => _zh
      ? '“$name”将从 Yoin 中移除。你的评分、笔记与播放记录会保留。'
      : '“$name” will be removed from Yoin. Your ratings, notes and history stay.'; // settings_remove_body
  String get removeConfirm => _zh ? '移除' : 'Remove'; // settings_remove_confirm
  String get removeCancel => _zh ? '取消' : 'Cancel'; // settings_remove_cancel
  String get switchFailed => _zh ? '无法切换账号' : "Couldn't switch account"; // settings_switch_failed
  String get serviceNameSubsonic => 'Subsonic'; // settings_service_name_subsonic (translatable="false")
  String get serviceNameApple => 'Apple Music'; // settings_service_name_apple (translatable="false")

  // ── Add-account service picker ──────────────────────────────────────
  String get addServiceTitle => _zh ? '选择服务' : 'Choose a service'; // (picker heading, no Android counterpart: v1 Android had one service)

  // ── Apple Music setup page ──────────────────────────────────────────
  String get introAppleName => 'Apple Music'; // settings_intro_apple_name (translatable="false")
  String get introAppleTagline =>
      _zh ? '你的 Apple Music 资料库与目录' : 'Your Apple Music library and catalog'; // settings_intro_apple_tagline
  String get introAppleNeedSub => _zh ? 'Apple Music 订阅' : 'Apple Music subscription'; // settings_intro_apple_need_sub
  String get introAppleNeedToken => _zh ? '开发者令牌服务' : 'Developer token service'; // settings_intro_apple_need_token
  String get appleEndpointLabel => _zh ? '开发者令牌服务' : 'Developer token service'; // settings_apple_endpoint_label
  String get appleEndpointPlaceholder => 'https://…/token'; // settings_apple_endpoint_placeholder (translatable="false")
  String get appleConnect => _zh ? '连接 Apple Music' : 'Connect Apple Music'; // settings_apple_connect
  String get appleWorking => _zh ? '正在处理…' : 'Working…'; // settings_apple_working
  String get appleStatusNeedService =>
      _zh ? '将你的 Apple Music 账号连接至 Yoin。' : 'Connect your Apple Music account to Yoin.'; // settings_apple_status_need_service
  String get appleStatusAuthorize =>
      _zh ? '请在 Apple Music 中完成授权。' : 'Complete authorization in Apple Music.'; // settings_apple_status_authorize
  String get appleStatusConnected => _zh ? '已连接' : 'Connected'; // settings_apple_status_connected_profiles
  String get appleStatusFailed => _zh ? '无法连接' : "Couldn't connect"; // settings_apple_status_failed
  String get appleStatusCancelled => _zh ? '无法连接' : "Couldn't connect"; // settings_apple_status_cancelled
  String get appleEndpointRequired =>
      _zh ? '需要填写令牌服务地址' : 'Token service address is required'; // (field validation; mirrors settings_setup_address_required)
  String get appleEndpointInvalid => _zh ? '地址无效' : 'Invalid address'; // (mirrors settings_setup_check_address)

  // ── Subsonic setup page ─────────────────────────────────────────────
  String get introSubsonicName => 'Subsonic'; // settings_intro_subsonic_name (translatable="false")
  String get introSubsonicTagline => _zh
      ? 'Navidrome、Airsonic、Gonic、OpenSubsonic'
      : 'Navidrome, Airsonic, Gonic, OpenSubsonic'; // settings_intro_subsonic_tagline
  String get sectionNeed => _zh ? '你需要' : "You'll need"; // settings_setup_section_need
  String get introSubsonicNeed =>
      _zh ? '服务器地址、用户名、密码' : 'Server address, username, password'; // settings_intro_subsonic_need
  String get sectionConnect => _zh ? '连接' : 'Connect'; // settings_setup_section_connect
  String get addressLabel => _zh ? '服务器地址' : 'Server address'; // settings_setup_address_label
  String get addressPlaceholder => 'https://music.example.com'; // settings_setup_address_placeholder (translatable="false")
  String get usernameLabel => _zh ? '用户名' : 'Username'; // settings_setup_username_label
  String get usernamePlaceholder => _zh ? '你的用户名' : 'Your username'; // settings_setup_username_placeholder
  String get passwordLabel => _zh ? '密码' : 'Password'; // settings_setup_password_label
  String get passwordPlaceholder => _zh ? '密码' : 'Password'; // settings_setup_password_placeholder
  String cdShow(String what) => _zh ? '显示 $what' : 'Show $what'; // settings_cd_show
  String cdHide(String what) => _zh ? '隐藏 $what' : 'Hide $what'; // settings_cd_hide
  String get test => _zh ? '测试' : 'Test'; // settings_setup_test
  String get connect => _zh ? '连接' : 'Connect'; // settings_setup_connect
  String get reaching => _zh ? '正在连接你的服务器…' : 'Reaching your server…'; // settings_setup_reaching
  String get connecting => _zh ? '正在连接…' : 'Connecting…'; // settings_setup_connecting
  String get reachable => _zh ? '已找到服务器' : 'Server found'; // settings_setup_reachable
  String get addressRequired => _zh ? '需要填写服务器地址' : 'Server address is required'; // settings_setup_address_required
  String get checkAddress => _zh ? '地址无效' : 'Invalid address'; // settings_setup_check_address
  String get usernameRequired => _zh ? '需要填写用户名' : 'Username is required'; // settings_setup_username_required
  String get passwordRequired => _zh ? '需要填写密码' : 'Password is required'; // settings_setup_password_required
  String get serverError => _zh ? '服务器返回了错误' : 'The server returned an error'; // settings_setup_server_error
  String get unreachable => _zh ? '无法连接服务器' : "Couldn't reach the server"; // settings_setup_unreachable
  String get saveFailed => _zh ? '无法保存' : "Couldn't save"; // settings_setup_save_failed

  // ── Managing an account ─────────────────────────────────────────────
  String get accountsManage => _zh ? '管理' : 'Manage'; // settings_accounts_manage
  String get cdManageAccount => _zh ? '管理账号' : 'Manage account'; // settings_cd_manage_account
  String get accountCredentialsMissing => _zh ? '凭据缺失' : 'Credentials missing'; // settings_account_credentials_missing
  String get sectionServer => _zh ? '服务器' : 'Server'; // settings_setup_section_server
  String get passwordMissing => _zh ? '密码缺失' : 'Password missing'; // settings_setup_password_missing
  String get save => _zh ? '保存' : 'Save'; // settings_setup_save

  // ── Playback › Streaming quality ────────────────────────────────────
  String get sectionPlayback => _zh ? '播放' : 'Playback'; // settings_section_playback
  String get streamQualityTitle => _zh ? '播放音质' : 'Streaming quality'; // settings_stream_quality_title
  String get streamQualityOriginal => _zh ? '原始' : 'Original'; // settings_stream_quality_original
  String streamQualityKbps(int kbps) => '$kbps kbps'; // settings_stream_quality_kbps
  String get streamQualityWifi => 'Wi‑Fi'; // settings_stream_quality_wifi
  String get streamQualityMobile => _zh ? '移动数据' : 'Mobile data'; // settings_stream_quality_mobile
  String streamQualitySummary(String wifi, String mobile) =>
      _zh ? 'Wi‑Fi $wifi，移动数据 $mobile' : 'Wi‑Fi $wifi, mobile data $mobile'; // settings_stream_quality_summary
  String get volumeLevelingTitle => _zh ? '音量平衡' : 'Volume leveling'; // settings_volume_leveling_title
  String get volumeLevelingOff => _zh ? '关' : 'Off'; // settings_volume_leveling_off
  String get volumeLevelingTrack => _zh ? '按曲目' : 'By track'; // settings_volume_leveling_track
  String get volumeLevelingAlbum => _zh ? '按专辑' : 'By album'; // settings_volume_leveling_album

  // ── Help and about ──────────────────────────────────────────────────
  String get sectionHelpAbout => _zh ? '帮助与关于' : 'Help and about'; // settings_section_help_about
  String get aboutName => 'Yoin'; // settings_about_name (translatable="false")
  String aboutVersion(String version) => _zh ? '版本 $version' : 'Version $version'; // settings_about_version
  String get aboutTips => _zh ? '小技巧' : 'Tips'; // settings_about_tips
  String get aboutLicenses => _zh ? '开源许可' : 'Open source licenses'; // settings_about_licenses
  String get licensesIntro => _zh
      ? 'Yoin 用到了下面这些字体和开源库，感谢它们的作者。点一项可以查看完整许可。'
      : 'Yoin is built with these fonts and open source libraries. Thanks to everyone who made them. Tap one to read its license.'; // settings_licenses_intro
  String tipsReadCount(int read, int total) =>
      _zh ? '已读 $read / $total' : '$read of $total read'; // settings_tips_read_count
  String get tipsAllRead => _zh ? '全部已读' : 'All read'; // settings_tips_all_read
  String get tipsMarkAllUnread => _zh ? '全部标为未读' : 'Mark all as unread'; // settings_tips_mark_all_unread
  String get tipRead => _zh ? '已读' : 'Read'; // tip_read (strings_home.xml)

  // ── Wide windows ────────────────────────────────────────────────────
  String get placeholder => _zh ? '未选择账号' : 'No account selected'; // settings_placeholder
}
