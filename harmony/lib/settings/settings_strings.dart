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
  String get sectionFeatures => _zh ? '功能与外观' : 'Features and appearance'; // settings_section_features
  String get syncBackupsTitle => _zh ? '同步与备份' : 'Sync and backups'; // settings_sync_backups_title
  String syncUnitBackups(int count) =>
      _zh ? '个歌单备份' : (count == 1 ? 'playlist backup' : 'playlist backups'); // settings_sync_unit_backups
  String get storageTitle => _zh ? '存储' : 'Storage'; // settings_section_storage
  String storageDownloadedShort(String size) =>
      _zh ? '已下载 $size' : '$size downloaded'; // settings_storage_downloaded_short
  String storageCacheShort(String size) => _zh ? '缓存 $size' : '$size cache'; // settings_storage_cache_short
  String storagePlaybackCacheSummary(String size) =>
      _zh ? '播放缓存 $size' : 'Playback cache $size'; // settings_storage_playback_cache_summary
  String get helpFeedbackTitle => _zh ? '帮助与反馈' : 'Help and feedback'; // settings_help_feedback_title
  String get aboutYoinTitle => _zh ? '关于 Yoin' : 'About Yoin'; // settings_about_yoin_title
  String helpTipsReadCount(int read, int total) =>
      _zh ? '小技巧已读 $read / $total' : '$read of $total tips read'; // settings_help_tips_read_count
  String get helpTipsAllRead => _zh ? '小技巧全部已读' : 'All tips read'; // settings_help_tips_all_read
  String get accountScopeSubsonic => _zh ? '音质与下载' : 'Quality and downloads'; // settings_account_scope_subsonic
  String get accountScopeApple => _zh ? '歌曲与艺人名' : 'Song and artist names'; // settings_account_scope_apple
  String get accountPillManage => _zh ? '设置 ›' : 'Settings ›'; // settings_account_pill_manage

  // Settings › AI features (strings_settings settings_gemini_*, strings_ai ai_*).
  String get aiTitle => _zh ? 'AI 功能' : 'AI features'; // settings_gemini_title
  String get aiTitlePage => _zh ? 'AI 功能' : 'AI features'; // settings_gemini_title_page
  String get aiSummaryEmpty =>
      _zh ? '歌曲信息、问 Gemini、歌词翻译与 Memories 标题' : 'Song info, Ask Gemini, translation and Memory titles'; // settings_gemini_summary_empty
  String get aiAnswerLanguage => _zh ? '回答语言' : 'Answer language'; // ai_answer_language
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
  String aiTierEstimated(String name) => _zh ? '$name（估）' : '$name (est.)'; // ai_tier_estimated
  String get aiCdTierDetails => _zh ? '档位区别' : 'How the tiers differ'; // ai_cd_tier_details
  String get aiFlip => _zh ? '换个角度' : 'Change view'; // ai_flip
  String get aiAxisSpeed => _zh ? '更快' : 'Faster'; // ai_axis_speed
  String get aiAxisCost => _zh ? '更省' : 'Cheaper'; // ai_axis_cost
  String get aiAxisAccuracy => _zh ? '更准' : 'More accurate'; // ai_axis_accuracy
  String get aiAxisOverall => _zh ? '又准又快' : 'Accurate and fast'; // ai_axis_overall

  /// Under the chart while Custom is chosen (Gemini 4 Carbon, 2026-11-14).
  String get aiSearchChartNote => _zh
      ? '测试时都用 deepseek-flash 写回答，只换搜索服务。Tavily 和 Brave 按 35 首歌估算，其余是 499 首歌实测。'
      : 'Tested with deepseek-flash as the writing model, changing only the search service. Tavily and Brave are '
            'estimated from 35 songs; the rest were measured on 499.'; // ai_search_chart_note
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

  /// The search service's name (searchServiceName: DeepSeek, Exa, Tavily, Brave, ai_search_doubao, ai_search_none).
  String aiSearchService(AiSearchService service) => switch (service) {
    AiSearchService.deepSeek => 'DeepSeek',
    AiSearchService.exa => 'Exa',
    AiSearchService.tavily => 'Tavily',
    AiSearchService.brave => 'Brave',
    AiSearchService.doubao => _zh ? '豆包' : 'Doubao', // ai_search_doubao
    AiSearchService.none => aiSearchNone,
  };
  String aiSearchKey(String name) => _zh ? '$name 密钥' : '$name key'; // ai_search_key

  /// "Get a … key", one string per service: English's article follows the name (an Exa key).
  /// ai_get_search_key_deepseek / _exa / _tavily / _brave / _doubao.
  String aiGetSearchKey(AiSearchService service) => switch (service) {
    AiSearchService.deepSeek => _zh ? '获取 DeepSeek 密钥' : 'Get a DeepSeek key',
    AiSearchService.exa => _zh ? '获取 Exa 密钥' : 'Get an Exa key',
    AiSearchService.tavily => _zh ? '获取 Tavily 密钥' : 'Get a Tavily key',
    AiSearchService.brave => _zh ? '获取 Brave 密钥' : 'Get a Brave key',
    AiSearchService.doubao => _zh ? '获取豆包密钥' : 'Get a Doubao key',
    AiSearchService.none => '',
  };
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
  String aiSecondsEstimated(String seconds) => _zh ? '$seconds 秒（估）' : '$seconds s (est.)'; // ai_seconds_estimated
  String aiAccuracyEstimated(String accuracy) => _zh ? '$accuracy%（估）' : '$accuracy% (est.)'; // ai_accuracy_estimated
  String get aiSearchGoogle => _zh ? 'Google 搜索' : 'Google Search'; // ai_search_google
  String get aiSearchQwen => _zh ? '千问自带搜索' : "Qwen's own search"; // ai_search_qwen
  String get aiSearchDeepSeekOnce =>
      _zh ? 'DeepSeek 搜索 1 次，读全文' : 'DeepSeek Search once, full pages'; // ai_search_deepseek_once
  String get aiSearchDeepSeekTwice =>
      _zh ? 'DeepSeek 搜索 2 次，读全文' : 'DeepSeek Search twice, full pages'; // ai_search_deepseek_twice
  String get aiNoteGemini => _zh
      ? '每月前 5,000 次搜索免费，超出后每千首约 \$25。没开结算的免费密钥不能搜索，会报 429。'
      : "The first 5,000 searches each month are free; after that, about \$25 per 1,000 songs. A free key without "
            "billing can't search and gets error 429."; // ai_note_gemini
  String get aiNoteDeepSeek => _zh
      ? '国内可以直接用，不需要代理。搜索用同一个密钥，按次收费。'
      : 'Works in mainland China without a proxy. Search uses the same key and is charged per call.'; // ai_note_deepseek
  String get aiNoteQwen => _zh
      ? '国内可以直接用，不需要代理。搜索用同一个密钥，按次收费。'
      : 'Works in mainland China without a proxy. Search uses the same key and is charged per call.'; // ai_note_qwen
  String get aiTiersSource => _zh
      ? '速度和准确来自 500 首歌的测试，花费按当前价格算。'
      : 'Speed and accuracy from a 500-song test; cost at current prices.'; // ai_tiers_source
  String get aiTryNoHistory => _zh ? '先播一首歌' : 'Play a song first'; // ai_try_no_history
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
  String get switchTitle => _zh ? '正在切换账号' : 'Switching account'; // settings_switch_title
  String get switchPreparing => _zh ? '正在关闭当前会话…' : 'Closing the current session…'; // settings_switch_preparing
  String switchConnecting(String name) => _zh ? '正在连接 $name…' : 'Connecting to $name…'; // settings_switch_connecting
  String get switchConnectingUnnamed =>
      _zh ? '正在连接新账号…' : 'Connecting to the new account…'; // settings_switch_connecting_unnamed
  String get switchOk => _zh ? '好' : 'OK'; // settings_switch_ok
  String get playerDlnaStopFailed => _zh
      ? '音响未确认停止。请重试后再切换账号。'
      : 'The speaker did not confirm stopping. Retry before changing accounts.'; // player_dlna_stop_failed (strings_shell)
  String get serviceNameSubsonic => 'Subsonic'; // settings_service_name_subsonic (translatable="false")
  String get serviceNameApple => 'Apple Music'; // settings_service_name_apple (translatable="false")

  // ── Add-account service picker ──────────────────────────────────────
  String get addSheetTitle => _zh ? '添加账号' : 'Add an account'; // settings_add_sheet_title
  String get providerSubsonicSummary =>
      _zh ? 'Navidrome、Airsonic 等其他服务器' : 'Navidrome, Airsonic and other servers'; // settings_provider_subsonic_summary
  String get providerAppleSummary =>
      _zh ? '你的 Apple Music 资料库与目录' : 'Your Apple Music library and catalog'; // settings_provider_apple_summary
  String get providerLocalSummary => _zh ? '即将推出' : 'Coming later'; // settings_provider_local_summary
  String get providerBadgePreview => _zh ? '预览' : 'Preview'; // settings_provider_badge_preview
  String get serviceNameLocal => _zh ? '本机文件' : 'Files on this device'; // settings_service_name_local

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
  String get appleReconnect => _zh ? '重新连接 Apple Music' : 'Reconnect Apple Music'; // settings_apple_reconnect
  String get sectionAccount => _zh ? '账号' : 'Account'; // settings_setup_section_account

  // An Apple Music account's song and artist names (AppleMusicNameRegionGroup).
  String get appleNamesTitle => _zh ? '歌曲与艺人名' : 'Song and artist names'; // settings_apple_names_title
  String get appleNamesFoot => _zh
      ? '适用于这个账号里的歌曲和专辑。那个商店没有的歌，会保留现在的名字。'
      : "Songs and albums from this account. A song the other storefront doesn't carry keeps the name it has."; // settings_apple_names_foot
  String get appleNamesOff => _zh ? '按 Apple Music 显示' : 'As Apple Music shows them'; // settings_apple_names_off
  String get appleNamesOffSummary =>
      _zh ? '用你所在地区商店的名字' : 'Names from your own storefront'; // settings_apple_names_off_summary
  String get appleNamesOriginal => _zh ? '按歌曲原地区' : 'In their original language'; // settings_apple_names_original
  String get appleNamesOriginalSummary => _zh
      ? '英文歌显示英文，日语歌显示日文，韩语歌显示韩文'
      : 'Each song keeps the name in its own language'; // settings_apple_names_original_summary
  bool get isZh => _zh;
  String get appleNamesRegion => _zh ? '按指定地区' : 'From a specific region'; // settings_apple_names_region
  String get appleNamesRegionSummary =>
      _zh ? '选择一个 Apple Music 商店地区' : 'Choose an Apple Music storefront'; // settings_apple_names_region_summary
  String appleNamesRegionSelected(String regionName) => _zh
      ? '用 Apple Music $regionName商店的名字'
      : "Names from Apple Music's $regionName storefront"; // settings_apple_names_region_selected
  String get appleNamesPickerTitle => _zh ? '选择商店地区' : 'Choose a region'; // settings_apple_names_picker_title
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
  String get streamQualityAuto => _zh ? '自动' : 'Auto'; // settings_stream_quality_auto
  String streamQualityAutoDetail(String wired, String bluetooth, String speaker) => _zh
      ? '有线 $wired　蓝牙 $bluetooth　扬声器 $speaker'
      : 'Wired $wired   Bluetooth $bluetooth   Speaker $speaker'; // settings_stream_quality_auto_detail

  // ── Playback › Streaming quality › Spatial audio ─────────────────────
  String get spatialAudioTitle => _zh ? '空间音频' : 'Spatial audio'; // settings_spatial_audio_title
  String get spatialAudioSwitch =>
      _zh ? '把 5.1 / 7.1 歌曲空间化' : 'Spatialize 5.1 and 7.1 songs'; // settings_spatial_audio_switch
  String get spatialAudioOff =>
      _zh ? '已关闭。多声道歌曲缩混成立体声播放。' : 'Off. Multichannel songs play folded down to stereo.'; // settings_spatial_audio_off
  String get spatialAudioUnsupported => _zh
      ? '这台设备不支持空间音频，多声道歌曲缩混成立体声播放。'
      : "This device can't spatialize. Multichannel songs play folded down to stereo."; // settings_spatial_audio_unsupported
  String get spatialAudioSystemOff => _zh
      ? '系统设置里的空间音频没开，多声道歌曲现在缩混成立体声播放。'
      : 'Spatial audio is off in system settings, so multichannel songs play folded down to stereo.'; // settings_spatial_audio_system_off
  String get spatialAudioOutput => _zh
      ? '已就绪。当前输出不支持空间化，戴上支持空间音频的耳机后自动生效。'
      : "Ready. The current output can't be spatialized; it starts on headphones that support spatial audio."; // settings_spatial_audio_output
  String get spatialAudioReady => _zh
      ? '已开启。当前输出上，多声道歌曲会被空间化。'
      : 'On. Multichannel songs are spatialized on this output.'; // settings_spatial_audio_ready
  String get spatialAudioReadyHead => _zh
      ? '已开启。当前输出上，多声道歌曲会被空间化，可用头部追踪。'
      : 'On. Multichannel songs are spatialized on this output, with head tracking available.'; // settings_spatial_audio_ready_head
  String get spatialAudioSystemSettings =>
      _zh ? '系统声音设置' : 'System sound settings'; // settings_spatial_audio_system_settings
  String get spatialAudioSystemSettingsDetail => _zh
      ? '空间音频和头部追踪在那里按耳机打开'
      : 'Spatial audio and head tracking are switched on there, per headset'; // settings_spatial_audio_system_settings_detail
  String get spatialAudioNote => _zh
      ? '只对 5.1 / 7.1 的原始文件起作用，立体声歌曲不变。'
      : "Only for songs that are 5.1 or 7.1 files, played as the original. Stereo songs aren't changed."; // settings_spatial_audio_note
  String get volumeLevelingTitle => _zh ? '音量平衡' : 'Volume leveling'; // settings_volume_leveling_title
  String get volumeLevelingOff => _zh ? '关' : 'Off'; // settings_volume_leveling_off
  String get volumeLevelingTrack => _zh ? '按曲目' : 'By track'; // settings_volume_leveling_track
  String get volumeLevelingAlbum => _zh ? '按专辑' : 'By album'; // settings_volume_leveling_album

  // ── Sync and storage › Playback cache ───────────────────────────────
  String get storageCache => _zh ? '播放缓存' : 'Playback cache'; // settings_storage_cache
  String get storageClear => _zh ? '清除' : 'Clear'; // settings_storage_clear

  // ── Help and about ──────────────────────────────────────────────────
  String get sectionHelpAbout => _zh ? '帮助与关于' : 'Help and about'; // settings_section_help_about
  String get aboutName => 'Yoin'; // settings_about_name (translatable="false")
  String aboutVersion(String version) => _zh ? '版本 $version' : 'Version $version'; // settings_about_version
  String get aboutTips => _zh ? '小技巧' : 'Tips'; // settings_about_tips
  String get aboutLicenses => _zh ? '开源许可' : 'Open source licenses'; // settings_about_licenses
  String get aboutWebsite => _zh ? '官网' : 'Website'; // settings_about_website
  String get aboutWebsiteOpenFailed =>
      _zh ? '无法打开官网' : "Couldn't open the website"; // settings_about_website_open_failed
  String get aboutDocs => _zh ? '使用文档' : 'Docs'; // settings_about_docs
  String get aboutDocsOpenFailed => _zh ? '无法打开使用文档' : "Couldn't open the docs"; // settings_about_docs_open_failed
  String get aboutPrivacyPolicy => _zh ? '隐私政策' : 'Privacy policy'; // settings_about_privacy_policy
  String get aboutPrivacyOpenFailed =>
      _zh ? '无法打开隐私政策' : "Couldn't open the privacy policy"; // settings_about_privacy_open_failed
  String get aboutFeedback => _zh ? '发送反馈' : 'Send feedback'; // settings_about_feedback
  String get aboutFeedbackOpenFailed =>
      _zh ? '无法打开反馈表单' : "Couldn't open the feedback form"; // settings_about_feedback_open_failed
  String get aboutExportLog => _zh ? '导出日志' : 'Export log'; // settings_about_export_log
  String get aboutExportLogSummary => _zh ? '播放诊断' : 'Playback diagnostics'; // settings_about_export_log_summary
  String get aboutExportLogFailed => _zh ? '无法导出日志' : "Couldn't export the log"; // settings_about_export_log_failed
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
