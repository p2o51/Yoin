import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/applemusic/apple_music_name_region.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';

import 'apple_music_fixtures.dart' as fx;

const _tokenUrl = 'https://token.example.com/token';
const _creds = AppleMusicCredentials(endpoint: _tokenUrl, musicUserToken: 'test-music-user-token');
final _devToken = fx.fakeDeveloperToken(exp: DateTime.now().millisecondsSinceEpoch ~/ 1000 + 3600);

http.Response _json(Object body, [int status = 200]) => http.Response(
  body is String ? body : jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

/// Token service + Apple Music API: path → handler; every request recorded.
final class _Server {
  _Server(this.routes);
  final Map<String, http.Response Function(http.Request)> routes;
  final requests = <http.Request>[];

  MockClient get client => MockClient((request) async {
    requests.add(request);
    if (request.url.toString() == _tokenUrl) return _json({'developerToken': _devToken});
    final route =
        routes[request.url.path] ?? (request.url.path == '/v1/me/storefront' ? (_) => _json(fx.storefront) : null);
    return route == null ? http.Response(fx.appleNotFound, 404) : route(request);
  });

  AppleMusicSource source({AppleMusicNameRegion region = AppleMusicNameRegion.off}) =>
      AppleMusicSource(_creds, client: client, nameRegion: () async => region);

  List<http.Request> to(String path) => [
    for (final r in requests)
      if (r.url.path == path) r,
  ];
}

void main() {
  group('library search and songs', () {
    test('should_searchTheLibraryAlone_when_scopeIsLibrary', () async {
      final server = _Server({
        '/v1/me/library/search': (_) => _json({
          'results': {
            'library-songs': {
              'data': [
                fx.librarySong('i.1', catalog: [fx.catalogSong('11')]),
                fx.librarySong('i.2', catalog: [fx.catalogSong('11')]),
              ],
            },
            'library-playlists': {
              'data': [
                {
                  'id': 'p.9',
                  'type': 'library-playlists',
                  'attributes': {'name': 'Mine'},
                },
              ],
            },
          },
        }),
      });
      final source = server.source();
      final results = await source.libraryCollection!.searchLibrary('x');
      final request = server.to('/v1/me/library/search').single;
      expect(request.url.queryParameters['types'], 'library-songs,library-albums,library-artists,library-playlists');
      expect(request.headers['Music-User-Token'], isNotNull);
      // Two library copies of one catalogue song show once.
      expect(results.tracks, hasLength(1));
      expect(results.playlists.single.name, 'Mine');
      expect(server.to('/v1/catalog/jp/search'), isEmpty);
    });

    test('should_askCatalogOfLibrarySongs_when_searchLeftItOut', () async {
      final server = _Server({
        '/v1/me/library/search': (_) => _json({
          'results': {
            'library-songs': {
              'data': [fx.librarySong('i.5')],
            },
          },
        }),
        '/v1/me/library/songs': (r) => _json({
          'data': [
            fx.librarySong('i.5', catalog: [fx.catalogSong('55')]),
          ],
        }),
      });
      final results = await server.source().libraryCollection!.searchLibrary('x');
      expect(server.to('/v1/me/library/songs').single.url.queryParameters, {'ids': 'i.5', 'include': 'catalog'});
      expect(results.tracks.single.id.rawId, '55');
    });

    test('should_pageLibrarySongs_when_moreThanAPage', () async {
      final server = _Server({
        '/v1/me/library/songs': (r) => r.url.queryParameters['offset'] == '1'
            ? _json({
                'data': [
                  fx.librarySong('i.2', catalog: [fx.catalogSong('22')]),
                ],
              })
            : _json({
                'next': '/v1/me/library/songs?offset=1',
                'data': [
                  fx.librarySong('i.1', catalog: [fx.catalogSong('21')]),
                ],
              }),
      });
      final songs = await server.source().libraryCollection!.librarySongs(size: 5);
      expect([for (final t in songs) t.id.rawId], ['21', '22']);
      expect(server.to('/v1/me/library/songs').first.url.queryParameters['include'], 'catalog,albums,artists');
    });
  });

  group('add to library', () {
    setUp(() {
      // No real waits in tests.
      AppleMusicSource.libraryAddConfirmWaits = const [Duration.zero, Duration.zero];
    });

    test('should_sayAdded_when_songIsInTheLibraryAlready', () async {
      final server = _Server({
        '/v1/catalog/jp/songs/77/library': (_) => _json({
          'data': [
            {'id': 'i.77', 'type': 'library-songs'},
          ],
        }),
      });
      final adds = server.source().libraryAdds!;
      expect(await adds.libraryMembership(MediaId(MediaId.providerAppleMusic, '77')), LibraryMembership.added);
      expect(await adds.addToLibrary(MediaId(MediaId.providerAppleMusic, '77')), LibraryMembership.added);
      expect(server.to('/v1/me/library'), isEmpty);
    });

    test('should_postOnceAndConfirm_when_songIsAdded', () async {
      var added = false;
      final server = _Server({
        '/v1/catalog/jp/songs/77/library': (_) => added
            ? _json({
                'data': [
                  {'id': 'i.77', 'type': 'library-songs'},
                ],
              })
            : http.Response(fx.appleNotFound, 404),
        '/v1/catalog/jp/songs/77': (_) => _json({
          'data': [fx.catalogSong('77')],
        }),
        '/v1/me/library': (r) {
          added = true;
          return http.Response('', 202);
        },
      });
      final adds = server.source().libraryAdds!;
      final id = MediaId(MediaId.providerAppleMusic, '77');
      expect(await adds.libraryMembership(id), LibraryMembership.notAdded);
      expect(await adds.addToLibrary(id), LibraryMembership.added);
      final post = server.to('/v1/me/library').single;
      expect(post.method, 'POST');
      expect(post.url.queryParameters, {'ids[songs]': '77'});
    });

    test('should_sayPendingAndNotPostAgain_when_appleIsSlowToShowIt', () async {
      final server = _Server({
        '/v1/catalog/jp/songs/77/library': (_) => http.Response(fx.appleNotFound, 404),
        '/v1/catalog/jp/songs/77': (_) => _json({
          'data': [fx.catalogSong('77')],
        }),
        '/v1/me/library': (_) => http.Response('', 202),
      });
      final adds = server.source().libraryAdds!;
      final id = MediaId(MediaId.providerAppleMusic, '77');
      expect(await adds.addToLibrary(id), LibraryMembership.pending);
      expect(await adds.libraryMembership(id), LibraryMembership.pending);
      expect(await adds.addToLibrary(id), LibraryMembership.pending);
      expect(server.to('/v1/me/library'), hasLength(1));
    });
  });

  group('name region', () {
    test('should_detectLanguage_when_genreIsrcOrScriptTells', () {
      expect(OriginalNameTarget.detect(genre: 'J-Pop'), OriginalNameTarget.japan);
      expect(OriginalNameTarget.detect(genre: '华语流行'), OriginalNameTarget.mainlandChina);
      expect(OriginalNameTarget.detect(isrc: 'KRA382400001'), OriginalNameTarget.korea);
      expect(OriginalNameTarget.detect(title: 'さよなら'), OriginalNameTarget.japan);
      expect(OriginalNameTarget.detect(title: '晴天'), OriginalNameTarget.mainlandChina);
      expect(OriginalNameTarget.detect(title: 'Hello', artist: 'Adele', genre: 'Pop', isrc: 'GBBKS1500214'), isNull);
    });

    test('should_takeOnlyOriginalScript_when_regionIsOriginal', () {
      const region = AppleMusicNameRegion.original;
      expect(OriginalNameTarget.accepts(region, OriginalNameTarget.japan, 'Sayonara', 'さよなら'), isTrue);
      expect(OriginalNameTarget.accepts(region, OriginalNameTarget.japan, 'Sayonara', 'Goodbye'), isFalse);
      expect(OriginalNameTarget.accepts(region, OriginalNameTarget.mainlandChina, 'X', 'さよなら'), isFalse);
      expect(OriginalNameTarget.accepts(AppleMusicNameRegion.japan, OriginalNameTarget.japan, 'A', 'B'), isTrue);
      expect(OriginalNameTarget.accepts(region, OriginalNameTarget.japan, 'さよなら', 'さよなら'), isFalse);
    });

    test('should_renameFromThatStorefront_when_regionIsChinaMainland', () async {
      final server = _Server({
        '/v1/catalog/jp/search': (_) => _json(fx.search),
        '/v1/catalog/cn/songs': (r) => _json({
          'data': [
            {
              'id': '11',
              'type': 'songs',
              'attributes': {'name': '歌曲十一', 'artistName': '歌手', 'albumName': '专辑'},
            },
          ],
        }),
        '/v1/catalog/cn/albums': (_) => _json({'data': []}),
      });
      final results = await server.source(region: AppleMusicNameRegion.mainlandChina).library.search('x');
      final request = server.to('/v1/catalog/cn/songs').first;
      expect(request.url.queryParameters['l'], 'zh-Hans-CN');
      expect(request.url.queryParameters['ids']!.split(','), containsAll(['11', '12']));
      expect(results.tracks.first.title, '歌曲十一');
      expect(results.tracks.first.artist, '歌手');
      // Not in that storefront by id; its ISRC was asked, then the shown name stays.
      expect(results.tracks[1].title, 'Song 12');
    });

    test('should_neverAsk_when_regionIsOff', () async {
      final server = _Server({'/v1/catalog/jp/search': (_) => _json(fx.search)});
      final results = await server.source().library.search('x');
      expect(results.tracks.first.title, 'Song 11');
      expect(server.requests.where((r) => r.url.path.startsWith('/v1/catalog/cn')), isEmpty);
    });

    test('should_recoverEnglishNames_when_originalModeRunsOnJpOrCnStorefront', () async {
      expect(
        OriginalNameTarget.detect(
          title: 'Bad Guy',
          artist: 'ビリー・アイリッシュ',
          genre: 'Alternative',
          isrc: 'USUM71900764',
        ),
        OriginalNameTarget.unitedStates,
      );
      expect(
        OriginalNameTarget.detect(title: 'ミッドナイツ', artist: 'テイラー・スウィフト', genre: 'Pop'),
        OriginalNameTarget.unitedStates,
      );
      expect(
        OriginalNameTarget.detect(title: '红', artist: '泰勒·斯威夫特', genre: 'Pop', isrc: 'USCJY1231001'),
        OriginalNameTarget.unitedStates,
      );
      expect(
        OriginalNameTarget.detect(title: '晴る', artist: 'ヨルシカ', genre: 'J-Pop', isrc: 'JPPO02400001'),
        OriginalNameTarget.japan,
      );
      expect(
        OriginalNameTarget.accepts(
          AppleMusicNameRegion.original,
          OriginalNameTarget.unitedStates,
          'ビリー・アイリッシュ',
          'Billie Eilish',
        ),
        isTrue,
      );
      expect(
        OriginalNameTarget.accepts(
          AppleMusicNameRegion.original,
          OriginalNameTarget.unitedStates,
          'Bad Guy',
          'bad guy',
        ),
        isFalse,
      );

      final server = _Server({
        '/v1/catalog/jp/search': (_) => _json({
          'results': {
            'songs': {
              'data': [
                {
                  'id': '91',
                  'type': 'songs',
                  'attributes': {
                    'name': 'Bad Guy',
                    'artistName': 'ビリー・アイリッシュ',
                    'albumName': 'ホエン・ウィ・オール・フォール・アスリープ',
                    'genreNames': ['Alternative'],
                    'isrc': 'USUM71900764',
                  },
                },
                {
                  'id': '92',
                  'type': 'songs',
                  'attributes': {
                    'name': '晴る',
                    'artistName': 'ヨルシカ',
                    'albumName': '晴る - Single',
                    'genreNames': ['J-Pop'],
                    'isrc': 'JPPO02400001',
                  },
                },
              ],
            },
          },
        }),
        '/v1/catalog/us/songs': (_) => _json({
          'data': [
            {
              'id': '91',
              'type': 'songs',
              'attributes': {
                'name': 'bad guy',
                'artistName': 'Billie Eilish',
                'albumName': 'WHEN WE ALL FALL ASLEEP',
              },
            },
          ],
        }),
      });
      final results = await server.source(region: AppleMusicNameRegion.original).library.search('billie');
      final usReq = server.to('/v1/catalog/us/songs').single;
      expect(usReq.url.queryParameters['l'], 'en-US');
      expect(usReq.url.queryParameters['ids'], '91');
      expect(results.tracks[0].title, 'Bad Guy');
      expect(results.tracks[0].artist, 'Billie Eilish');
      expect(results.tracks[0].album, 'WHEN WE ALL FALL ASLEEP');
      expect(results.tracks[1].title, '晴る');
      expect(results.tracks[1].artist, 'ヨルシカ');
    });

    test('should_renameLibraryArtistsAndGroupByRenamedInitial_when_originalModeRunsOnJpStorefront', () async {
      final server = _Server({
        '/v1/me/library/artists': (_) => _json({
          'data': [
            {
              'id': 'r.1',
              'type': 'library-artists',
              'attributes': {'name': 'テイラー・スウィフト'},
              'relationships': {
                'catalog': {
                  'data': [
                    {
                      'id': '159260351',
                      'type': 'artists',
                      'attributes': {
                        'name': 'テイラー・スウィフト',
                        'genreNames': ['Pop'],
                      },
                    },
                  ],
                },
              },
            },
            {
              'id': 'r.2',
              'type': 'library-artists',
              'attributes': {'name': 'ヨルシカ'},
              'relationships': {
                'catalog': {
                  'data': [
                    {
                      'id': '12345',
                      'type': 'artists',
                      'attributes': {
                        'name': 'ヨルシカ',
                        'genreNames': ['J-Pop'],
                      },
                    },
                  ],
                },
              },
            },
          ],
        }),
        '/v1/catalog/us/artists': (_) => _json({
          'data': [
            {
              'id': '159260351',
              'type': 'artists',
              'attributes': {'name': 'Taylor Swift'},
            },
          ],
        }),
      });
      final indices = await server.source(region: AppleMusicNameRegion.original).library.getArtists();
      final usReq = server.to('/v1/catalog/us/artists').single;
      expect(usReq.url.queryParameters['l'], 'en-US');
      expect(usReq.url.queryParameters['ids'], '159260351');
      final byGroup = {for (final g in indices) g.name: g.artists.map((a) => a.name).toList()};
      expect(byGroup['T'], ['Taylor Swift']);
      expect(byGroup['ヨ'], ['ヨルシカ']);
    });

    test('should_readEachAccountsRegion_when_storedByProfile', () async {
      final kv = InMemoryStore();
      final repo = ProfileRepository(kv, InMemoryStore());
      final p = await repo.add(provider: MediaId.providerAppleMusic, displayName: 'Apple Music', credentials: _creds);
      final read = appleMusicNameRegionReader(() => repo, () => kv, _creds);
      expect(await read(), AppleMusicNameRegion.off);
      await AppleMusicNameRegionStore(kv).write(p.id, AppleMusicNameRegion.japan);
      expect(await read(), AppleMusicNameRegion.japan);
      await AppleMusicNameRegionStore(kv).write(p.id, AppleMusicNameRegion.off);
      expect(kv.values.containsKey(AppleMusicNameRegionStore.keyFor(p.id)), isFalse);
    });
  });
}
