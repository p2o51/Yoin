// The Dart layout core against the shared golden vectors, which :shared Kotlin writes and checks
// (HomeLayoutGoldenTest / HomeLayoutGoldenReplayTest). Every vector is answered again from its own inputs,
// section by section, as HomeLayoutGolden.outputs does through YoinHomeLayout; ops that change nothing must
// hand the same layout back (identical), Kotlin's "return this" contract. A failure here means the port
// drifted: regenerate the file only from the Kotlin side.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/home/home_catalog.dart';
import 'package:yoin/data/home/home_edit_rules.dart';
import 'package:yoin/data/home/home_layout_codec.dart';
import 'package:yoin/data/home/home_layout_core.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/home-layout.json';

const _sections = [
  'codec',
  'reconcile',
  'ops',
  'predicates',
  'sameSections',
  'widePairsAt',
  'samePairs',
  'spanPairs',
  'feedRows',
  'rowUnits',
  'canPair',
  'unitsPreset',
  'draftAtWidth',
  'echoes',
  'editStep',
];

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  final states = {
    for (final MapEntry(:key, :value) in (golden['states'] as Map<String, dynamic>).entries)
      key: _layoutOf(value as Map<String, dynamic>),
  };

  test('should_haveEverySection_when_loadingTheGolden', () {
    for (final section in _sections) {
      expect(golden[section], isA<List<dynamic>>(), reason: section);
    }
    final total = _sections.map((s) => (golden[s] as List).length).reduce((a, b) => a + b);
    // ignore: avoid_print
    print('home-layout.json: $total vectors');
    expect(total, greaterThanOrEqualTo(650));
  });

  test('should_copyKotlinsConstants_when_comparingTheCatalog', () {
    _expectJson(_constants(), golden['constants'], 'constants');
  });

  test('should_readTheNamedStatesBack_when_roundTrippingThem', () {
    for (final MapEntry(:key, :value) in (golden['states'] as Map<String, dynamic>).entries) {
      _expectJson(_stateJson(states[key]!), value, 'states.$key');
    }
  });

  test('should_buildTheDefaults_when_comparingThemWithTheNamedStates', () {
    expect(HomeLayout.defaultLayout.sameSectionsAs(states['default']!), isTrue);
    expect(HomeLayout.wideDefault.sameSectionsAs(states['wideDefault']!), isTrue);
    _expectJson(_stateJson(HomeLayout.defaultLayout), golden['states']['default'], 'default');
    _expectJson(_stateJson(HomeLayout.wideDefault), golden['states']['wideDefault'], 'wideDefault');
  });

  for (final section in _sections) {
    group(section, () {
      for (final (index, raw) in (golden[section] as List).indexed) {
        final v = raw as Map<String, dynamic>;
        test('should_answerAsKotlin_when_vector$index ${v['name']}', () {
          final outputs = _outputs(section, v, states);
          for (final MapEntry(:key, :value) in outputs.entries) {
            if (key.startsWith('_')) continue;
            expect(v.containsKey(key), isTrue, reason: '$section[$index] has no $key');
            _expectJson(value, v[key], '$section[$index] ${v['name']} $key');
          }
          // Only the outputs ops / editStep leave out when nothing changed.
          if (section == 'ops' || section == 'editStep') expect(v.containsKey('after'), outputs.containsKey('after'));
        });
      }
    });
  }
}

void _expectJson(Object? actual, Object? expected, String reason) {
  expect(
    homeJsonEquals(actual, expected),
    isTrue,
    reason: '$reason\n  expected ${jsonEncode(expected)}\n  actual   ${jsonEncode(actual)}',
  );
}

/// What the core answers for one vector's inputs (HomeLayoutGolden.outputs).
Map<String, Object?> _outputs(String section, Map<String, dynamic> v, Map<String, HomeLayout> states) {
  HomeLayout state(String key) => _stateIn(v[key], states);
  switch (section) {
    case 'codec':
      final raw = v['raw'] as String;
      final document = HomeLayoutCodec.decode(raw);
      return {
        'document': _documentJson(document),
        'encoded': document == null ? null : HomeLayoutCodec.encode(document),
        'layoutEncoded': HomeLayoutCodec.encode(HomeLayout.fromDocument(document).toDocument()),
      };
    case 'reconcile':
      final doc = v['document'];
      final read = HomeLayout.fromDocument(doc is Map ? HomeLayoutCodec.decode(jsonEncode(doc)) : null);
      return {'state': _stateJson(read), 'isDefault': read.isDefault, 'persist': _persistJson(read)};
    case 'ops':
      final before = state('state');
      return _resultJson(before, _applyOp(before, v));
    case 'predicates':
      final s = state('state');
      return {
        'enabled': [for (final x in s.enabledSections) x.id],
        'hidden': [for (final x in s.hiddenSections) x.id],
        'isDefault': s.isDefault,
        'isWideDefault': s.isWideDefault,
        'isAnyDefault': s.isAnyDefault,
        'isDefaultForTwoColumn': s.isDefaultFor(true),
        'pairs': _pairsJson(s.pairs),
        'partners': {for (final x in HomeSection.values) x.id: s.partnerOf(x)?.id},
        'units': {for (final x in HomeSection.values) x.id: s.unitsOf(x)},
        'rows': {for (final x in HomeSection.values) x.id: s.rowsOf(x).key},
        'persist': _persistJson(s),
      };
    case 'sameSections':
      return {'same': state('a').sameSectionsAs(state('b'))};
    case 'widePairsAt':
      final order = _sectionsOf(v['order'] as List) ?? const [];
      final memory = _pairsOf(v['memory'] as List) ?? const {};
      return {'pairs': _pairsJson(homeWidePairsAt(order, memory))};
    case 'samePairs':
      return {'same': homeSamePairs(_pairsOf(v['a'] as List) ?? const {}, _pairsOf(v['b'] as List) ?? const {})};
    case 'spanPairs':
      final order = v['order'] as List;
      final halves = v['halves'] as List;
      return {
        'pairs': _pairsJson(
          homeSpanPairs([
            for (final (i, id) in order.indexed)
              if (HomeSection.byId(id as String) case final s?) (s, i < halves.length && halves[i] == true),
          ]),
        ),
      };
    case 'feedRows':
      final rows = homeFeedRows(
        _sectionsOf(v['displayed'] as List) ?? const [],
        _pairsOf(v['pairs'] as List) ?? const {},
        v['twoColumn'] as bool,
      );
      return {
        'rows': [
          for (final r in rows) [for (final s in r.sections) s.id],
        ],
      };
    case 'rowUnits':
      final row = _rowOf(v['row'] as List);
      return {'units': row == null ? 1 : state('state').unitsOfRow(row)};
    case 'canPair':
      final left = HomeSection.byId(v['left'] as String)!;
      final rightId = v['right'] as String?;
      final right = rightId == null ? null : HomeSection.byId(rightId);
      final range = homeRowUnitRange(left, right);
      return {
        // YoinHomeLayout.canPair: an unknown right id can't pair; no right id at all is no answer.
        'canPair': rightId == null ? null : right != null && homeCanPair(left, right),
        'rowUnitRange': [range.first, range.last],
      };
    case 'unitsPreset':
      return {'preset': homeUnitsPreset(v['units'] as int).key};
    case 'draftAtWidth':
      return {
        'state': _stateJson(
          homeDraftAtWidth(state('draft'), v['edited'] as bool, state('base'), v['twoColumn'] as bool),
        ),
      };
    case 'echoes':
      return {'echoes': homeLayoutEchoes(state('stored'), state('shown'))};
    case 'editStep':
      final before = state('state');
      final changed = _applyOp(before, v);
      final step = homeEditStep(before, changed, v['twoColumn'] as bool);
      return _resultJson(before, step ?? before);
    default:
      throw StateError('unknown section $section');
  }
}

/// One edit of `ops` / `editStep` (HomeLayoutGolden.applyOp through YoinHomeLayout): an unknown id changes nothing.
HomeLayout _applyOp(HomeLayout s, Map<String, dynamic> v) {
  final id = v['id'] as String?;
  final section = id == null ? null : HomeSection.byId(id);
  HomeLayout withSection(HomeLayout Function(HomeSection) edit) => section == null ? s : edit(section);
  switch (v['op'] as String) {
    case 'withEnabled':
      return withSection((x) => s.withEnabled(x, v['enabled'] as bool));
    case 'withEnabledOrder':
      final order = _sectionsOf(v['order'] as List);
      final unseen = _sectionsOf(v['unseen'] as List);
      if (order == null || unseen == null) return s;
      return s.withEnabledOrder(order, unseen: unseen.toSet());
    case 'moved':
      return withSection((x) => s.moved(x, v['index'] as int));
    case 'withHalf':
      return withSection(s.withHalf);
    case 'withFull':
      return withSection(s.withFull);
    case 'withBesidePrevious':
      return withSection(s.withBesidePrevious);
    case 'withArrangement':
      final order = _sectionsOf(v['order'] as List);
      final pairs = _pairsOf(v['pairs'] as List);
      final unseen = _sectionsOf(v['unseen'] as List);
      if (order == null || pairs == null || unseen == null) return s;
      return s.withArrangement(order, pairs, unseen: unseen.toSet());
    case 'withUnits':
      return withSection((x) => s.withUnits(x, v['units'] as int));
    case 'withRows':
      final rows = HomeRowPreset.fromKey(v['rows'] as String);
      return withSection((x) => rows == null ? s : s.withRows(x, rows));
    case 'reset':
      return s.reset(twoColumn: v['twoColumn'] as bool);
    case 'forTwoColumn':
      return s.forTwoColumn(v['twoColumn'] as bool);
    case 'shownAt':
      return s.shownAt(v['twoColumn'] as bool);
    case 'rememberingShownPairs':
      return s.rememberingShownPairs();
    case 'withRememberedPairs':
      return s.withRememberedPairs();
    case 'normalizedSpans':
      return s.normalizedSpans();
    default:
      throw StateError('unknown op ${v['op']}');
  }
}

Map<String, Object?> _resultJson(HomeLayout before, HomeLayout after) =>
    identical(before, after) ? {'changed': false} : {'changed': true, 'after': _stateJson(after)};

Map<String, Object?> _persistJson(HomeLayout layout) => switch (homePersistAction(layout)) {
  HomePersistClear() => {'clear': true, 'document': null},
  HomePersistWrite(:final document) => {'clear': false, 'document': HomeLayoutCodec.encode(document)},
};

Object? _documentJson(HomeLayoutDocument? d) => d == null
    ? null
    : {
        'sections': [for (final p in d.sections) HomeLayoutCodec.encodeEntry(p)],
        'wide': d.wide,
        'extras': d.extras,
      };

// ── state ⇄ JSON (HomeLayoutGolden.stateJson / stateOf, through YoinHomeLayout's plain state) ──

HomeLayout _stateIn(Object? e, Map<String, HomeLayout> states) =>
    e is String ? states[e] ?? (throw StateError('unknown state $e')) : _layoutOf(e! as Map<String, dynamic>);

HomeLayout _layoutOf(Map<String, dynamic> o) {
  final memory = <HomeSection, HomeSection>{};
  for (final pair in (o['widePairs'] as List?) ?? const []) {
    final p = pair as List;
    final left = HomeSection.byId(p[0] as String);
    final right = HomeSection.byId(p[1] as String);
    if (left != null && right != null && p.length == 2) memory[left] = right;
  }
  return HomeLayout(
    [
      for (final raw in o['sections'] as List)
        if (HomeSection.byId((raw as Map<String, dynamic>)['id'] as String) case final section?)
          HomeSectionState(
            section,
            raw['enabled'] as bool,
            rows: HomeRowPreset.fromKey(raw['rows'] as String?) ?? HomeRowPreset.defaultPreset,
            rowsSet: raw['rowsSet'] == true,
            half: raw['half'] == true,
            units: raw['units'] as int?,
            extras: raw['configExtras'] as Map<String, dynamic>?,
            entryExtras: raw['entryExtras'] as Map<String, dynamic>?,
          ),
    ],
    retained: [
      for (final raw in (o['retained'] as List?) ?? const [])
        HomeSectionPref(
          id: (raw as Map<String, dynamic>)['id'] as String,
          enabled: raw['enabled'] as bool,
          config: raw['config'] as Map<String, dynamic>?,
          extras: raw['extras'] as Map<String, dynamic>?,
        ),
    ],
    newSections: {for (final id in (o['newSections'] as List?) ?? const []) ?HomeSection.byId(id as String)},
    widePairs: memory,
    retainedWide: o['retainedWide'] as Map<String, dynamic>?,
    retainedDocument: o['retainedDocument'] as Map<String, dynamic>?,
  );
}

Map<String, Object?> _stateJson(HomeLayout s) => {
  'sections': [
    for (final x in s.sections)
      {
        'id': x.id,
        'enabled': x.enabled,
        if (x.rows != HomeRowPreset.defaultPreset) 'rows': x.rows.key,
        if (x.rowsSet) 'rowsSet': true,
        if (x.half) 'half': true,
        'units': ?x.units,
        'configExtras': ?x.extras,
        'entryExtras': ?x.entryExtras,
      },
  ],
  if (s.retained.isNotEmpty)
    'retained': [
      for (final r in s.retained) {'id': r.id, 'enabled': r.enabled, 'config': ?r.config, 'extras': ?r.extras},
    ],
  if (s.newSections.isNotEmpty)
    'newSections': [
      for (final x in HomeSection.values)
        if (s.newSections.contains(x)) x.id,
    ],
  'widePairs': _pairsJson(s.widePairs),
  'retainedWide': ?s.retainedWide,
  'retainedDocument': ?s.retainedDocument,
};

List<List<String>> _pairsJson(Map<HomeSection, HomeSection> pairs) => [
  for (final e in pairs.entries) [e.key.id, e.value.id],
];

// Null when any id is unknown (the facade's edits then change nothing; its helpers read an empty list).
List<HomeSection>? _sectionsOf(List ids) {
  final out = <HomeSection>[];
  for (final id in ids) {
    final s = HomeSection.byId(id as String);
    if (s == null) return null;
    out.add(s);
  }
  return out;
}

Map<HomeSection, HomeSection>? _pairsOf(List pairs) {
  final out = <HomeSection, HomeSection>{};
  for (final pair in pairs) {
    final p = pair as List;
    if (p.length != 2) return null;
    final left = HomeSection.byId(p[0] as String);
    final right = HomeSection.byId(p[1] as String);
    if (left == null || right == null) return null;
    out[left] = right;
  }
  return out;
}

HomeFeedRow? _rowOf(List ids) {
  final sections = _sectionsOf(ids);
  if (sections == null) return null;
  return switch (sections.length) {
    1 => HomeFeedRowFull(sections[0]),
    2 => HomeFeedRowHalves(sections[0], sections[1]),
    _ => null,
  };
}

/// The catalog and the codec's fixed keys (HomeLayoutGolden.constants).
Map<String, Object?> _constants() => {
  'schemaVersion': HomeLayoutCodec.schemaVersion,
  'documentKeys': HomeLayoutCodec.documentKeys,
  'entryKeys': HomeLayoutCodec.entryKeys,
  'configKeys': [HomeSectionState.rowsKey, HomeSectionState.spanKey, HomeSectionState.unitsKey],
  'spanHalf': HomeSectionState.spanHalf,
  'widePairsKey': HomeLayout.widePairsKey,
  'retiredIds': HomeSection.retiredIds.toList(),
  'undoMax': homeEditUndoMax,
  'presets': [
    for (final p in HomeRowPreset.values) {'key': p.key, 'units': p.units},
  ],
  'defaultPreset': HomeRowPreset.defaultPreset.key,
  'catalog': [
    for (final s in HomeSection.values)
      {
        'id': s.id,
        'defaultEnabled': s.defaultEnabled,
        'appendEnabled': s.appendEnabled,
        'supportsRows': s.supportsRows,
        'takesPresets': s.takesPresets,
        'unitsOwnKey': s.unitsOwnKey,
        'unitRange': [s.unitRange.first, s.unitRange.last],
        'defaultUnits': s.defaultUnits,
      },
  ],
};
