import 'package:flutter/widgets.dart';

import 'copy/memory_copy.dart';

/// Memories' UI strings, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings_memories.xml (key in the comment). Yoin's
/// own prose (narration, question) is built in copy/memory_voice.dart.
class MemoriesStrings {
  const MemoriesStrings._(this.zh);

  const MemoriesStrings.forLanguage({required bool chinese}) : zh = chinese;

  final bool zh;

  static MemoriesStrings of(BuildContext context) =>
      MemoriesStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get topbarHome => zh ? '首页' : 'Home'; // mem_topbar_home
  String get cdCloseHome => zh ? '关闭 Memories，返回首页' : 'Close Memories, back to Home'; // mem_cd_close_home
  String get topbarMemories => 'Memories'; // mem_topbar_memories
  String topbarLastHeard(String date) => zh ? '上次收听于 $date' : 'Last heard $date'; // mem_topbar_last_heard
  String get cdShowMemory => zh ? '展示此条 Memories' : 'Show this memory'; // mem_cd_show_memory
  String get cdCloseDiary => zh ? '关闭日记，返回卡片' : 'Close the diary, back to the card'; // mem_cd_close_diary
  String cdBarAlbum(String album, String artist) => zh ? '$album，$artist' : '$album, $artist'; // mem_cd_bar_album
  String paneMemory(String title) => zh ? 'Memories，$title' : 'Memory, $title'; // mem_pane_memory
  String paneDiary(String title) => zh ? '日记，$title' : 'Diary, $title'; // mem_pane_diary
  String cdPage(int n, int of, String title) =>
      zh ? 'Memories 第 $n / $of 张：$title' : 'Memory $n of $of: $title'; // mem_cd_page
  String get cardDiary => zh ? '日记' : 'Diary'; // mem_card_diary
  String get cdOpenDiary => zh ? '打开日记' : 'Open the diary'; // mem_cd_open_diary
  String get cardGoAlbum => zh ? '前往专辑' : 'Go to album'; // mem_card_go_album
  String get diaryGoAlbum => zh ? '前往专辑' : 'Go to album'; // mem_diary_go_album
  String get albumFallback => zh ? '专辑' : 'Album'; // mem_album_fallback
  String excerptQuote(String text) => '“$text”'; // mem_card_excerpt_quote
  String get reviewToday => zh ? '今天' : 'Today'; // mem_review_today
  String get reviewYours => zh ? '你的评论' : 'Your review'; // mem_review_yours
  String get noteYours => zh ? '你的笔记' : 'Your note'; // mem_note_yours
  String get cdReviewField => zh ? '你的评论' : 'Your review'; // mem_cd_review_field
  String get cdWriteReview => zh ? '写评论' : 'Write a review'; // mem_cd_write_review
  String get reviewPlaceholder => zh ? '写下评论' : 'Write'; // mem_review_placeholder
  String get reviewCancel => zh ? '取消' : 'Cancel'; // mem_review_cancel
  String get cdDiscardDraft => zh ? '放弃草稿' : 'Discard the draft'; // mem_cd_discard_draft
  String get cdSaveReview => zh ? '保存评论' : 'Save the review'; // mem_cd_save_review
  String get reviewSave => zh ? '保存' : 'Save'; // mem_review_save
  String get snackbarSaveReview =>
      zh ? '无法保存评论，草稿已保留。' : "Couldn't save the review. Your draft is kept."; // mem_snackbar_save_review
  String get emptyTitle => zh ? '暂无 Memories' : 'No memories yet'; // mem_empty_title
  String get errorLoad => zh ? '无法加载 Memories' : 'Failed to load memories'; // mem_error_load
  String get emblemUnrated => zh ? '未评分' : 'Unrated'; // mem_emblem_unrated
  String get cdEmblemUnrated => zh ? '未评分' : 'Not rated'; // mem_cd_emblem_unrated
  String cdEmblemAlbum(String score) => zh ? '专辑评分 $score' : 'Album rating $score'; // mem_cd_emblem_album
  String cdEmblemAverage(String score) => zh ? '曲目均分 $score' : 'Track average $score'; // mem_cd_emblem_average
  String cdPlayTrack(String title) => zh ? '播放 $title' : 'Play $title'; // mem_cd_play_track
  String cdTrackNumberedRated(int n, String title, String score) =>
      zh ? '第 $n 首，$title，评分 $score' : 'Track $n, $title, rated $score'; // mem_cd_track_numbered_rated
  String cdTrackNumbered(int n, String title) => zh ? '第 $n 首，$title' : 'Track $n, $title'; // mem_cd_track_numbered
  String get cdPlaying => zh ? '正在播放' : 'Playing'; // mem_cd_playing_track / mem_cd_playing_note
  String cdPlayFrom(String title, String from) =>
      zh ? '从 $from 播放 $title' : 'Play $title from $from'; // mem_cd_play_from
  String cdNoteAt(String at, String text) => zh ? '$at 的笔记：$text' : 'Note at $at: $text'; // mem_cd_note_at
  String cdNote(String text) => zh ? '笔记：$text' : 'Note: $text'; // mem_cd_note
  String cdStat(String numeral, String caption) => '$numeral $caption'; // mem_cd_stat

  /// mem_footer_plays (one / other; Chinese other only).
  String footerPlays(int n) => zh ? '次播放' : (n == 1 ? 'play' : 'plays');

  /// mem_footer_days_since.
  String footerDaysSince(int n, String since) =>
      zh ? '天，自 $since' : (n == 1 ? 'day since $since' : 'days since $since');

  /// Memories chrome dates in the app language: this year drops the year
  /// (formatMemoryChromeDate).
  String chromeDate(MemoryDay date, MemoryDay today) =>
      zh ? MemoryDates.dayZh(date, today) : MemoryDates.day(date, today);
}
