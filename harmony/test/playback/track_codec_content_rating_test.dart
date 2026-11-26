// The explicit / clean mark through the player item's extra (Now Playing's "E" after the UI is re-created):
// kept by name, and an item written before it reads as unknown.
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/playback/track_codec.dart';

void main() {
  test('should_keepRating_when_trackRoundTrips', () {
    for (final rating in [ContentRating.explicit, ContentRating.clean, null]) {
      final t = Track(id: MediaId.subsonic('s1'), title: 'HUMBLE.', contentRating: rating);
      expect(TrackCodec.decode(TrackCodec.encode(t))!.contentRating, rating);
    }
  });

  test('should_readUnknown_when_itemPredatesRatings', () {
    final old = jsonEncode({'id': 'subsonic:s1', 'title': 'HUMBLE.'});
    final t = TrackCodec.decode(old)!;
    expect(t.title, 'HUMBLE.');
    expect(t.contentRating, isNull);
    expect(TrackCodec.decode(jsonEncode({'id': 'subsonic:s1', 'cr': 'Restricted'}))!.contentRating, isNull);
  });

  test('should_keepRating_when_favouriteChanges', () {
    final t = Track(id: MediaId.subsonic('s1'), contentRating: ContentRating.explicit);
    expect(t.copyWith(isStarred: true).isExplicit, isTrue);
  });
}
