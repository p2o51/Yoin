import 'package:flutter/widgets.dart';

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
