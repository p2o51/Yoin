// Transfer Memories (id spaces, copied note ids, mention rewrites, review
// joins, the ledger prefix and whole applier runs) against the golden vectors
// :shared Kotlin writes and checks
// (shared/src/commonTest/resources/golden/memories-transfer.json). The applier
// runs on a port of Kotlin's FakeTransferStore, as the vectors were made.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/portability/backup_ports.dart';
import 'package:yoin/data/portability/memories/memory_items.dart';
import 'package:yoin/data/portability/memories/memory_transfer_applier.dart';
import 'package:yoin/data/portability/memories/transfer_models.dart';
import 'package:yoin/data/portability/memories/transfer_rules.dart';

const _goldenPath = '../shared/src/commonTest/resources/golden/memories-transfer.json';

/// Kotlin's FakeTransferStore (commonTest), row for row.
final class FakeTransferStore implements MemoryTransferStore {
  final songNotes = <String, TransferSongNote>{};
  final albumNotes = <String, TransferAlbumNote>{};
  final trackRatings = <String, TransferTrackRating>{};
  final albums = <String, TransferAlbumRow>{};
  final titles = <String, TransferAlbumTitle>{};
  final memories = <String, TransferMemoryRow>{};
  final links = <String, BackupTrackAlbumLink>{};

  static String k(List<String> parts) => parts.join('|');

  @override
  Future<TransferNoteState?> songNote(String id) async {
    final n = songNotes[id];
    return n == null ? null : TransferNoteState(n.profileId, n.createdAt, n.updatedAt);
  }

  @override
  Future<void> putSongNote(TransferSongNote note) async => songNotes[note.id] = note;

  @override
  Future<TransferNoteState?> albumNote(String id) async {
    final n = albumNotes[id];
    return n == null ? null : TransferNoteState(n.profileId, n.createdAt, n.updatedAt);
  }

  @override
  Future<void> putAlbumNote(TransferAlbumNote note) async => albumNotes[note.id] = note;

  @override
  Future<TransferTrackRating?> trackRating(String profileId, String provider, String songId) async =>
      trackRatings[k([profileId, provider, songId])];

  @override
  Future<void> putTrackRating(String profileId, String provider, String songId, double rating, int updatedAt) async =>
      trackRatings[k([profileId, provider, songId])] = TransferTrackRating(rating, updatedAt);

  @override
  Future<TransferAlbumRow?> albumRow(String profileId, String provider, String albumId) async =>
      albums[k([profileId, provider, albumId])];

  @override
  Future<void> insertAlbumRow(String profileId, String provider, String albumId, TransferAlbumRow row) async =>
      albums.putIfAbsent(k([profileId, provider, albumId]), () => row);

  @override
  Future<void> updateAlbumRating(
    String profileId,
    String provider,
    String albumId,
    double rating,
    int updatedAt,
  ) async {
    final key = k([profileId, provider, albumId]);
    final row = albums[key]!;
    albums[key] = TransferAlbumRow(rating, row.review, row.reviewUpdatedAt, updatedAt);
  }

  @override
  Future<void> updateAlbumReview(
    String profileId,
    String provider,
    String albumId,
    String review,
    int reviewUpdatedAt,
    int updatedAt,
  ) async {
    final key = k([profileId, provider, albumId]);
    final row = albums[key]!;
    albums[key] = TransferAlbumRow(row.rating, review, reviewUpdatedAt, updatedAt);
  }

  @override
  Future<TransferAlbumTitle?> albumTitle(String profileId, String provider, String albumId) async =>
      titles[k([profileId, provider, albumId])];

  @override
  Future<void> putAlbumTitle(String profileId, String provider, String albumId, TransferAlbumTitle title) async =>
      titles[k([profileId, provider, albumId])] = title;

  @override
  Future<TransferMemoryRow?> memory(String profileId, String provider, String entityType, String entityId) async =>
      memories[k([profileId, provider, entityType, entityId])];

  @override
  Future<void> putMemory(String profileId, TransferMemoryRow row) async =>
      memories[k([profileId, row.provider, row.entityType, row.entityId])] = row;

  @override
  Future<bool> hasTrackAlbumLink(String profileId, String provider, String trackId) async =>
      links.containsKey(k([profileId, provider, trackId]));

  @override
  Future<void> putTrackAlbumLink(String profileId, BackupTrackAlbumLink link) async =>
      links[k([profileId, link.provider, link.trackId])] = link;

  void seed(Map<String, dynamic> rows) {
    for (final n in (rows['songNotes'] as List).cast<Map<String, dynamic>>()) {
      songNotes[n['id'] as String] = TransferSongNote(
        id: n['id'] as String,
        profileId: n['profileId'] as String,
        provider: n['provider'] as String,
        trackId: n['trackId'] as String,
        content: n['content'] as String,
        createdAt: n['createdAt'] as int,
        updatedAt: n['updatedAt'] as int,
        title: n['title'] as String,
        artist: n['artist'] as String,
        positionMs: n['positionMs'] as int?,
      );
    }
    for (final n in (rows['albumNotes'] as List).cast<Map<String, dynamic>>()) {
      albumNotes[n['id'] as String] = TransferAlbumNote(
        id: n['id'] as String,
        profileId: n['profileId'] as String,
        provider: n['provider'] as String,
        albumId: n['albumId'] as String,
        content: n['content'] as String,
        createdAt: n['createdAt'] as int,
        updatedAt: n['updatedAt'] as int,
        albumName: n['albumName'] as String,
        artist: n['artist'] as String,
      );
    }
    for (final r in (rows['trackRatings'] as List).cast<Map<String, dynamic>>()) {
      trackRatings[k([r['profileId'] as String, r['provider'] as String, r['songId'] as String])] = TransferTrackRating(
        (r['rating'] as num?)?.toDouble(),
        r['updatedAt'] as int,
      );
    }
    for (final r in (rows['albumRows'] as List).cast<Map<String, dynamic>>()) {
      albums[k([r['profileId'] as String, r['provider'] as String, r['albumId'] as String])] = TransferAlbumRow(
        (r['rating'] as num).toDouble(),
        r['review'] as String?,
        r['reviewUpdatedAt'] as int?,
        r['updatedAt'] as int,
      );
    }
    for (final t in (rows['albumTitles'] as List).cast<Map<String, dynamic>>()) {
      titles[k([t['profileId'] as String, t['provider'] as String, t['albumId'] as String])] = TransferAlbumTitle(
        t['title'] as String,
        t['updatedAt'] as int,
      );
    }
    for (final m in (rows['memories'] as List).cast<Map<String, dynamic>>()) {
      memories[k([
        m['profileId'] as String,
        m['provider'] as String,
        m['entityType'] as String,
        m['entityId'] as String,
      ])] = TransferMemoryRow(
        provider: m['provider'] as String,
        entityType: m['entityType'] as String,
        entityId: m['entityId'] as String,
        copyText: m['copy'] as String,
        promptHash: m['promptHash'] as String,
        generatedAt: m['generatedAt'] as int,
        title: m['title'] as String?,
        titlePromptHash: m['titlePromptHash'] as String?,
      );
    }
    for (final l in (rows['trackAlbumLinks'] as List).cast<Map<String, dynamic>>()) {
      links[k([l['profileId'] as String, l['provider'] as String, l['trackId'] as String])] = BackupTrackAlbumLink(
        provider: l['provider'] as String,
        trackId: l['trackId'] as String,
        albumId: l['albumId'] as String,
        source: l['source'] as String,
        updatedAt: l['updatedAt'] as int,
      );
    }
  }

  Map<String, Object?> json() {
    List<Map<String, Object?>> sorted<V>(Map<String, V> rows, Map<String, Object?> Function(List<String>, V) row) {
      final keys = rows.keys.toList()..sort();
      return [for (final key in keys) row(key.split('|'), rows[key] as V)];
    }

    final noteIds = songNotes.keys.toList()..sort();
    final albumNoteIds = albumNotes.keys.toList()..sort();
    return {
      'songNotes': [
        for (final id in noteIds)
          {
            'id': id,
            'profileId': songNotes[id]!.profileId,
            'provider': songNotes[id]!.provider,
            'trackId': songNotes[id]!.trackId,
            'content': songNotes[id]!.content,
            'createdAt': songNotes[id]!.createdAt,
            'updatedAt': songNotes[id]!.updatedAt,
            'title': songNotes[id]!.title,
            'artist': songNotes[id]!.artist,
            'positionMs': songNotes[id]!.positionMs,
          },
      ],
      'albumNotes': [
        for (final id in albumNoteIds)
          {
            'id': id,
            'profileId': albumNotes[id]!.profileId,
            'provider': albumNotes[id]!.provider,
            'albumId': albumNotes[id]!.albumId,
            'content': albumNotes[id]!.content,
            'createdAt': albumNotes[id]!.createdAt,
            'updatedAt': albumNotes[id]!.updatedAt,
            'albumName': albumNotes[id]!.albumName,
            'artist': albumNotes[id]!.artist,
          },
      ],
      'trackRatings': sorted(
        trackRatings,
        (k, r) => {'profileId': k[0], 'provider': k[1], 'songId': k[2], 'rating': r.rating, 'updatedAt': r.updatedAt},
      ),
      'albumRows': sorted(
        albums,
        (k, r) => {
          'profileId': k[0],
          'provider': k[1],
          'albumId': k[2],
          'rating': r.rating,
          'review': r.review,
          'reviewUpdatedAt': r.reviewUpdatedAt,
          'updatedAt': r.updatedAt,
        },
      ),
      'albumTitles': sorted(
        titles,
        (k, r) => {'profileId': k[0], 'provider': k[1], 'albumId': k[2], 'title': r.title, 'updatedAt': r.updatedAt},
      ),
      'memories': sorted(
        memories,
        (k, r) => {
          'profileId': k[0],
          'provider': r.provider,
          'entityType': r.entityType,
          'entityId': r.entityId,
          'copy': r.copyText,
          'promptHash': r.promptHash,
          'generatedAt': r.generatedAt,
          'title': r.title,
          'titlePromptHash': r.titlePromptHash,
        },
      ),
      'trackAlbumLinks': sorted(
        links,
        (k, l) => {
          'profileId': k[0],
          'provider': l.provider,
          'trackId': l.trackId,
          'albumId': l.albumId,
          'source': l.source,
          'updatedAt': l.updatedAt,
        },
      ),
    };
  }
}

SourceRef ref(String text) {
  final sep = text.indexOf(':');
  return SourceRef(text.substring(0, sep), text.substring(sep + 1));
}

TransferAccount account(Map<String, dynamic> a) => TransferAccount(
  scope: a['scope'] as String,
  provider: a['provider'] as String,
  displayName: a['displayName'] as String,
  serverKey: a['serverKey'] as String?,
  localProfileId: a['localProfileId'] as String?,
);

MemoryItems items(Map<String, dynamic> i) {
  List<Map<String, dynamic>> list(String key) => (i[key] as List).cast<Map<String, dynamic>>();
  double num_(Object? v) => (v as num).toDouble();
  return MemoryItems(
    songNotes: [
      for (final n in list('songNotes'))
        SourceNote(
          id: n['id'] as String,
          track: ref(n['track'] as String),
          content: n['content'] as String,
          createdAt: n['createdAt'] as int,
          updatedAt: n['updatedAt'] as int,
          title: n['title'] as String,
          artist: n['artist'] as String,
          positionMs: n['positionMs'] as int?,
        ),
    ],
    trackRatings: [
      for (final r in list('trackRatings'))
        SourceTrackRating(ref(r['track'] as String), num_(r['rating']), r['updatedAt'] as int),
    ],
    albumRatings: [
      for (final r in list('albumRatings'))
        SourceAlbumRating(ref(r['album'] as String), num_(r['rating']), r['updatedAt'] as int),
    ],
    albumReviews: [
      for (final r in list('albumReviews'))
        SourceAlbumReview(
          ref(r['album'] as String),
          r['review'] as String,
          r['updatedAt'] as int,
          r['reviewUpdatedAt'] as int?,
        ),
    ],
    albumNotes: [
      for (final n in list('albumNotes'))
        SourceAlbumNote(
          id: n['id'] as String,
          album: ref(n['album'] as String),
          content: n['content'] as String,
          createdAt: n['createdAt'] as int,
          updatedAt: n['updatedAt'] as int,
          albumName: n['albumName'] as String,
          artist: n['artist'] as String,
        ),
    ],
    albumTitles: [
      for (final t in list('albumTitles'))
        SourceAlbumTitle(ref(t['album'] as String), t['title'] as String, t['updatedAt'] as int),
    ],
    memoryTitles: [
      for (final t in list('memoryTitles'))
        SourceMemoryTitle(
          t['provider'] as String,
          t['entityType'] as String,
          t['entityId'] as String,
          t['title'] as String,
          t['titlePromptHash'] as String?,
          t['updatedAt'] as int,
        ),
    ],
    memoryCopies: [
      for (final c in list('memoryCopies'))
        SourceMemoryCopy(
          c['provider'] as String,
          c['entityType'] as String,
          c['entityId'] as String,
          c['copy'] as String,
          c['promptHash'] as String,
          c['generatedAt'] as int,
        ),
    ],
    trackAlbumLinks: [
      for (final l in list('trackAlbumLinks'))
        SourceTrackLink(
          ref(l['track'] as String),
          l['albumId'] as String,
          l['source'] as String,
          l['updatedAt'] as int,
        ),
    ],
    trackFacts: {
      for (final e in (i['trackFacts'] as Map).entries)
        ref(e.key as String): SourceTrackFacts(
          title: e.value['title'] as String,
          artist: e.value['artist'] as String,
          album: e.value['album'] as String?,
          albumId: e.value['albumId'] as String?,
          durationSec: e.value['durationSec'] as int?,
        ),
    },
    albumFacts: {
      for (final e in (i['albumFacts'] as Map).entries)
        ref(e.key as String): SourceAlbumFacts(e.value['name'] as String, e.value['artist'] as String),
    },
  );
}

void main() {
  final golden = jsonDecode(File(_goldenPath).readAsStringSync()) as Map<String, dynamic>;
  List<Map<String, dynamic>> section(String name) => (golden[name] as List).cast<Map<String, dynamic>>();

  test('should_matchKotlin_when_decidingIdSpaces', () {
    final accounts = <String, TransferAccount>{};
    // Every (source, target) the vector lists, rebuilt from its scope: the accounts PortabilityGoldenTest uses.
    for (final a in const [
      ('a', 's-a', 'subsonic', 'music.example.com'),
      ('b', 's-b', 'subsonic', 'MUSIC.example.com'),
      ('c', 's-c', 'subsonic', 'other.example.com'),
      ('d', 's-d', 'subsonic', null),
      ('x', 'a-1', 'applemusic', null),
      ('y', 'a-2', 'applemusic', null),
      ('s1', 's-s1', 'spotify', null),
      ('s2', 's-s2', 'spotify', null),
      ('l1', 'local-l1', 'local', null),
    ]) {
      accounts[a.$2] = TransferAccount(
        scope: a.$2,
        provider: a.$3,
        displayName: a.$2,
        serverKey: a.$4,
        localProfileId: a.$1,
      );
    }
    final vectors = section('compatible');
    expect(vectors, isNotEmpty);
    for (final v in vectors) {
      expect(
        TransferRules.compatible(accounts[v['source']]!, accounts[v['target']]!, ref(v['ref'] as String)),
        v['compatible'],
        reason: '${v['source']} -> ${v['target']} ${v['ref']}',
      );
    }
  });

  test('should_deriveKotlinsIds_when_copyingNotes', () {
    for (final v in section('migrationIds')) {
      expect(MigrationIds.derive(v['srcScope'] as String, v['srcId'] as String, v['dstScope'] as String), v['id']);
    }
  });

  test('should_rewriteMentionsAsKotlin_when_itemsAreMapped', () {
    for (final v in section('remapMentions')) {
      TargetRef? lookup(Map<String, dynamic> map, SourceRef r) {
        final to = map[r.toString()] as String?;
        return to == null ? null : TransferRules.parseTarget(to);
      }

      final out = TransferRules.remapMentions(
        v['text'] as String,
        (r) => lookup(v['tracks'] as Map<String, dynamic>, r),
        (r) => lookup(v['albums'] as Map<String, dynamic>, r),
      );
      expect(out, v['out'], reason: v['text'] as String);
    }
  });

  test('should_joinReviewsAsKotlin_when_bothHaveText', () {
    for (final v in section('joinReviews')) {
      expect(
        TransferRules.joinReviews(v['target'] as String, v['source'] as String, v['sourceAccount'] as String),
        v['out'],
      );
    }
  });

  test('should_keyTheLedgerAsKotlin_when_storingIt', () {
    for (final v in section('ledgerMetaPrefix')) {
      expect(TransferLedger.metaPrefix(v['srcScope'] as String, v['dstProfileId'] as String), v['prefix']);
    }
  });

  for (final c in section('cases')) {
    test('should_matchKotlin_when_transferring ${c['name']}', () async {
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expected'] as Map<String, dynamic>;
      final store = FakeTransferStore()..seed(input['target_rows'] as Map<String, dynamic>);
      expect(store.json(), input['target_rows'], reason: 'seeded rows read back');
      final outcome = await MemoryTransferApplier(store).apply(
        source: account(input['source'] as Map<String, dynamic>),
        target: account(input['target'] as Map<String, dynamic>),
        items: items(input['items'] as Map<String, dynamic>),
        tracks: [
          for (final t in (input['tracks'] as List).cast<Map<String, dynamic>>())
            TrackPick(
              ref(t['source'] as String),
              MatchStatus.byWireName(t['status'] as String),
              t['chosen'] == null
                  ? null
                  : TrackTarget(
                      provider: t['chosen']['provider'] as String,
                      id: t['chosen']['id'] as String,
                      title: t['chosen']['title'] as String?,
                      artist: t['chosen']['artist'] as String?,
                      albumProvider: t['chosen']['albumProvider'] as String?,
                      albumId: t['chosen']['albumId'] as String?,
                    ),
            ),
        ],
        albums: [
          for (final a in (input['albums'] as List).cast<Map<String, dynamic>>())
            AlbumPick(
              ref(a['source'] as String),
              MatchStatus.byWireName(a['status'] as String),
              a['chosen'] == null
                  ? null
                  : AlbumTarget(
                      provider: a['chosen']['provider'] as String,
                      id: a['chosen']['id'] as String,
                      name: a['chosen']['name'] as String,
                      artist: a['chosen']['artist'] as String?,
                    ),
            ),
        ],
        ledger: (input['ledger'] as Map).cast<String, String>(),
        clock: () => input['now'] as int,
      );
      final r = outcome.result;
      expect({
        'copied': r.copied.toJson(),
        'updated': r.updated.toJson(),
        'kept': r.kept.toJson(),
        'unmatched': r.unmatched.toJson(),
        'reviewsJoined': r.reviewsJoined,
        'skippedPreviously': r.skippedPreviously,
      }, expected['result']);
      expect(outcome.ledgerEntries, expected['ledgerEntries']);
      expect(
        outcome.ledgerEntries.keys.toList(),
        (expected['ledgerEntries'] as Map).keys.toList(),
        reason: 'ledger order',
      );
      expect(store.json(), expected['target_rows']);
    });
  }
}
