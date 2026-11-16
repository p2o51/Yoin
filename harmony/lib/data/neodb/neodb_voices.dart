/// What other listeners on NeoDB make of an album: the page "别人怎么说"
/// (Android NeoDbVoices.kt, owner 2026-11-09). A reference, not a feature of
/// the album page: only the score spread, what people wrote, and ways out
/// (NeoDB, Douban, RYM, AOTY). No comparison with the user's own score.
library;

import 'package:flutter/foundation.dart';

import 'neodb_models.dart';

@immutable
final class NeoDbAlbumVoices {
  const NeoDbAlbumVoices({
    required this.itemUuid,
    required this.itemUrl,
    required this.title,
    this.rating,
    this.ratingCount = 0,
    this.distribution = const [],
    this.doubanUrl,
    this.discogsUrl,
    this.musicBrainzUrl,
  });

  final String itemUuid;

  /// The item's page on the instance.
  final String itemUrl;
  final String title;
  final double? rating;
  final int ratingCount;

  /// Five buckets (1 to 5 stars), percent. Empty when NeoDB gave none.
  final List<int> distribution;
  final String? doubanUrl;
  final String? discogsUrl;
  final String? musicBrainzUrl;
}

/// One listener's words on the album: a short comment, or a review's title and opening.
@immutable
final class NeoDbVoice {
  const NeoDbVoice({
    required this.id,
    required this.author,
    required this.handle,
    this.avatarUrl,
    this.rating,
    required this.text,
    this.reviewTitle,
    this.folded = false,
    this.foldNote,
    this.url,
    this.createdAtMillis,
  });

  final String id;
  final String author;

  /// user@instance: the instance says where the listener lives (NeoDB is federated).
  final String handle;
  final String? avatarUrl;

  /// 1–10 (NeoDB's grade), or null when they left words only.
  final int? rating;
  final String text;

  /// Non-null for a long review: its title; [text] is then its opening.
  final String? reviewTitle;

  /// The writer folded it (sensitive or a content warning): shown only on a tap.
  final bool folded;
  final String? foldNote;
  final String? url;
  final int? createdAtMillis;

  /// Long words, a review, or folded words take the whole width; short ones sit two by two.
  bool get isLong => reviewTitle != null || text.length > NeoDbVoicesParsing.longVoiceChars;

  /// The writer's name; their instance only when it isn't neodb.social.
  String get byline {
    final at = handle.indexOf('@');
    final host = at < 0 ? '' : handle.substring(at + 1);
    return host.isEmpty || host == 'neodb.social' ? author : '$author · $host';
  }
}

@immutable
final class NeoDbVoicePage {
  const NeoDbVoicePage({required this.voices, required this.page, required this.pages, required this.count});

  final List<NeoDbVoice> voices;
  final int page;
  final int pages;
  final int count;
}

/// A score from another catalogue, shown beside NeoDB's spread for reference.
@immutable
final class NeoDbReferenceScore {
  const NeoDbReferenceScore({
    required this.source,
    required this.value,
    required this.scale,
    this.votes,
    required this.url,
  });

  final String source;
  final double value;
  final int scale;
  final int? votes;
  final String url;
}

abstract final class NeoDbVoicesParsing {
  static const _searchWindow = 8;
  static const _reviewOpeningChars = 180;

  /// A voice longer than this many characters takes the whole width.
  static const longVoiceChars = 40;

  /// Which NeoDB item stands for the album, or null when NeoDB doesn't seem
  /// to have it. NeoDB's search is loose (for "Museum" by OWIS it answers
  /// with a jazz "Museum" and a dozen other museums), so a hit must carry the
  /// album's title, and the artist must agree whenever both sides name one; a
  /// title that is the other's plus an edition's words needs the artist to
  /// agree outright. Among the hits the one most people rated wins (OK
  /// Computer: 510 ratings on one item, 4 on the Apple Music edition).
  static CatalogItem? pickAlbumItem(String albumName, String? artistName, List<CatalogItem> items) {
    final key = normalizeTitle(albumName);
    if (key.isEmpty) return null;
    final keys = _artistKeys(artistName);
    final exact = <CatalogItem>[];
    final loose = <CatalogItem>[];
    for (final item in items.take(_searchWindow)) {
      if (item.uuid == null || item.uuid!.trim().isEmpty) continue;
      final title = normalizeTitle(item.title ?? item.displayTitle ?? '');
      if (title.isEmpty) continue;
      final artist = _artistAgreement(keys, item.artist);
      if (title == key && artist != _Agreement.differs) {
        exact.add(item);
      } else if (artist == _Agreement.same && _isEditionOf(title, key)) {
        loose.add(item);
      }
    }
    return _mostRated(exact) ?? _mostRated(loose);
  }

  /// The first of equal counts wins: search order breaks ties.
  static CatalogItem? _mostRated(List<CatalogItem> items) {
    CatalogItem? best;
    for (final item in items) {
      if (best == null || (item.ratingCount ?? 0) > (best.ratingCount ?? 0)) best = item;
    }
    return best;
  }

  /// One normalized title is the other followed by an edition's words and
  /// nothing else ("范特西plus" is not "范特西"; "Luv Is Rage 2" is not "Luv Is Rage").
  static bool _isEditionOf(String a, String b) {
    final (shorter, longer) = a.length <= b.length ? (a, b) : (b, a);
    if (!longer.startsWith(shorter)) return false;
    final added = longer.substring(shorter.length);
    // A year or an ordinal alone says too little: it needs a word beside it.
    return _editionAdded.hasMatch(added) && _editionWord.hasMatch(added);
  }

  static const _editionWords =
      'deluxe|super|expanded|extended|special|limited|collectors?|anniversary|'
      'edition|version|remaster(?:ed)?|reissue|bonus|tracks?|mono|stereo|vinyl|'
      '豪华|豪華|特别|特別|限定|限量|纪念|紀念|周年|週年|重制|重製|复刻|復刻|经典|經典|专辑|專輯|黑胶|黑膠|版|盤|盘';
  static final _editionWord = RegExp(_editionWords);
  static final _editionAdded = RegExp('^(?:$_editionWords|(?:19|20)\\d{2}|\\d{1,3}(?:st|nd|rd|th|周年|週年))+\$');

  static final _artistSplit = RegExp(r'\s*(?:,|&|/|、|;|＆|，|\bfeat\.?|\bft\.|\bwith\b|\bx\b)\s*', caseSensitive: false);

  /// "A, B & C feat. D" → each name, normalized like a title.
  static List<String> _artistKeys(String? artistName) => [
    for (final part in (artistName ?? '').split(_artistSplit))
      if (_normalizeName(part).isNotEmpty) _normalizeName(part),
  ];

  static _Agreement _artistAgreement(List<String> keys, List<String> itemArtists) {
    final names = [
      for (final a in itemArtists)
        if (_normalizeName(a).isNotEmpty) _normalizeName(a),
    ];
    if (keys.isEmpty || names.isEmpty) return _Agreement.unknown;
    final same = keys.any((k) => names.any((n) => n == k || n.contains(k) || k.contains(n)));
    return same ? _Agreement.same : _Agreement.differs;
  }

  static final _letterOrDigit = RegExp(r'[\p{L}\p{Nd}]', unicode: true);
  static final _whitespace = RegExp(r'\s', unicode: true);

  static String _keepLettersAndDigits(String s) =>
      String.fromCharCodes(s.runes.where((r) => _letterOrDigit.hasMatch(String.fromCharCode(r))));

  static String _normalizeName(String name) => _keepLettersAndDigits(name.toLowerCase());

  static final _trailingBracket = RegExp(r'\s*[(\[（【][^()\[\]（）【】]*[)\]）】]\s*$');

  /// Case, punctuation, spaces and a trailing "(Remastered)" / "[Deluxe]"
  /// don't make a different album. A title of signs alone ("?", "÷") keeps
  /// them: they are all there is to compare.
  static String normalizeTitle(String title) {
    var t = title.trim();
    while (true) {
      final stripped = t.replaceAll(_trailingBracket, '');
      if (stripped == t || stripped.trim().isEmpty) break;
      t = stripped;
    }
    final lowered = t.toLowerCase();
    final kept = _keepLettersAndDigits(lowered);
    return kept.isNotEmpty ? kept : lowered.replaceAll(_whitespace, '');
  }

  static NeoDbAlbumVoices? albumVoices(String instance, CatalogItem item) {
    final uuid = item.uuid;
    if (uuid == null || uuid.trim().isEmpty) return null;
    final links = item.externalResources ?? const <String>[];
    String? first(String part) => links.where((l) => l.contains(part)).firstOrNull;
    final distribution = item.ratingDistribution;
    return NeoDbAlbumVoices(
      itemUuid: uuid,
      itemUrl: _itemUrl(instance, item.url, uuid),
      title: item.title ?? item.displayTitle ?? '',
      rating: item.rating,
      ratingCount: item.ratingCount ?? 0,
      distribution: distribution != null && distribution.length == 5 ? distribution : const [],
      doubanUrl: first('douban.com/'),
      discogsUrl: first('discogs.com/master/') ?? first('discogs.com/release/'),
      musicBrainzUrl: first('musicbrainz.org/release-group/') ?? first('musicbrainz.org/release/'),
    );
  }

  static String _itemUrl(String instance, String? path, String uuid) {
    final base = instance.replaceAll(RegExp(r'/+$'), '');
    if (path == null || path.trim().isEmpty) return '$base/album/$uuid';
    if (path.startsWith('http')) return path;
    return base + (path.startsWith('/') ? path : '/$path');
  }

  static NeoDbVoicePage page(int page, NeoDbPostPage body) {
    // One voice per id: the list keys its cards by id.
    final seen = <String>{};
    final voices = <NeoDbVoice>[];
    for (final post in body.data) {
      final v = voice(post);
      if (v != null && seen.add(v.id)) voices.add(v);
    }
    return NeoDbVoicePage(voices: voices, page: page, pages: body.pages, count: body.count);
  }

  /// A post becomes a voice only when it carries words: a Comment's text or a Review.
  static NeoDbVoice? voice(NeoDbPost post) {
    final related = post.relatedWith;
    final comment = related.where((r) => _str(r, 'type') == 'Comment').firstOrNull;
    final review = related.where((r) => _str(r, 'type') == 'Review').firstOrNull;
    final ratingValue = related.where((r) => _str(r, 'type') == 'Rating').firstOrNull?['value'];
    final ratingDouble = switch (ratingValue) {
      final num n => n.toDouble(),
      final String s => double.tryParse(s),
      _ => null,
    };
    final ratingInt = ratingDouble?.truncate();
    final rating = ratingInt != null && ratingInt >= 1 && ratingInt <= 10 ? ratingInt : null;
    final reviewTitle = _str(review, 'name')?.trim();
    final title = reviewTitle == null || reviewTitle.isEmpty ? null : reviewTitle;
    final text = review != null
        ? reviewOpening(_str(review, 'content') ?? '')
        : (_str(comment, 'content') ?? '').trim();
    if (text.isEmpty && title == null) return null;
    final account = post.account;
    final handle = account?.acct ?? '';
    final spoiler = post.spoilerText?.trim();
    final foldNote = spoiler == null || spoiler.isEmpty ? null : spoiler;
    final display = account?.displayName?.trim();
    final at = handle.indexOf('@');
    return NeoDbVoice(
      id: post.id.isNotEmpty ? post.id : (_str(review, 'id') ?? _str(comment, 'id') ?? ''),
      author: display != null && display.isNotEmpty
          ? display
          : account?.username ?? (at < 0 ? handle : handle.substring(0, at)),
      handle: handle,
      avatarUrl: account?.avatar,
      rating: rating,
      text: text,
      reviewTitle: title,
      folded: post.sensitive || foldNote != null,
      foldNote: foldNote,
      url: _str(review, 'href') ?? post.url,
      createdAtMillis: post.createdAt == null ? null : DateTime.tryParse(post.createdAt!)?.millisecondsSinceEpoch,
    );
  }

  static final _image = RegExp(r'!\[[^\]]*]\([^)]*\)');
  static final _link = RegExp(r'\[([^\]]*)]\([^)]*\)');
  static final _marks = RegExp(r'[*_#>`~]+');

  /// A review is Markdown: its first lines as plain text, cut at a sentence when it can be.
  static String reviewOpening(String markdown) {
    final plain = markdown
        .replaceAll(_image, '')
        .replaceAllMapped(_link, (m) => m.group(1) ?? '')
        .replaceAll(_marks, '')
        .split(RegExp(r'\r\n|\n|\r'))
        .map((l) => l.trim())
        .where((l) => l.isNotEmpty)
        .join(' ');
    if (plain.length <= _reviewOpeningChars) return plain;
    final cut = plain.substring(0, _reviewOpeningChars);
    var end = -1;
    for (var i = cut.length - 1; i >= 0; i--) {
      if ('。！？.!?'.contains(cut[i])) {
        end = i;
        break;
      }
    }
    return end >= _reviewOpeningChars ~/ 2 ? cut.substring(0, end + 1) : '$cut…';
  }

  static String rymSearchUrl(String albumName, String? artist) =>
      'https://rateyourmusic.com/search?searchtype=l&searchterm=${_encode(query(albumName, artist))}';

  static String aotySearchUrl(String albumName, String? artist) =>
      'https://www.albumoftheyear.org/search/albums/?q=${_encode(query(albumName, artist))}';

  static String neoDbSearchUrl(String instance, String albumName, String? artist) =>
      '${instance.replaceAll(RegExp(r'/+$'), '')}/search?c=music&q=${_encode(query(albumName, artist))}';

  static String query(String albumName, String? artist) {
    final a = artist?.trim();
    return [albumName.trim(), if (a != null && a.isNotEmpty) a].join(' ');
  }

  /// A link the page may hand to the browser: post and catalogue links come
  /// from NeoDB and, through federation, from any server; only a web address
  /// is opened, never another scheme.
  static bool isWebLink(String url) {
    final lower = url.toLowerCase();
    return lower.startsWith('https://') || lower.startsWith('http://');
  }

  static final _discogs = RegExp(r'discogs\.com/(?:[a-z]{2}/)?(master|release)/(\d+)');
  static final _musicBrainz = RegExp(r'musicbrainz\.org/(release-group|release)/([0-9a-fA-F-]{36})');

  /// `https://www.discogs.com/master/21491-Radiohead-OK-Computer` → ("master", 21491).
  static (String, int)? discogsId(String url) {
    final m = _discogs.firstMatch(url);
    if (m == null) return null;
    final id = int.tryParse(m.group(2)!);
    return id == null ? null : (m.group(1)!, id);
  }

  /// `https://musicbrainz.org/release/<mbid>` → ("release", mbid).
  static (String, String)? musicBrainzId(String url) {
    final m = _musicBrainz.firstMatch(url);
    return m == null ? null : (m.group(1)!, m.group(2)!);
  }

  static String _encode(String value) => Uri.encodeQueryComponent(value);

  static String? _str(JsonObject? o, String key) => switch (o?[key]) {
    final String s => s,
    final num n => '$n',
    final bool b => '$b',
    _ => null,
  };
}

enum _Agreement { same, differs, unknown }
