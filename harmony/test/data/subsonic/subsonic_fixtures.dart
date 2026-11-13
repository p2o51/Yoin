/// JSON bodies adapted from the iOS shared module's SubsonicFixtures.kt
/// (which came from Android's SubsonicModelsTest / SubsonicMappersTest),
/// plus a few for fields the Harmony mapper reads on top.
library;

String ok([String inner = '']) =>
    '{"subsonic-response":{"status":"ok","version":"1.16.1"${inner.isEmpty ? '' : ',$inner'}}}';

String failed(int code, String message) =>
    '{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}';

const ping =
    '{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",'
    '"serverVersion":"0.55.0","openSubsonic":true}}';

final album = ok('''
"album": {
  "id": "al-1", "name": "Test Album", "artist": "Test Artist", "artistId": "ar-1",
  "coverArt": "al-1", "songCount": 10, "duration": 3600, "year": 2024, "genre": "Rock",
  "starred": "2024-01-01T00:00:00Z", "created": "2023-05-02T10:00:00.000Z", "playCount": 42,
  "releaseTypes": ["Album"], "version": " Deluxe ", "futureField": true, "anotherFuture": [1, 2, 3],
  "song": [
    {"id": "s-2", "title": "Song Two", "track": 1, "discNumber": 2, "duration": 200, "albumId": "al-1", "artistId": "ar-1"},
    {"id": "s-1", "title": "Song One", "album": "Test Album", "artist": "Test Artist",
     "track": 1, "discNumber": 1, "duration": 240, "bitRate": 320, "albumId": "al-1", "artistId": "ar-1"}
  ]
}''');

final albumNoCover = ok('"album": {"id": "al-9", "name": "Bare"}');

final albumList2 = ok('''
"albumList2": {"album": [
  {"id": "al-1", "name": "Album A", "artist": "Artist A", "releaseTypes": ["EP"]},
  {"id": "al-2", "name": "Album B", "artist": "Artist B", "releaseTypes": ["Mixtape", "single"]},
  {"name": "No id, skipped"}
]}''');

final artists = ok('''
"artists": {"ignoredArticles": "The", "index": [
  {"name": "A", "artist": [
    {"id": "ar-1", "name": "ABBA", "albumCount": 5},
    {"id": "ar-2", "name": "AC/DC", "albumCount": 12, "starred": "2024-06-01"}
  ]},
  {"name": "B", "artist": {"id": "ar-3", "name": "Beatles", "albumCount": "20"}}
]}''');

final artist = ok('''
"artist": {"id": "ar-1", "name": "Pink Floyd", "albumCount": 15, "coverArt": "ar-1",
  "album": [
    {"id": "al-1", "name": "The Wall", "year": 1979},
    {"id": "al-2", "name": "Wish You Were Here", "year": 1975}
  ]}''');

final search3 = ok('''
"searchResult3": {
  "artist": [{"id": "ar-1", "name": "Queen"}],
  "album": [{"id": "al-1", "name": "A Night at the Opera"}],
  "song": [{"id": "s-1", "title": "Bohemian Rhapsody", "duration": 354}]
}''');

final starred2 = ok('''
"starred2": {
  "artist": [{"id": "ar-1", "name": "Radiohead", "starred": "2024-06-01"}],
  "album": [{"id": "al-1", "name": "OK Computer", "starred": "2024-06-01"}],
  "song": [{"id": "s-1", "title": "Karma Police", "starred": "2024-06-01"}]
}''');

final randomSongs = ok('''
"randomSongs": {"song": [
  {"id": "s-99", "parent": "al-1", "title": "Full Song", "album": "Full Album", "artist": "Full Artist",
   "displayArtist": "Full Artist & Guest",
   "track": 3, "discNumber": 0, "year": 2023, "genre": "Jazz", "coverArt": "s-99", "size": 8500000,
   "contentType": "audio/flac", "suffix": "FLAC", "duration": 300, "bitRate": 1411,
   "samplingRate": 44100, "bitDepth": 16, "channelCount": 2, "created": "2023-02-03T04:05:06Z",
   "path": "Full Artist/Full Album/03 - Full Song.flac", "albumId": "al-1", "artistId": "ar-1",
   "starred": "2024-01-15T10:30:00Z", "userRating": 5, "playCount": 7},
  {"id": "s-2", "title": "Random Song 2", "duration": 210},
  {"id": "s-3", "title": "Unreadable MP4", "suffix": "m4a", "contentType": "audio/mp4",
   "bitRate": 0, "samplingRate": 0, "bitDepth": 0, "channelCount": 0}
]}''');

final playlists = ok('''
"playlists": {"playlist": [
  {"id": "pl1", "name": "Owned", "owner": "alice", "songCount": 3, "duration": 600},
  {"id": "pl2", "name": "Shared", "owner": "bob", "public": true, "coverArt": "pl-pl2"},
  {"id": "pl3", "name": "Smart", "owner": "alice", "readonly": true},
  {"id": "pl4", "name": "Legacy"}
]}''');

final playlist = ok('''
"playlist": {"id": "pl1", "name": "Owned", "owner": "alice", "comment": "for the road",
  "public": false, "created": "2024-01-01T00:00:00Z", "changed": "2024-02-01T00:00:00Z",
  "entry": [{"id": "s-1", "title": "One"}, {"id": "s-2", "title": "Two"}]}''');

final extensionsWithLyrics = ok('''
"openSubsonicExtensions": [
  {"name": "transcodeOffset", "versions": [1]},
  {"name": "songLyrics", "versions": [1]}
]''');

final extensionsWithoutLyrics = ok('"openSubsonicExtensions": [{"name": "transcodeOffset", "versions": [1]}]');

final structuredLyrics = ok('''
"lyricsList": {"structuredLyrics": [
  {"lang": "eng", "synced": true, "offset": 100, "line": [
    {"start": 5000, "value": "Second line"},
    {"start": 0, "value": "First line"}
  ]},
  {"lang": "xxx", "synced": false, "line": [{"value": "ignored"}]}
]}''');

final unsyncedStructuredLyrics = ok('''
"lyricsList": {"structuredLyrics": [
  {"lang": "und", "synced": false, "line": [{"value": "one"}, {"value": "two"}]}
]}''');

final emptyLyricsList = ok('"lyricsList": {}');

final plainLyrics = ok(
  r'"lyrics": {"artist": "Queen", "title": "Bohemian Rhapsody", "value": "Is this the real life?\nIs this just fantasy?"}',
);

final plainLrcLyrics = ok(
  r'"lyrics": {"artist": "A", "title": "B", "value": "[ar:A]\n[00:02.50]later\n[00:01.00][00:03.00]twice"}',
);

final emptyPlainLyrics = ok('"lyrics": {"artist": "A", "title": "B"}');
