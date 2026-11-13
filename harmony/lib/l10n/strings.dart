import 'package:flutter/widgets.dart';

/// UI strings, English and Simplified Chinese only (owner's i18n plan).
/// Copied from Android res/values{,-zh-rCN}/strings.xml; keep the keys.
///
/// TODO(harmony): move to gen-l10n ARB files once there are more than a handful.
class YoinStrings {
  const YoinStrings._(this._zh);

  final bool _zh;

  static YoinStrings of(BuildContext context) =>
      YoinStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get barLabelHome => _zh ? '首页' : 'Home'; // cmp_bar_label_home
  String get barLabelLibrary => _zh ? '资料库' : 'Library'; // cmp_bar_label_library

  // Subsonic account setup (strings_settings.xml).
  String get setupAddressLabel => _zh ? '服务器地址' : 'Server address'; // settings_setup_address_label
  String get setupAddressPlaceholder => 'https://music.example.com'; // settings_setup_address_placeholder
  String get setupUsernameLabel => _zh ? '用户名' : 'Username'; // settings_setup_username_label
  String get setupUsernamePlaceholder => _zh ? '你的用户名' : 'Your username'; // settings_setup_username_placeholder
  String get setupPasswordLabel => _zh ? '密码' : 'Password'; // settings_setup_password_label
  String get setupConnect => _zh ? '连接' : 'Connect'; // settings_setup_connect
  String get setupConnecting => _zh ? '正在连接…' : 'Connecting…'; // settings_setup_connecting
  String get setupAddressRequired => _zh ? '需要填写服务器地址' : 'Server address is required'; // settings_setup_address_required
  String get setupUsernameRequired => _zh ? '需要填写用户名' : 'Username is required'; // settings_setup_username_required
  String get setupPasswordRequired => _zh ? '需要填写密码' : 'Password is required'; // settings_setup_password_required
  String get setupServerError => _zh ? '服务器返回了错误' : 'The server returned an error'; // settings_setup_server_error
  String get setupUnreachable => _zh ? '无法连接服务器' : "Couldn't reach the server"; // settings_setup_unreachable
  String get setupSaveFailed => _zh ? '无法保存' : "Couldn't save"; // settings_setup_save_failed
}
