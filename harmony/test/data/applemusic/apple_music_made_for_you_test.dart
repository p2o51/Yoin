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

/// Home's Made For You on Apple Music (Android AppleMusicMadeForYouTest): the
/// personal mixes (`pl.pm-…`) among the recommendations, read and opened
/// with the account's own token.
void main() {
  final devToken = fx.fakeDeveloperToken(exp: DateTime.now().millisecondsSinceEpoch ~/ 1000 + 3600);
  late Map<String, String> routes;
  late List<http.Request> requests;

  AppleMusicSource source() => AppleMusicSource(
    _creds,
    client: MockClient((request) async {
      requests.add(request);
      if (request.url.toString() == _tokenUrl) return http.Response('{"developerToken":"$devToken"}', 200);
      final body = routes[request.url.path];
      return body == null
          ? http.Response(fx.appleNotFound, 404)
          : http.Response(body, 200, headers: {'content-type': 'application/json; charset=utf-8'});
    }),
  );

  http.Request requestTo(String path) => requests.singleWhere((r) => r.url.path == path);

  setUp(() {
    requests = [];
    routes = {'/v1/me/storefront': fx.storefront};
  });

  test('should_listPersonalMixesInApplesOrder_when_recommendationsHaveThem', () async {
    routes['/v1/me/recommendations'] = '''
{ "data": [
  { "id": "6-27s5hU6azhJY", "type": "personal-recommendation",
    "attributes": { "title": { "stringForDisplay": "为你推荐" } },
    "relationships": { "contents": { "data": [
      { "id": "pl.pm-favorites", "type": "playlists", "attributes": {
        "name": "Favorites Mix", "curatorName": "Apple Music",
        "description": { "short": "Updated Wednesdays", "standard": "The songs you love." },
        "artwork": { "url": "https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg", "width": 1080, "height": 1080 }
      } },
      { "id": "pl.pm-newmusic", "type": "playlists", "attributes": { "name": "New Music Mix" } },
      { "id": "ra.pm-station", "type": "stations", "attributes": { "name": "Your Station" } }
    ] } } },
  { "id": "6-editorial", "type": "personal-recommendation",
    "relationships": { "contents": { "data": [
      { "id": "pl.u-editorial", "type": "playlists", "attributes": { "name": "Today's Hits" } },
      { "id": "1440", "type": "albums", "attributes": { "name": "An album" } },
      { "id": "pl.pm-favorites", "type": "playlists", "attributes": { "name": "Favorites Mix" } }
    ] } } }
] }''';

    final mixes = await source().madeForYou!.madeForYou();

    expect(mixes.map((m) => m.title), ['Favorites Mix', 'New Music Mix']);
    final favorites = mixes.first;
    expect(favorites.id, MediaId(MediaId.providerAppleMusic, 'pl.pm-favorites'));
    expect(favorites.subtitle, 'Updated Wednesdays');
    expect(favorites.cover, const CoverUrl('https://is1-ssl.mzstatic.com/image/600x600bb.jpg'));
    // The recommendations are the user's own.
    expect(requestTo('/v1/me/recommendations').headers['Music-User-Token'], _userToken);
  });

  test('should_readBareMixesFromTheCatalog_when_listedWithoutAttributes', () async {
    routes['/v1/me/recommendations'] = '''
{ "data": [ { "id": "6-1", "type": "personal-recommendation", "relationships": { "contents": { "data": [
  { "id": "pl.pm-chill", "type": "playlists", "href": "/v1/catalog/jp/playlists/pl.pm-chill" },
  { "id": "pl.pm-favorites", "type": "playlists", "attributes": { "name": "Favorites Mix" } },
  { "id": "pl.pm-gone", "type": "playlists" }
] } } } ] }''';
    routes['/v1/catalog/jp/playlists'] =
        '{ "data": [ { "id": "pl.pm-chill", "type": "playlists", "attributes": { "name": "Chill Mix" } } ] }';

    final mixes = await source().madeForYou!.madeForYou();

    // Apple's order; a mix the catalog doesn't return is left out.
    expect(mixes.map((m) => m.title), ['Chill Mix', 'Favorites Mix']);
    final catalog = requestTo('/v1/catalog/jp/playlists');
    expect(catalog.url.queryParameters['ids'], 'pl.pm-chill,pl.pm-gone');
    expect(catalog.headers['Music-User-Token'], _userToken);
  });

  test('should_readTheBareTracklistWithTheUserToken_when_openingAPersonalMix', () async {
    routes['/v1/catalog/jp/playlists/pl.pm-chill'] =
        '{ "data": [ { "id": "pl.pm-chill", "type": "playlists", '
        '"attributes": { "name": "Chill Mix", "curatorName": "Apple Music" } } ] }';
    routes['/v1/catalog/jp/playlists/pl.pm-chill/tracks'] = '''
{ "data": [
  { "id": "1001", "type": "songs", "attributes": { "name": "see through", "artistName": "Amelia Moore", "albumName": "EP", "durationInMillis": 179000 } },
  { "id": "1002", "type": "songs", "attributes": { "name": "Roundabout", "artistName": "Fcj", "albumName": "Single", "durationInMillis": 132000 } }
] }''';

    final playlist = await source().library.getPlaylist(MediaId(MediaId.providerAppleMusic, 'pl.pm-chill'));

    expect(playlist!.name, 'Chill Mix');
    expect(playlist.tracks.map((t) => t.title), ['see through', 'Roundabout']);
    final tracks = requestTo('/v1/catalog/jp/playlists/pl.pm-chill/tracks');
    expect(tracks.url.queryParameters.containsKey('include'), isFalse);
    for (final r in requests.where((r) => r.url.path.startsWith('/v1/catalog/jp/playlists/pl.pm-'))) {
      expect(r.headers['Music-User-Token'], _userToken, reason: r.url.path);
    }
  });

  test('should_keepTheSongsInclude_when_openingAnotherCatalogPlaylist', () async {
    routes['/v1/catalog/jp/playlists/pl.u-editorial'] =
        '{ "data": [ { "id": "pl.u-editorial", "type": "playlists", "attributes": { "name": "Hits" } } ] }';
    routes['/v1/catalog/jp/playlists/pl.u-editorial/tracks'] = '{ "data": [] }';

    await source().library.getPlaylist(MediaId(MediaId.providerAppleMusic, 'pl.u-editorial'));

    final tracks = requestTo('/v1/catalog/jp/playlists/pl.u-editorial/tracks');
    expect(tracks.url.queryParameters['include'], 'albums,artists');
    expect(tracks.headers.containsKey('Music-User-Token'), isFalse);
  });

  test('should_roundTripASavedCard_when_storedAndReadBack', () {
    final mix = MadeForYouPlaylist(
      id: MediaId(MediaId.providerAppleMusic, 'pl.pm-x'),
      title: 'Chill Mix',
      subtitle: 's',
      cover: CoverUrl('https://a.test/600x600bb.jpg'),
    );
    expect(MadeForYouPlaylist.fromJson(mix.toJson()), mix);
    expect(MadeForYouPlaylist.fromJson({'id': 'x', 'provider': 'applemusic'}), isNull);
  });
}
