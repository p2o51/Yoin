// The Dart share-card rules against the shared golden vectors, which :shared
// Kotlin writes and checks (ShareCardsGoldenTest / ShareCardsGoldenReplayTest).
// Every vector is answered again from its own inputs, section by section, as
// ShareCardsGolden.outputs does; fractions compare within 1e-5 (32-bit floats
// written rounded to 6 decimals). A failure here means the port drifted:
// regenerate the file only from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:flutter/painting.dart';
import 'package:yoin/data/share/share_scripts.dart';
import 'package:yoin/data/share/yoin_share_cards.dart';
import 'package:yoin/nowplaying/about_widgets.dart' show markdownBoldSpan;

const _goldenPath = '../shared/src/commonTest/resources/golden/share-cards.json';

const _sections = [
  'isCjk',
  'content',
  'layoutStore',
  'currentLine',
  'passageStarts',
  'caption',
  'cardLineSize',
  'poster',
  'playerExcerpt',
  'verticalPlan',
  'verticalLyrics',
  'verticalText',
  'verticalForm',
  'about',
  'aiSign',
  'boldRuns',
  'note',
  'time',
  'tones',
  'toneOffer',
  'retone',
  'storyZoom',
  'storyFits',
  'exportSize',
  'fitScale',
  'fileName',
];

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  final labels = {
    for (final MapEntry(:key, :value) in (golden['labels'] as Map<String, dynamic>).entries)
      key: _labelsOf(value as Map<String, dynamic>),
  };

  test('should_haveEverySection_when_loadingTheGolden', () {
    for (final section in _sections) {
      expect(golden[section], isA<List<dynamic>>(), reason: section);
    }
    final total = _sections.map((s) => (golden[s] as List).length).reduce((a, b) => a + b);
    expect(total, greaterThanOrEqualTo(308));
  });

  test('should_drawAboutsBoldAsTheVectors_throughMarkdownBoldSpan', () {
    // The About share layouts draw values and the paragraph through Now Playing's markdownBoldSpan.
    for (final raw in golden['boldRuns'] as List) {
      final v = raw as Map<String, dynamic>;
      final runs = <Map<String, Object?>>[];
      for (final child in markdownBoldSpan(v['text'] as String).children ?? const <InlineSpan>[]) {
        final span = child as TextSpan;
        final bold = span.style?.fontWeight == FontWeight.w700;
        final text = span.text ?? '';
        if (text.isEmpty) continue;
        if (!bold && runs.isNotEmpty && runs.last['bold'] == false) {
          runs.last['text'] = '${runs.last['text']}$text';
        } else {
          runs.add({'text': text, 'bold': bold});
        }
      }
      _expectJson(runs, v['runs'], 'boldRuns ${v['name']}');
    }
  });

  test('should_copyKotlinsConstants_when_comparingTheTables', () {
    _expectJson(_constants(), golden['constants'], 'constants');
  });

  for (final section in _sections) {
    group(section, () {
      for (final (index, raw) in (golden[section] as List).indexed) {
        final v = raw as Map<String, dynamic>;
        test('should_answerAsKotlin_when_vector$index ${v['name']}', () {
          final outputs = _outputs(section, v, labels);
          for (final MapEntry(:key, :value) in outputs.entries) {
            expect(v.containsKey(key), isTrue, reason: '$section[$index] has no $key');
            _expectJson(value, v[key], '$section[$index] ${v['name']} $key');
          }
        });
      }
    });
  }
}

/// What the rules answer for one vector's inputs (ShareCardsGolden.outputs).
Map<String, Object?> _outputs(String section, Map<String, dynamic> v, Map<String, ShareAboutLabels> labels) {
  switch (section) {
    case 'isCjk':
      return {'cjk': YoinShareCards.isCjk(v['text'] as String)};
    case 'content':
      final content = _contentOf(v['content'] as Map<String, dynamic>);
      final remembered = v['remembered'] as Map<String, dynamic>;
      return {
        'tabs': [for (final t in YoinShareCards.tabs(content)) t.storeKey],
        'layouts': {
          for (final tab in ShareTab.values)
            tab.storeKey: [for (final l in YoinShareCards.layouts(content, tab)) l.storeValue],
        },
        'opens': {
          for (final tab in ShareTab.values)
            tab.storeKey: YoinShareCards.resolveLayout(
              content,
              tab,
              YoinShareCards.layoutForStoreValue(tab, remembered[tab.storeKey] as String?),
            )?.storeValue,
        },
      };
    case 'layoutStore':
      final tab = ShareTab.values.firstWhere((t) => t.storeKey == v['tab']);
      return {'layout': YoinShareCards.layoutForStoreValue(tab, v['value'] as String?)?.storeValue};
    case 'currentLine':
      final pick = YoinShareCards.currentLine(
        [for (final l in v['lines'] as List) _lineOf(l as Map<String, dynamic>)],
        v['positionMs'] as int,
        v['showTranslation'] as bool,
      );
      return {'pick': pick == null ? null : _lyricsJson(pick)};
    case 'passageStarts':
      return {'starts': YoinShareCards.passageStarts((v['selected'] as List).cast<int>(), v['lineCount'] as int)};
    case 'caption':
      return {'caption': YoinShareCards.caption(v['title'] as String?, v['artist'] as String?)};
    case 'cardLineSize':
      return {'sizeSp': YoinShareCards.cardLineSizeSp((v['lines'] as List).cast<String>())};
    case 'poster':
      final poster = YoinShareCards.poster(_lyricsOf(v['pick'] as Map<String, dynamic>));
      return {
        'poster': poster == null
            ? null
            : {
                'lead': _lineJson(poster.lead),
                'rest': [for (final l in poster.rest) _lineJson(l)],
                'translation': poster.translation,
                'leadSizeSp': poster.leadSizeSp,
                'leadTime': poster.leadTime,
              },
      };
    case 'playerExcerpt':
      final e = YoinShareCards.playerExcerpt(_lyricsOf(v['pick'] as Map<String, dynamic>), v['durationMs'] as int);
      return {
        'excerpt': e == null
            ? null
            : {
                'start': e.start,
                'end': e.end,
                'centre': e.centre,
                'startMs': e.startMs,
                'endMs': e.endMs,
                'startTime': YoinShareCards.time(e.startMs),
                'endTime': YoinShareCards.time(e.endMs),
              },
      };
    case 'verticalPlan':
      return {
        'plan': _planJson(
          YoinShareCards.verticalPlan(
            (v['lines'] as List).cast<String>(),
            (v['columnHeightDp'] as num).toDouble(),
            (v['widthDp'] as num).toDouble(),
          ),
        ),
      };
    case 'verticalLyrics':
      final pick = _lyricsOf(v['pick'] as Map<String, dynamic>);
      return {'gist': YoinShareCards.verticalGist(pick), 'plan': _planJson(YoinShareCards.verticalLyricsPlan(pick))};
    case 'verticalText':
      return {
        'columns': YoinShareCards.verticalTextColumns(
          v['text'] as String,
          (v['sizeSp'] as num).toDouble(),
          (v['columnHeightDp'] as num).toDouble(),
        ),
      };
    case 'verticalForm':
      return {'vertical': YoinShareCards.verticalText(v['text'] as String)};
    case 'about':
      final about = YoinShareCards.aboutPick(
        [for (final e in v['entries'] as List) _entryOf(e as Map<String, dynamic>)],
        _sourcesOf(v['sources'] as Map<String, dynamic>),
        labels[v['labels']]!,
        v['focusKind'] as String?,
        v['focusKey'] as String?,
      );
      return {
        'about': about == null ? null : _aboutJson(about),
        'aiContent': about?.aiContent == true,
        'hasTab': YoinShareCards.has(
          ShareCardContent(
            song: const ShareSong(songId: 's', title: 'T', artist: 'A', coverArtUrl: null),
            about: about,
          ),
          ShareTab.about,
        ),
      };
    case 'aiSign':
      return {'sign': YoinShareCards.aiSign(v['template'] as String, v['provider'] as String)};
    case 'boldRuns':
      return {
        'runs': [
          for (final r in YoinShareCards.boldRuns(v['text'] as String)) {'text': r.text, 'bold': r.bold},
        ],
      };
    case 'note':
      final note = YoinShareCards.notePick(
        [for (final n in v['notes'] as List) _noteOf(n as Map<String, dynamic>)],
        v['noteId'] as String?,
        v['positionMs'] as int,
      );
      final duration = v['durationMs'] as int;
      return {
        'note': note == null ? null : _noteJson(note),
        'time': note?.positionMs == null ? null : YoinShareCards.time(note!.positionMs!),
        'timelineFraction': note == null ? null : YoinShareCards.timelineFraction(note.positionMs, duration),
        'lines': note == null ? null : [for (final l in YoinShareCards.noteLines(note)) _noteLineJson(l)],
      };
    case 'time':
      return {'text': YoinShareCards.time(v['ms'] as int)};
    case 'tones':
      final colors = YoinShareCards.toneColors(
        ShareTone.values.firstWhere((t) => t.kotlinName == v['tone']),
        ShareRole.values.firstWhere((r) => r.kotlinName == v['role']),
      );
      return {
        'card': _inkJson(colors.card),
        'content': _inkJson(colors.content),
        'backdrop': _inkJson(colors.backdrop),
      };
    case 'toneOffer':
      final tab = ShareTab.values.firstWhere((t) => t.storeKey == v['tab']);
      final picked = v['picked'] == null ? null : ShareTone.values.firstWhere((t) => t.kotlinName == v['picked']);
      return {
        'tones': [for (final t in YoinShareCards.tones(tab)) t.kotlinName],
        'default': YoinShareCards.defaultTone(tab).kotlinName,
        'resolved': YoinShareCards.resolveTone(tab, picked).kotlinName,
      };
    case 'retone':
      return {'retoned': _hex(YoinShareCards.retone(_argbOf(v['argb'] as String), v['tone'] as int))};
    case 'storyZoom':
      final limit = f32(v['fitsUpTo'] as num);
      final tried = <double>[];
      final zoom = YoinShareCards.storyZoom(f32(v['maxZoom'] as num), (z) {
        tried.add(z);
        return z <= limit;
      });
      return {'zoom': zoom, 'tried': tried};
    case 'storyFits':
      return {'fits': YoinShareCards.storyFits(v['heightPx'] as int, f32(v['zoom'] as num), v['frameHeightPx'] as int)};
    case 'exportSize':
      final size = YoinShareCards.exportSize(
        ShareFormat.values.firstWhere((f) => f.kotlinName == v['format']),
        f32(v['width'] as num),
        f32(v['height'] as num),
      );
      return {'exportWidth': size.width, 'exportHeight': size.height};
    case 'fitScale':
      return {
        'scale': YoinShareCards.fitScale(
          v['childWidth'] as int,
          v['childHeight'] as int,
          v['maxWidth'] as int,
          v['maxHeight'] as int,
          f32(v['maxScale'] as num),
        ),
      };
    case 'fileName':
      return {
        'fileTitle': YoinShareCards.fileTitle(v['title'] as String?),
        'fileName': YoinShareCards.fileName(v['title'] as String?, v['nowMs'] as int),
      };
  }
  throw StateError('unknown section $section');
}

/// The rules' fixed numbers and tables (ShareCardsGolden.constants).
Map<String, Object?> _constants() => {
  'tabs': [
    for (final tab in ShareTab.values)
      {
        'tab': tab.storeKey,
        'role': tab.role.kotlinName,
        'storeKey': tab.storeKey,
        'layouts': [for (final l in YoinShareCards.allLayouts(tab)) l.storeValue],
      },
  ],
  'layouts': [
    for (final layout in ShareLayout.values)
      {
        'layout': layout.storeValue,
        'tab': layout.tab.storeKey,
        'storyIsPoster': layout.storyIsPoster,
        'storyMaxZoom': YoinShareCards.storyMaxZoom(layout),
      },
  ],
  'roleTones': {
    for (final role in ShareRole.values) role.kotlinName: [for (final t in role.tones()) t.kotlinName],
  },
  'roleDefaultTones': {for (final role in ShareRole.values) role.kotlinName: role.defaultTone.kotlinName},
  'cardDesignWidthDp': YoinShareCards.cardDesignWidthDp,
  'storyDesignWidthDp': YoinShareCards.storyDesignWidthDp,
  'storyDesignHeightDp': YoinShareCards.storyDesignHeightDp,
  'cardExportWidthPx': YoinShareCards.cardExportWidthPx,
  'storyExportWidthPx': YoinShareCards.storyExportWidthPx,
  'storyExportHeightPx': YoinShareCards.storyExportHeightPx,
  'storyMarginHorizontalDp': YoinShareCards.storyMarginHorizontalDp,
  'storyMarginVerticalDp': YoinShareCards.storyMarginVerticalDp,
  'storyMinZoom': YoinShareCards.storyMinZoom,
  'storyMaxZoom': YoinShareCards.storyMaxZoomLimit,
  'storyZoomSteps': 8,
  'storyFitSlackPx': 0.5,
  'captionSeparator': YoinShareCards.captionSeparator,
  'posterMaxLines': YoinShareCards.posterMaxLines,
  'posterStoryLeadBoostSp': YoinShareCards.posterStoryLeadBoostSp,
  'posterTranslationSeparator': YoinShareCards.posterTranslationSeparator,
  'playerExcerptMinFraction': YoinShareCards.playerExcerptMinFraction,
  'playerExcerptLineMs': YoinShareCards.playerExcerptLineMs,
  'verticalSizesSp': YoinShareCards.verticalSizesSp,
  'verticalCharAdvance': YoinShareCards.verticalCharAdvance,
  'verticalColumnAdvance': YoinShareCards.verticalColumnAdvance,
  'verticalColumnHeightDp': YoinShareCards.verticalColumnHeightDp,
  'verticalAvailableWidthDp': YoinShareCards.verticalAvailableWidthDp,
  'verticalFootnoteWidthDp': YoinShareCards.verticalFootnoteWidthDp,
  'verticalGistSeparator': YoinShareCards.verticalGistSeparator,
  'quoteCredits': YoinShareCards.quoteCredits,
  'linerCredits': YoinShareCards.linerCredits,
  'secondaryAlpha': YoinShareCards.secondaryAlpha,
  'paperInkTone': YoinShareCards.paperInkTone,
  'paperBackdropTone': YoinShareCards.paperBackdropTone,
  'fileTitleMaxChars': YoinShareCards.fileTitleMaxChars,
  'galleryFolder': YoinShareCards.galleryFolder,
  'cjkCommonLetters': [
    for (final cp in ShareScripts.cjkCommonLetters.toList()..sort())
      'U+${cp.toRadixString(16).toUpperCase().padLeft(4, '0')}',
  ],
  'kindCanonical': YoinShareCards.kindCanonical,
  'kindAsk': YoinShareCards.kindAsk,
  'kindOwn': YoinShareCards.kindOwn,
  'canonLyricist': YoinShareCards.canonLyricist,
  'canonComposer': YoinShareCards.canonComposer,
  'canonProducer': YoinShareCards.canonProducer,
  'canonCreationTime': YoinShareCards.canonCreationTime,
  'canonCreationLocation': YoinShareCards.canonCreationLocation,
  'canonReview': YoinShareCards.canonReview,
};

/// Deep equality with numbers within 1e-5 (the file writes floats rounded to 6 decimals).
void _expectJson(Object? actual, Object? expected, String where) {
  if (expected is num && actual is num) {
    expect((actual - expected).abs(), lessThanOrEqualTo(1e-5), reason: '$where: expected $expected but was $actual');
    return;
  }
  if (expected is List) {
    expect(actual, isA<List<dynamic>>(), reason: '$where: expected a list but was $actual');
    final a = actual! as List;
    expect(a.length, expected.length, reason: '$where: expected $expected but was $actual');
    for (var i = 0; i < expected.length; i++) {
      _expectJson(a[i], expected[i], '$where[$i]');
    }
    return;
  }
  if (expected is Map) {
    expect(actual, isA<Map<dynamic, dynamic>>(), reason: '$where: expected a map but was $actual');
    final a = actual! as Map;
    expect(a.keys.toSet(), expected.keys.toSet(), reason: '$where: keys');
    for (final key in expected.keys) {
      _expectJson(a[key], expected[key], '$where.$key');
    }
    return;
  }
  expect(actual, expected, reason: where);
}

// ── codecs (ShareCardsGolden's) ──

ShareAboutLabels _labelsOf(Map<String, dynamic> o) => ShareAboutLabels(
  lyricist: o['lyricist'] as String,
  composer: o['composer'] as String,
  arranger: o['arranger'] as String,
  producer: o['producer'] as String,
  creationTime: o['creationTime'] as String,
  creationLocation: o['creationLocation'] as String,
  year: o['year'] as String,
  review: o['review'] as String,
);

ShareSong _songOf(Map<String, dynamic> o) => ShareSong(
  songId: o['songId'] as String,
  title: o['title'] as String,
  artist: o['artist'] as String,
  coverArtUrl: o['coverArtUrl'] as String?,
  albumName: o['albumName'] as String,
  durationMs: o['durationMs'] as int,
);

ShareLyricLine _lineOf(Map<String, dynamic> o) =>
    ShareLyricLine(startMs: o['startMs'] as int?, text: o['text'] as String, translation: o['translation'] as String?);

Map<String, Object?> _lineJson(ShareLyricLine l) => {
  'startMs': l.startMs,
  'text': l.text,
  'translation': l.translation,
};

ShareLyrics _lyricsOf(Map<String, dynamic> o) => ShareLyrics(
  lines: [for (final l in o['lines'] as List) _lineOf(l as Map<String, dynamic>)],
  passageStarts: (o['passageStarts'] as List).cast<int>(),
  showTranslation: o['showTranslation'] as bool,
);

Map<String, Object?> _lyricsJson(ShareLyrics p) => {
  'lines': [for (final l in p.lines) _lineJson(l)],
  'passageStarts': p.passageStarts,
  'showTranslation': p.showTranslation,
};

ShareCredit _creditOf(Map<String, dynamic> o) =>
    ShareCredit(o['label'] as String, o['value'] as String, ai: o['ai'] as bool, fact: o['fact'] as bool);

Map<String, Object?> _aboutJson(ShareAbout a) => {
  'credits': [
    for (final c in a.credits) {'label': c.label, 'value': c.value, 'ai': c.ai, 'fact': c.fact},
  ],
  'heading': a.heading,
  'paragraph': a.paragraph,
};

ShareAbout _aboutOf(Map<String, dynamic> o) => ShareAbout(
  credits: [for (final c in o['credits'] as List) _creditOf(c as Map<String, dynamic>)],
  heading: o['heading'] as String,
  paragraph: o['paragraph'] as String?,
);

ShareNote _noteOf(Map<String, dynamic> o) => ShareNote(
  noteId: o['noteId'] as String,
  content: o['content'] as String,
  positionMs: o['positionMs'] as int?,
  createdAt: o['createdAt'] as int,
);

Map<String, Object?> _noteJson(ShareNote n) => {
  'noteId': n.noteId,
  'content': n.content,
  'positionMs': n.positionMs,
  'createdAt': n.createdAt,
};

ShareCardContent _contentOf(Map<String, dynamic> o) => ShareCardContent(
  song: _songOf(o['song'] as Map<String, dynamic>),
  lyrics: o['lyrics'] == null ? null : _lyricsOf(o['lyrics'] as Map<String, dynamic>),
  about: o['about'] == null ? null : _aboutOf(o['about'] as Map<String, dynamic>),
  note: o['note'] == null ? null : _noteOf(o['note'] as Map<String, dynamic>),
);

ShareAboutEntry _entryOf(Map<String, dynamic> o) => ShareAboutEntry(
  kind: o['kind'] as String,
  entryKey: o['entryKey'] as String,
  promptText: o['promptText'] as String?,
  titleText: o['titleText'] as String?,
  answerText: o['answerText'] as String,
);

ShareAboutSources _sourcesOf(Map<String, dynamic> o) => ShareAboutSources(
  lyricist: o['lyricist'] as String?,
  composer: o['composer'] as String?,
  arranger: o['arranger'] as String?,
  producer: o['producer'] as String?,
  serverComposer: o['serverComposer'] as String?,
  year: o['year'] as int?,
);

Map<String, Object?> _planJson(ShareVerticalPlan p) => {
  'columns': p.columns,
  'fontSizeSp': p.fontSizeSp,
  'charsPerColumn': p.charsPerColumn,
};

Map<String, Object?> _inkJson(ShareInk i) => {'source': i.source.kotlinName, 'tone': i.tone};

/// The mention's canonical `yoin:` destination (YoinNoteMention.link).
String _destination(NoteMention m) {
  final link = NoteMarkup.serializeMention(m);
  return link.substring(link.indexOf('](') + 2, link.length - 1);
}

Map<String, Object?> _noteLineJson(ShareNoteLine l) => {
  'kind': switch (l.kind) {
    ShareNoteLineKind.paragraph => 'Paragraph',
    ShareNoteLineKind.heading => 'Heading',
    ShareNoteLineKind.bullet => 'Bullet',
    ShareNoteLineKind.checklist => 'Checklist',
  },
  'level': l.level,
  'checked': l.checked,
  'runs': [
    for (final r in l.runs)
      {
        'text': r.text,
        'bold': r.bold,
        'italic': r.italic,
        'mention': r.mention == null ? null : _destination(r.mention!),
      },
  ],
};

String _hex(int argb) => '#${(argb & 0xFFFFFFFF).toRadixString(16).toUpperCase().padLeft(8, '0')}';

int _argbOf(String hex) => int.parse(hex.substring(1), radix: 16);
