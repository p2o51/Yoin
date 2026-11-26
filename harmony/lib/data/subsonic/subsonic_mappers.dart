/// Subsonic JSON → neutral models (Android data/source/subsonic/SubsonicMappers.kt).
///
/// Same semantics as Android: `starred` means "a non-empty timestamp", album
/// art falls back to the album's own id, ratings stay raw, dates stay the
/// server's ISO strings, playlist `canWrite` uses the same ownership rule.
/// Reading is lenient where servers differ: numbers may arrive as strings,
/// a one-element list may arrive as a bare object, and an entry without an
/// id is skipped instead of failing the whole response.
library;

import 'dart:convert';

import '../model/model.dart';
import 'subsonic_api.dart';

// ------------------------------------------------------------------ JSON reads

SubsonicJson? _obj(Object? v) => v is Map ? v.cast<String, Object?>() : null;

List<SubsonicJson> _objs(Object? v) => switch (v) {
  final List<Object?> list => [for (final e in list) ?_obj(e)],
  final Map<Object?, Object?> one => [one.cast<String, Object?>()],
  _ => const [],
};

String? _str(Object? v) => switch (v) {
  final String s => s,
  final num n => n.toString(),
  _ => null,
};

/// A non-blank string, else null.
String? _text(Object? v) {
  final s = _str(v);
  return s == null || s.trim().isEmpty ? null : s;
}

int? _int(Object? v) => switch (v) {
  final int i => i,
  final num n => n.toInt(),
  final String s => int.tryParse(s.trim()) ?? double.tryParse(s.trim())?.toInt(),
  _ => null,
};

double? _double(Object? v) => switch (v) {
  final num n => n.toDouble(),
  final String s => double.tryParse(s.trim()),
  _ => null,
};

int? _positive(Object? v) {
  final i = _int(v);
  return i != null && i > 0 ? i : null;
}

bool? _bool(Object? v) => switch (v) {
  final bool b => b,
  'true' => true,
  'false' => false,
  _ => null,
};

MediaId? _id(Object? v) {
  final s = _text(v);
  return s == null ? null : MediaId.subsonic(s);
}

/// Subsonic's "starred" is the timestamp the user starred it; present = starred.
bool _starred(Object? v) => _text(v) != null;

// --------------------------------------------------------------------- tracks

/// A `Child` / song entry → [Track]; null when it has no id.
Track? trackFromJson(SubsonicJson j) {
  final id = _id(j['id']);
  if (id == null) return null;
  final cover = _text(j['coverArt']);
  return Track(
    id: id,
    title: _str(j['title']),
    // OpenSubsonic displayArtist names every credited artist ("A & B").
    artist: _text(j['displayArtist']) ?? _str(j['artist']),
    artistId: _id(j['artistId']),
    album: _str(j['album']),
    albumId: _id(j['albumId']),
    coverArt: cover == null ? null : CoverSourceRelative(cover),
    durationSec: _int(j['duration']),
    trackNumber: _int(j['track']),
    discNumber: _positive(j['discNumber']),
    year: _int(j['year']),
    genre: _str(j['genre']),
    userRating: _int(j['userRating']),
    isStarred: _starred(j['starred']),
    starredAt: _text(j['starred']),
    // When the file came onto the server (Child.created, ISO-8601).
    addedAt: _text(j['created']),
    audioFormat: audioFormatFromJson(j),
    replayGain: replayGainFromJson(_obj(j['replayGain'])),
    artists: artistCreditsFromJson(j['artists']),
    // What the server says about the file, for About's receipt (Android EXTRA_SIZE … EXTRA_COMPOSER).
    sizeBytes: _positive(j['size']),
    playCount: _positive(j['playCount']),
    bpm: _positive(j['bpm']),
    composer: _text(j['displayComposer'])?.trim(),
    comment: _text(j['comment'])?.trim(),
    path: _text(j['path']),
  );
}

/// OpenSubsonic `artists` (each with its id) → the song's credits, carried
/// only when there is more than the one in `artist` (Android SubsonicMappers).
List<ArtistCredit> artistCreditsFromJson(Object? artists) {
  final credited = [
    for (final a in _objs(artists))
      if (_text(a['name']) case final name?) ArtistCredit(name.trim(), _id(a['id'])),
  ];
  return credited.length > 1 ? credited : const [];
}

/// OpenSubsonic `replayGain` (gains in dB, peaks linear, every field
/// optional) → [ReplayGain]; null when absent, empty or all "not tagged"
/// zeros (Android SubsonicMappers + ReplayGain.of).
ReplayGain? replayGainFromJson(SubsonicJson? j) {
  if (j == null) return null;
  return ReplayGain.of(
    trackGainDb: _double(j['trackGain']),
    albumGainDb: _double(j['albumGain']),
    trackPeak: _double(j['trackPeak']),
    albumPeak: _double(j['albumPeak']),
    baseGainDb: _double(j['baseGain']),
    fallbackGainDb: _double(j['fallbackGain']),
  );
}

const _mp4Suffixes = {'m4a', 'mp4', 'm4b'};

/// What the server says about the file's encoding; null when it says nothing
/// usable. Zero numbers mean "unknown" and are dropped. An MP4 with every
/// number 0 is one the server couldn't read (Navidrome and E-AC-3): it is
/// not AAC, so say nothing and let the player find out (Android
/// `subsonicAudioFormat`).
AudioFormat? audioFormatFromJson(SubsonicJson j) {
  final suffix = _text(j['suffix'])?.trim().toLowerCase();
  final contentType = _text(j['contentType'])?.trim();
  final bitRate = _positive(j['bitRate']);
  final sampleRate = _positive(j['samplingRate']);
  final bitDepth = _positive(j['bitDepth']);
  final channels = _positive(j['channelCount']);
  final noNumbers = bitRate == null && sampleRate == null && bitDepth == null && channels == null;
  if (suffix != null && _mp4Suffixes.contains(suffix) && noNumbers) return null;
  if (suffix == null && contentType == null && noNumbers) return null;
  return AudioFormat(
    suffix: suffix,
    contentType: contentType,
    bitRateKbps: bitRate,
    sampleRateHz: sampleRate,
    bitDepth: bitDepth,
    channels: channels,
  );
}

/// Tracks by (disc, track) when any disc is known, nulls last; otherwise the
/// server's order (Android `discTrackOrder`). Stable.
List<Track> discTrackOrder(List<Track> tracks) {
  if (tracks.every((t) => t.discNumber == null)) return tracks;
  int cmp(int? a, int? b) => a == null ? (b == null ? 0 : 1) : (b == null ? -1 : a.compareTo(b));
  final indexed = tracks.indexed.toList()
    ..sort((a, b) {
      final byDisc = cmp(a.$2.discNumber, b.$2.discNumber);
      if (byDisc != 0) return byDisc;
      final byTrack = cmp(a.$2.trackNumber, b.$2.trackNumber);
      return byTrack != 0 ? byTrack : a.$1.compareTo(b.$1);
    });
  return [for (final (_, t) in indexed) t];
}

// --------------------------------------------------------------------- albums

/// An `AlbumID3` → [Album]; null when it has no id. Songs (from getAlbum) in disc / track order.
Album? albumFromJson(SubsonicJson j) {
  final rawId = _text(j['id']);
  if (rawId == null) return null;
  return Album(
    id: MediaId.subsonic(rawId),
    name: _str(j['name']) ?? _str(j['title']) ?? '',
    artist: _str(j['artist']),
    artistId: _id(j['artistId']),
    coverArt: CoverSourceRelative(_text(j['coverArt']) ?? rawId),
    songCount: _int(j['songCount']),
    durationSec: _int(j['duration']),
    year: _int(j['year']),
    genre: _str(j['genre']),
    isStarred: _starred(j['starred']),
    starredAt: _text(j['starred']),
    tracks: discTrackOrder([for (final s in _objs(j['song'])) ?trackFromJson(s)]),
    // When the album came onto the server (AlbumID3.created).
    addedAt: _text(j['created']),
    releaseType: releaseTypeFrom(_objsOrStrings(j['releaseTypes'])),
    version: _text(j['version'])?.trim(),
    // OpenSubsonic: this release's date, else the first release's (a server whose files only carry
    // ORIGINALDATE), else the plain year (Android subsonicReleaseDate); each taken whole.
    releaseDate:
        isoItemDate(j['releaseDate']) ?? isoItemDate(j['originalReleaseDate']) ?? isoItemDate({'year': j['year']}),
    recordLabels: [
      ...{for (final l in _objs(j['recordLabels'])) ?_text(l['name'])?.trim()},
    ],
    originalReleaseDate: isoItemDate(j['originalReleaseDate']),
  );
}

/// An OpenSubsonic ItemDate (`{year, month, day}`, each optional) as ISO at its precision
/// ("2014-05-20" / "2014-05" / "2014"); null without a year (Android ReleaseDate.of + toIso).
String? isoItemDate(Object? v) {
  if (v is! Map) return null;
  final year = _int(v['year']);
  if (year == null || year <= 0) return null;
  final month = _int(v['month']);
  final day = _int(v['day']);
  final buffer = StringBuffer(year.toString().padLeft(4, '0'));
  if (month != null && month >= 1 && month <= 12) {
    buffer.write('-${month.toString().padLeft(2, '0')}');
    if (day != null && day >= 1 && day <= 31) buffer.write('-${day.toString().padLeft(2, '0')}');
  }
  return buffer.toString();
}

List<String> _objsOrStrings(Object? v) => switch (v) {
  final List<Object?> list => [for (final e in list) ?_str(e)],
  final String s => [s],
  _ => const [],
};

/// OpenSubsonic `releaseTypes` → [ReleaseType]; the first recognised value wins.
ReleaseType? releaseTypeFrom(List<String> releaseTypes) {
  for (final type in releaseTypes) {
    final match = switch (type.trim().toLowerCase()) {
      'album' => ReleaseType.album,
      'ep' => ReleaseType.ep,
      'single' => ReleaseType.single,
      'compilation' => ReleaseType.compilation,
      _ => null,
    };
    if (match != null) return match;
  }
  return null;
}

// -------------------------------------------------------------------- artists

Artist? artistFromJson(SubsonicJson j) {
  final id = _id(j['id']);
  if (id == null) return null;
  final cover = _text(j['coverArt']);
  return Artist(
    id: id,
    name: _str(j['name']) ?? '',
    albumCount: _int(j['albumCount']),
    coverArt: cover == null ? null : CoverSourceRelative(cover),
    isStarred: _starred(j['starred']),
  );
}

ArtistDetail? artistDetailFromJson(SubsonicJson j) {
  final artist = artistFromJson(j);
  if (artist == null) return null;
  return ArtistDetail(
    id: artist.id,
    name: artist.name,
    albumCount: artist.albumCount,
    coverArt: artist.coverArt,
    isStarred: artist.isStarred,
    albums: [for (final a in _objs(j['album'])) ?albumFromJson(a)],
  );
}

/// `getArtists` → its letter groups (`artists.index`).
List<ArtistIndex> artistIndexesFromJson(SubsonicJson? artists) => [
  for (final group in _objs(artists?['index']))
    ArtistIndex(name: _str(group['name']) ?? '', artists: [for (final a in _objs(group['artist'])) ?artistFromJson(a)]),
];

// ------------------------------------------------------------------ playlists

/// A playlist → [Playlist]. [currentUsername] decides `canWrite`: getPlaylists
/// lists other users' public playlists too; an explicit OpenSubsonic
/// `readonly` wins, and a legacy server that omits the owner is trusted.
Playlist? playlistFromJson(SubsonicJson j, {String? currentUsername}) {
  final rawId = _text(j['id']);
  if (rawId == null) return null;
  final owner = _text(j['owner']);
  return Playlist(
    id: MediaId.subsonic(rawId),
    name: _str(j['name']) ?? '',
    owner: owner,
    coverArt: CoverSourceRelative(_text(j['coverArt']) ?? rawId),
    songCount: _int(j['songCount']),
    durationSec: _int(j['duration']),
    tracks: [for (final s in _objs(j['entry'])) ?trackFromJson(s)],
    canWrite: _bool(j['readonly']) != true && (owner == null || owner == currentUsername),
    comment: _text(j['comment']),
    ownedByMe: owner == null || currentUsername == null ? null : owner.toLowerCase() == currentUsername.toLowerCase(),
    libraryAddedAt: _text(j['created']),
  );
}

/// `createShare`'s answer → the new share's URL; null when it holds none.
String? shareUrlFromJson(SubsonicJson body) {
  final shares = _obj(body['shares']);
  for (final share in _objs(shares?['share'])) {
    final url = _text(share['url']);
    if (url != null) return url;
  }
  return null;
}

// ------------------------------------------------------- search and starred

SearchResults searchResultsFromJson(SubsonicJson? j) => SearchResults(
  tracks: [for (final s in _objs(j?['song'])) ?trackFromJson(s)],
  albums: [for (final a in _objs(j?['album'])) ?albumFromJson(a)],
  artists: [for (final a in _objs(j?['artist'])) ?artistFromJson(a)],
);

Starred starredFromJson(SubsonicJson? j) => Starred(
  tracks: [for (final s in _objs(j?['song'])) ?trackFromJson(s)],
  albums: [for (final a in _objs(j?['album'])) ?albumFromJson(a)],
  artists: [for (final a in _objs(j?['artist'])) ?artistFromJson(a)],
);

List<Track> tracksFromJson(Object? songs) => [for (final s in _objs(songs)) ?trackFromJson(s)];
List<Album> albumsFromJson(Object? albums) => [for (final a in _objs(albums)) ?albumFromJson(a)];

// --------------------------------------------------------------------- lyrics

/// OpenSubsonic `lyricsList` → [Lyrics]. Android's rule: the first entry;
/// synced when it says so and at least one line has a start, lines sorted by
/// time (servers send them out of order). The entry's `offset` (ms, positive
/// = show sooner, as iOS applies it) shifts every line, clamped at 0. Null
/// when there is no entry or it has no lines.
Lyrics? lyricsFromStructured(SubsonicJson? lyricsList) {
  final entries = _objs(lyricsList?['structuredLyrics']);
  if (entries.isEmpty) return null;
  final first = entries.first;
  final lines = _objs(first['line']);
  if (lines.isEmpty) return null;
  final language = _text(first['lang']);
  if (_bool(first['synced']) == true && lines.any((l) => _int(l['start']) != null)) {
    final offset = _int(first['offset']) ?? 0;
    final timed = [for (final l in lines) LyricLine(_max0((_int(l['start']) ?? 0) - offset), _str(l['value']) ?? '')]
      ..sort((a, b) => a.startMs.compareTo(b.startMs));
    return SyncedLyrics(timed, language: language);
  }
  return UnsyncedLyrics(lines.map((l) => _str(l['value']) ?? '').join('\n'), language: language);
}

/// Classic `getLyrics` → [Lyrics]: the text may itself be LRC, then it's
/// synced. Null when the server sent no text.
Lyrics? lyricsFromPlain(SubsonicJson? lyrics) {
  final raw = _text(lyrics?['value']);
  if (raw == null) return null;
  return parseLrc(raw);
}

int _max0(int v) => v < 0 ? 0 : v;

final _lrcTime = RegExp(r'\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]');
final _lrcTag = RegExp(r'^\[[A-Za-z]+:.*\]$');

/// Plain text or LRC: timed lines (`[mm:ss.xx]`, several stamps per line
/// allowed) make [SyncedLyrics]; metadata tags (`[ar:…]`) are dropped.
/// Without a single stamp the text comes back as [UnsyncedLyrics].
Lyrics parseLrc(String text) {
  final lines = <LyricLine>[];
  for (final raw in const LineSplitter().convert(text)) {
    final stamps = _lrcTime.allMatches(raw).toList();
    if (stamps.isEmpty || stamps.first.start != 0) continue;
    // Stamps lead the line; the lyric is what follows the last leading one.
    var end = 0;
    final times = <int>[];
    for (final m in stamps) {
      if (m.start != end) break;
      end = m.end;
      final fraction = m[3] ?? '0';
      final ms = switch (fraction.length) {
        1 => int.parse(fraction) * 100,
        2 => int.parse(fraction) * 10,
        _ => int.parse(fraction),
      };
      times.add((int.parse(m[1]!) * 60 + int.parse(m[2]!)) * 1000 + ms);
    }
    final lyric = raw.substring(end).trim();
    for (final t in times) {
      lines.add(LyricLine(t, lyric));
    }
  }
  if (lines.isEmpty) {
    final body = const LineSplitter().convert(text).where((l) => !_lrcTag.hasMatch(l.trim())).join('\n').trim();
    return UnsyncedLyrics(body);
  }
  lines.sort((a, b) => a.startMs.compareTo(b.startMs));
  return SyncedLyrics(lines);
}
