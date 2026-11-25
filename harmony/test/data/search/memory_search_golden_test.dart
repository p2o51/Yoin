// The Dart search core (lib/data/search/) against the shared golden vectors,
// which :shared Kotlin writes and checks through YoinMemorySearch
// (shared/src/androidHostTest/.../search/MemorySearchGoldenTest.kt). A failure
// here means the port drifted from Android; regenerate the file only from the
// Kotlin side (YOIN_WRITE_SEARCH_GOLDEN=1).
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/search/memory_search.dart';
import 'package:yoin/data/search/note_open_target.dart';
import 'package:yoin/data/search/search_filter.dart';
import 'package:yoin/data/search/search_match.dart';
import 'package:yoin/data/search/search_page.dart';
import 'package:yoin/theme/tokens.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/memory-search.json';

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  List<Map<String, dynamic>> section(String name) => (golden[name] as List).cast<Map<String, dynamic>>();

  final ledger = [for (final e in section('ledger')) _entry(e)];
  final aiTitles = {
    for (final t in section('aiTitles'))
      ledgerAlbumKey(t['profileId'] as String, t['provider'] as String, t['albumId'] as String): t['title'] as String,
  };
  final songs = [
    for (final s in section('songs'))
      SearchSongRow(
        provider: s['provider'] as String,
        rawId: s['rawId'] as String,
        title: s['title'] as String?,
        artist: s['artist'] as String?,
        album: s['album'] as String?,
      ),
  ];
  final albums = [
    for (final a in section('albums'))
      SearchAlbumRow(
        provider: a['provider'] as String,
        rawId: a['rawId'] as String,
        name: a['name'] as String,
        artist: a['artist'] as String?,
      ),
  ];
  final artists = [
    for (final a in section('artists'))
      SearchArtistRow(provider: a['provider'] as String, rawId: a['rawId'] as String, name: a['name'] as String),
  ];
  final playlists = [
    for (final p in section('playlists'))
      SearchPlaylistRow(
        provider: p['provider'] as String,
        rawId: p['rawId'] as String,
        name: p['name'] as String,
        owner: p['owner'] as String?,
      ),
  ];

  MemorySearchMatches search(String query, String? active) =>
      searchMemoryLedger(ledger, SearchQuery(query), active, aiTitles: aiTitles);

  /// The facade's attachments: the returned albums' memories, in the albums' order, each once.
  List<(SearchAlbumRef, MemoryAttachment)> attachments(String? active) {
    final found = memoryAttachments([for (final a in albums) a.ref], ledger, active, aiTitles: aiTitles);
    final seen = <SearchAlbumRef>{};
    return [
      for (final a in albums)
        if (seen.add(a.ref) && found[a.ref] != null) (a.ref, found[a.ref]!),
    ];
  }

  test('should_matchKotlinConstants_when_readingTheGolden', () {
    final constants = golden['constants'] as Map<String, dynamic>;
    expect(golden['offsets'], startsWith('UTF-16 code units'));
    expect(constants['fragmentMaxChars'], fragmentMaxChars);
    expect(constants['allMaxRows'], SearchTokens.allMaxRows);
    expect(constants['allMaxWide'], SearchTokens.allMaxWide);
    expect(constants['allRowsPerType'], searchAllRowsPerType);
    expect(constants['noteSeekTimeoutMs'], noteSeekTimeout.inMilliseconds);
  });

  group('fold', () {
    for (final (i, v) in section('fold').indexed) {
      test('should_foldAsKotlin_when_vector$i ${jsonEncode(v['text'])}', () {
        expect(FoldedText.of(v['text'] as String).text, v['folded']);
      });
    }
  });

  group('match', () {
    for (final (i, v) in section('match').indexed) {
      test('should_matchAsKotlin_when_vector$i ${_short(v['query'])} in ${_short(v['text'])}', () {
        final m = SearchQuery(v['query'] as String).match(v['text'] as String?);
        final range = m.originalRange;
        expect(
          {
            'strength': _name(m.strength),
            'start': range.isEmpty ? -1 : range.start,
            'end': range.isEmpty ? -1 : range.end,
          },
          {'strength': v['strength'], 'start': v['start'], 'end': v['end']},
        );
      });
    }
  });

  group('fragment', () {
    for (final (i, v) in section('fragment').indexed) {
      test('should_cutAsKotlin_when_vector$i ${_short(v['query'])} max ${v['maxChars']}', () {
        final q = SearchQuery(v['query'] as String);
        final m = q.match(v['text'] as String?);
        final fragment = m.strength == MatchStrength.none ? null : q.fragment(m, maxChars: v['maxChars'] as int);
        expect(fragment == null ? null : _fragment(fragment), v['fragment']);
      });
    }
  });

  group('plainWords', () {
    for (final (i, v) in section('plainWords').indexed) {
      test('should_readWordsAsKotlin_when_vector$i', () {
        expect(noteWords(v['text'] as String), v['words']);
      });
    }
  });

  group('search', () {
    for (final (i, v) in section('search').indexed) {
      test('should_findAsKotlin_when_vector$i ${_short(v['query'])} as ${v['activeProfileId']}', () {
        final r = search(v['query'] as String, v['activeProfileId'] as String?);
        expect(_result(r), _floats(v['result']));
      });
    }
  });

  group('attachments', () {
    for (final (i, v) in section('attachments').indexed) {
      test('should_attachAsKotlin_when_vector$i as ${v['activeProfileId']}', () {
        final found = attachments(v['activeProfileId'] as String?);
        expect([for (final (ref, a) in found) _attachment(ref, a)], _floats(v['result']));
      });
    }
  });

  group('pages', () {
    for (final (i, v) in section('pages').indexed) {
      final query = v['query'] as String;
      final active = v['activeProfileId'] as String?;
      final withService = v['withService'] as bool;
      test('should_layOutAsKotlin_when_vector$i ${_short(query)} as $active '
          'cols ${v['columns']} caps ${v['maxRows']}/${v['maxWide']} service $withService', () {
        final page = layoutSearchPage(
          SearchQuery(query),
          SearchServiceRows(
            songs: withService ? songs : const [],
            albums: withService ? albums : const [],
            artists: withService ? artists : const [],
            playlists: withService ? playlists : const [],
          ),
          search(query, active),
          {for (final (ref, a) in attachments(active)) ref: a},
          active,
          columns: v['columns'] as int,
          maxRows: v['maxRows'] as int,
          maxWide: v['maxWide'] as int,
        );
        expect(_page(page), v['page']);
      });
    }
  });

  group('pack', () {
    for (final (i, v) in section('pack').indexed) {
      test('should_packAsKotlin_when_vector$i', () {
        final wide = (v['wide'] as List).cast<bool>();
        final rows = packSearchTiles(
          [for (var k = 0; k < wide.length; k++) k],
          v['columns'] as int,
          maxRows: v['maxRows'] as int,
          maxWide: v['maxWide'] as int,
          isWide: (k) => wide[k],
        );
        expect([
          for (final r in rows) {'tiles': r.tiles, 'wideAtEnd': r.wideAtEnd},
        ], v['rows']);
      });
    }
  });

  group('filters', () {
    for (final (i, v) in section('filters').indexed) {
      test('should_offerChipsAsKotlin_when_vector$i', () {
        final filters = searchFilters(
          canSearchPlaylists: v['canSearchPlaylists'] as bool,
          canReadPlaylists: v['canReadPlaylists'] as bool,
          savedLibrary: v['savedLibrary'] as bool,
        );
        expect([for (final f in filters) _name(f)], v['filters']);
      });
    }
  });

  group('shownRows', () {
    for (final (i, v) in section('shownRows').indexed) {
      test('should_listAsKotlin_when_vector$i ${v['filter']} ${v['section']} ${v['available']}', () {
        final filter = SearchFilter.values.firstWhere((f) => _name(f) == v['filter']);
        final s = SearchSection.values.firstWhere((s) => _name(s) == v['section']);
        expect(filter.shownRows(s, v['available'] as int), v['shown']);
      });
    }
  });

  group('noteTargets', () {
    for (final (i, v) in section('noteTargets').indexed) {
      test('should_leadAsKotlin_when_vector$i', () {
        final returned = v['returnedSong'] as Map<String, dynamic>?;
        final target = noteOpenTarget(
          _note(v['note'] as Map<String, dynamic>),
          returned == null ? null : (provider: returned['provider'] as String, rawId: returned['rawId'] as String),
          v['activeProfileId'] as String?,
        );
        expect({
          'action': _name(target.action),
          'songProvider': target.song?.provider,
          'songId': target.song?.rawId,
          'albumProvider': target.album?.provider,
          'albumId': target.album?.rawId,
          'profileId': target.profileId,
          'switchAccount': target.switchAccount,
          'seekToMs': target.seekToMs,
          'subject': target.subject,
        }, v['target']);
      });
    }
  });

  group('noteSeekReady', () {
    for (final (i, v) in section('noteSeekReady').indexed) {
      test('should_answerAsKotlin_when_vector$i', () {
        expect(
          noteSeekReady(v['currentRawId'] as String?, v['durationMs'] as int, v['targetRawId'] as String),
          v['ready'],
        );
      });
    }
  });
}

// ── reading inputs ─────────────────────────────────────────────────

SearchLedgerAlbum? _album(Object? json) {
  if (json == null) return null;
  final o = json as Map<String, dynamic>;
  return SearchLedgerAlbum(
    profileId: o['profileId'] as String,
    provider: o['provider'] as String,
    albumId: o['albumId'] as String,
    name: o['name'] as String,
    artist: o['artist'] as String,
    coverKey: o['coverKey'] as String?,
  );
}

SearchLedgerEntry _entry(Map<String, dynamic> o) => SearchLedgerEntry(
  id: o['id'] as String,
  kind: SearchLedgerKind.values.firstWhere((k) => _name(k) == o['kind']),
  at: o['at'] as int,
  profileId: o['profileId'] as String,
  album: _album(o['album']),
  text: o['text'] as String?,
  trackTitle: o['trackTitle'] as String?,
  songId: o['songId'] as String?,
  songProvider: o['songProvider'] as String?,
  positionMs: o['positionMs'] as int?,
  score: (o['score'] as num?)?.toDouble(),
);

NoteSearchHit _note(Map<String, dynamic> o) {
  final f = o['fragment'] as Map<String, dynamic>;
  return NoteSearchHit(
    entryId: o['entryId'] as String,
    profileId: o['profileId'] as String,
    provider: o['provider'] as String?,
    songId: o['songId'] as String?,
    trackTitle: o['trackTitle'] as String?,
    album: _album(o['album']),
    positionMs: o['positionMs'] as int?,
    fragment: SearchFragment(f['text'] as String, SearchRange(f['highlightStart'] as int, f['highlightEnd'] as int)),
    score: SearchScore(
      MatchTier.values.firstWhere((t) => _name(t) == o['tier']),
      MatchStrength.values.firstWhere((s) => _name(s) == o['strength']),
    ),
    at: o['at'] as int,
    isOtherAccount: o['isOtherAccount'] as bool,
  );
}

// ── the Kotlin facade's JSON shapes ────────────────────────────────

/// A Kotlin enum constant's name: `wordPrefix` → `WordPrefix`.
String _name(Enum e) => e.name[0].toUpperCase() + e.name.substring(1);

String _short(Object? s) {
  final text = jsonEncode(s);
  return text.length > 24 ? '${text.substring(0, 24)}…' : text;
}

/// A score as Kotlin's Float: compared after rounding to 32 bits.
Object? _float(double? v) => v == null ? null : toFloat32(v);

/// [json] with every number that isn't an int read as a 32-bit float.
Object? _floats(Object? json) => switch (json) {
  final Map<String, dynamic> m => {for (final e in m.entries) e.key: _floats(e.value)},
  final List<dynamic> l => [for (final e in l) _floats(e)],
  final double d => toFloat32(d),
  _ => json,
};

Map<String, Object?> _fragment(SearchFragment f) => {
  'text': f.text,
  'highlightStart': f.highlight.isEmpty ? -1 : f.highlight.start,
  'highlightEnd': f.highlight.isEmpty ? -1 : f.highlight.end,
};

Map<String, Object?>? _albumJson(SearchLedgerAlbum? a) => a == null
    ? null
    : {
        'profileId': a.profileId,
        'provider': a.provider,
        'albumId': a.albumId,
        'name': a.name,
        'artist': a.artist,
        'coverKey': a.coverKey,
      };

Map<String, Object?>? _mark(LedgerMark? m) =>
    m == null ? null : {'kind': _name(m.kind), 'score': _float(m.score), 'noteCount': m.noteCount};

Map<String, Object?> _memory(MemorySearchHit h) => {
  'albumKey': h.album.key,
  'album': _albumJson(h.album),
  'field': _name(h.field),
  'entryId': h.entryId,
  'fragment': _fragment(h.fragment),
  'tier': _name(h.score.tier),
  'strength': _name(h.score.strength),
  'title': h.title,
  'albumScore': _float(h.albumScore),
  'lastAt': h.lastAt,
  'isOtherAccount': h.isOtherAccount,
  'mark': _mark(h.mark),
};

Map<String, Object?> _noteJson(NoteSearchHit n) => {
  'entryId': n.entryId,
  'profileId': n.profileId,
  'provider': n.provider,
  'songId': n.songId,
  'trackTitle': n.trackTitle,
  'album': _albumJson(n.album),
  'positionMs': n.positionMs,
  'fragment': _fragment(n.fragment),
  'tier': _name(n.score.tier),
  'strength': _name(n.score.strength),
  'at': n.at,
  'isOtherAccount': n.isOtherAccount,
};

Map<String, Object?> _result(MemorySearchMatches r) => {
  'memories': [for (final m in r.memories) _memory(m)],
  'notes': [for (final n in r.notes) _noteJson(n)],
};

Map<String, Object?> _attachment(SearchAlbumRef ref, MemoryAttachment a) => {
  'provider': ref.provider,
  'albumId': ref.rawId,
  'albumKey': a.albumKey,
  'title': a.title,
  'albumScore': _float(a.albumScore),
  'lastAt': a.lastAt,
  'mark': _mark(a.mark),
};

Map<String, Object?> _tile(SearchPageTile t) => {
  'kind': _name(t.kind),
  'key': t.key,
  'index': t.index,
  'wide': t.wide,
  'relevant': t.relevant,
  'tier': _name(t.score.tier),
  'strength': _name(t.score.strength),
  'note': t.note?.entryId,
  'memory': t.hit?.album.key,
  'attachment': t.attachment?.albumKey,
};

Map<String, Object?> _page(SearchPageLayout page) => {
  'best': page.best == null ? null : {'kind': _name(page.best!.kind), 'index': page.best!.index},
  'sections': [
    for (final s in page.sections)
      {
        'section': _name(s.section),
        'tier': _name(s.score.tier),
        'strength': _name(s.score.strength),
        'folded': s.folded,
        'more': s.more,
        'tiles': [for (final t in s.tiles) _tile(t)],
        'rows': [
          for (final r in s.allRows)
            {
              'keys': [for (final t in r.tiles) t.key],
              'wideAtEnd': r.wideAtEnd,
            },
        ],
      },
  ],
  'memories': [for (final t in page.memories) t.key],
};
