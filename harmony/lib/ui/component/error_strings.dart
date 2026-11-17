import 'package:flutter/widgets.dart';

/// The error surfaces' shared lines (UserMessage.kt, ErrorDiagnostics.kt),
/// English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings_component.xml (key in the comment).
class ErrorStrings {
  const ErrorStrings._(this.zh);

  /// For tests and non-widget callers.
  const ErrorStrings.forLanguage({required bool chinese}) : zh = chinese;

  final bool zh;

  static ErrorStrings of(BuildContext context) =>
      ErrorStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  static const en = ErrorStrings._(false);

  String get unreachableConnect =>
      zh ? '无法连接服务器。请检查网络连接。' : "Can't reach the server. Check your connection."; // cmp_error_unreachable_connect
  String get unreachableIo =>
      zh ? '无法连接服务器。请检查网络连接。' : "Can't reach the server. Check your connection."; // cmp_error_unreachable_io
  String get timeout => zh ? '服务器响应超时。请重试。' : 'The server is taking too long. Try again.'; // cmp_error_timeout
  String get ssl => zh ? '安全连接失败。请检查服务器地址。' : 'Secure connection failed. Check the server address.'; // cmp_error_ssl
  String get appleReconnect => zh
      ? 'Apple Music 授权已过期。请在设置中重新连接。'
      : 'Apple Music access expired. Reconnect in Settings.'; // cmp_error_apple_reconnect
  String get appleToken => zh
      ? 'Apple Music 拒绝了开发者令牌。请检查令牌服务。'
      : 'Apple Music rejected the developer token. Check the token service.'; // cmp_error_apple_token
  String get appleDenied => zh
      ? 'Apple Music 拒绝了这次请求。请稍后再试。'
      : 'Apple Music refused this request. Try again later.'; // cmp_error_apple_denied
  String get appleBusy =>
      zh ? 'Apple Music 当前繁忙。请稍后再试。' : 'Apple Music is busy right now. Try again in a moment.'; // cmp_error_apple_busy
  String get copyDetails => zh ? '复制诊断信息' : 'Copy details'; // cmp_error_copy_details
  String get detailsCopied => zh ? '已复制' : 'Copied'; // cmp_error_details_copied
}
