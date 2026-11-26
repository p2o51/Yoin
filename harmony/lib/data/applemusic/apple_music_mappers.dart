/// Apple Music API resources → neutral models (Android AppleMusicSong.kt and
/// the mapping half of AppleMusicSource.kt).
///
/// Identity model, as on Android:
/// - A song is its catalog id (digits) whenever Apple knows one: a library
///   song (`i.…`) folds onto the catalog song it came from, so the same song
///   is one row everywhere and plays through MusicKit by that id. A song with
///   no catalog counterpart (an import) stays `library:<id>`.
/// - Albums, artists and playlists keep `library:<id>` for library
///   resources and the bare catalog id otherwise; a library album that lists
///   its catalog album folds onto it (it opens as the full catalog album).
/// - Artwork templates are sized to 600 px, like Android.
library;

import '../model/model.dart';
import '../source/music_source.dart' show MadeForYouPlaylist;
import 'apple_music_api.dart';

/// What [Track] can't carry about an Apple Music song (Android Track.extras).
final class AppleMusicSongFacts {
  const AppleMusicSongFacts({this.libraryId, this.catalogId, this.catalogUnavailable = false, this.isrc});

  /// The user's library song (`i.…`), when this row is or names one.
  final String? libraryId;

  /// The catalog song id (digits) MusicKit plays, when there is one.
  final String? catalogId;

  /// Apple names a catalog id but can't stream it here (pulled, another
  /// storefront's, or not released yet). MusicKit would drop such an id from a
  /// queue without a word and start that many songs further on.
  final bool catalogUnavailable;

  final String? isrc;
}

/// A mapped song: the neutral row and its Apple-only facts.
typedef AppleMusicSong = ({Track track, AppleMusicSongFacts facts});

const _artworkSize = '600';
const songTypes = {'songs', 'library-songs'};
const _libraryPrefix = 'library:';

// ------------------------------------------------------------------ JSON reads

AppleMusicJson? obj(Object? v) => v is Map ? v.cast<String, Object?>() : null;

List<AppleMusicJson> objs(Object? v) => [
  if (v is List)
    for (final e in v) ?obj(e),
];

String? text(AppleMusicJson? o, String key) {
  final v = o?[key];
  return v is String && v.trim().isNotEmpty ? v : null;
}

int? _int(Object? v) => switch (v) {
  final int i => i,
  final num n => n.toInt(),
  final String s => int.tryParse(s),
  _ => null,
};

/// A response's (or relationship's) `data` resources.
List<AppleMusicJson> resources(AppleMusicJson? document) => objs(document?['data']);

AppleMusicJson attributesOf(AppleMusicJson resource) => obj(resource['attributes']) ?? const {};

/// The resources of relationship [name], empty when absent.
List<AppleMusicJson> related(AppleMusicJson resource, String name) =>
    resources(obj(obj(resource['relationships'])?[name]));

/// Whether the resource carries relationship [name]'s answer, even an empty one.
bool answers(AppleMusicJson resource, String name) => obj(obj(resource['relationships'])?[name])?['data'] is List;

bool _isLibraryType(String? type) => type != null && type.startsWith('library-');

/// `library:<id>` for a library resource, the bare id for a catalog one.
MediaId? mediaIdOf(AppleMusicJson resource) {
  final id = text(resource, 'id');
  if (id == null) return null;
  return MediaId(MediaId.providerAppleMusic, _isLibraryType(text(resource, 'type')) ? '$_libraryPrefix$id' : id);
}

/// True for a `library:` id.
bool isLibraryId(MediaId id) => id.rawId.startsWith(_libraryPrefix);

/// The Apple id without the `library:` namespace.
String appleId(MediaId id) => isLibraryId(id) ? id.rawId.substring(_libraryPrefix.length) : id.rawId;

CoverRef? coverOf(AppleMusicJson attributes) {
  final url = text(obj(attributes['artwork']), 'url');
  if (url == null) return null;
  return CoverUrl(url.replaceAll('{w}', _artworkSize).replaceAll('{h}', _artworkSize));
}

MediaId? _relatedId(AppleMusicJson resource, String relation) {
  final first = related(resource, relation).firstOrNull;
  return first == null ? null : mediaIdOf(first);
}

/// A catalog song Apple lists but won't stream yet: full attributes with a
/// `url` and no `playParams`. A resource without attributes says nothing.
bool _isUnreleased(AppleMusicJson song) {
  final attributes = obj(song['attributes']);
  if (attributes == null) return false;
  return text(attributes, 'url') != null && attributes['playParams'] == null;
}

// ------------------------------------------------------------------ songs

/// A `songs` / `library-songs` resource, or null for anything else (music
/// videos, entries without an id).
AppleMusicSong? songFrom(AppleMusicJson resource) {
  final id = text(resource, 'id');
  final type = text(resource, 'type');
  if (id == null || !songTypes.contains(type)) return null;
  final library = type == 'library-songs';
  final attributes = obj(resource['attributes']);
  final catalogAnswer = obj(obj(resource['relationships'])?['catalog'])?['data'];
  final catalog = objs(catalogAnswer).firstOrNull;
  final catalogId = library
      ? text(catalog, 'id') ?? text(obj(attributes?['playParams']), 'catalogId')
      : id;
  final libraryCopy = related(resource, 'library').where((r) => text(r, 'type') == 'library-songs').firstOrNull;
  final libraryId = library ? id : text(libraryCopy, 'id');
  final catalogAttributes = obj(catalog?['attributes']);
  // An import answers `catalog` empty as well, but names no catalog id: it stays an import.
  final pulled = library && catalogAnswer is List && catalogAnswer.isEmpty && catalogId != null;
  final playable = library ? catalog : resource;
  final catalogUnavailable = pulled || (playable != null && _isUnreleased(playable));
  final genres = attributes?['genreNames'] ?? catalogAttributes?['genreNames'];
  final isrc = (text(attributes, 'isrc') ?? text(catalogAttributes, 'isrc'))?.trim().toUpperCase();

  final track = Track(
    id: MediaId(MediaId.providerAppleMusic, catalogId ?? '$_libraryPrefix$id'),
    title: text(attributes, 'name'),
    artist: text(attributes, 'artistName'),
    artistId: _relatedId(resource, 'artists') ?? (catalog == null ? null : _relatedId(catalog, 'artists')),
    album: text(attributes, 'albumName'),
    albumId: _relatedId(resource, 'albums') ?? (catalog == null ? null : _relatedId(catalog, 'albums')),
    coverArt: attributes == null ? null : coverOf(attributes),
    durationSec: switch (_int(attributes?['durationInMillis'])) {
      final ms? => ms ~/ 1000,
      null => null,
    },
    trackNumber: _int(attributes?['trackNumber']),
    discNumber: _int(attributes?['discNumber']),
    genre: genres is List ? genres.whereType<String>().where((g) => g.trim().isNotEmpty).firstOrNull : null,
    // The catalogue's composer credit (About's composer when the lyrics name none).
    composer: (text(attributes, 'composerName') ?? text(catalogAttributes, 'composerName'))?.trim(),
    // Library membership is deliberately not a favourite (Android).
  );
  return (
    track: track,
    facts: AppleMusicSongFacts(
      libraryId: libraryId,
      catalogId: catalogId,
      catalogUnavailable: catalogUnavailable,
      isrc: isrc == null || isrc.isEmpty ? null : isrc,
    ),
  );
}

/// Every song among [entries], in order. A playlist or search result keeps a
/// song it lists twice, as Android does; an album tracklist passes [dedupe]
/// so two library copies of one catalog song show once (the first wins),
/// matching Android's album `distinctBy { it.id }`.
List<AppleMusicSong> songsFrom(Iterable<AppleMusicJson> entries, {bool dedupe = false}) {
  final seen = <MediaId>{};
  final songs = <AppleMusicSong>[];
  for (final e in entries) {
    final song = songFrom(e);
    if (song == null) continue;
    if (dedupe && !seen.add(song.track.id)) continue;
    songs.add(song);
  }
  return songs;
}

// ------------------------------------------------------------------ albums, artists, playlists

/// An album resource. A library album that lists its catalog album takes the
/// catalog id (the id it opens as and is remembered under).
Album? albumFrom(AppleMusicJson resource) {
  final ownId = mediaIdOf(resource);
  if (ownId == null) return null;
  final a = attributesOf(resource);
  final catalog = related(resource, 'catalog').where((r) => text(r, 'type') == 'albums').firstOrNull;
  final genres = a['genreNames'];
  return Album(
    id: (catalog == null ? null : mediaIdOf(catalog)) ?? ownId,
    name: text(a, 'name') ?? '',
    artist: text(a, 'artistName'),
    artistId: _relatedId(resource, 'artists'),
    coverArt: coverOf(a),
    songCount: _int(a['trackCount']),
    year: switch (text(a, 'releaseDate')) {
      final d? when d.length >= 4 => int.tryParse(d.substring(0, 4)),
      _ => null,
    },
    genre: genres is List ? genres.whereType<String>().firstOrNull : null,
    addedAt: text(a, 'dateAdded'),
    releaseType: switch ((a['isSingle'], a['isCompilation'])) {
      (true, _) => ReleaseType.single,
      (_, true) => ReleaseType.compilation,
      _ => null,
    },
    // "2014-05-20" / "2014" as Apple gives it: the ISO date the credits line reads.
    releaseDate: switch (text(a, 'releaseDate')?.trim()) {
      final d? when RegExp(r'^\d{4}(-\d{2}(-\d{2})?)?$').hasMatch(d) => d,
      _ => null,
    },
    // The scrapbook's credits line names the label before asking any AI (Android AppleMusicSource).
    recordLabels: [
      ?(text(a, 'recordLabel') ?? text(catalog == null ? null : attributesOf(catalog), 'recordLabel'))?.trim(),
    ],
  );
}

/// [album] with [tracks] and the count and length they add up to.
Album albumWithTracks(Album album, List<Track> tracks) => Album(
  id: album.id,
  name: album.name,
  artist: album.artist,
  artistId: album.artistId,
  coverArt: album.coverArt,
  songCount: tracks.length,
  durationSec: tracks.fold<int>(0, (sum, t) => sum + (t.durationSec ?? 0)),
  year: album.year,
  genre: album.genre,
  isStarred: album.isStarred,
  tracks: tracks,
  starredAt: album.starredAt,
  addedAt: album.addedAt,
  releaseType: album.releaseType,
  version: album.version,
  releaseDate: album.releaseDate,
  recordLabels: album.recordLabels,
  originalReleaseDate: album.originalReleaseDate,
);

/// An artist. A library artist keeps its library id but has no artwork: its
/// portrait is its catalog artist's, when the request included `catalog`.
Artist? artistFrom(AppleMusicJson resource) {
  final id = mediaIdOf(resource);
  if (id == null) return null;
  final a = attributesOf(resource);
  final catalog = related(resource, 'catalog').where((r) => text(r, 'type') == 'artists').firstOrNull;
  return Artist(
    id: id,
    name: text(a, 'name') ?? '',
    coverArt: coverOf(a) ?? (catalog == null ? null : coverOf(attributesOf(catalog))),
  );
}

/// A playlist. Apple names no owner beyond the curator; Yoin never edits one
/// (Apple's API has no rename, delete or remove), so [Playlist.canWrite] is false.
Playlist? playlistFrom(AppleMusicJson resource) {
  final id = mediaIdOf(resource);
  if (id == null) return null;
  final a = attributesOf(resource);
  return Playlist(
    id: id,
    name: text(a, 'name') ?? '',
    owner: text(a, 'curatorName'),
    coverArt: coverOf(a),
    comment: text(obj(a['description']), 'standard'),
    // Apple names no owner: a library playlist the user can edit is theirs (Android).
    ownedByMe: isLibraryId(id) && a['canEdit'] is bool ? a['canEdit'] as bool : null,
    libraryAddedAt: text(a, 'dateAdded'),
  );
}

/// Apple Music's personal mixes are catalog playlists with this id prefix (`pl.pm-…`).
const personalMixPrefix = 'pl.pm-';

/// The personal mixes among the recommendation [groups], once each, in
/// Apple's order (Android AppleMusicSource.madeForYouMixes).
List<MadeForYouPlaylist> madeForYouMixes(List<AppleMusicJson> groups) {
  final seen = <String>{};
  final mixes = <MadeForYouPlaylist>[];
  for (final group in groups) {
    for (final r in related(group, 'contents')) {
      if (text(r, 'type') != 'playlists' || !(text(r, 'id')?.startsWith(personalMixPrefix) ?? false)) continue;
      final mix = madeForYouMix(r);
      if (mix != null && seen.add(mix.id.rawId)) mixes.add(mix);
    }
  }
  return mixes;
}

/// One personal mix; a blank title when Apple listed it without attributes.
MadeForYouPlaylist? madeForYouMix(AppleMusicJson resource) {
  final id = text(resource, 'id');
  if (id == null || id.isEmpty) return null;
  final a = attributesOf(resource);
  final description = obj(a['description']);
  return MadeForYouPlaylist(
    id: MediaId(MediaId.providerAppleMusic, id),
    title: text(a, 'name') ?? '',
    subtitle: text(description, 'short') ?? text(description, 'standard') ?? text(a, 'curatorName') ?? '',
    cover: coverOf(a),
  );
}

/// [playlist] with [tracks] and the count and length they add up to.
Playlist playlistWithTracks(Playlist playlist, List<Track> tracks) => Playlist(
  id: playlist.id,
  name: playlist.name,
  owner: playlist.owner,
  coverArt: playlist.coverArt,
  songCount: tracks.length,
  durationSec: tracks.fold<int>(0, (sum, t) => sum + (t.durationSec ?? 0)),
  tracks: tracks,
  canWrite: playlist.canWrite,
  comment: playlist.comment,
  ownedByMe: playlist.ownedByMe,
  libraryAddedAt: playlist.libraryAddedAt,
);

/// A library playlist's catalog playlist (`playParams.globalId`, `pl.…`), if any.
String? catalogPlaylistIdOf(AppleMusicJson resource) {
  if (text(resource, 'type') != 'library-playlists') return null;
  final globalId = text(obj(attributesOf(resource)['playParams']), 'globalId');
  return globalId != null && globalId.startsWith('pl.') ? globalId : null;
}

/// The album's whole tracklist as its own response embeds it, or null when it
/// must be read on its own: a `next` page, missing or empty, or a song
/// lacking its attributes or the `albums` / `artists` relationships.
List<AppleMusicJson>? embeddedTracks(AppleMusicJson album) {
  final tracks = obj(obj(album['relationships'])?['tracks']);
  if (tracks == null || text(tracks, 'next') != null) return null;
  final data = tracks['data'];
  if (data is! List || data.isEmpty || data.any((e) => e is! Map)) return null;
  final entries = objs(data);
  final complete = entries
      .where((s) => text(s, 'type') == 'songs')
      .every((s) => s['attributes'] is Map && answers(s, 'albums') && answers(s, 'artists'));
  return complete ? entries : null;
}
