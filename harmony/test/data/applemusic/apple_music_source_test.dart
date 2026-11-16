import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';

import 'apple_music_fixtures.dart' as fx;

const _tokenUrl = 'https://token.example.com/token';
const _userToken = 'test-music-user-token';
const _creds = AppleMusicCredentials(endpoint: _tokenUrl, musicUserToken: _userToken);

final _devToken = fx.fakeDeveloperToken(exp: DateTime.now().millisecondsSinceEpoch ~/ 1000 + 3600);

typedef _Route = Object; // String body | http.Response | http.Response Function(http.Request)

/// A fake token service + Apple Music API: path → route; records every request.
final class _Server {
  _Server(this.routes);
  final Map<String, _Route> routes;
  final requests = <http.Request>[];

  List<String> get apiPaths => [
    for (final r in requests)
      if (r.url.host == 'api.music.apple.com') r.url.path,
  ];

  http.Request lastTo(String path) => requests.lastWhere((r) => r.url.path == path);

  MockClient get client => MockClient((request) async {
    requests.add(request);
    if (request.url.toString() == _tokenUrl) return _json('{"developerToken":"$_devToken"}');
    final route = routes[request.url.path];
    return switch (route) {
      final String body => _json(body),
      final http.Response r => r,
      final http.Response Function(http.Request) f => f(request),
      _ => http.Response(fx.appleNotFound, 404),
    };
  });

  AppleMusicSource source() => AppleMusicSource(_creds, client: client);
}

http.Response _json(String body, [int status = 200]) =>
    http.Response(body, status, headers: {'content-type': 'application/json; charset=utf-8'});

Map<String, _Route> _withStorefront(Map<String, _Route> routes) => {'/v1/me/storefront': fx.storefront, ...routes};

void main() {
  group('transport', () {
    test('should_sendMusicUserTokenOnlyOnPersonalRequests_when_readingCatalogAndLibrary', () async {
      final server = _Server(_withStorefront({'/v1/catalog/jp/search': fx.search}));
      final source = server.source();

      await source.library.search('x');

      final personal = server.lastTo('/v1/me/storefront');
      final catalog = server.lastTo('/v1/catalog/jp/search');
      expect(personal.headers['Music-User-Token'], _userToken);
      expect(catalog.headers.containsKey('Music-User-Token'), isFalse);
      expect(catalog.headers['Authorization'], 'Bearer $_devToken');
      expect(personal.headers['Authorization'], 'Bearer $_devToken');
    });

    test('should_fetchDeveloperTokenOnce_when_manyRequestsRun', () async {
      final server = _Server(_withStorefront({'/v1/catalog/jp/search': fx.search}));
      final source = server.source();

      await Future.wait([source.library.search('a'), source.library.search('b'), source.library.ping()]);

      expect(server.requests.where((r) => r.url.toString() == _tokenUrl), hasLength(1));
      expect(server.apiPaths.where((p) => p == '/v1/me/storefront'), hasLength(1), reason: 'storefront is single-flight');
    });

    test('should_throwAuthException_when_appleAnswers401', () async {
      final server = _Server({'/v1/me/storefront': http.Response('', 401)});
      await expectLater(server.source().library.ping(), throwsA(isA<SourceAuthException>()));
    });

    test('should_throwServerExceptionWith403_when_appleRefusesAccess', () async {
      final server = _Server({
        '/v1/me/storefront': http.Response('{"errors":[{"code":"40300","title":"Forbidden"}]}', 403),
      });
      await expectLater(
        server.source().library.ping(),
        throwsA(isA<SourceServerException>().having((e) => e.code, 'code', 403)),
      );
    });

    test('should_throwNetworkException_when_connectionFails', () async {
      final server = _Server({'/v1/me/storefront': (http.Request _) => throw const SocketException('down')});
      await expectLater(server.source().library.ping(), throwsA(isA<SourceNetworkException>()));
    });

    test('should_readStorefrontAgain_when_firstReadFailed', () async {
      var calls = 0;
      final server = _Server({
        '/v1/me/storefront': (http.Request _) => ++calls == 1 ? http.Response('', 500) : _json(fx.storefront),
      });
      final source = server.source();

      await expectLater(source.library.ping(), throwsA(isA<SourceServerException>()));
      expect(await source.library.ping(), isTrue);
    });

    test('should_followNextLinkAndKeepQuery_when_listingArtists', () async {
      final server = _Server({
        '/v1/me/library/artists': (http.Request r) =>
            _json(r.url.queryParameters['offset'] == '2' ? fx.libraryArtistsPage2 : fx.libraryArtistsPage1),
      });

      await server.source().library.getArtists();

      final pages = server.requests.where((r) => r.url.path == '/v1/me/library/artists').toList();
      expect(pages, hasLength(2));
      expect(pages[1].url.queryParameters, {'offset': '2', 'limit': '100', 'include': 'catalog'});
    });

    test('should_rejectPaginationLink_when_itLeavesTheCollection', () async {
      final server = _Server({
        '/v1/me/library/playlists': _json('{"next":"https://evil.example.com/v1/me/library/playlists?offset=1","data":[]}'),
      });
      await expectLater(server.source().library.getPlaylists(), throwsA(isA<SourceServerException>()));
      expect(server.requests.where((r) => r.url.host == 'evil.example.com'), isEmpty);
    });

    test('should_dropIncludeOnLaterPages_when_appleRefusesIt', () async {
      final server = _Server({
        '/v1/me/library/artists': (http.Request r) {
          if (r.url.queryParameters.containsKey('include')) return http.Response('', 400);
          return _json(r.url.queryParameters['offset'] == '2' ? fx.libraryArtistsPage2 : fx.libraryArtistsPage1);
        },
      });

      final index = await server.source().library.getArtists();

      expect(index.expand((g) => g.artists), hasLength(3));
      final last = server.requests.last.url;
      expect(last.queryParameters.containsKey('include'), isFalse);
      expect(last.queryParameters['limit'], '100');
    });
  });

  group('albums', () {
    test('should_openFullCatalogAlbum_when_libraryAlbumHasCatalogMatch', () async {
      final server = _Server(_withStorefront({
        '/v1/me/library/albums/l.AAA': fx.libraryAlbumMatched,
        '/v1/catalog/jp/albums/1500': fx.catalogAlbum1500,
      }));

      final album = await server.source().library.getAlbum(MediaId(MediaId.providerAppleMusic, 'library:l.AAA'));

      expect(album!.id, MediaId(MediaId.providerAppleMusic, '1500'));
      expect(album.tracks.map((t) => t.id.rawId), ['11', '12']);
      expect(album.tracks.first.albumId, MediaId(MediaId.providerAppleMusic, '1500'));
      expect(album.tracks.first.artistId, MediaId(MediaId.providerAppleMusic, '900'));
      expect(album.songCount, 2);
      expect(album.durationSec, 400);
      expect(album.year, 2024);
      expect(server.apiPaths, isNot(contains('/v1/catalog/jp/albums/1500/tracks')), reason: 'embedded tracklist');
      expect(server.lastTo('/v1/catalog/jp/albums/1500').url.queryParameters['include[songs]'], 'albums,artists');
    });

    test('should_keepLibraryTracklist_when_libraryAlbumIsAnImport', () async {
      final server = _Server(_withStorefront({
        '/v1/me/library/albums/l.IMP': fx.libraryAlbumImport,
        '/v1/me/library/albums/l.IMP/tracks': fx.libraryImportTracks,
      }));
      final source = server.source();
      final id = MediaId(MediaId.providerAppleMusic, 'library:l.IMP');

      final album = await source.library.getAlbum(id);

      expect(album!.id, id);
      final track = album.tracks.single;
      expect(track.id.rawId, 'library:i.IMP1');
      expect(track.albumId, id);
      expect(track.artistId, MediaId(MediaId.providerAppleMusic, 'library:r.ME'));
      expect(source.factsFor(track.id)!.libraryId, 'i.IMP1');
    });

    test('should_readTracklistSeparately_when_appleRefusesSongIncludes', () async {
      final server = _Server(_withStorefront({
        '/v1/catalog/jp/albums/1500': (http.Request r) =>
            r.url.queryParameters.containsKey('include[songs]') ? http.Response('', 400) : _json(fx.catalogAlbum1500),
        '/v1/catalog/jp/albums/1500/tracks': fx.doc({
          'data': [fx.catalogSong('11'), fx.catalogSong('12', track: 2)],
        }),
      }));
      final source = server.source();
      final id = MediaId(MediaId.providerAppleMusic, '1500');

      final album = await source.library.getAlbum(id);
      await source.library.getAlbum(id);

      expect(album!.tracks, hasLength(2));
      final albumReads = server.requests.where((r) => r.url.path == '/v1/catalog/jp/albums/1500').toList();
      expect(albumReads, hasLength(3), reason: 'refused once, then later albums skip include[songs]');
      expect(albumReads.last.url.queryParameters.containsKey('include[songs]'), isFalse);
    });

    test('should_collapseLibraryCopies_when_albumTracklistFoldsTwoOntoOneCatalogSong', () async {
      final server = _Server(_withStorefront({
        '/v1/me/library/albums/l.IMP': fx.libraryAlbumImport,
        '/v1/me/library/albums/l.IMP/tracks': fx.doc({
          'data': [
            fx.librarySong('i.ONE', catalog: [fx.catalogSong('11')]),
            fx.librarySong('i.TWO', catalog: [fx.catalogSong('11')]),
            fx.librarySong('i.THREE', catalog: [fx.catalogSong('12', track: 2)]),
          ],
        }),
      }));
      final source = server.source();

      final album = await source.library.getAlbum(MediaId(MediaId.providerAppleMusic, 'library:l.IMP'));

      expect(album!.tracks.map((t) => t.id.rawId), ['11', '12']);
      expect(album.songCount, 2);
      expect(source.factsFor(album.tracks.first.id)!.libraryId, 'i.ONE', reason: 'the first copy wins');
    });

    test('should_returnNull_when_albumDoesNotExist', () async {
      final server = _Server(_withStorefront({}));
      expect(await server.source().library.getAlbum(MediaId(MediaId.providerAppleMusic, '404404')), isNull);
    });

    test('should_listOnlyAlbums_when_readingRecentlyAdded', () async {
      final server = _Server({'/v1/me/library/recently-added': fx.recentlyAdded});

      final albums = await server.source().library.getAlbumList(AlbumListType.newest, size: 5);

      expect(albums.map((a) => a.id.rawId), ['library:l.AAA', 'library:l.BBB']);
      expect(server.lastTo('/v1/me/library/recently-added').url.queryParameters['limit'], '25');
    });

    test('should_foldAndDeduplicate_when_readingRecentlyPlayed', () async {
      final server = _Server({'/v1/me/recent/played': fx.recentPlayed});

      final albums = await server.source().library.getAlbumList(AlbumListType.recent);

      expect(albums.map((a) => a.id.rawId), ['1500'], reason: 'library album folds onto 1500; station dropped');
      expect(server.lastTo('/v1/me/recent/played').url.queryParameters['include[library-albums]'], 'catalog');
    });

    test('should_returnNothing_when_askedForStarredAlbums', () async {
      final server = _Server({});
      expect(await server.source().library.getAlbumList(AlbumListType.starred), isEmpty);
      expect(server.requests, isEmpty);
    });
  });

  group('artists and playlists', () {
    test('should_groupByFirstLetterWithCatalogPortraits_when_listingArtists', () async {
      final server = _Server({
        '/v1/me/library/artists': (http.Request r) =>
            _json(r.url.queryParameters['offset'] == '2' ? fx.libraryArtistsPage2 : fx.libraryArtistsPage1),
      });

      final index = await server.source().library.getArtists();

      expect(index.map((g) => g.name), ['A', 'B']);
      expect(index.first.artists.map((a) => a.name), ['Alpha', 'Aria']);
      final beta = index.last.artists.single;
      expect(beta.id.rawId, 'library:r.B');
      expect((beta.coverArt! as CoverUrl).url, 'https://is1.example.com/artist901/600x600bb.jpg');
    });

    test('should_readCatalogArtistWithAlbums_when_openingCatalogArtist', () async {
      final server = _Server(_withStorefront({
        '/v1/catalog/jp/artists/900': fx.catalogArtist,
        '/v1/catalog/jp/artists/900/albums': fx.catalogArtistAlbums,
      }));

      final artist = await server.source().library.getArtist(MediaId(MediaId.providerAppleMusic, '900'));

      expect(artist!.name, 'Artist');
      expect(artist.albumCount, 2);
      expect(artist.albums.map((a) => a.year), [2024, 2020]);
      expect(server.lastTo('/v1/catalog/jp/artists/900/albums').url.queryParameters['limit'], '100');
    });

    test('should_openCatalogPlaylist_when_libraryRefusesAPublishedPlaylist', () async {
      final server = _Server(_withStorefront({
        '/v1/me/library/playlists': fx.libraryPlaylists,
        '/v1/me/library/playlists/p.MINE': http.Response('', 403),
        '/v1/catalog/jp/playlists/pl.u-PUBLISHED': fx.catalogPlaylistPublished,
        '/v1/catalog/jp/playlists/pl.u-PUBLISHED/tracks': fx.catalogPlaylistTracks,
      }));
      final source = server.source();

      final listed = await source.library.getPlaylists();
      final playlist = await source.library.getPlaylist(listed.first.id);

      expect(listed.map((p) => p.name), ['Road Trip', 'Local only']);
      expect(listed.every((p) => !p.canWrite), isTrue);
      expect(playlist!.id.rawId, 'library:p.MINE');
      expect(playlist.name, 'Road Trip', reason: "the library's own name");
      expect(playlist.comment, 'Songs for driving');
      expect(playlist.tracks.map((t) => t.id.rawId), ['11', '12'], reason: 'the music video is not a song');
      expect(playlist.songCount, 2);
    });

    test('should_keepRepeatedSongs_when_playlistListsASongTwice', () async {
      final server = _Server(_withStorefront({
        '/v1/catalog/jp/playlists/pl.REPEAT': fx.doc({
          'data': [
            {
              'id': 'pl.REPEAT',
              'type': 'playlists',
              'attributes': {'name': 'On repeat', 'curatorName': 'Someone'},
            },
          ],
        }),
        '/v1/catalog/jp/playlists/pl.REPEAT/tracks': fx.doc({
          'data': [fx.catalogSong('11'), fx.catalogSong('12', track: 2), fx.catalogSong('11')],
        }),
      }));

      final playlist = await server.source().library.getPlaylist(MediaId(MediaId.providerAppleMusic, 'pl.REPEAT'));

      expect(playlist!.tracks.map((t) => t.id.rawId), ['11', '12', '11']);
      expect(playlist.songCount, 3);
      expect(playlist.durationSec, 600);
    });

    test('should_failAsBefore_when_libraryOnlyPlaylistIsRefused', () async {
      final server = _Server({
        '/v1/me/library/playlists': fx.libraryPlaylists,
        '/v1/me/library/playlists/p.LOCAL': http.Response('', 403),
      });
      final source = server.source();

      await source.library.getPlaylists();

      await expectLater(
        source.library.getPlaylist(MediaId(MediaId.providerAppleMusic, 'library:p.LOCAL')),
        throwsA(isA<SourceServerException>().having((e) => e.code, 'code', 403)),
      );
    });
  });

  group('search', () {
    test('should_mapEveryTypeAndHonourCounts_when_searchingCatalog', () async {
      final server = _Server(_withStorefront({'/v1/catalog/jp/search': fx.search}));
      final source = server.source();

      final results = await source.library.search('x', trackCount: 2);

      final q = server.lastTo('/v1/catalog/jp/search').url.queryParameters;
      expect(q['types'], 'songs,albums,artists,playlists');
      expect(q['limit'], '25');
      expect(q['term'], 'x');
      expect(results.tracks.map((t) => t.id.rawId), ['11', '12']);
      final song = results.tracks.first;
      expect(song.durationSec, 200);
      expect(song.genre, 'Pop');
      expect((song.coverArt! as CoverUrl).url, 'https://is1.example.com/song11/600x600bb.jpg');
      expect(results.albums.single.releaseType, ReleaseType.single);
      expect(results.artists.single.name, 'Artist');
      expect(results.playlists.single.owner, 'Apple Music');
      expect(source.factsFor(song.id)!.isrc, 'USABC2400001');
    });

    test('should_sendNothing_when_queryIsBlank', () async {
      final server = _Server({});
      expect((await server.source().library.search('  ')).isEmpty, isTrue);
      expect(server.requests, isEmpty);
    });
  });

  group('capabilities and refusals', () {
    test('should_declareAndroidsAppleCapabilities_when_asked', () {
      expect(_Server({}).source().capabilities, {
        Capability.search,
        Capability.catalogSearch,
        Capability.searchPlaylists,
        Capability.libraryAdd,
        Capability.librarySongs,
        Capability.playlistsRead,
        Capability.playlistAdd,
      });
    });

    test('should_refuseFavoritesAndRatings_when_called', () async {
      final source = _Server({}).source();
      final id = MediaId(MediaId.providerAppleMusic, '11');
      await expectLater(source.writeActions.setFavorite(id, true), throwsA(isA<UnsupportedSourceOperation>()));
      await expectLater(source.writeActions.setRating(id, 5), throwsA(isA<UnsupportedSourceOperation>()));
    });

    test('should_returnNoLyricsAndNoRandomSongs_when_asked', () async {
      final source = _Server({}).source();
      expect(await source.metadata.getLyrics(Track(id: MediaId(MediaId.providerAppleMusic, '11'))), isNull);
      expect(await source.library.getRandomSongs(), isEmpty);
      expect((await source.library.getStarred()).tracks, isEmpty);
    });
  });

  group('playback', () {
    test('should_resolveCatalogIdThenRefuse_when_catalogSongIsPlayed', () async {
      final server = _Server(_withStorefront({'/v1/catalog/jp/search': fx.search}));
      final source = server.source();
      final song = (await source.library.search('x')).tracks.first;

      expect(source.catalogIdForPlayback(song), '11');
      await expectLater(source.playback.handleFor(song), throwsA(isA<UnsupportedSourceOperation>()));
    });

    test('should_refuseWithReason_when_songIsAnImport', () async {
      final source = _Server({}).source();
      final import = Track(id: MediaId(MediaId.providerAppleMusic, 'library:i.IMP1'));

      expect(
        () => source.catalogIdForPlayback(import),
        throwsA(isA<UnsupportedSourceOperation>().having((e) => e.operation, 'operation', contains('catalog version'))),
      );
    });

    test('should_refuse_when_catalogSongIsNotStreamable', () async {
      final server = _Server(_withStorefront({
        '/v1/catalog/jp/search': fx.doc({
          'results': {
            'songs': {
              'data': [fx.catalogSong('13', playable: false)],
            },
          },
        }),
      }));
      final source = server.source();
      final song = (await source.library.search('x')).tracks.single;

      expect(source.factsFor(song.id)!.catalogUnavailable, isTrue);
      expect(() => source.catalogIdForPlayback(song), throwsA(isA<UnsupportedSourceOperation>()));
    });

    test('should_rejectForeignIds_when_handedAnotherProvidersTrack', () {
      final source = _Server({}).source();
      expect(() => source.catalogIdForPlayback(Track(id: MediaId.subsonic('1'))), throwsArgumentError);
    });

    test('should_neverReportPlays_when_called', () async {
      final source = _Server({}).source();
      final id = MediaId(MediaId.providerAppleMusic, '11');
      await expectLater(source.playback.reportNowPlaying(id), throwsA(isA<UnsupportedSourceOperation>()));
      await expectLater(source.playback.submitPlay(id, DateTime(2026)), throwsA(isA<UnsupportedSourceOperation>()));
    });
  });

  group('playlist writes', () {
    test('should_createLibraryPlaylistWithSongs_when_creating', () async {
      final server = _Server({
        '/v1/me/library/playlists': (http.Request r) => _json(
          '{"data":[{"id":"p.AbC","type":"library-playlists","attributes":{"name":"Road","canEdit":true}}]}',
          201,
        ),
      });
      final writes = server.source().playlistWrites!;
      final created = await writes.createPlaylist('Road', tracks: [
        MediaId(MediaId.providerAppleMusic, '123'),
        MediaId(MediaId.providerAppleMusic, 'library:i.XyZ'),
      ]);
      expect(created.id, MediaId(MediaId.providerAppleMusic, 'library:p.AbC'));
      expect(server.source().playlistWrites!.acceptsTracks(created), isTrue);
      final request = server.lastTo('/v1/me/library/playlists');
      expect(request.method, 'POST');
      expect(request.headers['Music-User-Token'], _userToken);
      final body = jsonDecode(request.body) as Map<String, Object?>;
      expect((body['attributes']! as Map)['name'], 'Road');
      expect(((body['relationships']! as Map)['tracks']! as Map)['data'], [
        {'id': '123', 'type': 'songs'},
        {'id': 'i.XyZ', 'type': 'library-songs'},
      ]);
    });

    test('should_appendToLibraryPlaylist_when_adding', () async {
      final server = _Server({
        '/v1/me/library/playlists/p.AbC/tracks': (http.Request r) => http.Response('', 204),
      });
      await server.source().playlistWrites!.addTracksToPlaylist(
        MediaId(MediaId.providerAppleMusic, 'library:p.AbC'),
        [MediaId(MediaId.providerAppleMusic, '123')],
      );
      final request = server.lastTo('/v1/me/library/playlists/p.AbC/tracks');
      expect(jsonDecode(request.body), {
        'data': [
          {'id': '123', 'type': 'songs'},
        ],
      });
    });

    test('should_refuseRenameDeleteRemoveAndReorder_when_called', () async {
      final writes = _Server({}).source().playlistWrites!;
      final id = MediaId(MediaId.providerAppleMusic, 'library:p.AbC');
      await expectLater(writes.renamePlaylist(id, 'x'), throwsA(isA<UnsupportedSourceOperation>()));
      await expectLater(writes.deletePlaylist(id), throwsA(isA<UnsupportedSourceOperation>()));
      await expectLater(writes.removeFromPlaylist(id, [0]), throwsA(isA<UnsupportedSourceOperation>()));
      await expectLater(writes.setPlaylistTracks(id, const []), throwsA(isA<UnsupportedSourceOperation>()));
    });

    test('should_refuseCatalogPlaylist_when_adding', () async {
      final writes = _Server({}).source().playlistWrites!;
      expect(writes.acceptsTracks(Playlist(id: MediaId(MediaId.providerAppleMusic, 'pl.123'), name: 'x')), isFalse);
      await expectLater(
        writes.addTracksToPlaylist(MediaId(MediaId.providerAppleMusic, 'pl.123'), const []),
        throwsArgumentError,
      );
    });
  });
}
