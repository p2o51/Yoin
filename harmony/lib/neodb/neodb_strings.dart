import 'package:flutter/widgets.dart';

/// NeoDB's words, from Android res/values{,-zh-rCN}/strings_detail.xml,
/// strings_memories.xml and strings_settings.xml (key in each comment).
class NeoDbStrings {
  const NeoDbStrings._(this.zh);

  const NeoDbStrings.forLanguage({required bool chinese}) : zh = chinese;

  final bool zh;

  static NeoDbStrings of(BuildContext context) =>
      NeoDbStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  // ---- the rate sheet's line (strings_detail.xml) ----
  String get name => 'NeoDB'; // detail_neodb_name (a brand, not translated)
  String get lineSignIn => zh ? '登录以同步' : 'Sign in to sync'; // detail_neodb_sign_in
  String get lineSyncing => zh ? '正在同步…' : 'Syncing…'; // detail_neodb_syncing
  String get lineSynced => zh ? '已同步' : 'Synced'; // detail_neodb_synced
  String get lineFailed => zh ? '无法同步' : "Couldn't sync"; // detail_neodb_failed
  String get retry => zh ? '重试' : 'Retry'; // detail_retry / mem_retry
  String get albumSyncFailed => zh ? '无法同步到 NeoDB' : "Couldn't sync to NeoDB"; // detail_album_neodb_failed

  // ---- the Memories diary (strings_memories.xml) ----
  String get diarySync => zh ? '同步到 NeoDB' : 'Sync to NeoDB'; // mem_neodb_sync
  String get diarySyncing => zh ? '正在同步到 NeoDB…' : 'Syncing to NeoDB…'; // mem_neodb_syncing
  String get diarySynced => zh ? '已同步到 NeoDB' : 'Synced to NeoDB'; // mem_neodb_synced
  String get diaryFailed => zh ? '无法同步到 NeoDB' : "Couldn't sync to NeoDB"; // mem_neodb_failed
  String get snackbarSignIn =>
      zh ? '先登录 NeoDB，才能同步评分和评论。' : 'Sign in to NeoDB first to push ratings and reviews.'; // mem_snackbar_neodb_signin
  String get snackbarSignInAction => zh ? '登录' : 'Sign in'; // mem_snackbar_neodb_signin_action
  String get snackbarSyncFailed => zh ? '无法同步到 NeoDB' : "Couldn't sync to NeoDB"; // mem_snackbar_sync

  // ---- 别人怎么说 (strings_detail.xml) ----
  String get voicesPeek => zh ? '别人怎么说' : 'What others say'; // neodb_voices_peek
  String get voicesTitle => zh ? 'NeoDB 上别人怎么说' : 'What others say on NeoDB'; // neodb_voices_title
  String voicesRated(int n) => zh ? '$n 人打过分' : (n == 1 ? '$n rating' : '$n ratings'); // neodb_voices_rated
  String voicesCount(int n) =>
      zh ? '共 $n 条' : (n == 1 ? '$n comment in all' : '$n comments in all'); // neodb_voices_count
  String voicesSpreadCd(int stars, int percent) =>
      zh ? '$stars 星：$percent%' : '$stars stars: $percent%'; // neodb_voices_spread_cd
  String voicesPercent(int percent) => '$percent%'; // neodb_voices_percent
  String voicesScale(int scale) => ' /$scale'; // neodb_voices_scale
  String get voicesReview => zh ? '长评' : 'Review'; // neodb_voices_review
  String get voicesFolded => zh ? '作者折叠了这条，点开看' : 'Folded by the writer. Tap to read'; // neodb_voices_folded
  String get voicesEmpty =>
      zh ? 'NeoDB 上还没有人写过这张专辑。' : 'No one on NeoDB has written about this album yet.'; // neodb_voices_empty
  String get voicesNotFound => zh ? 'NeoDB 上没找到这张专辑。' : "NeoDB doesn't have this album."; // neodb_voices_not_found
  String get voicesSearch => zh ? '在 NeoDB 搜索' : 'Search NeoDB'; // neodb_voices_search
  String get voicesFailed => zh ? '连不上 NeoDB。' : "Couldn't reach NeoDB."; // neodb_voices_failed
  String get voicesOpenNeoDb => zh ? '在 NeoDB 里看全部' : 'See all on NeoDB'; // neodb_voices_open_neodb
  String get voicesDouban => zh ? '豆瓣' : 'Douban'; // neodb_voices_douban
  String get voicesRym => 'RYM'; // neodb_voices_rym (not translated)
  String get voicesAoty => 'AOTY'; // neodb_voices_aoty (not translated)

  // ---- Settings (strings_settings.xml) ----
  String get settingsTitle => 'NeoDB'; // settings_neodb_title / _title_page (not translated)
  String get summaryOn => zh ? '专辑评分与评论已开启同步' : 'Album ratings and reviews sync'; // settings_neodb_summary_on(_page)
  String get summaryOff => zh ? '同步专辑评分与评论' : 'Sync album ratings and reviews'; // settings_neodb_summary_off(_page)
  String get instanceLabel => zh ? '实例' : 'Instance'; // settings_neodb_instance_label
  String get instancePlaceholder => 'https://neodb.social'; // settings_neodb_instance_placeholder
  String get signIn => zh ? '登录' : 'Sign in'; // settings_neodb_sign_in
  String get signInAgain => zh ? '重新登录' : 'Sign in again'; // settings_neodb_sign_in_again
  String get signOut => zh ? '退出登录' : 'Sign out'; // settings_neodb_sign_out
  String get useToken => zh ? '改用访问令牌' : 'Use an access token instead'; // settings_neodb_use_token
  String get tokenLabel => zh ? '访问令牌' : 'Access token'; // settings_neodb_token_label
  String get tokenPlaceholder => zh ? '粘贴令牌' : 'Paste token'; // settings_neodb_token_placeholder
  String get saveToken => zh ? '保存令牌' : 'Save token'; // settings_neodb_save_token
  String get invalidUrl =>
      zh ? '无效的 NeoDB 地址。请包含有效的主机名。' : 'Invalid NeoDB URL. Include a valid host.'; // settings_neodb_invalid_url
}
