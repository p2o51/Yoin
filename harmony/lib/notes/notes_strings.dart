import 'package:flutter/widgets.dart';

/// The note editor's and 被提及's strings, English and Simplified Chinese,
/// copied from Android res/values{,-zh-rCN}/strings_notes.xml (key in the comment).
class NotesStrings {
  const NotesStrings._(this.zh);

  final bool zh;

  static NotesStrings of(BuildContext context) =>
      NotesStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  static const en = NotesStrings._(false);

  // The @ popup.
  String get mentionPopupTitle => zh ? '提及' : 'Mention'; // notes_mention_popup_title
  String get mentionExpand => zh ? '展开搜索' : 'Search all'; // notes_mention_expand
  String get mentionSectionTracks => zh ? '歌曲' : 'Songs'; // notes_mention_section_tracks
  String get mentionSectionAlbums => zh ? '专辑' : 'Albums'; // notes_mention_section_albums
  String get mentionSectionArtists => zh ? '艺术家' : 'Artists'; // notes_mention_section_artists
  String get mentionLoading => zh ? '搜索中…' : 'Searching…'; // notes_mention_loading
  String get mentionEmpty => zh ? '没有匹配结果' : 'No results'; // notes_mention_empty
  String get mentionSearchHint => zh ? '搜索资料库' : 'Search your library'; // notes_mention_search_hint
  String mentionCdTrack(String title) => zh ? '歌曲：$title' : 'Song: $title'; // notes_mention_cd_track
  String mentionCdAlbum(String title) => zh ? '专辑：$title' : 'Album: $title'; // notes_mention_cd_album
  String mentionCdArtist(String title) => zh ? '艺术家：$title' : 'Artist: $title'; // notes_mention_cd_artist

  // The formatting row.
  String get toolBold => zh ? '粗体' : 'Bold'; // notes_tool_bold
  String get toolItalic => zh ? '斜体' : 'Italic'; // notes_tool_italic
  String get toolHeading => zh ? '标题' : 'Heading'; // notes_tool_heading
  String get toolBullet => zh ? '列表' : 'List'; // notes_tool_bullet
  String get toolChecklist => zh ? '待办' : 'To-do'; // notes_tool_checklist
  String get toolMention => zh ? '提及歌曲、专辑或艺术家' : 'Mention a song, album, or artist'; // notes_tool_mention

  // The album scrapbook's 被提及.
  String get backlinksTitle => zh ? '被提及' : 'Mentioned in'; // notes_backlinks_title
  String backlinksCount(int n) => zh ? '$n 处' : (n == 1 ? '$n place' : '$n places'); // notes_backlinks_count
  String get backlinkKindNote => zh ? '笔记' : 'Note'; // notes_backlink_kind_note
  String get backlinkKindAlbumNote => zh ? '专辑笔记' : 'Album note'; // notes_backlink_kind_album_note
  String get backlinkKindReview => zh ? '乐评' : 'Review'; // notes_backlink_kind_review

  /// notes_backlink_cd: "%1$s, %2$s: %3$s" (the source's name, its meta line, the snippet).
  String backlinkCd(String name, String meta, String snippet) => zh ? '$name，$meta：$snippet' : '$name, $meta: $snippet';
}
