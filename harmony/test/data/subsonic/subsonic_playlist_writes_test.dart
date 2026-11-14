import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';

import 'subsonic_fixtures.dart' as fx;

const _creds = SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'alice', password: 'sesame');

/// endpoint → body; records every request.
final class _Server {
  _Server(this.routes);
  final Map<String, String> routes;
  final requests = <Uri>[];

  Uri last(String endpoint) => requests.lastWhere((u) => u.pathSegments.last == '$endpoint.view');

  MusicPlaylistWrites writes() => SubsonicMusicSource(
    _creds,
    client: MockClient((request) async {
      requests.add(request.url);
      final body = routes[request.url.pathSegments.last.replaceAll('.view', '')];
      return body == null
          ? http.Response('not found', 404)
          : http.Response(body, 200, headers: {'content-type': 'application/json; charset=utf-8'});
    }),
  ).playlistWrites!;
}

MediaId _s(String id) => MediaId.subsonic(id);

void main() {
  test('should_createWithSongsInOrder_when_serverAnswersWithPlaylist', () async {
    final server = _Server({
      'createPlaylist': fx.ok('"playlist": {"id": "pl9", "name": "Road", "owner": "alice", "songCount": 2}'),
    });
    final created = await server.writes().createPlaylist('  Road ', tracks: [_s('a'), _s('b')]);
    expect(created.id, _s('pl9'));
    expect(created.canWrite, isTrue);
    final q = server.last('createPlaylist').queryParametersAll;
    expect(q['name'], ['Road']);
    expect(q['songId'], ['a', 'b']);
  });

  test('should_findNewestOwnedByName_when_olderServerAnswersEmpty', () async {
    final server = _Server({
      'createPlaylist': fx.ok(),
      'getPlaylists': fx.ok(
        '"playlists": {"playlist": [{"id": "p1", "name": "Road", "owner": "bob"}, '
        '{"id": "p2", "name": "Road", "owner": "alice"}]}',
      ),
    });
    expect((await server.writes().createPlaylist('Road')).id, _s('p2'));
  });

  test('should_sendSongIdToAdd_when_appending', () async {
    final server = _Server({'updatePlaylist': fx.ok()});
    await server.writes().addTracksToPlaylist(_s('pl1'), [_s('x'), _s('y')]);
    final q = server.last('updatePlaylist').queryParametersAll;
    expect(q['playlistId'], ['pl1']);
    expect(q['songIdToAdd'], ['x', 'y']);
  });

  test('should_renameAndDelete_when_asked', () async {
    final server = _Server({'updatePlaylist': fx.ok(), 'deletePlaylist': fx.ok()});
    final writes = server.writes();
    await writes.renamePlaylist(_s('pl1'), ' New ');
    expect(server.last('updatePlaylist').queryParameters['name'], 'New');
    await writes.deletePlaylist(_s('pl1'));
    expect(server.last('deletePlaylist').queryParameters['id'], 'pl1');
  });

  test('should_sendEachIndexOnce_when_removing', () async {
    final server = _Server({'updatePlaylist': fx.ok()});
    await server.writes().removeFromPlaylist(_s('pl1'), [3, 1, 3]);
    expect(server.last('updatePlaylist').queryParametersAll['songIndexToRemove'], ['3', '1']);
  });

  test('should_replaceSongsInNewOrder_when_reordering', () async {
    final server = _Server({'createPlaylist': fx.ok()});
    await server.writes().setPlaylistTracks(_s('pl1'), [_s('b'), _s('a')]);
    final q = server.last('createPlaylist').queryParametersAll;
    expect(q['playlistId'], ['pl1']);
    expect(q['songId'], ['b', 'a']);
    expect(q.containsKey('name'), isFalse);
  });

  test('should_refuseBlankName_when_creating', () async {
    final server = _Server({});
    await expectLater(server.writes().createPlaylist('  '), throwsArgumentError);
    expect(server.requests, isEmpty);
  });
}
