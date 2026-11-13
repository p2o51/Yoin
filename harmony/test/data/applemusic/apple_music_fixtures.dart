/// Apple Music API documents shaped like Apple's (trimmed), for the source tests.
/// Ids follow Apple's forms: catalogue digits, library `l.` / `i.` / `r.` / `p.`, `pl.` playlists.
library;

import 'dart:convert';

/// A developer token of the right shape (ES256 header, `exp`), unsigned. Not a real token.
String fakeDeveloperToken({required int exp}) {
  String part(Map<String, Object?> json) => base64Url.encode(utf8.encode(jsonEncode(json))).replaceAll('=', '');
  return '${part({'alg': 'ES256', 'kid': 'TEST', 'typ': 'JWT'})}.${part({'iss': 'TEST', 'exp': exp})}.c2ln';
}

String doc(Map<String, Object?> json) => jsonEncode(json);

/// The `data` resources of a fixture document.
List<Map<String, Object?>> resourcesOf(String document) => [
  for (final r in (jsonDecode(document) as Map)['data'] as List) (r as Map).cast<String, Object?>(),
];

Map<String, Object?> artwork(String name) => {'url': 'https://is1.example.com/$name/{w}x{h}bb.jpg', 'width': 3000, 'height': 3000};

final storefront = doc({
  'data': [
    {
      'id': 'jp',
      'type': 'storefronts',
      'attributes': {
        'defaultLanguageTag': 'ja',
        'supportedLanguageTags': ['ja', 'en-US'],
      },
    },
  ],
});

Map<String, Object?> catalogSong(String id, {String album = '1500', String artist = '900', int track = 1, bool playable = true}) => {
  'id': id,
  'type': 'songs',
  'attributes': {
    'name': 'Song $id',
    'artistName': 'Artist',
    'albumName': 'Album',
    'durationInMillis': 200500,
    'trackNumber': track,
    'discNumber': 1,
    'genreNames': ['Pop', 'Music'],
    'isrc': 'usabc2400001',
    'artwork': artwork('song$id'),
    'url': 'https://music.apple.com/jp/song/$id',
    if (playable) 'playParams': {'id': id, 'kind': 'song'},
  },
  'relationships': {
    'albums': {
      'data': [
        {'id': album, 'type': 'albums'},
      ],
    },
    'artists': {
      'data': [
        {'id': artist, 'type': 'artists'},
      ],
    },
  },
};

/// A library song; [catalog] null leaves the relationship out, [] answers it empty.
Map<String, Object?> librarySong(String id, {List<Map<String, Object?>>? catalog, String? playParamsCatalogId}) => {
  'id': id,
  'type': 'library-songs',
  'attributes': {
    'name': 'Library $id',
    'artistName': 'Artist',
    'albumName': 'Album',
    'durationInMillis': 180000,
    'trackNumber': 2,
    'artwork': artwork('lib$id'),
    'playParams': {'id': id, 'kind': 'song', 'isLibrary': true, 'catalogId': ?playParamsCatalogId},
  },
  if (catalog != null) 'relationships': {'catalog': {'data': catalog}},
};

final catalogAlbum1500 = doc({
  'data': [
    {
      'id': '1500',
      'type': 'albums',
      'attributes': {
        'name': 'Album',
        'artistName': 'Artist',
        'trackCount': 2,
        'releaseDate': '2024-05-01',
        'genreNames': ['Pop'],
        'artwork': artwork('album1500'),
      },
      'relationships': {
        'artists': {
          'data': [
            {'id': '900', 'type': 'artists'},
          ],
        },
        'tracks': {
          'data': [catalogSong('11', track: 1), catalogSong('12', track: 2)],
        },
      },
    },
  ],
});

/// Library album l.AAA whose catalogue album is 1500.
final libraryAlbumMatched = doc({
  'data': [
    {
      'id': 'l.AAA',
      'type': 'library-albums',
      'attributes': {'name': 'Album', 'artistName': 'Artist', 'trackCount': 1, 'dateAdded': '2026-01-02T00:00:00Z', 'artwork': artwork('libalbum')},
      'relationships': {
        'catalog': {
          'data': [
            {'id': '1500', 'type': 'albums'},
          ],
        },
        'artists': {
          'data': [
            {'id': 'r.ART', 'type': 'library-artists'},
          ],
        },
      },
    },
  ],
});

/// Library album l.IMP with no catalogue match (an import).
final libraryAlbumImport = doc({
  'data': [
    {
      'id': 'l.IMP',
      'type': 'library-albums',
      'attributes': {'name': 'Home Recordings', 'artistName': 'Me', 'trackCount': 1},
      'relationships': {
        'catalog': {'data': <Object?>[]},
        'artists': {
          'data': [
            {'id': 'r.ME', 'type': 'library-artists'},
          ],
        },
      },
    },
  ],
});

final libraryImportTracks = doc({
  'data': [
    librarySong('i.IMP1', catalog: const []),
  ],
});

final recentlyAdded = doc({
  'data': [
    {
      'id': 'l.AAA',
      'type': 'library-albums',
      'attributes': {'name': 'Album', 'artistName': 'Artist'},
    },
    {
      'id': 'p.PL1',
      'type': 'library-playlists',
      'attributes': {'name': 'Mix'},
    },
    {
      'id': 'l.BBB',
      'type': 'library-albums',
      'attributes': {'name': 'Second', 'artistName': 'Other'},
    },
  ],
});

final recentPlayed = doc({
  'data': [
    {
      'id': 'l.AAA',
      'type': 'library-albums',
      'attributes': {'name': 'Album'},
      'relationships': {
        'catalog': {
          'data': [
            {'id': '1500', 'type': 'albums'},
          ],
        },
      },
    },
    {
      'id': 'ra.STATION',
      'type': 'stations',
      'attributes': {'name': 'Radio'},
    },
    {
      'id': '1500',
      'type': 'albums',
      'attributes': {'name': 'Album'},
    },
  ],
});

final libraryArtistsPage1 = doc({
  'next': '/v1/me/library/artists?offset=2',
  'data': [
    {
      'id': 'r.B',
      'type': 'library-artists',
      'attributes': {'name': 'beta'},
      'relationships': {
        'catalog': {
          'data': [
            {
              'id': '901',
              'type': 'artists',
              'attributes': {'name': 'beta', 'artwork': artwork('artist901')},
            },
          ],
        },
      },
    },
    {
      'id': 'r.A',
      'type': 'library-artists',
      'attributes': {'name': 'Alpha'},
    },
  ],
});

final libraryArtistsPage2 = doc({
  'data': [
    {
      'id': 'r.A2',
      'type': 'library-artists',
      'attributes': {'name': 'Aria'},
    },
  ],
});

final libraryPlaylists = doc({
  'data': [
    {
      'id': 'p.MINE',
      'type': 'library-playlists',
      'attributes': {
        'name': 'Road Trip',
        'canEdit': true,
        'artwork': artwork('pl'),
        'playParams': {'id': 'p.MINE', 'kind': 'playlist', 'isLibrary': true, 'globalId': 'pl.u-PUBLISHED'},
      },
    },
    {
      'id': 'p.LOCAL',
      'type': 'library-playlists',
      'attributes': {'name': 'Local only'},
    },
  ],
});

final catalogPlaylistPublished = doc({
  'data': [
    {
      'id': 'pl.u-PUBLISHED',
      'type': 'playlists',
      'attributes': {
        'name': 'Road Trip (catalog)',
        'curatorName': 'Someone',
        'description': {'standard': 'Songs for driving'},
      },
    },
  ],
});

final catalogPlaylistTracks = doc({
  'data': [
    catalogSong('11'),
    {
      'id': '77',
      'type': 'music-videos',
      'attributes': {'name': 'A video'},
    },
    catalogSong('12', track: 2),
  ],
});

final search = doc({
  'results': {
    'songs': {
      'href': '/v1/catalog/jp/search?term=x&types=songs',
      'data': [catalogSong('11'), catalogSong('12'), catalogSong('13', playable: false)],
    },
    'albums': {
      'data': [
        {
          'id': '1500',
          'type': 'albums',
          'attributes': {'name': 'Album', 'artistName': 'Artist', 'isSingle': true},
        },
      ],
    },
    'artists': {
      'data': [
        {
          'id': '900',
          'type': 'artists',
          'attributes': {'name': 'Artist', 'artwork': artwork('artist900')},
        },
      ],
    },
    'playlists': {
      'data': [
        {
          'id': 'pl.123',
          'type': 'playlists',
          'attributes': {'name': 'Hits', 'curatorName': 'Apple Music'},
        },
      ],
    },
  },
});

final catalogArtist = doc({
  'data': [
    {
      'id': '900',
      'type': 'artists',
      'attributes': {'name': 'Artist', 'artwork': artwork('artist900')},
    },
  ],
});

final catalogArtistAlbums = doc({
  'data': [
    {
      'id': '1500',
      'type': 'albums',
      'attributes': {'name': 'Album', 'releaseDate': '2024-05-01'},
    },
    {
      'id': '1600',
      'type': 'albums',
      'attributes': {'name': 'Another', 'releaseDate': '2020'},
    },
  ],
});

final appleNotFound = doc({
  'errors': [
    {'id': 'X', 'title': 'Resource Not Found', 'status': '404', 'code': '40400'},
  ],
});
