// The Dart release marks (lib/data/release/release_marks.dart) against the shared golden vectors, which
// :shared Kotlin writes through YoinReleaseMarks (ReleaseMarksGoldenTest, androidHostTest) and replays on
// the JVM and the iOS simulator. A failure here means the port drifted from Android; regenerate the file
// only from the Kotlin side.
//
// Every section is replayed, the Spotify-only ones too (explicitFlag, fromTracks, tracklistComplete,
// spotifyReleaseType: HarmonyOS has no Spotify, but the rules are a few lines and replaying them keeps the
// port whole), except releaseTypeStorage: HarmonyOS stores a release's kind by its Dart enum name (the
// detail cache's 'rt', since before PR #343), not Kotlin's, so Kotlin's stored names are never read here.
// Ratings, kinds and filters are Kotlin names in the file; they are mapped to the Dart enums explicitly.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/release/release_marks.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/release-marks.json';

const _sections = [
  'appleMusicRating', 'openSubsonicRating', 'explicitFlag', 'fromTracks', 'tracklistComplete', 'storage', //
  'spotifyReleaseType', 'subsonicReleaseType', 'appleMusicReleaseType', 'kindSuffix', 'kindTag',
  'filterAccepts', 'offeredFilters', 'badgeFits', 'badgeSize', 'coverBand',
];

typedef _Json = Map<String, dynamic>;

const _ratings = {'Explicit': ContentRating.explicit, 'Clean': ContentRating.clean};
const _types = {
  'Album': ReleaseType.album,
  'EP': ReleaseType.ep,
  'Single': ReleaseType.single,
  'Compilation': ReleaseType.compilation,
};
const _filters = {
  'All': ReleaseKindFilter.all,
  'Albums': ReleaseKindFilter.albums,
  'SinglesAndEps': ReleaseKindFilter.singlesAndEps,
  'Compilations': ReleaseKindFilter.compilations,
};
const _roles = {'Kind': CoverBandRole.kind, 'Version': CoverBandRole.version, 'Explicit': CoverBandRole.explicit};

String? _ratingName(ContentRating? r) => r == null ? null : _ratings.entries.firstWhere((e) => e.value == r).key;
String? _typeName(ReleaseType? t) => t == null ? null : _types.entries.firstWhere((e) => e.value == t).key;
String _filterName(ReleaseKindFilter f) => _filters.entries.firstWhere((e) => e.value == f).key;
String _roleName(CoverBandRole r) => _roles.entries.firstWhere((e) => e.value == r).key;
ReleaseType? _type(Object? name) => name == null ? null : _types[name]!;
double _d(Object? n) => (n as num).toDouble();

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as _Json;

  test('should_matchKotlinConstants_when_read', () {
    final c = golden['constants'] as _Json;
    const near = 1e-6;
    expect(_d(c['capHeightEm']), closeTo(ReleaseMarks.capHeightEm, near));
    expect(_d(c['xHeightEm']), closeTo(ReleaseMarks.xHeightEm, near));
    expect(_d(c['letterShare']), closeTo(ReleaseMarks.letterShare, near));
    expect(_d(c['cornerShare']), closeTo(ReleaseMarks.cornerShare, near));
    expect(_d(c['minSideDp']), ReleaseMarks.minSideDp);
    expect(_d(c['heroMaxSideDp']), ReleaseMarks.heroMaxSideDp);
    expect(_d(c['gapDp']), ReleaseMarks.gapDp);
    expect(_d(c['bandBadgeTextSp']), ReleaseMarks.bandBadgeTextSp);
    expect(_d(c['bandInsetFraction']), closeTo(ReleaseMarks.bandInsetFraction, near));
    expect(_d(c['bandMinTagWidthDp']), ReleaseMarks.bandMinTagWidthDp);
    expect(_d(c['bandGapDp']), ReleaseMarks.bandGapDp);
    expect(_d(c['bandStickerRoomDp']), ReleaseMarks.bandStickerRoomDp);
    // The enums: the same members, in the same order, as Kotlin's.
    expect(c['ratings'], [for (final r in ContentRating.values) _ratingName(r)]);
    expect(c['releaseTypes'], [for (final t in ReleaseType.values) _typeName(t)]);
    expect(c['filters'], [for (final f in ReleaseKindFilter.values) _filterName(f)]);
  });

  test('should_coverEverySection_when_read', () {
    for (final section in _sections) {
      expect(golden[section], isA<List<dynamic>>(), reason: 'missing section $section');
    }
    final total = _sections.fold<int>(0, (n, s) => n + (golden[s] as List).length);
    expect(total, 196);
  });

  for (final section in _sections) {
    group(section, () {
      final vectors = (golden[section] as List).cast<_Json>();
      for (var i = 0; i < vectors.length; i++) {
        final vector = vectors[i];
        final name = vector['name'] as String?;
        test('should_answerAsKotlin_when_${section}_$i${name == null ? '' : ' ($name)'}', () {
          _check(section, vector, '$section[$i] ${name ?? ''}');
        });
      }
    });
  }
}

void _check(String section, _Json v, String reason) {
  switch (section) {
    case 'appleMusicRating':
      expect(_ratingName(ContentRating.fromAppleMusic(v['value'] as String?)), v['rating'], reason: reason);
    case 'openSubsonicRating':
      expect(_ratingName(ContentRating.fromOpenSubsonic(v['value'] as String?)), v['rating'], reason: reason);
    case 'explicitFlag':
      expect(_ratingName(ContentRating.fromExplicitFlag(v['explicit'] as bool?)), v['rating'], reason: reason);
    case 'fromTracks':
      final ratings = [for (final r in v['ratings'] as List) r == null ? null : _ratings[r]!];
      expect(
        _ratingName(ContentRating.fromTracks(ratings, complete: v['complete'] as bool)),
        v['rating'],
        reason: reason,
      );
    case 'tracklistComplete':
      expect(
        ReleaseMarks.tracklistComplete(v['loadedTracks'] as int, v['totalTracks'] as int?, v['pageTotal'] as int?),
        v['complete'],
        reason: reason,
      );
    case 'storage':
      final rating = ContentRating.fromStorage(v['name'] as String?);
      expect(_ratingName(rating), v['rating'], reason: reason);
      // What Harmony writes reads back as the same rating.
      if (rating != null) expect(ContentRating.fromStorage(rating.storageName), rating, reason: reason);
    case 'spotifyReleaseType':
      expect(
        _typeName(ReleaseMarks.spotifyReleaseType(v['albumType'] as String?, v['totalTracks'] as int?)),
        v['type'],
        reason: reason,
      );
    case 'subsonicReleaseType':
      expect(
        _typeName(ReleaseMarks.subsonicReleaseType((v['releaseTypes'] as List).cast<String>())),
        v['type'],
        reason: reason,
      );
    case 'appleMusicReleaseType':
      expect(
        _typeName(
          ReleaseMarks.appleMusicReleaseType(
            v['name'] as String?,
            isCompilation: v['isCompilation'] as bool?,
            isSingle: v['isSingle'] as bool?,
          ),
        ),
        v['type'],
        reason: reason,
      );
    case 'kindSuffix':
      expect(ReleaseMarks.nameWithoutKindSuffix(v['name'] as String, _type(v['type'])), v['shown'], reason: reason);
    case 'kindTag':
      final type = _type(v['type']);
      expect(ReleaseMarks.showsKindTag(type), v['showsKindTag'], reason: reason);
      expect(_typeName(ReleaseMarks.headerKind(type)), v['headerKind'], reason: reason);
    case 'filterAccepts':
      expect(_filters[v['filter']]!.accepts(_type(v['type'])), v['accepts'], reason: reason);
    case 'offeredFilters':
      final types = [for (final t in v['types'] as List) _type(t)];
      expect([for (final f in ReleaseKindFilter.offeredFor(types)) _filterName(f)], v['filters'], reason: reason);
    case 'badgeFits':
      expect(
        ReleaseMarks.explicitBadgeFits(v['maxWidth'] as int, v['titleRoom'] as int, v['naturalTitle'] as int),
        v['fits'],
        reason: reason,
      );
    case 'badgeSize':
      final fontSize = _d(v['fontSize']);
      final hero = v['hero'] as bool;
      final minSide = _d(v['minSide']);
      final heroMaxSide = _d(v['heroMaxSide']);
      expect(
        ReleaseMarks.badgeSide(fontSize, hero: hero, minSide: minSide, heroMaxSide: heroMaxSide),
        closeTo(_d(v['side']), 1e-4),
        reason: reason,
      );
      expect(
        ReleaseMarks.badgeGap(
          fontSize,
          hero: hero,
          minSide: minSide,
          heroMaxSide: heroMaxSide,
          minGap: _d(v['minGap']),
        ),
        v['gap'],
        reason: reason,
      );
    case 'coverBand':
      CoverBandItem? item(Object? j) =>
          j == null ? null : CoverBandItem((j as _Json)['width'] as int, j['height'] as int);
      final layout = ReleaseMarks.coverBand(
        width: v['width'] as int,
        height: v['height'] as int,
        coverFraction: _d(v['coverFraction']),
        stickerRoom: v['stickerRoom'] as int,
        gap: v['gap'] as int,
        minTagWidth: v['minTagWidth'] as int,
        kind: item(v['kind']),
        version: item(v['version']),
        explicit: item(v['explicit']),
      );
      expect(layout.band, v['band'], reason: '$reason band');
      expect(layout.start, v['start'], reason: '$reason start');
      expect(
        [
          for (final p in layout.placed)
            {'role': _roleName(p.role), 'x': p.x, 'y': p.y, 'width': p.width, 'height': p.height},
        ],
        v['placed'],
        reason: reason,
      );
    default:
      fail('unknown section $section');
  }
}
