import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';

void main() {
  final id = MediaId.subsonic('pl-1');

  test('should_showPlaceholder_when_playlistIsEmpty', () {
    // Subsonic maps a playlist's cover to its own id; for an empty one the
    // server draws a black square, so nothing is requested at all.
    final empty = Playlist(id: id, name: 'QA list', coverArt: CoverSourceRelative('pl-1'), songCount: 0);
    expect(playlistHeroCover(empty), isNull);
    expect(PlaylistDetailContent(empty).heroCover, isNull);
  });

  test('should_keepOwnCover_when_playlistHasSongs', () {
    final full = Playlist(id: id, name: 'Late Night Drive', coverArt: CoverSourceRelative('pl-1'), songCount: 11);
    expect(playlistHeroCover(full), CoverSourceRelative('pl-1'));
  });

  test('should_keepOwnCover_when_songCountUnknown', () {
    final unknown = Playlist(id: id, name: 'Mix', coverArt: CoverSourceRelative('pl-1'));
    expect(playlistHeroCover(unknown), CoverSourceRelative('pl-1'));
  });

  test('should_useFirstTrackCover_when_countSaysEmptyButRowsArrived', () {
    final rows = Playlist(
      id: id,
      name: 'Stale count',
      songCount: 0,
      tracks: [Track(id: MediaId.subsonic('s1'), albumId: MediaId.subsonic('al-1'))],
    );
    expect(playlistHeroCover(rows), CoverSourceRelative('al-1'));
  });
}
