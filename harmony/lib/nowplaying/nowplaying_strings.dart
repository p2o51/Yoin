import 'package:flutter/widgets.dart';

/// Now Playing, the pill, the queue sheet and the bar's detail pose: English
/// and Simplified Chinese, copied from Android res/values{,-zh-rCN}/strings*.xml
/// (key in the comment). PLAY / PAUSE words are English-only on Android
/// (translatable="false"); other locales show the glyph instead.
class NowPlayingStrings {
  const NowPlayingStrings._(this._zh, this.isEnglish);

  final bool _zh;

  /// The transport shows the PLAY / PAUSE words only in English (NowPlayingScreen transport).
  final bool isEnglish;

  static NowPlayingStrings of(BuildContext context) {
    final code = Localizations.maybeLocaleOf(context)?.languageCode ?? 'en';
    return NowPlayingStrings._(code == 'zh', code == 'en');
  }

  // Bar + pill.
  String get pillTapOpen => _zh ? '打开播放器' : 'Open player'; // cmp_pill_tap_open
  String get pillNothingPlaying => _zh ? '未在播放' : 'Nothing playing'; // cmp_pill_nothing_playing
  String get pillPlaybackUnavailable => _zh ? '无法播放' : 'Playback unavailable'; // cmp_pill_playback_unavailable
  String get pillCurrentTrack => _zh ? '当前曲目' : 'Current track'; // cmp_pill_cd_current_track
  String get barCdHome => _zh ? '首页' : 'Home'; // cmp_bar_cd_home
  String get barCdLibrary => _zh ? '资料库' : 'Library'; // cmp_bar_cd_library
  String get barCdSearch => _zh ? '搜索快捷方式' : 'Search shortcut'; // cmp_bar_cd_search

  // Detail pose Play split button.
  String get playLabel => _zh ? '播放' : 'Play'; // cmp_play_label
  String get playCdMore => _zh ? '更多播放选项' : 'More play options'; // cmp_play_cd_more
  String get playMenuShuffle => _zh ? '随机播放' : 'Shuffle play'; // cmp_play_menu_shuffle

  // Overlay.
  String get cdCloseNowPlaying => _zh ? '关闭正在播放' : 'Close Now Playing'; // np_cd_stage_close_now_playing
  String get cdBackNowPlaying => _zh ? '返回正在播放' : 'Back to Now Playing'; // np_cd_stage_back_now_playing
  String formatDescription(String tags) => _zh ? '音频格式 $tags' : 'Audio format $tags'; // np_format_description
  String formatTranscodedDescription(String tags, String from) =>
      _zh ? '音频格式 $tags，由 $from 转码' : 'Audio format $tags, transcoded from $from'; // np_format_transcoded_description
  String get formatSpatial => _zh ? '空间音频' : 'Spatial'; // np_format_spatial
  String get waveNote => _zh ? '笔记' : 'Note'; // cmp_wave_note
  String get cdCopyLyrics => _zh ? '复制所选歌词' : 'Copy selected lyrics'; // np_cd_copy_lyrics
  String get cdDoneSelecting => _zh ? '完成选择' : 'Done selecting lyrics'; // np_cd_done_selecting
  String lyricsSelectCount(int n, int total) => '$n / $total'; // np_lyrics_select_count
  String get lyricsSelectTap => _zh ? '选择歌词行' : 'Select lines'; // np_lyrics_select_tap
  // np_lyrics_copied
  String lyricsCopied(int n) => _zh ? '已复制 $n 行' : (n == 1 ? 'Copied 1 line' : 'Copied $n lines');
  String lyricsCredit(String title, String artist) => '$title\n$artist'; // np_lyrics_credit
  String lyricsClipboardWithCredit(String body, String credit) =>
      '$body\n\n— $credit'; // np_lyrics_clipboard_with_credit
  String get lyricsClipLabel => _zh ? '歌词' : 'Lyrics'; // np_lyrics_clip_label

  // Lyric tools (LyricsActionBar / LyricsSelectionBar).
  String get cdSearchLyrics => _zh ? '搜索歌词' : 'Search lyrics'; // np_cd_search_lyrics
  String get cdTranslateLyrics => _zh ? '翻译歌词' : 'Translate lyrics'; // np_cd_translate_lyrics
  String get cdSelectLyrics => _zh ? '选择歌词' : 'Select lyrics'; // np_cd_select_lyrics
  String get cdAddLyrics => _zh ? '添加歌词' : 'Add lyrics'; // np_cd_add_lyrics
  String get cdShareLyricsImage => _zh ? '将所选歌词分享为图片' : 'Share selected lyrics as an image'; // np_cd_share_lyrics_image
  String get cdEditLyrics => _zh ? '编辑歌词' : 'Edit lyrics'; // np_cd_edit_lyrics

  // Lyrics search + LRC editor (LyricsSheets).
  String get lyricsSearchPlaceholder => _zh ? '歌曲或艺术家' : 'Song or artist'; // np_lyrics_search_placeholder
  String get cdCloseLyricsSearch => _zh ? '关闭歌词搜索' : 'Close lyrics search'; // np_cd_close_lyrics_search
  String get cdClear => _zh ? '清除' : 'Clear'; // np_cd_clear
  String get lyricsSearchingProviders => _zh ? '正在搜索歌词源…' : 'Searching providers...'; // np_lyrics_searching_providers
  String get lyricsNoneFound => _zh ? '未找到歌词。' : 'No lyrics found.'; // np_lyrics_none_found
  String get lyricsSearching => _zh ? '正在搜索…' : 'Searching...'; // np_lyrics_searching
  String get lyricsNoResults => _zh ? '无结果' : 'No results'; // np_lyrics_no_results
  String get lyricsApplyTitle => _zh ? '应用歌词' : 'Apply lyrics'; // np_lyrics_apply_title
  String get lyricsFieldPlaceholder => _zh ? '歌词' : 'Lyrics'; // np_lyrics_field_placeholder
  String get lyricsApply => _zh ? '应用' : 'Apply'; // np_lyrics_apply
  String get lyricsCancel => _zh ? '取消' : 'Cancel'; // np_lyrics_cancel

  /// np_lyrics_provider_qq / _netease / _huawei / _lrclib; unknown ids as they are.
  String lyricsProviderLabel(String id) => switch (id) {
    'qq' => _zh ? 'QQ 音乐' : 'QQ Music',
    'netease' => _zh ? '网易云' : 'NetEase',
    'huawei' => _zh ? '华为音乐' : 'Huawei Music',
    'lrclib' => 'LRCLIB',
    _ => id,
  };
  String get msgNeedTitleArtist =>
      _zh ? '搜索歌词需要歌曲名或艺术家' : 'Need title or artist to search lyrics'; // np_msg_need_title_artist
  String get msgCouldntSearch => _zh ? '无法搜索歌词' : "Couldn't search lyrics"; // np_msg_couldnt_search
  String get msgCouldntApply => _zh ? '无法应用歌词' : "Couldn't apply lyrics"; // np_msg_couldnt_apply
  String get msgLyricsApplied => _zh ? '已应用歌词' : 'Lyrics applied'; // np_msg_lyrics_applied
  String get msgLyricsTranslated => _zh ? '已翻译歌词' : 'Lyrics translated'; // np_msg_lyrics_translated

  /// np_msg_already_language; [language] is the "Answer in" setting's name, as Android passes it.
  String msgAlreadyLanguage(String language) => _zh ? '歌词似乎已经是 $language' : 'Lyrics already appear to be $language';

  /// Fallback when lyric translation fails without a provider-specific message.
  String get msgTranslateFailed => _zh ? '翻译歌词失败' : 'Failed to translate lyrics';
  String get msgApiKeyMissing => _zh ? '缺少 Gemini API 密钥' : 'Gemini API key missing'; // np_ask_api_key_missing
  String get msgSwitch => _zh ? '切换' : 'Switch'; // np_msg_switch

  /// np_msg_translation_qq / _netease / _huawei / _lrclib / _available: a later source can translate.
  String msgTranslationAvailable(String id) => switch (id) {
    'qq' => _zh ? '有可用的 QQ 音乐翻译' : 'QQ Music translation is available',
    'netease' => _zh ? '有可用的网易云翻译' : 'NetEase translation is available',
    'huawei' => _zh ? '有可用的华为音乐翻译' : 'Huawei Music translation is available',
    'lrclib' => _zh ? '有可用的 LRCLIB 翻译' : 'LRCLIB translation is available',
    _ => _zh ? '有可用的 ${lyricsProviderLabel(id)} 翻译' : '${lyricsProviderLabel(id)} translation is available',
  };

  /// np_msg_lyrics_translated_qq / _netease / _huawei / _lrclib / _from: after "Switch".
  String msgLyricsTranslatedFrom(String id) => switch (id) {
    'qq' => _zh ? '已使用 QQ 音乐翻译' : 'Lyrics translated from QQ Music',
    'netease' => _zh ? '已使用网易云翻译' : 'Lyrics translated from NetEase',
    'huawei' => _zh ? '已使用华为音乐翻译' : 'Lyrics translated from Huawei Music',
    'lrclib' => _zh ? '已使用 LRCLIB 翻译' : 'Lyrics translated from LRCLIB',
    _ => _zh ? '已使用 $id 翻译' : 'Lyrics translated from $id',
  };

  /// np_msg_lyrics_applied_qq / _netease / _huawei / _lrclib / _from.
  String msgLyricsAppliedFrom(String id) => switch (id) {
    'qq' => _zh ? '已应用来自 QQ 音乐的歌词' : 'Lyrics applied from QQ Music',
    'netease' => _zh ? '已应用来自网易云的歌词' : 'Lyrics applied from NetEase',
    'huawei' => _zh ? '已应用来自华为音乐的歌词' : 'Lyrics applied from Huawei Music',
    'lrclib' => _zh ? '已应用来自 LRCLIB 的歌词' : 'Lyrics applied from LRCLIB',
    _ => _zh ? '已应用来自 $id 的歌词' : 'Lyrics applied from $id',
  };

  // Share as image (LyricsShareCard).
  String get shareTitle => _zh ? '分享歌词' : 'Share lyrics'; // np_share_title
  String get shareSave => _zh ? '保存' : 'Save'; // np_share_save
  String get shareSaving => _zh ? '正在保存…' : 'Saving…'; // np_share_saving
  String get shareSaved => _zh ? '已保存' : 'Saved'; // np_share_saved
  String get shareTryAgain => _zh ? '重试' : 'Try again'; // np_share_try_again
  String get shareFailed => _zh ? '无法分享图片' : "Couldn't share the image"; // np_share_failed
  String get shareAction => _zh ? '分享' : 'Share'; // np_share_action
  String get shareFormatCard => _zh ? '卡片' : 'Card'; // np_share_format_card
  String get shareFormatStory => 'Story'; // np_share_format_story
  String get cdToneSoft => _zh ? '柔和配色' : 'Soft colours'; // np_cd_tone_soft
  String get cdToneDeep => _zh ? '深色配色' : 'Deep colours'; // np_cd_tone_deep
  String get cdToneVivid => _zh ? '鲜艳配色' : 'Vivid colours'; // np_cd_tone_vivid
  String get cdToneMist => _zh ? '薄雾配色' : 'Mist colours'; // np_cd_tone_mist
  String get shareBrand => 'Yoin'; // np_share_brand (not translatable)
  String shareFileUntitled(String stamp) =>
      _zh ? 'Yoin 歌词 $stamp.png' : 'Yoin lyrics $stamp.png'; // np_share_file_untitled
  // np_share_file_named
  String shareFileNamed(String title, String stamp) =>
      _zh ? 'Yoin 歌词 - $title - $stamp.png' : 'Yoin lyrics - $title - $stamp.png';

  String get cdFullScreen => _zh ? '全屏' : 'Full screen'; // np_cd_full_screen
  String get cdBackToPanel => _zh ? '返回侧栏' : 'Back to side panel'; // np_cd_back_to_panel

  // Top bar.
  String get fromAlbum => _zh ? '播放自专辑' : 'PLAYING FROM ALBUM'; // np_from_album
  String get fromPlaylist => _zh ? '播放自播放列表' : 'PLAYING FROM PLAYLIST'; // np_from_playlist
  String get fromArtist => _zh ? '播放自艺术家' : 'PLAYING FROM ARTIST'; // np_from_artist
  String get fromNowPlaying => _zh ? '正在播放' : 'NOW PLAYING'; // np_from_now_playing

  // Cover row.
  String get cdAlbumCover => _zh ? '专辑封面' : 'Album cover'; // np_cd_album_cover
  String get cdAddFavorite => _zh ? '加入收藏' : 'Add to favorites'; // np_cd_add_favorite
  String get cdRemoveFavorite => _zh ? '取消收藏' : 'Remove from favorites'; // np_cd_remove_favorite
  String ratingState(String value) => _zh ? '评分 $value / 10' : 'Rated $value out of 10'; // cmp_rating_state
  String get ratingUnrated => _zh ? '未评分' : 'Not rated'; // cmp_rating_state_unrated

  // Transport.
  String get pauseWord => 'PAUSE'; // np_transport_pause_word (not translatable)
  String get playWord => 'PLAY'; // np_transport_play_word (not translatable)
  String get cdPause => 'Pause'; // np_cd_transport_pause (not translatable)
  String get cdPlay => 'Play'; // np_cd_transport_play (not translatable)
  String get cdSkipPrevious => _zh ? '上一首' : 'Skip previous'; // np_cd_skip_previous
  String get cdSkipNext => _zh ? '下一首' : 'Skip next'; // np_cd_skip_next
  String get cdPlayMode => _zh ? '播放模式' : 'Play mode'; // np_cd_play_mode
  String get repeatAll => _zh ? '列表循环' : 'Repeat all'; // np_transport_repeat_all
  String get shuffle => _zh ? '随机播放' : 'Shuffle'; // np_transport_shuffle
  String get shuffleRepeatOne => _zh ? '随机播放，单曲循环' : 'Shuffle, repeat one'; // np_transport_shuffle_repeat_one
  String get repeatOne => _zh ? '单曲循环' : 'Repeat one'; // np_transport_repeat_one
  String get repeatOff => _zh ? '不循环' : 'Repeat off'; // np_transport_repeat_off
  String get shuffleRepeatOff => _zh ? '随机播放，不循环' : 'Shuffle, repeat off'; // np_transport_shuffle_repeat_off
  String get repeat => _zh ? '循环' : 'Repeat'; // np_transport_repeat
  String get cdPlayModeSplit => _zh ? '分开随机和循环' : 'Separate shuffle and repeat'; // np_cd_play_mode_split

  // Lyrics.
  String get lyricsEmpty => _zh ? '暂无歌词' : 'No lyrics available'; // cmp_lyrics_empty / np_lyrics_none
  String get cdReturnCurrentLine => _zh ? '回到当前句' : 'Return to current line'; // np_cd_return_current_line

  // Queue.
  String get pillQueue => _zh ? '队列' : 'Queue'; // np_pill_queue
  String get pillWrite => _zh ? '写笔记' : 'Write'; // np_pill_write
  String get queueTitle => _zh ? '队列' : 'Queue'; // cmp_queue_title
  String get queuePlayingFrom => _zh ? '播放自' : 'Playing from'; // cmp_queue_playing_from
  String get queueSectionNow => _zh ? '正在播放' : 'Now playing'; // cmp_queue_section_now
  String get queueSectionQueued => _zh ? '队列中接下来' : 'Next in queue'; // cmp_queue_section_queued
  String queueNextFrom(String name) =>
      _zh ? '接下来：$name' : 'Next from: $name'; // cmp_queue_next_from_album / _playlist / _artist
  String get queueNextUp => _zh ? '接下来' : 'Next up'; // cmp_queue_next_up
  String get queueCdPlaying => _zh ? '正在播放' : 'Playing'; // cmp_queue_cd_playing
  String get queueCdPaused => _zh ? '已暂停' : 'Paused'; // cmp_queue_cd_paused

  // Detail tabs.
  String get tabLyrics => _zh ? '歌词' : 'Lyrics'; // np_tab_lyrics / np_text_tab_lyrics
  String get tabAbout => _zh ? '关于' : 'About'; // np_tab_about / np_text_tab_about
  String get tabNote => _zh ? '笔记' : 'Note'; // np_tab_note / np_text_tab_note

  // About (strings_nowplaying np_about_*, np_spine_*; strings_component cmp_song_info_*).
  String get aboutSearching => _zh ? '正在查找歌曲信息…' : 'Searching for song info…'; // np_about_searching
  String get aboutKeyMissing => _zh ? '未设置 Gemini API 密钥' : 'No Gemini API key'; // np_about_api_key
  String get aboutOpenSettings => _zh ? '设置' : 'Settings'; // np_about_open_settings
  String get aboutRetry => _zh ? '重试' : 'Retry'; // np_about_retry
  String get songInfoTapLoad => _zh ? '加载歌曲信息' : 'Load song info'; // cmp_song_info_tap_load
  String get songInfoSearching => _zh ? '正在查找歌曲信息…' : 'Searching for song info…'; // cmp_song_info_searching
  String get songInfoNoKey => _zh ? '未设置 Gemini API 密钥' : 'No Gemini API key'; // cmp_song_info_no_key
  String get songInfoSettings => _zh ? '设置' : 'Settings'; // cmp_song_info_settings
  String get songInfoRetry => _zh ? '重试' : 'Retry'; // cmp_song_info_retry

  /// An About field's label (np_about_creation_time … np_about_review).
  String aboutField(String key) => switch (key) {
    'creation_time' => _zh ? '创作时间' : 'Creation Time',
    'creation_location' => _zh ? '创作地点' : 'Creation Location',
    'lyricist' => _zh ? '作词' : 'Lyricist',
    'composer' => _zh ? '作曲' : 'Composer',
    'producer' => _zh ? '制作人' : 'Producer',
    'review' => _zh ? '关于' : 'About',
    _ => key,
  };
  String get aboutReview => _zh ? '关于' : 'About'; // np_about_review
  String get aboutDuration => _zh ? '时长' : 'Length'; // np_about_duration
  String get aboutChannels => _zh ? '声道' : 'Channels'; // np_about_channels
  String aboutChannelCount(int n) => switch (n) {
    1 => _zh ? '单声道' : 'Mono', // np_about_mono
    2 => _zh ? '立体声' : 'Stereo', // np_about_stereo
    _ => _zh ? '$n 声道' : '$n channels', // np_about_channel_count
  };
  String get aboutAdded => _zh ? '加入资料库' : 'Added'; // np_about_added
  String get aboutArranger => _zh ? '编曲' : 'Arranger'; // np_about_arranger
  String get aboutSize => _zh ? '文件大小' : 'File size'; // np_about_size
  String aboutMegabytes(String v) => '$v MB'; // np_about_megabytes
  String get aboutBpm => 'BPM'; // np_about_bpm
  String get aboutPlayCount => _zh ? '播放次数' : 'Plays'; // np_about_play_count
  String get aboutComment => _zh ? '备注' : 'Comment'; // np_about_comment
  String get aboutFile => _zh ? '文件' : 'File'; // np_about_file

  // The lyrics' other credits under the receipt (strings_component cmp_lyric_credits_*).
  String lyricCreditsMore(int n) => _zh ? '更多 $n 项' : '$n more'; // cmp_lyric_credits_more
  String get lyricCreditsLess => _zh ? '收起' : 'Show less'; // cmp_lyric_credits_less
  String get lyricCreditsNote => _zh ? '备注' : 'Note'; // cmp_lyric_credits_note
  String get spineQuality => _zh ? '音质' : 'Quality'; // np_spine_quality
  String get spineServerRating => _zh ? '服务器评分' : 'Server rating'; // np_spine_server_rating
  String get spineGenre => _zh ? '流派' : 'Genre'; // np_spine_genre
  String get spineYear => _zh ? '年份' : 'Year'; // np_spine_year
  String get spineTrack => _zh ? '曲目' : 'Track'; // np_spine_track
  String spineDiscTrack(int disc, int track) =>
      _zh ? '第 $disc 碟 第 $track 首' : 'Disc $disc, track $track'; // np_spine_disc_track
  String spineKbps(int v) => '$v kbps'; // np_spine_kbps
  String spineKhz(String v) => '$v kHz'; // np_spine_khz
  String spineBit(int v) => '$v-bit'; // np_spine_bit
  String get aboutCdEntryMenu => _zh ? '这一条的选项' : 'Options for this entry'; // np_about_cd_entry_menu
  String get aboutManage => _zh ? '在剪贴簿里管理' : 'Manage in Scrapbook'; // np_about_manage
  String get aboutRegenerate => _zh ? '用 Gemini 重新生成' : 'Regenerate with Gemini'; // np_about_regenerate
  String get aboutRegenerated => _zh ? '已重新生成' : 'Regenerated'; // np_about_regenerated
  String get aboutRegenerateNothing => _zh ? 'Gemini 没有新的内容' : 'Gemini had nothing new'; // np_about_regenerate_nothing
  String get aboutRegenerateFailed =>
      _zh ? 'Gemini 没能回答，再试一次' : "Gemini couldn't answer. Try again."; // np_about_regenerate_failed
  String get aboutUndo => _zh ? '撤销' : 'Undo'; // np_about_undo

  // Ask Gemini (np_ask_*; cmp_quick_prompts, cmp_cd_gemini_thinking).
  String get askGemini => _zh ? '问 Gemini' : 'Ask Gemini'; // np_ask_gemini
  String get askPlaceholder => _zh ? '问 Gemini' : 'Ask Gemini'; // np_ask_placeholder
  String get quickPromptsLabel => _zh ? '快捷提问' : 'Quick questions'; // cmp_quick_prompts_label
  String get cdGeminiThinking => _zh ? 'Gemini 正在写' : 'Gemini is writing'; // cmp_cd_gemini_thinking

  /// cmp_quick_prompts (string-array).
  List<String> get quickPrompts => _zh
      ? const ['这首歌采样了谁？', '谁采样了这首歌？', '歌词在讲什么？', '是怎么写出来的？', '有哪些翻唱？', '当时反响如何？']
      : const [
          'What does this song sample?',
          'Who sampled this song?',
          'What are the lyrics about?',
          'How was it written?',
          'What covers are there?',
          'How was it received?',
        ];

  // Notes.
  String get noteTapToWrite => _zh ? '写笔记' : 'Write'; // np_note_tap_to_write
  String noteCount(int n) => _zh ? '$n 条笔记' : (n == 1 ? '$n note' : '$n notes'); // np_note_count
  String notePlayFrom(String at) => _zh ? '从 $at 播放' : 'Play from $at'; // np_cd_note_play_from
  String get noteActions => _zh ? '笔记操作' : 'Note actions'; // np_cd_note_actions
  String get noteRealign => _zh ? '对齐至当前时刻' : 'Align to now'; // np_note_realign
  String get noteDelete => _zh ? '删除' : 'Delete'; // np_note_delete
  String get noteDeleted => _zh ? '已删除' : 'Deleted'; // np_note_deleted
  String get noteUndo => _zh ? '撤销' : 'Undo'; // np_note_undo
  String get noteWriting => _zh ? '正在写…' : 'Writing…'; // np_note_writing
  String get noteEmpty => _zh ? '暂无笔记' : 'No notes yet'; // np_note_empty
  String get noteSortTimeline => _zh ? '时间线' : 'Timeline'; // cmp_note_sort_timeline
  String get noteSortCreated => _zh ? '创建时间' : 'Created'; // cmp_note_sort_created
  String get notePlaceholder => _zh ? '写笔记' : 'Write a note'; // cmp_note_placeholder / cmp_note_write_bar_cd
  String get noteStampWrite => _zh ? '写笔记' : 'Write a note'; // cmp_note_stamp_cd_write
  String get noteStampRealign => _zh ? '对齐至当前时刻' : 'Align to now'; // cmp_note_stamp_cd_realign
  String get noteSave => _zh ? '保存' : 'Save'; // cmp_note_save

  /// The journal order's date line (cmp_note_just_now / cmp_note_*_ago).
  bool get zh => _zh;

  // Idle.
  String get emptyNothingPlaying => _zh ? '未在播放' : 'Nothing playing'; // np_empty_nothing_playing
}
