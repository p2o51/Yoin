import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/applemusic/apple_music_source.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';
import 'package:yoin/data/sync/identity/account_keys.dart';
import 'package:yoin/data/sync/identity/account_fingerprints.dart';
import 'package:yoin/data/sync/sync_database.dart';

import 'applemusic/apple_music_fixtures.dart' as am;
import 'subsonic/subsonic_fixtures.dart' as fx;

/// One song by id (MusicTrackLookup, Android MusicLibrary.getTrack) — what
/// Continue On reads a handed-off queue back with — and AccountKeys, which
/// matches the handed-off account.
void main() {
  group('Subsonic getTrack', () {
    final requests = <Uri>[];
    SubsonicMusicSource source(String body) => SubsonicMusicSource(
      const SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'alice', password: 'sesame'),
      client: MockClient((request) async {
        requests.add(request.url);
        return http.Response(body, 200, headers: {'content-type': 'application/json; charset=utf-8'});
      }),
    );

    setUp(requests.clear);

    test('should_readOneSong_when_askedById', () async {
      final s = source(fx.ok('"song":{"id":"t7","title":"Song","artist":"Artist","album":"Album","duration":180}'));
      final track = await s.trackLookup!.getTrack(MediaId.subsonic('t7'));
      expect(track?.id, MediaId.subsonic('t7'));
      expect(track?.title, 'Song');
      expect(track?.durationSec, 180);
      expect(requests.single.pathSegments.last, 'getSong.view');
      expect(requests.single.queryParameters['id'], 't7');
    });

    test('should_returnNull_when_theSongIsGone', () async {
      final s = source(fx.failed(70, 'Song not found'));
      expect(await s.trackLookup!.getTrack(MediaId.subsonic('gone')), isNull);
    });
  });

  group('Apple Music getTrack', () {
    final devToken = am.fakeDeveloperToken(exp: DateTime.now().millisecondsSinceEpoch ~/ 1000 + 3600);
    final requests = <http.Request>[];
    AppleMusicSource source(Map<String, String> routes) => AppleMusicSource(
      const AppleMusicCredentials(endpoint: 'https://token.example.com/token', musicUserToken: 'u'),
      client: MockClient((request) async {
        requests.add(request);
        if (request.url.host == 'token.example.com') return http.Response('{"developerToken":"$devToken"}', 200);
        final body = {'/v1/me/storefront': am.storefront, ...routes}[request.url.path];
        return body == null
            ? http.Response(am.appleNotFound, 404)
            : http.Response.bytes(utf8.encode(body), 200, headers: {'content-type': 'application/json'});
      }),
    );

    setUp(requests.clear);

    test('should_readACatalogSong_when_askedByCatalogId', () async {
      final s = source({
        '/v1/catalog/jp/songs/1501': am.doc({
          'data': [am.catalogSong('1501')],
        }),
      });
      final track = await s.trackLookup!.getTrack(MediaId(MediaId.providerAppleMusic, '1501'));
      expect(track?.id.rawId, '1501');
      expect(track?.title, 'Song 1501');
      final read = requests.lastWhere((r) => r.url.path == '/v1/catalog/jp/songs/1501');
      expect(read.url.queryParameters['include'], 'albums,artists');
    });

    test('should_returnNull_when_appleHasNoSuchSong', () async {
      final s = source(const {});
      expect(await s.trackLookup!.getTrack(MediaId(MediaId.providerAppleMusic, '999')), isNull);
    });
  });

  group('AccountKeys', () {
    const profile = Profile(id: 'p1', provider: MediaId.providerSubsonic, displayName: 'Home');
    const creds = SubsonicCredentials(baseUrl: 'https://Music.Example.com/', username: 'Alice', password: 'x');
    final fingerprint = AccountFingerprints.subsonic(creds.baseUrl, creds.username)!;

    SyncBindingEntity binding({String state = SyncBindingEntity.stateActive, String? hash}) => SyncBindingEntity(
      localProfileId: 'p1',
      syncProfileId: 's-bound',
      provider: MediaId.providerSubsonic,
      fingerprintHash: hash,
      state: state,
      boundAt: 0,
    );

    AccountKeys keys({SyncBindingEntity? bound, ProfileCredentials? credentials = creds}) =>
        AccountKeys(binding: (_) async => bound, credentials: (_) async => credentials);

    test('should_useTheSyncId_when_theBindingIsActive', () async {
      expect(await keys(bound: binding(hash: AccountFingerprints.fingerprintHash(fingerprint))).of(profile), 's-bound');
    });

    test('should_mintTheSubsonicId_when_notSyncing', () async {
      expect(await keys().of(profile), AccountFingerprints.deterministicId(fingerprint));
    });

    test('should_ignoreTheBinding_when_theServerChangedSinceItWasMade', () async {
      final key = await keys(bound: binding(hash: AccountFingerprints.fingerprintHash('another'))).of(profile);
      expect(key, AccountFingerprints.deterministicId(fingerprint));
      final paused = await keys(bound: binding(state: SyncBindingEntity.statePausedMismatch)).of(profile);
      expect(paused, AccountFingerprints.deterministicId(fingerprint));
    });

    test('should_haveNoKey_when_anAppleMusicAccountIsNotLinked', () async {
      const apple = AppleMusicCredentials(endpoint: 'https://t', musicUserToken: 'u');
      expect(await keys(credentials: apple).of(profile), isNull);
      // Linked by hand: its binding's id, with no fingerprint to check.
      expect(await keys(credentials: apple, bound: binding()).of(profile), 's-bound');
    });
  });
}
