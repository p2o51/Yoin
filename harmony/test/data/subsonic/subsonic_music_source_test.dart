import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';

import 'subsonic_fixtures.dart' as fx;

const _creds = SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'alice', password: 'sesame');

/// A fake server: endpoint name (without `.view`) → body, records each request.
final class _Server {
  _Server(this.routes);
  final Map<String, Object> routes;
  final requests = <Uri>[];

  List<String> get endpoints => [for (final r in requests) r.pathSegments.last.replaceAll('.view', '')];

  MockClient get client => MockClient((request) async {
    requests.add(request.url);
    final endpoint = request.url.pathSegments.last.replaceAll('.view', '');
    final route = routes[endpoint];
    return switch (route) {
      final String body => http.Response(body, 200, headers: {'content-type': 'application/json; charset=utf-8'}),
      final http.Response r => r,
      final Object Function() thrower => throw thrower(),
      _ => http.Response('not found', 404),
    };
  });

  SubsonicMusicSource source({SubsonicCredentials credentials = _creds, Duration? timeout}) =>
      SubsonicMusicSource(credentials, client: client, timeout: timeout ?? SubsonicApi.defaultTimeout);
}

SubsonicMusicSource _sourceFor(Map<String, Object> routes) => _Server(routes).source();

Track _track(String id, {String? artist, String? title}) =>
    Track(id: MediaId.subsonic(id), artist: artist, title: title);

void main() {
  group('auth', () {
    test('should_sendTokenAuthParams_when_anyRequest', () async {
      final server = _Server({'ping': fx.ping});
      await server.source().library.ping();

      final q = server.requests.single.queryParameters;
      expect(q['u'], 'alice');
      expect(q['v'], '1.16.1');
      expect(q['c'], 'Yoin');
      expect(q['f'], 'json');
      expect(q['s'], matches(RegExp(r'^[0-9a-f]{16}$')));
      expect(q['t'], md5.convert(utf8.encode('sesame${q['s']}')).toString());
      expect(q.containsKey('p'), isFalse, reason: 'the password never goes on the wire');
    });

    test('should_drawDifferentSalts_when_twoSourcesAreBuilt', () {
      final a = SubsonicMusicSource(_creds, client: MockClient((_) async => http.Response('', 200)));
      final b = SubsonicMusicSource(_creds, client: MockClient((_) async => http.Response('', 200)));
      expect(a.api.salt, isNot(b.api.salt));
      expect(a.api.token, SubsonicAuth.token('sesame', a.api.salt));
    });

    test('should_keepOneSaltPerSource_when_urlsAreBuiltTwice', () {
      final source = _sourceFor({});
      const ref = CoverSourceRelative('al-1');
      expect(source.resolveCoverUrl(ref), source.resolveCoverUrl(ref));
    });

    test('should_matchKnownMd5_when_tokenIsComputed', () {
      // RFC 1321 test vector: md5("abc").
      expect(SubsonicAuth.token('ab', 'c'), '900150983cd24fb0d6963f7d28e17f72');
    });
  });

  group('URL building', () {
    String coverPath(String baseUrl) {
      final source = SubsonicMusicSource(
        SubsonicCredentials(baseUrl: baseUrl, username: 'alice', password: 'x'),
        client: MockClient((_) async => http.Response('', 200)),
        salt: '0123456789abcdef',
      );
      final url = Uri.parse(source.resolveCoverUrl(const CoverSourceRelative('c1'))!);
      return '${url.scheme}://${url.authority}${url.path}';
    }

    test('should_appendRestPath_when_baseUrlIsBare', () {
      expect(coverPath('https://music.example.com'), 'https://music.example.com/rest/getCoverArt.view');
    });

    test('should_ignoreTrailingSlashesAndBlanks_when_baseUrlHasThem', () {
      expect(coverPath('  https://music.example.com//  '), 'https://music.example.com/rest/getCoverArt.view');
    });

    test('should_notDoubleRest_when_baseUrlAlreadyEndsInRest', () {
      expect(coverPath('https://music.example.com/rest'), 'https://music.example.com/rest/getCoverArt.view');
      expect(coverPath('https://music.example.com/rest/'), 'https://music.example.com/rest/getCoverArt.view');
    });

    test('should_keepSubpathAndPort_when_serverIsBehindAPrefix', () {
      expect(coverPath('http://10.0.0.2:4533/navidrome/'), 'http://10.0.0.2:4533/navidrome/rest/getCoverArt.view');
    });

    test('should_returnNullCoverAndThrowNetwork_when_baseUrlIsNotHttp', () async {
      final source = SubsonicMusicSource(
        const SubsonicCredentials(baseUrl: 'music.example.com', username: 'a', password: 'b'),
        client: MockClient((_) async => http.Response('', 200)),
      );
      expect(source.resolveCoverUrl(const CoverSourceRelative('c')), isNull);
      await expectLater(source.library.ping(), throwsA(isA<SourceNetworkException>()));
    });

    test('should_encodeQueryValues_when_theyHoldReservedCharacters', () async {
      final server = _Server({'search3': fx.search3});
      await server.source().library.search('AC/DC & friends?');
      expect(server.requests.single.queryParameters['query'], 'AC/DC & friends?');
    });

    test('should_signCoverUrlWithSize_when_sourceRelativeRefIsResolved', () {
      final url = Uri.parse(_sourceFor({}).resolveCoverUrl(const CoverSourceRelative('al-1'), size: 600)!);
      expect(url.path, '/rest/getCoverArt.view');
      expect(url.queryParameters['id'], 'al-1');
      expect(url.queryParameters['size'], '600');
      expect(url.queryParameters.keys, containsAll(['u', 't', 's', 'v', 'c', 'f']));
    });

    test('should_omitSize_when_noSizeIsGiven', () {
      final url = Uri.parse(_sourceFor({}).resolveCoverUrl(const CoverSourceRelative('al-1'))!);
      expect(url.queryParameters.containsKey('size'), isFalse);
    });

    test('should_passThrough_when_coverIsAFullUrl', () {
      expect(
        _sourceFor({}).resolveCoverUrl(const CoverUrl('https://img.example/a.jpg'), size: 300),
        'https://img.example/a.jpg',
      );
    });

    test('should_returnSignedStreamUrlWithoutTranscode_when_handleForTrack', () async {
      final handle = await _sourceFor({}).playback.handleFor(_track('s-1'));
      final url = Uri.parse((handle as DirectStream).uri);
      expect(url.path, '/rest/stream.view');
      expect(url.queryParameters['id'], 's-1');
      expect(url.queryParameters.keys, isNot(contains('maxBitRate')));
      expect(url.queryParameters.keys, isNot(contains('format')));
      expect(url.queryParameters.keys, containsAll(['u', 't', 's', 'v', 'c', 'f']));
    });

    test('should_signADownload_when_offlineDownloadAsksForOne', () {
      final source = _sourceFor({});
      expect(source.capabilities, contains(Capability.offlineDownload));
      final downloads = source.downloads!;
      final original = downloads.downloadUri(MediaId.subsonic('s-1'))!;
      expect(original.path, '/rest/stream.view');
      expect(original.queryParameters['id'], 's-1');
      expect(original.queryParameters.keys, isNot(contains('maxBitRate')));
      final capped = downloads.downloadUri(MediaId.subsonic('s-1'), maxBitRateKbps: 192, format: 'mp3')!;
      expect(capped.queryParameters, containsPair('maxBitRate', '192'));
      expect(capped.queryParameters, containsPair('format', 'mp3'));
      // A download takes what comes: no length estimate.
      expect(capped.queryParameters.keys, isNot(contains('estimateContentLength')));
    });

    test('should_throwArgumentError_when_mediaIdIsNotSubsonic', () async {
      final track = Track(id: MediaId(MediaId.providerSpotify, 'x'));
      await expectLater(_sourceFor({}).playback.handleFor(track), throwsArgumentError);
    });
  });

  group('library mappers', () {
    test('should_mapEveryAlbumField_when_getAlbum', () async {
      final album = (await _sourceFor({'getAlbum': fx.album}).library.getAlbum(MediaId.subsonic('al-1')))!;
      expect(album.id, MediaId.subsonic('al-1'));
      expect(album.name, 'Test Album');
      expect(album.artist, 'Test Artist');
      expect(album.artistId, MediaId.subsonic('ar-1'));
      expect(album.coverArt, const CoverSourceRelative('al-1'));
      expect(album.songCount, 10);
      expect(album.durationSec, 3600);
      expect(album.year, 2024);
      expect(album.genre, 'Rock');
      expect(album.isStarred, isTrue);
      expect(album.starredAt, '2024-01-01T00:00:00Z');
      expect(album.addedAt, '2023-05-02T10:00:00.000Z');
      expect(album.releaseType, ReleaseType.album);
      expect(album.version, 'Deluxe');
      // Disc 1 before disc 2, whatever the server's order.
      expect(album.tracks.map((t) => t.id.rawId), ['s-1', 's-2']);
      expect(album.tracks.first.audioFormat?.bitRateKbps, 320);
    });

    test('should_fallBackToAlbumIdForCover_when_albumHasNoCoverArt', () async {
      final album = (await _sourceFor({'getAlbum': fx.albumNoCover}).library.getAlbum(MediaId.subsonic('al-9')))!;
      expect(album.coverArt, const CoverSourceRelative('al-9'));
      expect(album.isStarred, isFalse);
      expect(album.tracks, isEmpty);
      expect(album.releaseType, isNull);
    });

    test('should_sendTypeAndPaging_when_getAlbumList', () async {
      final server = _Server({'getAlbumList2': fx.albumList2});
      final albums = await server.source().library.getAlbumList(
        AlbumListType.alphabeticalByArtist,
        size: 5,
        offset: 10,
      );
      final q = server.requests.single.queryParameters;
      expect(q['type'], 'alphabeticalByArtist');
      expect(q['size'], '5');
      expect(q['offset'], '10');
      expect(albums.map((a) => a.name), ['Album A', 'Album B'], reason: 'the entry without an id is skipped');
      expect(albums.map((a) => a.releaseType), [ReleaseType.ep, ReleaseType.single]);
    });

    test('should_mapEveryListType_when_typesAreConverted', () {
      expect(AlbumListType.values.map(albumListTypeParam), [
        'newest',
        'recent',
        'frequent',
        'random',
        'alphabeticalByName',
        'alphabeticalByArtist',
        'starred',
      ]);
    });

    test('should_mapEveryTrackField_when_songIsFull', () async {
      final tracks = await _sourceFor({'getRandomSongs': fx.randomSongs}).library.getRandomSongs(size: 3);
      final t = tracks.first;
      expect(t.id, MediaId.subsonic('s-99'));
      expect(t.title, 'Full Song');
      expect(t.artist, 'Full Artist & Guest', reason: 'displayArtist wins');
      expect(t.artistId, MediaId.subsonic('ar-1'));
      expect(t.album, 'Full Album');
      expect(t.albumId, MediaId.subsonic('al-1'));
      expect(t.coverArt, const CoverSourceRelative('s-99'));
      expect(t.durationSec, 300);
      expect(t.trackNumber, 3);
      expect(t.discNumber, isNull, reason: 'disc 0 means unknown');
      expect(t.year, 2023);
      expect(t.genre, 'Jazz');
      expect(t.userRating, 5);
      expect(t.isStarred, isTrue);
      expect(t.starredAt, '2024-01-15T10:30:00Z');
      expect(t.addedAt, '2023-02-03T04:05:06Z');
      final f = t.audioFormat!;
      expect(f.suffix, 'flac');
      expect(f.contentType, 'audio/flac');
      expect(f.bitRateKbps, 1411);
      expect(f.sampleRateHz, 44100);
      expect(f.bitDepth, 16);
      expect(f.channels, 2);
    });

    test('should_leaveOptionalFieldsNull_when_songIsBare', () async {
      final tracks = await _sourceFor({'getRandomSongs': fx.randomSongs}).library.getRandomSongs();
      final t = tracks[1];
      expect(t.artist, isNull);
      expect(t.coverArt, isNull);
      expect(t.isStarred, isFalse);
      expect(t.audioFormat, isNull);
    });

    test('should_dropAudioFormat_when_mp4ReportsOnlyZeros', () async {
      final tracks = await _sourceFor({'getRandomSongs': fx.randomSongs}).library.getRandomSongs();
      expect(tracks[2].audioFormat, isNull);
    });

    test('should_groupArtistsByIndex_when_getArtists', () async {
      final groups = await _sourceFor({'getArtists': fx.artists}).library.getArtists();
      expect(groups.map((g) => g.name), ['A', 'B']);
      expect(groups.first.artists.map((a) => a.name), ['ABBA', 'AC/DC']);
      expect(groups.first.artists[1].isStarred, isTrue);
      // A one-element list sent as a bare object, and a count sent as a string.
      expect(groups[1].artists.single.name, 'Beatles');
      expect(groups[1].artists.single.albumCount, 20);
    });

    test('should_includeAlbums_when_getArtist', () async {
      final artist = (await _sourceFor({'getArtist': fx.artist}).library.getArtist(MediaId.subsonic('ar-1')))!;
      expect(artist.name, 'Pink Floyd');
      expect(artist.albumCount, 15);
      expect(artist.coverArt, const CoverSourceRelative('ar-1'));
      expect(artist.albums.map((a) => a.name), ['The Wall', 'Wish You Were Here']);
      expect(artist.albums.first.year, 1979);
    });

    test('should_decideCanWriteByOwnerAndReadonly_when_getPlaylists', () async {
      final lists = await _sourceFor({'getPlaylists': fx.playlists}).library.getPlaylists();
      expect(lists.map((p) => p.canWrite), [true, false, false, true]);
      expect(lists[0].coverArt, const CoverSourceRelative('pl1'));
      expect(lists[1].coverArt, const CoverSourceRelative('pl-pl2'));
      expect(lists[0].songCount, 3);
      expect(lists[0].durationSec, 600);
      expect(lists[0].owner, 'alice');
    });

    test('should_includeEntriesAndComment_when_getPlaylist', () async {
      final p = (await _sourceFor({'getPlaylist': fx.playlist}).library.getPlaylist(MediaId.subsonic('pl1')))!;
      expect(p.comment, 'for the road');
      expect(p.tracks.map((t) => t.title), ['One', 'Two']);
    });

    test('should_sendCountsAndMapAllKinds_when_search', () async {
      final server = _Server({'search3': fx.search3});
      final r = await server.source().library.search('queen', trackCount: 3, albumCount: 2, artistCount: 1);
      final q = server.requests.single.queryParameters;
      expect([q['songCount'], q['albumCount'], q['artistCount']], ['3', '2', '1']);
      expect(r.artists.single.name, 'Queen');
      expect(r.albums.single.name, 'A Night at the Opera');
      expect(r.tracks.single.durationSec, 354);
    });

    test('should_mapAllKinds_when_getStarred', () async {
      final s = await _sourceFor({'getStarred2': fx.starred2}).library.getStarred();
      expect(s.artists.single.isStarred, isTrue);
      expect(s.albums.single.isStarred, isTrue);
      expect(s.tracks.single.title, 'Karma Police');
    });

    test('should_returnTrue_when_pingSucceeds', () async {
      expect(await _sourceFor({'ping': fx.ping}).library.ping(), isTrue);
    });
  });

  group('lyrics', () {
    test('should_returnSortedSyncedLyricsWithOffset_when_serverListsSongLyrics', () async {
      final server = _Server({
        'getOpenSubsonicExtensions': fx.extensionsWithLyrics,
        'getLyricsBySongId': fx.structuredLyrics,
      });
      final lyrics = await server.source().metadata.getLyrics(_track('s-1', artist: 'A', title: 'B'));
      final synced = lyrics as SyncedLyrics;
      expect(synced.language, 'eng');
      expect(synced.lines.map((l) => (l.startMs, l.text)), [(0, 'First line'), (4900, 'Second line')]);
      expect(server.requests.last.queryParameters['id'], 's-1');
      expect(server.endpoints, isNot(contains('getLyrics')));
    });

    test('should_returnUnsynced_when_structuredLyricsAreNotSynced', () async {
      final lyrics = await _sourceFor({
        'getOpenSubsonicExtensions': fx.extensionsWithLyrics,
        'getLyricsBySongId': fx.unsyncedStructuredLyrics,
      }).metadata.getLyrics(_track('s-1'));
      expect((lyrics as UnsyncedLyrics).text, 'one\ntwo');
      expect(lyrics.language, 'und');
    });

    test('should_useClassicGetLyrics_when_serverLacksSongLyrics', () async {
      final server = _Server({'getOpenSubsonicExtensions': fx.extensionsWithoutLyrics, 'getLyrics': fx.plainLyrics});
      final lyrics = await server.source().metadata.getLyrics(
        _track('s-1', artist: 'Queen', title: 'Bohemian Rhapsody'),
      );
      expect((lyrics as UnsyncedLyrics).text, 'Is this the real life?\nIs this just fantasy?');
      expect(server.endpoints, ['getOpenSubsonicExtensions', 'getLyrics']);
      final q = server.requests.last.queryParameters;
      expect([q['artist'], q['title']], ['Queen', 'Bohemian Rhapsody']);
    });

    test('should_useClassicGetLyrics_when_extensionsEndpointIsMissing', () async {
      // Plain Subsonic: getOpenSubsonicExtensions is a 404 (no route in the fake).
      final lyrics = await _sourceFor({
        'getLyrics': fx.plainLyrics,
      }).metadata.getLyrics(_track('s-1', artist: 'Queen', title: 'X'));
      expect(lyrics, isA<UnsyncedLyrics>());
    });

    test('should_parseLrc_when_classicLyricsCarryTimestamps', () async {
      final lyrics = await _sourceFor({
        'getOpenSubsonicExtensions': fx.extensionsWithoutLyrics,
        'getLyrics': fx.plainLrcLyrics,
      }).metadata.getLyrics(_track('s-1', artist: 'A', title: 'B'));
      final synced = lyrics as SyncedLyrics;
      expect(synced.lines.map((l) => (l.startMs, l.text)), [(1000, 'twice'), (2500, 'later'), (3000, 'twice')]);
    });

    test('should_fallBackToClassic_when_structuredLyricsAreEmpty', () async {
      final server = _Server({
        'getOpenSubsonicExtensions': fx.extensionsWithLyrics,
        'getLyricsBySongId': fx.emptyLyricsList,
        'getLyrics': fx.plainLyrics,
      });
      final lyrics = await server.source().metadata.getLyrics(_track('s-1', artist: 'Queen', title: 'X'));
      expect(lyrics, isA<UnsyncedLyrics>());
      expect(server.endpoints, ['getOpenSubsonicExtensions', 'getLyricsBySongId', 'getLyrics']);
    });

    test('should_returnNull_when_neitherPathHasLyrics', () async {
      final lyrics = await _sourceFor({
        'getOpenSubsonicExtensions': fx.extensionsWithLyrics,
        'getLyricsBySongId': fx.emptyLyricsList,
        'getLyrics': fx.emptyPlainLyrics,
      }).metadata.getLyrics(_track('s-1', artist: 'A', title: 'B'));
      expect(lyrics, isNull);
    });

    test('should_returnNullWithoutClassicCall_when_trackHasNoArtistOrTitle', () async {
      final server = _Server({'getOpenSubsonicExtensions': fx.extensionsWithoutLyrics, 'getLyrics': fx.plainLyrics});
      expect(await server.source().metadata.getLyrics(_track('s-1')), isNull);
      expect(server.endpoints, ['getOpenSubsonicExtensions']);
    });

    test('should_askExtensionsOnce_when_lyricsAreFetchedTwice', () async {
      final server = _Server({
        'getOpenSubsonicExtensions': fx.extensionsWithLyrics,
        'getLyricsBySongId': fx.structuredLyrics,
      });
      final source = server.source();
      await source.metadata.getLyrics(_track('s-1'));
      await source.metadata.getLyrics(_track('s-2'));
      expect(server.endpoints.where((e) => e == 'getOpenSubsonicExtensions'), hasLength(1));
    });

    test('should_askExtensionsAgain_when_firstAskFailedOnNetwork', () async {
      var calls = 0;
      final client = MockClient((request) async {
        if (request.url.path.endsWith('getOpenSubsonicExtensions.view') && calls++ == 0) {
          throw const SocketException('down');
        }
        return http.Response(
          request.url.path.endsWith('getOpenSubsonicExtensions.view') ? fx.extensionsWithLyrics : fx.structuredLyrics,
          200,
        );
      });
      final source = SubsonicMusicSource(_creds, client: client);
      await expectLater(source.metadata.getLyrics(_track('s-1')), throwsA(isA<SourceNetworkException>()));
      expect(await source.metadata.getLyrics(_track('s-1')), isA<SyncedLyrics>());
    });
  });

  group('errors', () {
    for (final code in [40, 41, 50]) {
      test('should_throwAuth_when_subsonicErrorIs$code', () async {
        final source = _sourceFor({'ping': fx.failed(code, 'nope')});
        await expectLater(
          source.library.ping(),
          throwsA(isA<SourceAuthException>().having((e) => e.message, 'message', 'nope')),
        );
      });
    }

    test('should_throwServerWithCode_when_subsonicErrorIsNotAuth', () async {
      final source = _sourceFor({'getArtists': fx.failed(10, 'Required parameter is missing')});
      await expectLater(
        source.library.getArtists(),
        throwsA(isA<SourceServerException>().having((e) => e.code, 'code', 10)),
      );
    });

    test('should_returnNull_when_getAlbumAnswersNotFound', () async {
      final source = _sourceFor({
        'getAlbum': fx.failed(70, 'Album not found'),
        'getArtist': fx.failed(70, 'x'),
        'getPlaylist': fx.failed(70, 'x'),
      });
      expect(await source.library.getAlbum(MediaId.subsonic('gone')), isNull);
      expect(await source.library.getArtist(MediaId.subsonic('gone')), isNull);
      expect(await source.library.getPlaylist(MediaId.subsonic('gone')), isNull);
    });

    test('should_throwServer_when_httpErrorHasNoSubsonicBody', () async {
      final source = _sourceFor({'ping': http.Response('<html>502</html>', 502)});
      await expectLater(
        source.library.ping(),
        throwsA(
          isA<SourceServerException>()
              .having((e) => e.message, 'message', 'HTTP 502')
              .having((e) => e.code, 'code', isNull),
        ),
      );
    });

    test('should_throwServer_when_okBodyIsNotSubsonic', () async {
      final source = _sourceFor({'ping': '{"hello":"world"}'});
      await expectLater(source.library.ping(), throwsA(isA<SourceServerException>()));
    });

    test('should_preferSubsonicError_when_httpStatusIsAlsoAnError', () async {
      final source = _sourceFor({'ping': http.Response(fx.failed(40, 'Wrong username or password'), 401)});
      await expectLater(source.library.ping(), throwsA(isA<SourceAuthException>()));
    });

    final networkFailures = <String, Object Function()>{
      'SocketException': () => const SocketException('Connection refused'),
      'HandshakeException': () => const HandshakeException('bad cert'),
      'ClientException': () => http.ClientException('Connection closed'),
      'TimeoutException': () => TimeoutException('slow'),
    };
    networkFailures.forEach((name, error) {
      test('should_throwNetwork_when_clientThrows$name', () async {
        final source = _sourceFor({'ping': error});
        await expectLater(source.library.ping(), throwsA(isA<SourceNetworkException>()));
      });
    });

    test('should_throwNetwork_when_serverNeverAnswers', () async {
      final never = Completer<http.Response>();
      final source = SubsonicMusicSource(
        _creds,
        client: MockClient((_) => never.future),
        timeout: const Duration(milliseconds: 50),
      );
      await expectLater(source.library.ping(), throwsA(isA<SourceNetworkException>()));
    });

    test('should_redactCredentials_when_errorMessageQuotesTheUrl', () async {
      final source = _sourceFor({
        'ping': () =>
            http.ClientException('failed', Uri.parse('https://h/rest/ping.view?u=alice&t=abc123&s=salt99&v=1')),
      });
      await expectLater(
        source.library.ping(),
        throwsA(
          isA<SourceNetworkException>().having(
            (e) => e.message,
            'message',
            allOf(isNot(contains('abc123')), isNot(contains('salt99')), contains('t=REDACTED')),
          ),
        ),
      );
    });
  });

  group('writes and reporting', () {
    test('should_starWithKindParam_when_idWasSeenAsTrackAlbumOrArtist', () async {
      final server = _Server({'getStarred2': fx.starred2, 'star': fx.ok(), 'unstar': fx.ok()});
      final source = server.source();
      await source.library.getStarred(); // s-1 song, al-1 album, ar-1 artist
      await source.writeActions.setFavorite(MediaId.subsonic('s-1'), true);
      await source.writeActions.setFavorite(MediaId.subsonic('al-1'), true);
      await source.writeActions.setFavorite(MediaId.subsonic('ar-1'), false);

      final writes = server.requests.skip(1).toList();
      expect(writes.map((u) => u.pathSegments.last), ['star.view', 'star.view', 'unstar.view']);
      expect(writes[0].queryParameters['id'], 's-1');
      expect(writes[1].queryParameters['albumId'], 'al-1');
      expect(writes[1].queryParameters.containsKey('id'), isFalse);
      expect(writes[2].queryParameters['artistId'], 'ar-1');
    });

    test('should_starAsId_when_idWasNeverSeen', () async {
      final server = _Server({'star': fx.ok()});
      await server.source().writeActions.setFavorite(MediaId.subsonic('mystery'), true);
      expect(server.requests.single.queryParameters['id'], 'mystery');
    });

    test('should_clampRating_when_outOfRange', () async {
      final server = _Server({'setRating': fx.ok()});
      final source = server.source();
      await source.writeActions.setRating(MediaId.subsonic('s-1'), 9);
      await source.writeActions.setRating(MediaId.subsonic('s-1'), -2);
      await source.writeActions.setRating(MediaId.subsonic('s-1'), 3);
      expect(server.requests.map((u) => u.queryParameters['rating']), ['5', '0', '3']);
      expect(server.requests.first.queryParameters['id'], 's-1');
    });

    test('should_scrobbleWithoutSubmission_when_reportNowPlaying', () async {
      final server = _Server({'scrobble': fx.ok()});
      await server.source().playback.reportNowPlaying(MediaId.subsonic('s-1'));
      final q = server.requests.single.queryParameters;
      expect(q['id'], 's-1');
      expect(q['submission'], 'false');
      expect(q.containsKey('time'), isFalse);
    });

    test('should_scrobbleSubmissionWithTime_when_submitPlay', () async {
      final server = _Server({'scrobble': fx.ok()});
      final at = DateTime.utc(2026, 11, 13, 8);
      await server.source().playback.submitPlay(MediaId.subsonic('s-1'), at);
      final q = server.requests.single.queryParameters;
      expect(q['submission'], 'true');
      expect(q['time'], '${at.millisecondsSinceEpoch}');
    });

    test('should_listMvpCapabilities_when_asked', () {
      // Every capability but the catalogue / library-collection ones (Apple Music's).
      expect(
        _sourceFor({}).capabilities,
        Capability.values.toSet().difference({
          Capability.catalogSearch,
          Capability.searchPlaylists,
          Capability.libraryAdd,
          Capability.librarySongs,
        }),
      );
      expect(_sourceFor({}).provider, 'subsonic');
    });
  });
}
