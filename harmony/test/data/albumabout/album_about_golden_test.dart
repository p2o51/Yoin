// The Dart album About core (lib/data/albumabout/) against the shared golden
// vectors, which :shared Kotlin writes through YoinAlbumAbout
// (shared/src/androidHostTest/.../albumabout/AlbumAboutGoldenTest.kt) and
// replays on the JVM and the iOS simulator. A failure here means the port
// drifted from Android; regenerate the file only from the Kotlin side.
//
// Each vector is its inputs followed by the answer; every output field is
// rendered here in the same JSON shape as AlbumAboutGolden.outputs and
// compared exactly.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/albumabout/album_about.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/album-about.json';

const _sections = [
  'searchLeads', 'blurbLength', 'searchQueries', 'prompts', 'replies', 'credits', 'cleanValue', 'cleanBlurb', //
  'encodeCredits', 'decodeCredits', 'content', 'questionKey', 'creditsLine', 'unbreakable', 'editorialNotes',
  'plainTextFromHtml', 'aiCopy',
];

typedef _Json = Map<String, dynamic>;

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as _Json;
  final labels = {for (final e in (golden['labels'] as _Json).entries) e.key: _labelsOf(e.value as _Json)};

  test('should_matchKotlinConstants_when_read', () {
    expect(golden['constants'], {
      'maxPromptTracks': YoinAlbumAbout.maxPromptTracks,
      'maxNotesChars': YoinAlbumAbout.maxNotesChars,
      'maxNames': YoinAlbumAbout.maxNames,
      'kindCanonical': YoinAlbumAbout.kindCanonical,
      'kindAsk': YoinAlbumAbout.kindAsk,
      'canonCredits': YoinAlbumAbout.canonCredits,
      'canonBlurb': YoinAlbumAbout.canonBlurb,
      'creditItemSeparator': YoinAlbumAbout.creditItemSeparator,
    });
  });

  test('should_coverEverySection_when_read', () {
    for (final section in _sections) {
      expect(golden[section], isA<List<dynamic>>(), reason: 'missing section $section');
    }
    final total = _sections.fold<int>(0, (n, s) => n + (golden[s] as List).length);
    expect(total, 239);
  });

  for (final section in _sections) {
    group(section, () {
      final vectors = (golden[section] as List).cast<_Json>();
      for (var i = 0; i < vectors.length; i++) {
        final vector = vectors[i];
        final name = vector['name'] as String?;
        test('should_answerAsKotlin_when_${section}_$i${name == null ? '' : ' ($name)'}', () {
          final actual = _outputs(section, vector, labels);
          for (final entry in actual.entries) {
            expect(entry.value, vector[entry.key], reason: '$section[$i] ${name ?? ''} ${entry.key}');
          }
        });
      }
    });
  }
}

/// What the Dart facade answers for one vector's inputs (AlbumAboutGolden.outputs).
_Json _outputs(String section, _Json v, Map<String, AlbumCreditLabels> labels) {
  switch (section) {
    case 'searchLeads':
      return {'lead': YoinAlbumAbout.searchLead(v['builtInSearch'] as bool, v['searchedPages'] as int)};
    case 'blurbLength':
      return {'length': YoinAlbumAbout.blurbLength(v['targetLanguage'] as String)};
    case 'searchQueries':
      return {'queries': YoinAlbumAbout.searchQueries(_groundingOf(v['grounding'] as _Json), v['count'] as int)};
    case 'prompts':
      final grounding = _groundingOf(v['grounding'] as _Json);
      final language = v['targetLanguage'] as String;
      final lead = v['searchLead'] as String;
      final question = v['question'] as String?;
      return {
        'creditsOnly': YoinAlbumAbout.isCreditsOnly(grounding),
        'groundingLines': YoinAlbumAbout.groundingLines(grounding),
        'about': YoinAlbumAbout.aboutPrompt(grounding, language, lead),
        'ask': question == null ? null : YoinAlbumAbout.askPrompt(grounding, question, language, lead),
      };
    case 'replies':
      final raw = v['raw'] as String;
      return {
        'parsed': _replyJson(YoinAlbumAbout.parseReply(raw)),
        'stored': _replyJson(YoinAlbumAbout.storedReply(raw, hasServiceNotes: v['hasServiceNotes'] as bool)),
      };
    case 'credits':
      return {'credits': _creditsJson(YoinAlbumAbout.parseCredits(v['text'] as String?))};
    case 'cleanValue':
      return {'clean': YoinAlbumAbout.cleanValue(v['value'] as String)};
    case 'cleanBlurb':
      return {'clean': YoinAlbumAbout.cleanBlurb(v['text'] as String?)};
    case 'encodeCredits':
      return {'encoded': YoinAlbumAbout.encodeCredits(_creditsOf(v['credits'] as _Json))};
    case 'decodeCredits':
      return {'credits': _creditsJson(YoinAlbumAbout.decodeCredits(v['text'] as String?))};
    case 'content':
      return {
        'content': _contentJson(YoinAlbumAbout.content([for (final r in v['rows'] as List) _rowOf(r as _Json)])),
      };
    case 'questionKey':
      return {'key': YoinAlbumAbout.questionKey(v['question'] as String)};
    case 'creditsLine':
      final items = YoinAlbumAbout.creditItems(
        sourceLabels: _strings(v['sourceLabels']),
        releaseDate: v['releaseDate'] as String?,
        originalReleaseDate: v['originalReleaseDate'] as String?,
        ai: _creditsOf(v['ai'] as _Json),
      );
      final line = YoinAlbumAbout.creditsLine(items, labels[v['labels'] as String]!, v['dateText'] as String?);
      return {
        'items': [for (final i in items) _itemJson(i)],
        'line': line == null ? null : _lineJson(line),
      };
    case 'unbreakable':
      return {'text': YoinAlbumAbout.unbreakable(v['label'] as String)};
    case 'editorialNotes':
      return {'notes': YoinAlbumAbout.editorialNotes(v['standard'] as String?, v['short'] as String?)};
    case 'plainTextFromHtml':
      return {'text': YoinAlbumAbout.plainTextFromHtml(v['html'] as String)};
    case 'aiCopy':
      return {'text': YoinAlbumAbout.aiCopy(v['template'] as String, v['name'] as String)};
  }
  throw ArgumentError('unknown section $section');
}

List<String> _strings(Object? value) => value == null ? const [] : (value as List).cast<String>().toList();

AlbumAboutGrounding _groundingOf(_Json o) => AlbumAboutGrounding(
  album: o['album'] as String,
  artist: o['artist'] as String,
  version: o['version'] as String?,
  releaseDate: o['releaseDate'] as String?,
  originalReleaseDate: o['originalReleaseDate'] as String?,
  labels: _strings(o['labels']),
  releaseType: o['releaseType'] as String?,
  genre: o['genre'] as String?,
  tracks: _strings(o['tracks']),
  totalDurationSec: o['totalDurationSec'] as int?,
  serviceNotes: o['serviceNotes'] as String?,
);

_Json _creditsJson(AlbumAiCredits c) => {
  'label': c.label,
  'producers': c.producers,
  'mastering': c.mastering,
  'mixing': c.mixing,
  'recordedTime': c.recordedTime,
  'recordedStudios': c.recordedStudios,
  'source': c.source,
  'firstReleased': c.firstReleased,
};

AlbumAiCredits _creditsOf(_Json o) => AlbumAiCredits(
  label: _strings(o['label']),
  producers: _strings(o['producers']),
  mastering: _strings(o['mastering']),
  mixing: _strings(o['mixing']),
  recordedTime: o['recordedTime'] as String?,
  recordedStudios: _strings(o['recordedStudios']),
  source: o['source'] as String?,
  firstReleased: o['firstReleased'] as String?,
);

_Json _replyJson(AlbumAboutReply r) => {
  'credits': _creditsJson(r.credits),
  'blurb': r.blurb,
  'answered': r.answered,
  'isEmpty': r.isEmpty,
};

AlbumAboutRow _rowOf(_Json o) => AlbumAboutRow(
  kind: o['kind'] as String,
  entryKey: o['entryKey'] as String,
  promptText: o['promptText'] as String?,
  titleText: o['titleText'] as String?,
  answerText: o['answerText'] as String,
  createdAt: o['createdAt'] as int,
);

_Json _contentJson(AlbumAboutContent c) => {
  'credits': _creditsJson(c.credits),
  'blurb': c.blurb,
  'asks': [
    for (final a in c.asks) {'question': a.question, 'title': a.title, 'answer': a.answer},
  ],
  'generated': c.generated,
};

_Json _itemJson(AlbumCreditItem i) => {'kind': i.kind.kotlinName, 'names': i.names, 'text': i.text, 'date': i.date};

_Json _lineJson(AlbumCreditsLine l) => {
  'text': l.text,
  'plain': l.plain,
  'segments': l.segments,
  'rows': [
    for (final r in l.rows) {'kind': r.kind.kotlinName, 'label': r.label, 'value': r.value},
  ],
};

AlbumCreditLabels _labelsOf(_Json o) => AlbumCreditLabels(
  label: o['label'] as String,
  producer: o['producer'] as String,
  mastering: o['mastering'] as String,
  mixing: o['mixing'] as String,
  recorded: o['recorded'] as String,
  source: o['source'] as String,
  firstReleased: o['firstReleased'] as String,
  nameSeparator: o['nameSeparator'] as String,
  timeAndPlace: o['timeAndPlace'] as String,
);
