import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/cards/play_card.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/platform/cards/cards_channel.dart';

ActivityEvent _event(ActivityEntityType type, String id, int ts, {ActivityActionType action = ActivityActionType.played}) =>
    ActivityEvent(entityType: type, actionType: action, entityId: id, title: 'T $id', timestampMs: ts);

void main() {
  group('selectPlayEvents', () {
    test('should_keepOnlyPlayedAlbumsAndPlaylists_when_logHasVisitsSongsAndArtists', () {
      final out = selectPlayEvents([
        _event(ActivityEntityType.album, 'a1', 10),
        _event(ActivityEntityType.album, 'a2', 20, action: ActivityActionType.visited),
        _event(ActivityEntityType.song, 's1', 30),
        _event(ActivityEntityType.artist, 'r1', 40),
        _event(ActivityEntityType.playlist, 'p1', 50),
      ]);
      expect(out.map((e) => e.entityId), ['p1', 'a1']);
    });

    test('should_orderNewestFirst_when_eventsArriveOutOfOrder', () {
      final out = selectPlayEvents([
        _event(ActivityEntityType.album, 'old', 1),
        _event(ActivityEntityType.album, 'new', 3),
        _event(ActivityEntityType.album, 'mid', 2),
      ]);
      expect(out.map((e) => e.entityId), ['new', 'mid', 'old']);
    });

    test('should_listEachEntityOnce_when_itWasPlayedTwice', () {
      final out = selectPlayEvents([
        _event(ActivityEntityType.album, 'a1', 1),
        _event(ActivityEntityType.album, 'a1', 5),
        _event(ActivityEntityType.playlist, 'a1', 3),
      ]);
      expect(out.map((e) => '${e.entityType.name}:${e.entityId}:${e.timestampMs}'), ['album:a1:5', 'playlist:a1:3']);
    });

    test('should_stopAtTheLimit_when_moreItemsWerePlayed', () {
      final out = selectPlayEvents([for (var i = 0; i < 10; i++) _event(ActivityEntityType.album, 'a$i', i)]);
      expect(out, hasLength(playCardLimit));
      expect(out.first.entityId, 'a9');
    });
  });

  group('CardAction.fromMap', () {
    test('should_parseOpenAndPlay_when_entityAndIdArePresent', () {
      final open = CardAction.fromMap({'kind': 'open', 'entity': 'album', 'id': 'subsonic:al-1'});
      final play = CardAction.fromMap({'kind': 'play', 'entity': 'playlist', 'id': 'subsonic:pl-1'});
      expect(open, isA<CardOpen>().having((a) => a.id, 'id', 'subsonic:al-1'));
      expect(play, isA<CardPlay>().having((a) => a.entity, 'entity', 'playlist'));
    });

    test('should_returnNull_when_kindIsUnknownOrIdMissing', () {
      expect(CardAction.fromMap({'kind': 'open', 'entity': 'album'}), isNull);
      expect(CardAction.fromMap({'kind': 'shuffle'}), isNull);
      expect(CardAction.fromMap({'kind': 'app'}), isA<CardOpenApp>());
    });
  });
}
