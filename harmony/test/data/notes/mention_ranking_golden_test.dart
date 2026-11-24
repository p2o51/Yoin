// The Dart @ popup ranking against the shared golden vectors, which the
// Android app's Kotlin writes and checks
// (app/src/test/.../ui/notes/editor/MentionRankingGoldenTest.kt). A failure
// here means the port drifted from Android; regenerate the file only from the
// Kotlin side. Pinyin differs by platform, so both rank with the file's own
// "romanizations" table.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/notes/mention_ranking.dart';
import 'package:yoin/data/notes/note_markup.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/mention-ranking.json';

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  List<Map<String, dynamic>> section(String name) => (golden[name] as List).cast<Map<String, dynamic>>();
  final table = (golden['romanizations'] as Map).cast<String, String>();
  String? romanizer(String name) => table[name];

  MentionCandidate row(MentionKind kind, Map<String, dynamic> json) {
    final artist = kind == MentionKind.artist ? null : json['artist'] as String?;
    return MentionCandidate(
      mention: NoteMention(
        kind: kind,
        provider: 'subsonic',
        id: json['id'] as String,
        title: json['title'] as String,
        artist: artist,
      ),
      artist: artist,
      source: MentionSource.values.firstWhere((s) => s.name.toLowerCase() == (json['source'] as String).toLowerCase()),
    );
  }

  MentionCandidates rows(Map<String, dynamic> json) => MentionCandidates(
    tracks: [for (final r in json['tracks'] as List) row(MentionKind.track, r as Map<String, dynamic>)],
    albums: [for (final r in json['albums'] as List) row(MentionKind.album, r as Map<String, dynamic>)],
    artists: [for (final r in json['artists'] as List) row(MentionKind.artist, r as Map<String, dynamic>)],
  );

  String kindName(MentionKind kind) => switch (kind) {
    MentionKind.track => 'Track',
    MentionKind.album => 'Album',
    MentionKind.artist => 'Artist',
  };

  String sourceName(MentionSource s) => s.name[0].toUpperCase() + s.name.substring(1);

  Map<String, Object?> ranked(MentionCandidates r) => {
    'order': [for (final k in r.order) kindName(k)],
    'tracks': [for (final c in r.tracks) c.mention.id],
    'albums': [for (final c in r.albums) c.mention.id],
    'artists': [for (final c in r.artists) c.mention.id],
    'all': [for (final c in r.all) c.mention.id],
    'sources': [for (final c in r.all) sourceName(c.source)],
  };

  String tierName(MentionTier t) => switch (t) {
    MentionTier.titlePrefix => 'TitlePrefix',
    MentionTier.initials => 'Initials',
    MentionTier.titleSubstring => 'TitleSubstring',
    MentionTier.artist => 'Artist',
    MentionTier.none => 'None',
  };

  test('should_carryEverySection_when_loadingTheGolden', () {
    expect(section('fold'), isNotEmpty);
    expect(section('tier').length, greaterThan(1000));
    expect(section('rank'), isNotEmpty);
    expect(section('live'), isNotEmpty);
  });

  test('should_foldAndCompactAsKotlin_when_readingEveryFoldVector', () {
    for (final v in section('fold')) {
      final text = v['text'] as String;
      expect(MentionRanking.fold(text), v['fold'], reason: 'fold ${jsonEncode(text)}');
      expect(MentionRanking.compact(text), v['compact'], reason: 'compact ${jsonEncode(text)}');
    }
  });

  test('should_tierAsKotlin_when_readingEveryTierVector', () {
    for (final v in section('tier')) {
      final kind = MentionKind.values.firstWhere((k) => kindName(k) == v['kind']);
      final candidate = row(kind, {'id': 'x', 'title': v['title'], 'artist': v['artist'], 'source': 'Search'});
      expect(
        tierName(MentionRanking.tier(v['query'] as String, candidate, romanizer)),
        v['tier'],
        reason: 'tier(${jsonEncode(v['query'])}, ${jsonEncode(v['title'])} / ${v['artist']})',
      );
    }
  });

  group('rank', () {
    for (final (index, v) in section('rank').indexed) {
      test('should_rankAsKotlin_when_readingRankVector$index ${jsonEncode(v['query'])}', () {
        final out = MentionRanking.rank(v['query'] as String, rows(v['rows'] as Map<String, dynamic>), romanizer);
        expect(ranked(out), v['ranked']);
      });
    }
  });

  group('live filtering', () {
    for (final (index, v) in section('live').indexed) {
      test('should_filterAsKotlin_when_readingLiveVector$index ${jsonEncode(v['query'])}', () {
        final query = v['query'] as String;
        final pool = rows(v['pool'] as Map<String, dynamic>);
        final found = rows(v['found'] as Map<String, dynamic>);
        expect(ranked(mentionRowsWhileSearching(query, pool, romanizer)), v['whileSearching'], reason: 'while');
        expect(ranked(mentionRowsAfterSearch(query, found, pool, romanizer)), v['afterSearch'], reason: 'after');
      });
    }
  });

  group('pinyin', () {
    test('should_spellChineseNamesAsSyllables_when_romanizingWithThePackage', () {
      expect(PinyinMentionRomanizer.romanize('纽约'), 'niu yue');
      expect(PinyinMentionRomanizer.romanize('周杰伦'), 'zhou jie lun');
      expect(PinyinMentionRomanizer.romanize('New York'), isNull);
    });

    test('should_rankChineseTitlesByPinyin_when_usingThePackage', () {
      final rows = MentionCandidates(
        tracks: [
          for (final t in ['晴天', '纽约', 'Nuyorican Soul'])
            MentionCandidate(
              mention: NoteMention(kind: MentionKind.track, provider: 'subsonic', id: t, title: t),
            ),
        ],
      );
      String top(String q) => MentionRanking.rank(q, rows, PinyinMentionRomanizer.romanize).all.first.mention.title;
      expect(top("niu'yue"), '纽约');
      expect(top('ny'), '纽约');
      expect(top('qt'), '晴天');
    });
  });
}
