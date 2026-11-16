import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/copy/memory_copy.dart';
import 'package:yoin/memories/copy/memory_voice.dart';

MemoryDay d(int y, int m, int day) => DateTime.utc(y, m, day);

/// Android MemoryVoice.narrationBrief: the listening facts the AI may narrate from.
void main() {
  final today = d(2026, 11, 14);

  test('should_listListeningFacts_when_noReview', () {
    const top = MemoryCopyTrack(number: 3, title: 'Undertow', rating: 9);
    final input = MemoryCopyInput(
      albumName: 'Blue',
      tracks: const [
        MemoryCopyTrack(number: 1, title: 'Intro', rating: 7),
        top,
      ],
      ratedTracks: 2,
      totalTracks: 10,
      albumScore: 8.5,
      listening: MemoryListening(plays: 12, firstHeard: d(2026, 2, 1), lastHeard: d(2026, 11, 12)),
      notes: [
        MemoryCopyNote(text: 'a', writtenOn: d(2026, 3, 1), track: top),
        MemoryCopyNote(text: 'b', writtenOn: d(2026, 3, 5), track: top),
      ],
    );
    final voice = MemoryVoice.composeIn(input, today, MemoryProseLanguage.en);
    final brief = MemoryVoice.narrationBrief(input, voice, today)!;
    expect(brief.languageName, 'English');
    expect(brief.facts, [
      'Plays in Yoin: 12',
      'Listening since: February',
      'Last played: two days ago',
      'Seasons between the first and the latest play: 3',
      'Tracks rated: 2 of 10',
      'Highest-rated track: Undertow, 9.0',
      'Album score the listener gave: 8.5',
      'Notes written: 2, over 5 days; the latest is on the track Undertow',
    ]);
    expect(brief.signal, startsWith('EN\n\n'));
  });

  test('should_quoteTrackInChineseMarks_when_zh', () {
    const top = MemoryCopyTrack(number: 3, title: 'Undertow', rating: 9);
    const input = MemoryCopyInput(albumName: 'Blue', tracks: [top], ratedTracks: 1, totalTracks: 1);
    final voice = MemoryVoice.composeIn(input, today, MemoryProseLanguage.zh);
    final brief = MemoryVoice.narrationBrief(input, voice, today)!;
    expect(brief.languageName, 'Simplified Chinese');
    expect(brief.facts, contains('Highest-rated track: 《Undertow》, 9.0'));
  });

  test('should_giveNoBrief_when_reviewOnCardOrNothingToSay', () {
    const reviewed = MemoryCopyInput(albumName: 'Blue', review: 'Mine.', ratedTracks: 1, totalTracks: 2);
    expect(
      MemoryVoice.narrationBrief(reviewed, MemoryVoice.composeIn(reviewed, today, MemoryProseLanguage.en), today),
      isNull,
    );
    const empty = MemoryCopyInput(albumName: 'Blue');
    expect(
      MemoryVoice.narrationBrief(empty, MemoryVoice.composeIn(empty, today, MemoryProseLanguage.en), today),
      isNull,
    );
  });
}
