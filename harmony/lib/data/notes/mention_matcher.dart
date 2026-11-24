/// Whether a mention names an item when its id can't say: a pure-Dart port of
/// `:shared` `notes/MentionMatcher.kt` (owner 2026-11-22). By id first; then
/// by name: the title, the artist when both sides know one, and for a track
/// the album when both sides know one. Names compare the way the playlist
/// transfer compares them ([TrackMatcher]: case, width, accents,
/// Traditional/Simplified, "feat." credits and edition tags fall away;
/// "Live" / "Remix" tags must agree). Checked against the Kotlin by the shared
/// golden vectors (`test/data/notes/note_markup_golden_test.dart`).
library;

import '../lyrics/lyric_candidate_matcher.dart' show traditionalToSimplified;
import '../transfer/track_matcher.dart';
import 'note_markup.dart';

abstract final class MentionMatcher {
  static bool matchesId(NoteMention mention, String provider, String id) =>
      mention.provider == provider && mention.id == id;

  static bool titlesMatch(String? a, String? b) {
    if (a == null || b == null || isBlank(a) || isBlank(b)) return false;
    final left = _normalizeTitle(a);
    return left.isNotEmpty &&
        left == _normalizeTitle(b) &&
        _sameTags(TrackMatcher.versionTags(a), TrackMatcher.versionTags(b));
  }

  /// True when either side doesn't know the artist; otherwise they must share one ("A & B" vs "A").
  static bool artistsMatch(String? a, String? b) =>
      a == null || b == null || isBlank(a) || isBlank(b) || TrackMatcher.artistSimilarity(_fold(a), _fold(b)) >= 1.0;

  static bool namesMatchTrack(NoteMention mention, String? title, String? artist, String? album) =>
      mention.kind == MentionKind.track &&
      titlesMatch(mention.title, title) &&
      artistsMatch(mention.artist, artist) &&
      (_isNullOrBlank(mention.album) || _isNullOrBlank(album) || titlesMatch(mention.album, album));

  static bool namesMatchAlbum(NoteMention mention, String? title, String? artist) =>
      mention.kind == MentionKind.album && titlesMatch(mention.title, title) && artistsMatch(mention.artist, artist);

  static bool namesMatchArtist(NoteMention mention, String? name) {
    if (mention.kind != MentionKind.artist || name == null || isBlank(name)) return false;
    final left = TrackMatcher.normalizeArtist(_fold(mention.title));
    return left.isNotEmpty && left == TrackMatcher.normalizeArtist(_fold(name));
  }

  /// A track mention's album, by name: the mention says which album (`al`)
  /// and it is this one, by an artist this album shares.
  static bool trackMentionOnAlbum(NoteMention mention, String? albumTitle, String? albumArtist) =>
      mention.kind == MentionKind.track &&
      titlesMatch(mention.album, albumTitle) &&
      artistsMatch(mention.artist, albumArtist);

  /// The start of a title for a prefix pre-filter (credits and edition tags
  /// dropped); null when nothing is left.
  static String? searchPrefix(String title) {
    final prefix = trimKotlin(TrackMatcher.stripDecorations(title));
    return prefix.isEmpty ? null : prefix;
  }

  /// `:shared` TrackMatcher folds Traditional → Simplified Han inside its
  /// normalisation (ICU on Android); HarmonyOS's TrackMatcher doesn't, so
  /// names are folded here with the same ICU-generated table the lyrics use.
  static String _fold(String text) => traditionalToSimplified(text);

  static String _normalizeTitle(String title) => TrackMatcher.normalizeTitle(_fold(title));

  static bool _sameTags(Set<String> a, Set<String> b) => a.length == b.length && a.containsAll(b);

  static bool _isNullOrBlank(String? s) => s == null || isBlank(s);
}
