// The Dart NoteMarkup / MentionMatcher against the shared golden vectors, which
// :shared Kotlin writes and checks (shared/src/androidHostTest/.../NoteMarkupGoldenTest.kt).
// A failure here means the port drifted from Android; regenerate the file only
// from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/notes/mention_matcher.dart';
import 'package:yoin/data/notes/note_markup.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/note-markup.json';

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  List<Map<String, dynamic>> section(String name) => (golden[name] as List).cast<Map<String, dynamic>>();

  test('should_readOffsetsAsUtf16CodeUnits_when_loadingTheGolden', () {
    expect(golden['offsets'], 'UTF-16 code units');
    final total = [
      'parse',
      'serializeMention',
      'serializeDocument',
      'rewriteMentions',
      'toggleChecklist',
      'percent',
      'matcher',
    ].map((name) => section(name).length).reduce((a, b) => a + b);
    expect(total, greaterThan(300));
  });

  group('parse', () {
    for (final (index, v) in section('parse').indexed) {
      final text = v['text'] as String;
      test(
        'should_matchKotlin_when_parsingVector$index ${jsonEncode(text).substring(0, jsonEncode(text).length.clamp(0, 40))}',
        () {
          final doc = NoteMarkup.parse(text);
          expect(NoteMarkup.hasMentions(text), v['hasMentions'], reason: 'hasMentions');
          expect(_blocks(doc.blocks), v['blocks'], reason: 'blocks');
          expect(NoteMarkup.serialize(doc), v['serialized'], reason: 'serialize');
          expect([for (final s in NoteMarkup.mentionSpans(text)) _span(s)], v['mentions'], reason: 'mentionSpans');
          expect(_mentions(doc.mentions), [
            for (final s in v['mentions'] as List) (s as Map)['mention'],
          ], reason: 'doc.mentions');
          expect(NoteMarkup.plainText(text), v['plain'], reason: 'plainText');
          expect(NoteMarkup.plainText(text, lineSeparator: ' '), v['plainSpaced'], reason: 'plainText spaced');
          final snippet = NoteMarkup.snippetWithMentions(text);
          expect(
            {
              'text': snippet.text,
              'mentions': [for (final s in snippet.mentions) _span(s)],
            },
            v['snippet'],
            reason: 'snippet',
          );
          expect(NoteMarkup.snippet(text), (v['snippet'] as Map)['text'], reason: 'snippet text');
          expect(NoteMarkup.toNeoDb(text), v['neoDb'], reason: 'toNeoDb');
          expect([for (final l in NoteMarkup.layout(text)) _line(l)], v['layout'], reason: 'layout');
        },
      );
    }
  });

  group('serializeMention', () {
    for (final (index, v) in section('serializeMention').indexed) {
      test('should_writeKotlinsLink_when_serializingMention$index', () {
        final mention = _mention(v['mention'] as Map)!;
        final link = v['link'] as String;
        expect(NoteMarkup.serializeMention(mention), link);
        // What Kotlin writes reads back (title newlines become spaces, blank values drop).
        expect(NoteMarkup.mentions(link), hasLength(1));
      });
    }
  });

  group('serializeDocument', () {
    for (final (index, v) in section('serializeDocument').indexed) {
      test('should_writeKotlinsText_when_serializingDocument$index', () {
        final doc = NoteDocument(_parseBlocks(v['blocks'] as List));
        expect(NoteMarkup.serialize(doc), v['text']);
        expect(_blocks(NoteMarkup.parse(v['text'] as String).blocks), v['reparsed']);
      });
    }
  });

  group('rewriteMentions', () {
    for (final (index, v) in section('rewriteMentions').indexed) {
      test('should_rewriteLikeKotlin_when_mappingVector$index', () {
        final mapping = {
          for (final pair in (v['mapping'] as List).cast<Map>()) _ref(pair['from'] as Map): _ref(pair['to'] as Map),
        };
        expect(NoteMarkup.rewriteMentionRefs(v['text'] as String, mapping), v['result']);
        expect(NoteMarkup.rewriteMentions(v['text'] as String, (_) => null), v['text']);
      });
    }
  });

  test('should_toggleLikeKotlin_when_tickingEveryLine', () {
    for (final v in section('toggleChecklist')) {
      expect(NoteMarkup.toggleChecklist(v['text'] as String, v['line'] as int), v['result'], reason: '$v');
    }
  });

  test('should_percentCodeLikeKotlin_when_encodingAndDecoding', () {
    for (final v in section('percent')) {
      final input = v['input'] as String;
      final actual = switch (v['op']) {
        'encode' => PercentCoding.encode(input),
        'encodeKeepColon' => PercentCoding.encode(input, keep: ':'),
        'decode' => PercentCoding.decode(input),
        _ => throw StateError('unknown op ${v['op']}'),
      };
      expect(actual, v['output'], reason: '$v');
    }
  });

  group('MentionMatcher', () {
    for (final (index, v) in section('matcher').indexed) {
      test('should_answerLikeKotlin_when_${v['fn']}$index', () {
        final mention = _mention(v['mention'] as Map?);
        final args = (v['args'] as List).cast<String?>();
        final Object? actual = switch (v['fn']) {
          'namesMatchTrack' => MentionMatcher.namesMatchTrack(mention!, args[0], args[1], args[2]),
          'namesMatchAlbum' => MentionMatcher.namesMatchAlbum(mention!, args[0], args[1]),
          'namesMatchArtist' => MentionMatcher.namesMatchArtist(mention!, args[0]),
          'trackMentionOnAlbum' => MentionMatcher.trackMentionOnAlbum(mention!, args[0], args[1]),
          'titlesMatch' => MentionMatcher.titlesMatch(args[0], args[1]),
          'artistsMatch' => MentionMatcher.artistsMatch(args[0], args[1]),
          'matchesId' => MentionMatcher.matchesId(mention!, args[0]!, args[1]!),
          'searchPrefix' => MentionMatcher.searchPrefix(args[0]!),
          _ => throw StateError('unknown fn ${v['fn']}'),
        };
        expect(actual, v['result'], reason: jsonEncode(v));
      });
    }
  });
}

// ── the golden's encoding (same shape NoteMarkupGoldenTest writes) ──────

List<Object?> _blocks(List<NoteBlock> blocks) => [for (final b in blocks) _block(b)];

Map<String, Object?> _block(NoteBlock block) => switch (block) {
  NoteParagraph() => {'type': 'paragraph', 'inlines': _inlines(block.inlines)},
  NoteHeading(:final level) => {'type': 'heading', 'level': level, 'inlines': _inlines(block.inlines)},
  NoteBullet(:final marker) => {'type': 'bullet', 'marker': marker, 'inlines': _inlines(block.inlines)},
  NoteChecklist(:final checked, :final marker, :final upperX) => {
    'type': 'checklist',
    'checked': checked,
    'marker': marker,
    'upperX': upperX,
    'inlines': _inlines(block.inlines),
  },
};

List<Object?> _inlines(List<NoteInline> nodes) => [
  for (final node in nodes)
    switch (node) {
      NoteText(:final text) => {'text': text},
      NoteBold(:final children) => {'bold': _inlines(children)},
      NoteItalic(:final children, :final marker) => {'italic': _inlines(children), 'marker': marker},
      NoteMentionInline(:final mention) => {'mention': _mentionJson(mention)},
    },
];

List<Object?> _mentions(List<NoteMention> mentions) => [for (final m in mentions) _mentionJson(m)];

Map<String, Object?> _mentionJson(NoteMention m) => {
  'kind': m.kind.token,
  'provider': m.provider,
  'id': m.id,
  'title': m.title,
  'artist': m.artist,
  'album': m.album,
};

Map<String, Object?> _span(MentionSpan s) => {'start': s.start, 'end': s.end, 'mention': _mentionJson(s.mention)};

Map<String, Object?> _line(LineLayout l) => {
  'start': l.start,
  'end': l.end,
  'contentStart': l.contentStart,
  'inlines': [
    for (final r in l.inlines)
      {'kind': r.kind.name, 'start': r.start, 'end': r.end, 'innerStart': r.innerStart, 'innerEnd': r.innerEnd},
  ],
};

NoteMention? _mention(Map? json) => json == null
    ? null
    : NoteMention(
        kind: MentionKind.fromToken(json['kind'] as String)!,
        provider: json['provider'] as String,
        id: json['id'] as String,
        title: json['title'] as String,
        artist: json['artist'] as String?,
        album: json['album'] as String?,
      );

MentionRef _ref(Map json) =>
    MentionRef(MentionKind.fromToken(json['kind'] as String)!, json['provider'] as String, json['id'] as String);

List<NoteBlock> _parseBlocks(List json) => [
  for (final b in json.cast<Map>())
    switch (b['type']) {
      'paragraph' => NoteParagraph(_parseInlines(b['inlines'] as List)),
      'heading' => NoteHeading(b['level'] as int, _parseInlines(b['inlines'] as List)),
      'bullet' => NoteBullet(_parseInlines(b['inlines'] as List), marker: b['marker'] as String),
      'checklist' => NoteChecklist(
        b['checked'] as bool,
        _parseInlines(b['inlines'] as List),
        marker: b['marker'] as String,
        upperX: b['upperX'] as bool,
      ),
      _ => throw StateError('unknown block ${b['type']}'),
    },
];

List<NoteInline> _parseInlines(List json) => [
  for (final n in json.cast<Map>())
    if (n.containsKey('text'))
      NoteText(n['text'] as String)
    else if (n.containsKey('bold'))
      NoteBold(_parseInlines(n['bold'] as List))
    else if (n.containsKey('italic'))
      NoteItalic(_parseInlines(n['italic'] as List), marker: n['marker'] as String)
    else
      NoteMentionInline(_mention(n['mention'] as Map)!),
];
