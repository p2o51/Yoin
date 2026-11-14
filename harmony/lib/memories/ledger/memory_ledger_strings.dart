import 'package:flutter/widgets.dart';

import '../copy/memory_copy.dart';

/// All Memories' strings, English and Simplified Chinese, copied from
/// Android res/values{,-zh-rCN}/strings_memory_ledger.xml (key in the comment).
class MemoryLedgerStrings {
  const MemoryLedgerStrings._(this.zh);

  const MemoryLedgerStrings.forLanguage({required bool chinese}) : zh = chinese;

  final bool zh;

  static MemoryLedgerStrings of(BuildContext context) =>
      MemoryLedgerStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get title => zh ? '全部回忆' : 'All Memories'; // memory_ledger_title

  /// memory_ledger_writings_label (one / other; Chinese other only).
  String writingsLabel(int n) => zh ? '笔回忆' : (n == 1 ? 'memory written' : 'memories written');

  /// memory_ledger_scores_label.
  String scoresLabel(int n) => zh ? '个评分' : (n == 1 ? 'score' : 'scores');
  String get open => zh ? '打开全部回忆' : 'Open All Memories'; // memory_ledger_open

  String get statAlbums => zh ? '张专辑' : 'Albums'; // memory_ledger_stat_albums
  String get statWritings => zh ? '篇文字' : 'Written'; // memory_ledger_stat_writings
  String get statCharacters => zh ? '个字' : 'Characters'; // memory_ledger_stat_characters
  String get statRated => zh ? '首歌打了分' : 'Songs scored'; // memory_ledger_stat_rated

  String get viewTimeline => zh ? '时间线' : 'Timeline'; // memory_ledger_view_timeline
  String get viewCards => zh ? '卡片' : 'Cards'; // memory_ledger_view_cards
  String get viewList => zh ? '列表' : 'List'; // memory_ledger_view_list
  String get sortRecent => zh ? '最近' : 'Recent'; // memory_ledger_sort_recent
  String get sortScore => zh ? '评分最高' : 'Highest score'; // memory_ledger_sort_score
  String get sortName => zh ? '名称' : 'Name'; // memory_ledger_sort_name
  String get albumScore => zh ? '专辑评分' : 'Album score'; // memory_ledger_album_score
  String get unknownSong => zh ? '未知歌曲' : 'Song unknown'; // memory_ledger_unknown_song

  /// memory_ledger_month_count.
  String monthCount(int n) => zh ? '$n 条' : (n == 1 ? '$n entry' : '$n entries');
  String quietOne(String month) => zh ? '$month没有写' : 'Nothing written in $month'; // memory_ledger_quiet_one
  String quietRange(String from, String to) =>
      zh ? '$from到$to没有写' : 'Nothing written from $from to $to'; // memory_ledger_quiet_range
  String get rangeEmpty => zh ? '这段时间没有留下什么。' : 'Nothing kept in this stretch.'; // memory_ledger_range_empty

  String get kindSongNote => zh ? '歌曲笔记' : 'Song note'; // memory_ledger_kind_song_note
  String get kindReview => zh ? '短评' : 'Review'; // memory_ledger_kind_review
  String get kindTitle => zh ? '回忆标题' : 'Memory title'; // memory_ledger_kind_title
  String get kindAlbumRating => zh ? '专辑评分' : 'Album score'; // memory_ledger_kind_album_rating
  String get kindTrackRating => zh ? '歌曲评分' : 'Song score'; // memory_ledger_kind_track_rating
  String get unknownAlbum => zh ? '不知道是哪张专辑' : 'Album unknown'; // memory_ledger_unknown_album

  String since(String date) =>
      zh ? '从 $date 开始记。所有资料算同一个人。' : 'Kept since $date. Every account counts as you.'; // memory_ledger_since
  String get emptyTitle => zh ? '还没有回忆' : 'No memories yet'; // memory_ledger_empty_title

  // ---- dates (Android DateFormat best patterns: "MMMM", "yMMMM", "MMMd", "yMMMd") ----

  String monthName(DateTime month, DateTime today) {
    final sameYear = month.year == today.year;
    if (zh) return sameYear ? MemoryDates.monthName(month.month, zh: true) : '${month.year}年${month.month}月';
    final name = MemoryDates.monthName(month.month, zh: false);
    return sameYear ? name : '$name ${month.year}';
  }

  /// A month cell's short name under its quad ("Nov", "11月").
  String monthShort(DateTime month) =>
      zh ? '${month.month}月' : MemoryDates.monthName(month.month, zh: false).substring(0, 3);

  String longDate(DateTime day) => zh ? '${day.year}年${day.month}月${day.day}日' : MemoryDates.dayWithYear(day);

  /// A day as the cards and rows show it: "Nov 8", with the year when it isn't this one.
  String shortDay(DateTime day, DateTime today) => zh ? MemoryDates.dayZh(day, today) : MemoryDates.day(day, today);

  /// The picked grid cell's chip: a month, a week's range, or a day.
  String monthLong(DateTime month) =>
      zh ? '${month.year}年${month.month}月' : '${MemoryDates.monthName(month.month, zh: false)} ${month.year}';

  String dayNoYear(DateTime day) => zh ? '${day.month}月${day.day}日' : MemoryDates.day(day, day);
}
