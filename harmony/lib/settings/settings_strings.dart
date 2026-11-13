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
}
