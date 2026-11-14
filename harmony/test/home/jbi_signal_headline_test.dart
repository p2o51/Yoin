import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_memory_signals.dart';
import 'package:yoin/home/home_strings.dart';
import 'package:yoin/home/jump_back_in.dart';

/// Android (HomeViewModel.notedTrackCard / formatMemoryScore +
/// HomeWidgetGrid.shownRating) heads an unrated note card with N/A (暂无):
/// the rating text is never null there, only marked unavailable.
void main() {
  const zh = HomeStrings.forLanguage(chinese: true);
  const en = HomeStrings.forLanguage(chinese: false);

  SongNote note() => SongNote(
        id: 'n1',
        trackId: MediaId.subsonic('t1'),
        title: 'Harbor Lights',
        artist: 'The Meridians',
        content: 'QA note test',
        createdAt: 0,
        updatedAt: 0,
      );

  test('should_readNotAvailable_when_theNotedSongHasNoRating', () {
    final card = noteWidgetCard(note());
    expect(jbiSignalHeadline(card.signal!, zh), '暂无');
    expect(jbiSignalHeadline(card.signal!, en), 'N/A');
  });

  test('should_readTheScore_when_theNotedSongIsRated', () {
    final card = noteWidgetCard(note(), rating: 8.5);
    expect(jbiSignalHeadline(card.signal!, zh), '8.5');
  });
}
