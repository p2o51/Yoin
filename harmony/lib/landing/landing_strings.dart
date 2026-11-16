import 'package:flutter/widgets.dart';

/// The first-run landing's strings, English and Simplified Chinese, copied
/// from Android res/values{,-zh-rCN}/strings_landing.xml (key in the
/// comment). The bubble lines are the mascot speaking (the one place copy
/// talks).
class LandingStrings {
  const LandingStrings._(this._zh);

  /// For tests and non-widget callers.
  const LandingStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  static LandingStrings of(BuildContext context) =>
      LandingStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  // ── The mascot's lines ──────────────────────────────────────────────
  String get helloTitle => _zh ? '你好，我是 Yoin。' : "Hi, I'm Yoin."; // landing_hello_title
  String get helloBody => _zh
      ? '我播放你的音乐资料库：你的服务器、Spotify 或 Apple Music。'
      : 'I play your music library, from your server, Spotify or Apple Music.'; // landing_hello_body
  String get pickTitle => _zh ? '你平时用什么听歌？' : 'What do you listen with?'; // landing_pick_title
  String get aboutTitle =>
      _zh ? 'Yoin 负责这些，其余的与你的音乐服务有关。' : "Here's my part, and what stays with your service."; // landing_about_title
  String get subsonicTitle => _zh ? '你的服务器在哪里？' : "Where's your server?"; // landing_subsonic_title
  String get appleTitle =>
      _zh ? 'Apple Music 需要一个开发者令牌。' : 'Apple Music needs a developer token.'; // landing_apple_title
  String get appleBody => _zh
      ? '你需要一个开发者令牌来使用 Apple MusicKit。如果没有，也可以直接使用 Yoin 提供的。'
      : "You need a developer token to use Apple MusicKit. If you don't have one, you can use the one provided by Yoin."; // landing_apple_body
  String get homeTitle => _zh ? '首页上放些什么？' : 'What goes on Home?'; // landing_home_title
  String get readyTitle => _zh ? '都准备好了。' : 'All set.'; // landing_ready_title
  String get readyNoneTitle => _zh ? '还没有连接账号。' : 'No account yet.'; // landing_ready_none_title
  String get readyNoneBody => _zh ? '之后随时可以在设置中添加。' : 'You can add one anytime in Settings.'; // landing_ready_none_body

  // ── The pill ────────────────────────────────────────────────────────
  String get getStarted => _zh ? '开始' : 'Get started'; // landing_get_started
  String get next => _zh ? '下一步' : 'Next'; // landing_next
  String get skip => _zh ? '跳过' : 'Skip'; // landing_skip
  String get openYoin => _zh ? '进入 Yoin' : 'Open Yoin'; // landing_open_yoin
  String get back => _zh ? '返回' : 'Back'; // landing_back

  // ── Pick ────────────────────────────────────────────────────────────
  String get pickSubsonic =>
      _zh ? 'Navidrome、Gonic 等其他服务器' : 'Navidrome, Gonic and other servers'; // landing_pick_subsonic
  String get pickApple => _zh ? '需要订阅' : 'Subscription'; // landing_pick_apple

  // ── About ───────────────────────────────────────────────────────────
  String get aboutDoes => _zh ? 'Yoin 负责' : 'Yoin does'; // landing_about_does
  String get aboutDoesnt => _zh ? 'Yoin 不负责' : "Yoin doesn't"; // landing_about_doesnt
  String get aboutNeed => _zh ? '你需要' : "You'll need"; // landing_about_need
  String get doesPlay => _zh ? '播放你的资料库，带同步歌词' : 'Play your library, with synced lyrics'; // landing_does_play
  String get doesNotes => _zh ? '评分、笔记与 Memories' : 'Ratings, notes and Memories'; // landing_does_notes
  String get doesAccounts => _zh ? '多个账号，点按以切换账号' : 'Several accounts, one tap to switch'; // landing_does_accounts
  String get doesPasswords => _zh ? '将密码保存在本地' : 'Keep passwords on this device'; // landing_does_passwords
  String get dontSell => _zh ? '出售音乐或订阅' : 'Sell music or subscriptions'; // landing_dont_sell
  String get dontHost => _zh ? '托管或上传你的音频文件' : 'Host or upload your files'; // landing_dont_host
  String get needSubsonic => _zh ? '一台兼容 Subsonic 的服务器' : 'A Subsonic-compatible server'; // landing_need_subsonic
  String get needApple => _zh ? 'Apple Music 订阅' : 'An Apple Music subscription'; // landing_need_apple
  String get needNone => _zh ? '以上任意一项服务' : 'One of the services above'; // landing_need_none

  // ── Connect ─────────────────────────────────────────────────────────
  String get connected => _zh ? '已连接' : 'Connected'; // landing_connected
  String get appleBadge => _zh ? '预览' : 'Preview'; // settings_intro_apple_badge (strings_settings.xml)
  String get appleToken => _zh ? '开发者令牌' : 'Developer token'; // landing_apple_token
  String get appleYoinTitle => _zh ? 'Yoin 的令牌服务' : "Yoin's token service"; // landing_apple_yoin_title
  String get appleYoinBody => _zh ? '所有 Yoin 用户共用' : 'Shared by everyone on Yoin'; // landing_apple_yoin_body
  String get appleOwnTitle => _zh ? '你自己的令牌服务' : 'Your own token service'; // landing_apple_own_title
  String get appleOwnBody =>
      _zh ? '需要 Apple 开发者计划会员资格' : 'Needs an Apple Developer Program membership'; // landing_apple_own_body
  String get appleNeed => _zh ? '需要 Apple Music 订阅' : 'Needs an Apple Music subscription'; // landing_apple_need

  /// The Apple Developer Program link, as Android writes it (not translated).
  String get appleProgramLink => 'developer.apple.com/programs ↗';

  // ── Home ────────────────────────────────────────────────────────────
  String get homeRowsNote => _zh
      ? '要更改分区显示的行数，之后长按首页即可。'
      : 'To change how many rows a section shows, long-press Home later.'; // landing_home_rows_note
  String homeDrag(String section) => _zh ? '调整“$section”的顺序' : 'Reorder $section'; // landing_home_drag

  // ── Ready ───────────────────────────────────────────────────────────
  String get readyAccounts => _zh ? '账号' : 'Accounts'; // landing_ready_accounts
  String get readyInUse => _zh ? '使用中' : 'In use'; // landing_ready_in_use
  String get readyEmpty => _zh ? '未连接账号' : 'No accounts connected'; // landing_ready_empty

  // ── Settings › Help and about ───────────────────────────────────────
  String get welcomeGuide => _zh ? '欢迎向导' : 'Welcome guide'; // settings_about_welcome_guide (strings_scroll_edge.xml)

  /// The dots' reading: "2 / 5" (Android builds it the same way, untranslated).
  String dots(int index, int count) => '$index / $count';
}
