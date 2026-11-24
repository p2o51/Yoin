import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/sync/cloud_data_views.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';

/// Android CloudDataViews.summary / account over the same sync records.
void main() {
  const scope = 's-home';
  var ts = 1000;

  SyncRecordEntity rec(
    String kind,
    String key,
    Map<String, Object?>? payload, {
    String s = scope,
    bool deleted = false,
  }) => SyncRecordEntity(
    kind: kind,
    scope: s,
    key: key,
    kindVersion: 1,
    ts: ts++,
    device: 'd',
    deleted: deleted,
    payload: payload == null ? null : jsonEncode(payload),
  );

  String key(List<String> parts) => SyncFormat.compositeKey(parts);

  final records = [
    rec(SyncKinds.songNote, 'n1', {
      'provider': 'subsonic',
      'trackId': 'subsonic:tr-1',
      'content': 'the bridge',
      'title': 'Paper Lanterns',
      'artist': 'Kiri Ono',
      'positionMs': 61000,
      'updatedAt': 5000,
    }),
    rec(SyncKinds.songNote, 'n-gone', null, deleted: true),
    rec(SyncKinds.albumNote, 'an1', {
      'provider': 'subsonic',
      'albumId': 'al-1',
      'content': 'sticky',
      'albumName': 'Charm',
      'artist': 'Clairo',
    }),
    rec(SyncKinds.trackRating, key(['subsonic', 'tr-1']), {'provider': 'subsonic', 'songId': 'tr-1', 'rating': 8.5}),
    // A real 0 score travels as 0 + zero: true; a bare 0 means "not rated" and isn't counted.
    rec(SyncKinds.trackRating, key(['subsonic', 'tr-2']), {
      'provider': 'subsonic',
      'songId': 'tr-2',
      'rating': 0,
      'zero': true,
    }),
    rec(SyncKinds.trackRating, key(['subsonic', 'tr-3']), {'provider': 'subsonic', 'songId': 'tr-3', 'rating': 0}),
    rec(SyncKinds.albumRating, key(['subsonic', 'al-1']), {'rating': 9}),
    rec(SyncKinds.albumRating, key(['subsonic', 'al-2']), {'rating': 0}),
    rec(SyncKinds.albumReview, key(['subsonic', 'al-1']), {'review': 'Warm.'}),
    rec(SyncKinds.albumReview, key(['subsonic', 'al-3']), {'review': '  '}),
    rec(SyncKinds.albumTitle, key(['subsonic', 'al-1']), {'title': ' The warm year '}),
    rec(SyncKinds.memoryTitle, key(['subsonic', 'album', 'al-1']), {'title': 'Rain on the roof'}),
    rec(SyncKinds.memoryCopy, key(['subsonic', 'album', 'al-1']), {'copy': 'x', 'promptHash': 'h'}),
    rec(SyncKinds.songNote, 'other', {'provider': 'subsonic', 'trackId': 't', 'content': 'elsewhere'}, s: 's-other'),
    rec(SyncKinds.setting, 'seam_top_style', {'value': 'fade'}, s: SyncFormat.globalScope),
  ];

  test('should_countLikeAndroid_when_summarizingAScope', () {
    final summary = CloudDataViews.summary(records);
    expect(summary.keys, unorderedEquals([scope, 's-other']));
    expect(
      summary[scope],
      const CloudScopeCounts(notes: 2, albums: 1, songRatings: 2, memoryTitles: 2),
      reason: 'song + album note; al-1 rated and reviewed once; a real 0 score counts; both titles',
    );
    expect(summary[scope]!.total, 7);
    expect(summary['s-other'], const CloudScopeCounts(notes: 1));
  });

  test('should_listNewestFirstWithNamesFromTheNotes_when_browsingAnAccount', () {
    final content = CloudDataViews.account(records, scope);
    final note = content.notes.single;
    expect(
      [note.id, note.title, note.artist, note.content, note.positionMs, note.updatedAt],
      ['n1', 'Paper Lanterns', 'Kiri Ono', 'the bridge', 61000, 5000],
    );
    final album = content.albums.single;
    expect(
      [album.albumId, album.name, album.artist, album.rating, album.review],
      ['al-1', 'Charm', 'Clairo', 9.0, 'Warm.'],
    );
    expect(content.songRatings.map((r) => (r.trackId, r.title, r.rating)), [
      ('tr-2', null, 0.0),
      ('tr-1', 'Paper Lanterns', 8.5),
    ]);
    expect(content.titles.map((t) => (t.title, t.custom, t.subject)), [
      ('Rain on the roof', false, 'Charm'),
      ('The warm year', true, 'Charm'),
    ]);
  });

  test('should_beEmpty_when_theScopeHoldsNothing', () {
    final content = CloudDataViews.account(records, 's-none');
    expect([content.notes, content.albums, content.songRatings, content.titles], everyElement(isEmpty));
    expect(CloudDataViews.summary(const []), isEmpty);
  });
}
