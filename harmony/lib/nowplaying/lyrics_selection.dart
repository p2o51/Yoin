import 'package:flutter/foundation.dart';

import 'lyrics_logic.dart';
import 'nowplaying_strings.dart';

/// Most lines one pick holds — spotoolfy's poster cap (Android
/// `MaxSelectedLyricLines`); past it a share card stops reading as a quote.
const int maxSelectedLyricLines = 15;

/// How long the top label names the cap after a refused tap (SelectLimitNudgeMs).
const Duration selectLimitNudge = Duration(milliseconds: 1800);

/// One tap in select mode: the new pick, and whether the cap refused it.
@immutable
final class LyricSelectionTap {
  const LyricSelectionTap(this.selected, {this.hitLimit = false});

  final Set<int> selected;
  final bool hitLimit;
}

/// spotoolfy's free pick, as one tap on line [tapped] (Android
/// `nextLyricSelection`): a picked line lets go, any other line joins —
/// refused once [maxLines] are picked.
LyricSelectionTap nextLyricSelection(Set<int> current, int tapped, {int maxLines = maxSelectedLyricLines}) {
  if (current.contains(tapped)) return LyricSelectionTap({...current}..remove(tapped));
  if (current.length >= maxLines) return LyricSelectionTap(current, hitLimit: true);
  return LyricSelectionTap({...current, tapped});
}

/// How a line reads in select mode, from the pick (LyricSelectionRole).
enum LyricSelectionRole {
  /// Nothing picked yet: every line is an even candidate.
  idle,

  /// Part of the pick.
  picked,

  /// Not picked, and the pick has room: one tap adds it.
  open,

  /// Steps back: the pick is full, or the line can't be picked at all (the title card).
  outside,
}

LyricSelectionRole lyricSelectionRole(int index, Set<int> selected, {int maxLines = maxSelectedLyricLines}) {
  if (selected.isEmpty) return LyricSelectionRole.idle;
  if (selected.contains(index)) return LyricSelectionRole.picked;
  if (selected.length >= maxLines) return LyricSelectionRole.outside;
  return LyricSelectionRole.open;
}

/// The picked lines in lyric order; stale indices (lyrics replaced) drop out.
List<LyricRow> selectedLyricLines(List<LyricRow> lyrics, Set<int> selected) => [
  for (final i in (selected.where((i) => i >= 0 && i < lyrics.length).toList()..sort())) lyrics[i],
];

/// Where the picked lines skip part of the song: positions in
/// [selectedLyricLines] whose line doesn't follow the previous one. Each
/// starts a new passage on the card and after a blank line in the copy.
Set<int> lyricPassageStarts(List<LyricRow> lyrics, Set<int> selected) {
  final picked = selected.where((i) => i >= 0 && i < lyrics.length).toList()..sort();
  return {
    for (var i = 1; i < picked.length; i++)
      if (picked[i] != picked[i - 1] + 1) i,
  };
}

/// The title, then the artist on a line of its own (or just the title); null
/// without a title (lyricsCredit).
String? lyricsCredit(String? songTitle, String? artist, NowPlayingStrings strings) {
  final title = songTitle?.trim() ?? '';
  if (title.isEmpty) return null;
  final by = artist?.trim() ?? '';
  return by.isEmpty ? title : strings.lyricsCredit(title, by);
}

/// The clipboard's text (lyricsClipboardText): one lyric per line, each
/// followed by its translation when the translation shows, a blank line
/// before each of [passageStarts], then — after a blank line — the credit
/// "— Title", the artist on the next line.
String lyricsClipboardText(
  List<LyricRow> lines, {
  required bool includeTranslation,
  required NowPlayingStrings strings,
  String? songTitle,
  String? artist,
  Set<int> passageStarts = const {},
}) {
  final body = [
    for (var i = 0; i < lines.length; i++)
      () {
        final line = lines[i];
        final translation = includeTranslation && (line.translation?.trim().isNotEmpty ?? false)
            ? line.translation
            : null;
        final text = translation == null ? line.text : '${line.text}\n$translation';
        return passageStarts.contains(i) ? '\n$text' : text;
      }(),
  ].join('\n');
  final credit = lyricsCredit(songTitle, artist, strings);
  return credit == null ? body : strings.lyricsClipboardWithCredit(body, credit);
}

/// The top label (lyricsSelectionLabel): "Select lines" with nothing picked,
/// "N / 15" after, and "15 / 15" when full or while a refused tap nudges.
String lyricsSelectionLabel(
  int count,
  NowPlayingStrings strings, {
  int maxLines = maxSelectedLyricLines,
  bool limitNudge = false,
}) {
  if (limitNudge || count >= maxLines) return strings.lyricsSelectCount(maxLines, maxLines);
  if (count == 0) return strings.lyricsSelectTap;
  return strings.lyricsSelectCount(count, maxLines);
}

/// Select mode of the expanded Lyrics page (the ✓ lyric tool; Android
/// LyricsSelectionState): tap lines to pick them, then copy or share them.
/// While [active] the page stops following the playhead, taps pick instead of
/// seeking, the pager stops swiping and the tools never auto-hide.
class LyricsSelection extends ChangeNotifier {
  LyricsSelection({this.maxLines = maxSelectedLyricLines});

  final int maxLines;
  bool _active = false;
  Set<int> _selected = const {};

  bool get active => _active;
  Set<int> get selected => _selected;

  void enter() {
    _active = true;
    _selected = const {};
    notifyListeners();
  }

  void exit() {
    if (!_active && _selected.isEmpty) return;
    _active = false;
    _selected = const {};
    notifyListeners();
  }

  /// Applies a tap on line [index]; false when the pick is full and the tap would add to it.
  bool toggle(int index) {
    if (!_active) return false;
    final step = nextLyricSelection(_selected, index, maxLines: maxLines);
    if (step.hitLimit) return false;
    _selected = step.selected;
    notifyListeners();
    return true;
  }
}
