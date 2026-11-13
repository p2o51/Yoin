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

  String get title => _zh ? '设置' : 'Settings'; // settings_title
  String get sectionAccounts => _zh ? '账号' : 'Accounts'; // settings_section_accounts
  String get accountsAdd => _zh ? '添加账号' : 'Add account'; // settings_accounts_add
  String get accountsEmptyTitle => _zh ? '暂无账号' : 'No accounts'; // settings_accounts_empty_title
  String get accountsUse => _zh ? '使用此账号' : 'Use this account'; // settings_accounts_use
  String get accountsRemoveMenu => _zh ? '移除' : 'Remove'; // settings_accounts_remove_menu
  String get cdSwitchAccount => _zh ? '切换到此账号' : 'Switch to this account'; // settings_cd_switch_account
  String cdAccountOptionsRow(String name) => _zh ? '$name 的选项' : 'Options for $name'; // settings_cd_account_options_row
  String get removeTitle => _zh ? '移除此账号？' : 'Remove this account?'; // settings_remove_title
  String removeBody(String name) => _zh
      ? '“$name”将从 Yoin 中移除。你的评分、笔记与播放记录会保留。'
      : '“$name” will be removed from Yoin. Your ratings, notes and history stay.'; // settings_remove_body
  String get removeConfirm => _zh ? '移除' : 'Remove'; // settings_remove_confirm
  String get removeCancel => _zh ? '取消' : 'Cancel'; // settings_remove_cancel
  String get switchFailed => _zh ? '无法切换账号' : "Couldn't switch account"; // settings_switch_failed
  String get serviceNameSubsonic => 'Subsonic'; // settings_service_name_subsonic (not translated)
}
