import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_mappers.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'Alice', password: 'sesame');

String _ok(Map<String, Object?> body) => jsonEncode({
  'subsonic-response': {'status': 'ok', 'version': '1.16.1', ...body},
});

String _failed(int code, String message) => jsonEncode({
  'subsonic-response': {
    'status': 'failed',
    'version': '1.16.1',
    'error': {'code': code, 'message': message},
  },
});

/// A server whose `createShare` answers from [share] (called with the request's ids).
final class _Server {
  _Server(this.share, {this.playlist});

  final http.Response Function(List<String> ids) share;
  final Map<String, Object?>? playlist;
  final requests = <Uri>[];

  SubsonicMusicSource source() => SubsonicMusicSource(
    _creds,
    client: MockClient((request) async {
      requests.add(request.url);
      final endpoint = request.url.pathSegments.last.replaceAll('.view', '');
      return switch (endpoint) {
        'createShare' => share(request.url.queryParametersAll['id'] ?? const []),
        'getPlaylist' when playlist != null => http.Response(_ok({'playlist': playlist}), 200),
        _ => http.Response('not found', 404),
      };
    }),
  );

  int get shareCalls => requests.where((u) => u.path.endsWith('createShare.view')).length;
}

http.Response _shared(String url) => http.Response(
  _ok({
    'shares': {
      'share': [
        {'id': '7', 'url': url, 'description': 'x'},
      ],
    },
  }),
  200,
);

void main() {
  group('createShareLink', () {
    test('should_returnTheShareUrl_when_serverMakesOne', () async {
      final server = _Server((ids) => _shared('https://music.example.com/share/abc'));
      final source = server.source();
      final link = await source.links!.createShareLink(
        WebLinkKind.album,
        MediaId.subsonic('al1'),
        description: 'Blue – Joni',
      );
      expect(link, 'https://music.example.com/share/abc');
      final q = server.requests.single.queryParameters;
      expect(q['id'], 'al1');
      expect(q['description'], 'Blue – Joni');
      expect(q.containsKey('expires'), isFalse);
      expect(source.capabilities, contains(Capability.shareLinks));
    });

    test('should_makeNoShare_when_itemIsAnArtist', () async {
      final server = _Server((ids) => _shared('https://x/share'));
      expect(await server.source().links!.createShareLink(WebLinkKind.artist, MediaId.subsonic('ar1')), isNull);
      expect(server.requests, isEmpty);
    });

    test('should_shareThePlaylistSongs_when_serverRefusesThePlaylistId', () async {
      final server = _Server(
        (ids) => ids.length == 1 && ids.single == 'pl1'
            ? http.Response(_failed(70, 'Not found'), 200)
            : _shared('https://music.example.com/share/songs'),
        playlist: {
          'id': 'pl1',
          'name': 'Mix',
          'entry': [
            {'id': 's1', 'title': 'One'},
            {'id': 's2', 'title': 'Two'},
          ],
        },
      );
      final link = await server.source().links!.createShareLink(WebLinkKind.playlist, MediaId.subsonic('pl1'));
      expect(link, 'https://music.example.com/share/songs');
      final last = server.requests.last;
      expect(last.queryParametersAll['id'], ['s1', 's2']);
    });

    test('should_stopAsking_when_serverRefusedForGood', () async {
      final server = _Server((ids) => http.Response(_failed(0, 'Sharing is disabled'), 200));
      final source = server.source();
      await expectLater(
        source.links!.createShareLink(WebLinkKind.track, MediaId.subsonic('s1')),
        throwsA(isA<SourceServerException>()),
      );
      expect(await source.links!.createShareLink(WebLinkKind.track, MediaId.subsonic('s2')), isNull);
      expect(server.shareCalls, 1);
    });

    test('should_askAgain_when_failureMayPass', () async {
      var calls = 0;
      final server = _Server((ids) {
        calls++;
        return calls == 1 ? http.Response('busy', 503) : _shared('https://music.example.com/share/ok');
      });
      final source = server.source();
      await expectLater(
        source.links!.createShareLink(WebLinkKind.track, MediaId.subsonic('s1')),
        throwsA(isA<SourceServerException>()),
      );
      expect(
        await source.links!.createShareLink(WebLinkKind.track, MediaId.subsonic('s1')),
        'https://music.example.com/share/ok',
      );
    });

    test('should_rememberRefusal_when_endpointIsMissing', () async {
      final server = _Server((ids) => http.Response('nope', 404));
      final source = server.source();
      await expectLater(
        source.links!.createShareLink(WebLinkKind.album, MediaId.subsonic('al1')),
        throwsA(isA<SourceServerException>()),
      );
      expect(await source.links!.createShareLink(WebLinkKind.album, MediaId.subsonic('al1')), isNull);
      expect(server.shareCalls, 1);
    });

    test('should_notRetryWithSongs_when_playlistShareFailsInTransport', () async {
      final server = _Server(
        (ids) => http.Response('busy', 503),
        playlist: {
          'id': 'pl1',
          'name': 'Mix',
          'entry': [
            {'id': 's1', 'title': 'One'},
          ],
        },
      );
      await expectLater(
        server.source().links!.createShareLink(WebLinkKind.playlist, MediaId.subsonic('pl1')),
        throwsA(isA<SourceServerException>()),
      );
      expect(server.shareCalls, 1);
      expect(server.requests.where((u) => u.path.endsWith('getPlaylist.view')), isEmpty);
    });

    test('should_stopAsking_when_serverAnswersWithoutAShare', () async {
      final server = _Server((ids) => http.Response(_ok({'shares': <String, Object?>{}}), 200));
      final source = server.source();
      await expectLater(
        source.links!.createShareLink(WebLinkKind.album, MediaId.subsonic('al1')),
        throwsA(isA<SourceServerException>()),
      );
      expect(await source.links!.createShareLink(WebLinkKind.album, MediaId.subsonic('al2')), isNull);
      expect(server.shareCalls, 1);
    });

    test('should_haveNoPublicPage_when_sourceIsSubsonic', () async {
      final server = _Server((ids) => _shared('https://x'));
      expect(await server.source().links!.webUrl(WebLinkKind.album, MediaId.subsonic('al1')), isNull);
    });
  });

  group('mappers', () {
    test('should_readShareUrl_when_answerHoldsOne', () {
      expect(
        shareUrlFromJson({
          'shares': {
            'share': [
              {'id': '1', 'url': 'https://s/1'},
            ],
          },
        }),
        'https://s/1',
      );
      expect(shareUrlFromJson({'shares': <String, Object?>{}}), isNull);
    });

    test('should_carryEveryCredit_when_songNamesSeveralArtists', () {
      final track = trackFromJson({
        'id': 's1',
        'artist': 'A & B',
        'artistId': 'a',
        'artists': [
          {'id': 'a', 'name': 'A'},
          {'id': 'b', 'name': 'B'},
        ],
      })!;
      expect(track.artistCredits, [ArtistCredit('A', MediaId.subsonic('a')), ArtistCredit('B', MediaId.subsonic('b'))]);
    });

    test('should_keepTheOneArtist_when_songNamesOne', () {
      final track = trackFromJson({
        'id': 's1',
        'artist': 'A',
        'artistId': 'a',
        'artists': [
          {'id': 'a', 'name': 'A'},
        ],
      })!;
      expect(track.artists, isEmpty);
      expect(track.artistCredits, [ArtistCredit('A', MediaId.subsonic('a'))]);
    });

    test('should_markOwnPlaylists_when_ownerIsTheLoginIgnoringCase', () {
      expect(playlistFromJson({'id': 'p', 'name': 'x', 'owner': 'alice'}, currentUsername: 'Alice')!.ownedByMe, isTrue);
      expect(playlistFromJson({'id': 'p', 'name': 'x', 'owner': 'bob'}, currentUsername: 'Alice')!.ownedByMe, isFalse);
      expect(playlistFromJson({'id': 'p', 'name': 'x'}, currentUsername: 'Alice')!.ownedByMe, isNull);
    });
  });
}
