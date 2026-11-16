/// Library order and fast-scroller sections as pure functions (Android
/// LibrarySort.kt + LibraryIndex.kt). No Flutter, safe in an isolate.
///
/// Collation follows Android's ICU AlphabeticIndex (English primary + Japanese
/// and Korean labels): A–Z, an inflow "…" for the scripts ICU orders between
/// Latin and Hangul (Greek, Cyrillic …), the Hangul initials ㄱ–ㅎ, the kana
/// rows あ か さ … わ, a trailing overflow "…", then digits and symbols under
/// "#". Han has no bucket of its own: [LibraryListInputs.latinNames] carries
/// its pinyin (ArkTS `yoin/i18n`, lib/platform/i18n/), so 林间 files under L
/// and 午後のソーダ under W, as on Android API 29+. A Han name without a
/// pinyin form (the pure-Dart fallback) goes to the overflow "…".
/// Within a bucket names compare by their folded key: Latin lower-cased with
/// diacritics (pinyin tones included) folded, katakana read as hiragana.
library;

import '../data/model/model.dart';
import '../platform/i18n/name_transliterator.dart' show isHan;
import 'library_playlists_by_you.dart';

/// The Library's views. [all] has no chip.
enum LibraryTab { all, playlists, artists, albums, songs, favorites }

/// The chip order (Android LibraryTab.Chips).
const libraryChipOrder = [
  LibraryTab.playlists,
  LibraryTab.artists,
  LibraryTab.albums,
  LibraryTab.songs,
  LibraryTab.favorites,
];

enum LibrarySort { recents, recentlyAdded, alphabetical, creator }

/// The orders a view offers on Subsonic (albums and playlists carry library
/// dates); the first is the default. Empty = no sort row.
List<LibrarySort> librarySortOptions(LibraryTab tab) => switch (tab) {
  LibraryTab.all || LibraryTab.albums || LibraryTab.playlists => const [
    LibrarySort.recents,
    LibrarySort.recentlyAdded,
    LibrarySort.alphabetical,
    LibrarySort.creator,
  ],
  LibraryTab.artists => const [LibrarySort.recents, LibrarySort.alphabetical],
  LibraryTab.songs || LibraryTab.favorites => const [],
};

/// One cell of a sortable view.
sealed class LibraryItem {
  const LibraryItem();
  MediaId get id;
  String get name;

  /// Lazy-list key; unique across the three kinds (All mixes them).
  String get key;

  /// The Recents / activity-log key: `album:<raw>`, `artist:<raw>`, `playlist:<raw>`.
  String get recentsKey;
}

final class LibraryArtistItem extends LibraryItem {
  const LibraryArtistItem(this.artist);
  final Artist artist;
  @override
  MediaId get id => artist.id;
  @override
  String get name => artist.name;
  @override
  String get key => 'artist:${artist.id}';
  @override
  String get recentsKey => 'artist:${artist.id.rawId}';
}

final class LibraryAlbumItem extends LibraryItem {
  const LibraryAlbumItem(this.album);
  final Album album;
  @override
  MediaId get id => album.id;
  @override
  String get name => album.name;
  @override
  String get key => 'album:${album.id}';
  @override
  String get recentsKey => 'album:${album.id.rawId}';
}

final class LibraryPlaylistItem extends LibraryItem {
  const LibraryPlaylistItem(this.playlist);
  final Playlist playlist;
  @override
  MediaId get id => playlist.id;
  @override
  String get name => playlist.name;
  @override
  String get key => 'playlist:${playlist.id}';
  @override
  String get recentsKey => 'playlist:${playlist.id.rawId}';
}

/// What a fast-scroller section is called; drawn with the app's strings.
sealed class SectionLabel {
  const SectionLabel();
}

/// A letter bucket ("A", "…", "#").
final class LetterLabel extends SectionLabel {
  const LetterLabel(this.letter);
  final String letter;
  @override
  bool operator ==(Object other) => other is LetterLabel && other.letter == letter;
  @override
  int get hashCode => letter.hashCode;
}

/// A month of a timeline: bubble "Mar 2024", tick the year or the short month.
final class MonthLabel extends SectionLabel {
  const MonthLabel(this.year, this.month, {required this.tickIsMonth});
  final int year;
  final int month;
  final bool tickIsMonth;
}

enum RecentsBucket { today, thisWeek, thisMonth, notOpened }

/// Today / This week / This month / Not opened.
final class RecentsLabel extends SectionLabel {
  const RecentsLabel(this.bucket);
  final RecentsBucket bucket;
}

/// One fast-scroller section: its label and the first item index it covers.
final class FastScrollSection {
  const FastScrollSection(this.label, this.startIndex);
  final SectionLabel label;
  final int startIndex;
}

/// A sorted view and its sections (same pass).
final class SortedView {
  const SortedView(this.items, this.sections);
  final List<LibraryItem> items;
  final List<FastScrollSection> sections;
}

/// Everything the sorter needs (Android LibraryListInputs). Lists are the
/// live loaded collections; null = not loaded yet.
final class LibraryListInputs {
  const LibraryListInputs({
    this.artists,
    this.albums,
    this.playlists,
    this.sorts = const {},
    this.lastSeenMs = const {},
    this.latinNames = const {},
    required this.nowMs,
  });

  final List<Artist>? artists;
  final List<Album>? albums;
  final List<Playlist>? playlists;
  final Map<LibraryTab, LibrarySort> sorts;

  /// [LibraryItem.recentsKey] → when it was last opened or played.
  final Map<String, int> lastSeenMs;
  final int nowMs;

  /// A display name (name, artist, owner) → its Latin sort form, for names
  /// with Han ([latinKeyMap]). Absent = the name sorts as itself.
  final Map<String, String> latinNames;

  /// [name]'s sort form: its pinyin when it has one.
  String latinOf(String name) => latinNames[name] ?? name;

  LibrarySort sortOf(LibraryTab tab) => sorts[tab] ?? librarySortOptions(tab).first;
}

/// The sorter's output, one entry per sortable view.
final class SortedLibraryLists {
  const SortedLibraryLists({this.all, this.artists, this.albums, this.playlists, this.playlistsByYou});
  final SortedView? all;
  final SortedView? artists;
  final SortedView? albums;
  final SortedView? playlists;

  /// The user's own playlists in the Playlists order, when the loaded ones
  /// are mixed ([hasMixedOwnership]); null otherwise: By You has nothing to narrow.
  final SortedView? playlistsByYou;

  static const empty = SortedLibraryLists();

  SortedView? of(LibraryTab tab) => switch (tab) {
    LibraryTab.all => all,
    LibraryTab.artists => artists,
    LibraryTab.albums => albums,
    LibraryTab.playlists => playlists,
    _ => null,
  };
}

/// Sorts every view (Android LibraryViewModel.sortLists). All = artists +
/// albums + playlists in one sort, never songs; shown as soon as any list
/// has loaded.
SortedLibraryLists sortLibrary(LibraryListInputs inputs) {
  final artists = inputs.artists == null ? null : _distinct([for (final a in inputs.artists!) LibraryArtistItem(a)]);
  final albums = inputs.albums == null ? null : _distinct([for (final a in inputs.albums!) LibraryAlbumItem(a)]);
  final playlists = inputs.playlists == null
      ? null
      : _distinct([for (final p in inputs.playlists!) LibraryPlaylistItem(p)]);
  final artistDates = _artistAddedDates(inputs.albums ?? const []);
  final anyLoaded = artists != null || albums != null || playlists != null;
  return SortedLibraryLists(
    artists: artists == null ? null : sortItems(artists, inputs.sortOf(LibraryTab.artists), inputs, artistDates),
    albums: albums == null ? null : sortItems(albums, inputs.sortOf(LibraryTab.albums), inputs, artistDates),
    playlists: playlists == null
        ? null
        : sortItems(playlists, inputs.sortOf(LibraryTab.playlists), inputs, artistDates),
    playlistsByYou: inputs.playlists == null || !hasMixedOwnership(inputs.playlists!)
        ? null
        : sortItems(
            _distinct([for (final p in playlistsByYou(inputs.playlists!)) LibraryPlaylistItem(p)]),
            inputs.sortOf(LibraryTab.playlists),
            inputs,
            artistDates,
          ),
    all: !anyLoaded
        ? null
        : sortItems(
            _distinct([...?artists, ...?albums, ...?playlists]),
            inputs.sortOf(LibraryTab.all),
            inputs,
            artistDates,
          ),
  );
}

List<LibraryItem> _distinct(List<LibraryItem> items) {
  final seen = <String>{};
  return [
    for (final i in items)
      if (seen.add(i.key)) i,
  ];
}

/// An artist's "added" date = the newest addedAt of its albums (by artistId,
/// else by trimmed lower-case name).
Map<String, int> _artistAddedDates(List<Album> albums) {
  final dates = <String, int>{};
  void put(String key, int ms) {
    final old = dates[key];
    if (old == null || ms > old) dates[key] = ms;
  }

  for (final album in albums) {
    final ms = parseLibraryDate(album.addedAt);
    if (ms == null) continue;
    if (album.artistId case final id?) put('id:$id', ms);
    final name = album.artist?.trim().toLowerCase();
    if (name != null && name.isNotEmpty) put('name:$name', ms);
  }
  return dates;
}

final class _Entry {
  _Entry(
    this.item,
    this.position,
    this.sortKey,
    this.bucket,
    this.creatorKey,
    this.creatorBucket,
    this.addedAtMs,
    this.lastSeenMs,
  );
  final LibraryItem item;
  final int position;
  final String sortKey;
  final _Bucket bucket;
  final String? creatorKey;
  final _Bucket creatorBucket;
  final int? addedAtMs;
  final int? lastSeenMs;
}

/// Sorts [items] by [sort] and cuts the fast-scroller sections in the same pass.
SortedView sortItems(
  List<LibraryItem> items,
  LibrarySort sort,
  LibraryListInputs inputs, [
  Map<String, int> artistDates = const {},
]) {
  final entries = <_Entry>[];
  for (final (position, item) in items.indexed) {
    final key = collationKey(stripLeadingArticle(inputs.latinOf(item.name)));
    final creator = _creatorOf(item);
    final creatorKey = creator == null ? null : collationKey(stripLeadingArticle(inputs.latinOf(creator)));
    entries.add(
      _Entry(
        item,
        position,
        key,
        _bucketOf(key),
        creatorKey,
        creatorKey == null ? _Bucket.number : _bucketOf(creatorKey),
        _addedAtOf(item, artistDates),
        inputs.lastSeenMs[item.recentsKey],
      ),
    );
  }

  int alphabetical(_Entry a, _Entry b) {
    final byBucket = a.bucket.compareTo(b.bucket);
    if (byBucket != 0) return byBucket;
    final byKey = a.sortKey.compareTo(b.sortKey);
    return byKey != 0 ? byKey : a.position.compareTo(b.position);
  }

  int descNullsLast(int? a, int? b) => a == null ? (b == null ? 0 : 1) : (b == null ? -1 : b.compareTo(a));

  int recentlyAdded(_Entry a, _Entry b) {
    final byDate = descNullsLast(a.addedAtMs, b.addedAtMs);
    return byDate != 0 ? byDate : alphabetical(a, b);
  }

  final Comparator<_Entry> comparator = switch (sort) {
    LibrarySort.alphabetical => alphabetical,
    LibrarySort.recentlyAdded => recentlyAdded,
    LibrarySort.recents => (a, b) {
      final bySeen = descNullsLast(a.lastSeenMs, b.lastSeenMs);
      return bySeen != 0 ? bySeen : recentlyAdded(a, b);
    },
    LibrarySort.creator => (a, b) {
      final ak = a.creatorKey, bk = b.creatorKey;
      if (ak == null || bk == null) {
        if (ak != bk) return ak == null ? 1 : -1;
        return alphabetical(a, b);
      }
      final byBucket = a.creatorBucket.compareTo(b.creatorBucket);
      if (byBucket != 0) return byBucket;
      final byCreator = ak.compareTo(bk);
      return byCreator != 0 ? byCreator : alphabetical(a, b);
    },
  };
  entries.sort(comparator);

  final List<FastScrollSection> sections;
  switch (sort) {
    case LibrarySort.alphabetical:
      sections = _letterSections([for (final e in entries) e.bucket]);
    case LibrarySort.creator:
      sections = _letterSections([for (final e in entries) e.creatorBucket]);
    case LibrarySort.recentlyAdded:
      sections = timelineSections([for (final e in entries) e.addedAtMs]);
    case LibrarySort.recents:
      final anyRecord = entries.any((e) => e.lastSeenMs != null);
      sections = anyRecord
          ? recentsSections([for (final e in entries) e.lastSeenMs], nowMs: inputs.nowMs)
          // No record at all: Recents falls back to Recently added, and so do its sections.
          : timelineSections([for (final e in entries) e.addedAtMs]);
  }
  return SortedView([for (final e in entries) e.item], sections);
}

String? _creatorOf(LibraryItem item) {
  final raw = switch (item) {
    LibraryArtistItem(:final artist) => artist.name,
    LibraryAlbumItem(:final album) => album.artist,
    LibraryPlaylistItem(:final playlist) => playlist.owner,
  };
  return raw == null || raw.trim().isEmpty ? null : raw;
}

int? _addedAtOf(LibraryItem item, Map<String, int> artistDates) => switch (item) {
  LibraryAlbumItem(:final album) => parseLibraryDate(album.addedAt),
  // Subsonic playlists carry no library date in the Harmony model yet.
  LibraryPlaylistItem() => null,
  LibraryArtistItem(:final artist) =>
    artistDates['id:${artist.id}'] ?? artistDates['name:${artist.name.trim().toLowerCase()}'],
};

/// Subsonic's ignoredArticles default (Android uses the same fixed list).
const libraryIgnoredArticles = ['The', 'El', 'La', 'Los', 'Las', 'Le', 'Les', 'Os', 'As', 'O', 'A'];

/// [name] trimmed, with ONE leading article removed when a word follows it.
String stripLeadingArticle(String name, [List<String> articles = libraryIgnoredArticles]) {
  final trimmed = name.trim();
  final lower = trimmed.toLowerCase();
  for (final article in articles) {
    final a = article.toLowerCase();
    if (lower.length <= a.length || !lower.startsWith(a)) continue;
    final after = trimmed.codeUnitAt(a.length);
    if (after != 0x20 && after != 0x09 && after != 0xA0) continue;
    final rest = trimmed.substring(a.length).trim();
    if (rest.isNotEmpty) return rest;
  }
  return trimmed;
}

/// A library date (Subsonic `created`) as epoch ms: ISO instant, offset
/// date-time, a local date-time read as UTC, or a bare yyyy-MM-dd (UTC).
/// Unparsable or blank = null.
int? parseLibraryDate(String? raw) {
  final s = raw?.trim();
  if (s == null || s.isEmpty) return null;
  final parsed = DateTime.tryParse(s);
  if (parsed == null) return null;
  if (parsed.isUtc) return parsed.millisecondsSinceEpoch;
  // No zone in the text: read the wall-clock fields as UTC.
  return DateTime.utc(
    parsed.year,
    parsed.month,
    parsed.day,
    parsed.hour,
    parsed.minute,
    parsed.second,
    parsed.millisecond,
  ).millisecondsSinceEpoch;
}

// ---------------------------------------------------------------- collation

/// A fast-scroller bucket, in section order: A–Z (0–25), inflow "…" (26),
/// Hangul initials ㄱ–ㅎ (27–40), kana rows あ–わ (41–50), overflow "…" (51),
/// digits/symbols "#" (52).
extension type const _Bucket(int index) {
  static const inflow = _Bucket(26);
  static const _hangulStart = 27;
  static const _kanaStart = 41;
  static const overflow = _Bucket(51);
  static const number = _Bucket(52);
  int compareTo(_Bucket b) => index.compareTo(b.index);
  String get label {
    if (index < 26) return String.fromCharCode(0x41 + index);
    if (index >= _hangulStart && index < _kanaStart) return _hangulLabels[index - _hangulStart];
    if (index >= _kanaStart && index < overflow.index) return _kanaLabels[index - _kanaStart];
    return index == number.index ? '#' : '…';
  }
}

/// ICU's Korean index labels.
const _hangulLabels = ['ㄱ', 'ㄴ', 'ㄷ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];

/// ICU's Japanese index labels (the gojūon rows).
const _kanaLabels = ['あ', 'か', 'さ', 'た', 'な', 'は', 'ま', 'や', 'ら', 'わ'];

/// A syllable's 19 initials (ㄱ ㄲ ㄴ ㄷ ㄸ ㄹ ㅁ ㅂ ㅃ ㅅ ㅆ ㅇ ㅈ ㅉ ㅊ ㅋ ㅌ ㅍ ㅎ) → label index; a doubled
/// consonant files under its single one.
const _hangulInitialLabel = [0, 0, 1, 2, 2, 3, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13];

/// Compatibility jamo ㄱ (U+3131) … ㅎ (U+314E) → label index; clusters under their first consonant.
const _hangulJamoLabel = [0, 0, 0, 1, 1, 1, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 5, 5, 5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13];

/// The first hiragana of each row after あ (か さ た な は ま や ら わ, small ゎ leading わ).
const _kanaRowStarts = [0x304B, 0x3055, 0x305F, 0x306A, 0x306F, 0x307E, 0x3083, 0x3089, 0x308E];

final _letter = RegExp(r'\p{L}', unicode: true);

/// The bucket a collation key files under.
_Bucket _bucketOf(String key) {
  if (key.isEmpty) return _Bucket.number;
  final c = key.runes.first;
  if (c >= 0x61 && c <= 0x7A) return _Bucket(c - 0x61);
  // Hangul syllables, then leading jamo, then compatibility jamo.
  if (c >= 0xAC00 && c <= 0xD7A3) return _Bucket(_Bucket._hangulStart + _hangulInitialLabel[(c - 0xAC00) ~/ 588]);
  if (c >= 0x1100 && c <= 0x1112) return _Bucket(_Bucket._hangulStart + _hangulInitialLabel[c - 0x1100]);
  if (c >= 0x3131 && c <= 0x314E) return _Bucket(_Bucket._hangulStart + _hangulJamoLabel[c - 0x3131]);
  // Kana (the key reads katakana as hiragana): ゔ is a vowel-row sound, ゕ ゖ are small か け.
  if (c >= 0x3041 && c <= 0x3096) {
    if (c == 0x3094) return const _Bucket(_Bucket._kanaStart);
    if (c >= 0x3095) return const _Bucket(_Bucket._kanaStart + 1);
    var row = 0;
    while (row < _kanaRowStarts.length && c >= _kanaRowStarts[row]) {
      row++;
    }
    return _Bucket(_Bucket._kanaStart + row);
  }
  // Han without a pinyin form, Bopomofo and the rest of the CJK block sort after kana in ICU.
  if (isHan(c) || (c >= 0x3100 && c <= 0x312F) || (c >= 0x31A0 && c <= 0x31BF)) return _Bucket.overflow;
  return _letter.hasMatch(String.fromCharCode(c)) ? _Bucket.inflow : _Bucket.number;
}

/// The bucket's label for [name] (tests, search); [latinNames] as in
/// [LibraryListInputs.latinNames].
String bucketLabelOf(String name, [Map<String, String> latinNames = const {}]) =>
    _bucketOf(collationKey(stripLeadingArticle(latinNames[name] ?? name))).label;

/// Lower-case, Latin diacritics (pinyin tone marks too) folded, katakana as
/// hiragana.
String collationKey(String s) {
  final out = StringBuffer();
  for (final rune in s.toLowerCase().runes) {
    if (rune >= 0x30A1 && rune <= 0x30F6) {
      out.writeCharCode(rune - 0x60);
      continue;
    }
    final ch = String.fromCharCode(rune);
    out.write(_fold[ch] ?? ch);
  }
  return out.toString();
}

const _foldFrom = 'àáâãäåāăąǎçćĉċčďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıǐĵķĺļľŀłñńņňǹòóôõöøōŏőǒŕŗřśŝşšţťŧùúûüũūŭůűųǔǖǘǚǜŵýÿŷźżžḿ';
const _foldTo = 'aaaaaaaaaacccccddeeeeeeeeegggghhiiiiiiiiiijklllllnnnnnoooooooooorrrsssstttuuuuuuuuuuuuuuuwyyyzzzm';
final Map<String, String> _fold = {
  for (var i = 0; i < _foldFrom.length; i++) _foldFrom[i]: _foldTo[i],
  'æ': 'ae',
  'œ': 'oe',
  'ß': 'ss',
  'þ': 'th',
  'ð': 'd',
};

List<FastScrollSection> _letterSections(List<_Bucket> buckets) {
  final sections = <FastScrollSection>[];
  _Bucket? current;
  for (final (i, b) in buckets.indexed) {
    if (b != current) {
      current = b;
      sections.add(FastScrollSection(LetterLabel(b.label), i));
    }
  }
  return sections;
}

// --------------------------------------------------------------- timelines

const _twoYearsMs = 63115200000; // 2 × 365.25 days
const _maxSegmentShare = 0.6;
const _maxUndatedShare = 0.2;

int _monthOf(int ms) {
  final d = DateTime.fromMillisecondsSinceEpoch(ms);
  return d.year * 12 + (d.month - 1);
}

/// Sections for a list in date order (Android LibraryIndex.timeline). None
/// when more than 20 % is undated or one segment holds more than 60 %.
List<FastScrollSection> timelineSections(List<int?> datesMs) {
  final dated = datesMs.whereType<int>().toList();
  if (dated.isEmpty) return const [];
  if (datesMs.length - dated.length > datesMs.length * _maxUndatedShare) return const [];
  var min = dated.first, max = dated.first;
  for (final d in dated) {
    if (d < min) min = d;
    if (d > max) max = d;
  }
  final byMonth = max - min < _twoYearsMs;
  final counts = <int, int>{};
  for (final ms in dated) {
    final m = _monthOf(ms);
    final seg = byMonth ? m : m ~/ 12;
    counts[seg] = (counts[seg] ?? 0) + 1;
  }
  if (counts.values.reduce((a, b) => a > b ? a : b) > dated.length * _maxSegmentShare) return const [];

  final sections = <FastScrollSection>[];
  var currentMonth = -1;
  var currentYear = -1;
  for (final (i, ms) in datesMs.indexed) {
    if (ms == null) continue;
    final m = _monthOf(ms);
    if (m == currentMonth) continue;
    currentMonth = m;
    final year = m ~/ 12;
    sections.add(
      FastScrollSection(
        MonthLabel(year, m % 12 + 1, tickIsMonth: byMonth && year == currentYear),
        sections.isEmpty ? 0 : i,
      ),
    );
    currentYear = year;
  }
  return sections;
}

/// Recents sections (Android LibraryIndex.recentsSections): today, this
/// week (Monday-first), this month, then months (or years once the records
/// reach back two years), then "Not opened" for the unrecorded tail.
List<FastScrollSection> recentsSections(List<int?> lastSeenMs, {required int nowMs}) {
  final recorded = lastSeenMs.whereType<int>().toList();
  if (recorded.isEmpty) return const [];
  final now = DateTime.fromMillisecondsSinceEpoch(nowMs);
  final today = DateTime(now.year, now.month, now.day);
  final startOfToday = today.millisecondsSinceEpoch;
  final startOfWeek = today.subtract(Duration(days: today.weekday - DateTime.monday)).millisecondsSinceEpoch;
  final startOfMonth = DateTime(now.year, now.month).millisecondsSinceEpoch;
  final oldest = recorded.reduce((a, b) => a < b ? a : b);
  final byMonth = nowMs - oldest < _twoYearsMs;

  // -1 today, -2 this week, -3 this month; else a month or a year number.
  int segmentOf(int ms) {
    if (ms >= startOfToday) return -1;
    if (ms >= startOfWeek) return -2;
    if (ms >= startOfMonth) return -3;
    return byMonth ? _monthOf(ms) : _monthOf(ms) ~/ 12;
  }

  final counts = <int, int>{};
  for (final ms in recorded) {
    final s = segmentOf(ms);
    counts[s] = (counts[s] ?? 0) + 1;
  }
  if (counts.values.reduce((a, b) => a > b ? a : b) > recorded.length * _maxSegmentShare) return const [];

  final sections = <FastScrollSection>[];
  int? currentSegment;
  var currentYear = now.year;
  var unrecordedStarted = false;
  for (final (i, ms) in lastSeenMs.indexed) {
    if (ms == null) {
      if (!unrecordedStarted) {
        unrecordedStarted = true;
        sections.add(FastScrollSection(const RecentsLabel(RecentsBucket.notOpened), i));
      }
      continue;
    }
    final seg = segmentOf(ms);
    if (seg == currentSegment) continue;
    currentSegment = seg;
    final SectionLabel label;
    switch (seg) {
      case -1:
        label = const RecentsLabel(RecentsBucket.today);
      case -2:
        label = const RecentsLabel(RecentsBucket.thisWeek);
      case -3:
        label = const RecentsLabel(RecentsBucket.thisMonth);
      default:
        final m = _monthOf(ms);
        final year = m ~/ 12;
        label = MonthLabel(year, m % 12 + 1, tickIsMonth: byMonth && year == currentYear);
        currentYear = year;
    }
    sections.add(FastScrollSection(label, sections.isEmpty ? 0 : i));
  }
  return sections;
}
