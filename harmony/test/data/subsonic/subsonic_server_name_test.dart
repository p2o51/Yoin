import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';

import 'subsonic_fixtures.dart' as fx;

const _creds = SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'alice', password: 'sesame');

/// A source whose server answers every request with [body]; [pings] counts the pings.
SubsonicMusicSource _answering(String body, List<String> pings) => SubsonicMusicSource(
  _creds,
  client: MockClient((request) async {
    pings.add(request.url.pathSegments.last);
    return http.Response(body, 200, headers: {'content-type': 'application/json; charset=utf-8'});
  }),
);

/// What a server calls itself in a ping's answer (Android SubsonicModelsTest
/// serverName): the account page's Test shows it under its status.
void main() {
  test('should_nameTheServerWithItsVersion_when_anOpenSubsonicServerAnswersThePing', () {
    final body = {'status': 'ok', 'type': 'navidrome', 'serverVersion': '0.64.2 (1a2b3c4d)', 'openSubsonic': true};
    expect(subsonicServerName(body), 'Navidrome 0.64.2 (1a2b3c4d)');
  });

  test('should_haveNoServerName_when_aPlainSubsonicServerAnswersThePing', () {
    expect(subsonicServerName({'status': 'ok', 'version': '1.16.1'}), isNull);
  });

  test('should_nameTheServerByWhatItSends_when_typeOrVersionIsMissing', () {
    expect(subsonicServerName({'type': 'gonic'}), 'Gonic');
    expect(subsonicServerName({'serverVersion': ' 0.64.2 '}), '0.64.2');
    expect(subsonicServerName({'type': ' ', 'serverVersion': ''}), isNull);
    expect(subsonicServerName({'type': 7, 'serverVersion': null}), isNull);
  });

  test('should_capitaliseEachWordAndKeepTheRest_when_theTypeHasSeveralWords', () {
    expect(subsonicServerName({'type': 'nextcloud  music', 'serverVersion': '2.1'}), 'Nextcloud Music 2.1');
    expect(subsonicServerName({'type': 'LMS', 'serverVersion': '3.60'}), 'LMS 3.60');
  });

  // Android SubsonicServerNameTest: one ping, answered with what the server calls itself.
  test('should_answerWithTheServersName_when_anOpenSubsonicServerIsReached', () async {
    final pings = <String>[];
    final source = _answering(fx.ping, pings);
    expect(await source.serverIdentity!.pingServerName(), 'Navidrome 0.55.0');
    expect(pings, ['ping.view']);
  });

  test('should_answerWithNoName_when_aPlainSubsonicServerIsReached', () async {
    final source = _answering(fx.ok(), []);
    expect(await source.serverIdentity!.pingServerName(), isNull);
  });

  test('should_failAsPingDoes_when_theServerRefusesTheSignIn', () async {
    final source = _answering(fx.failed(40, 'Wrong username or password'), []);
    await expectLater(source.serverIdentity!.pingServerName(), throwsA(isA<SourceAuthException>()));
  });
}
