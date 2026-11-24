// A HarmonyOS device for the portability tests: the real sync adapters over
// in-memory stores, seeded from and read back as the golden vectors' neutral
// "domain" rows (PortabilityGoldenTest.domainJson).
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/memory/track_album_links.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/portability/backup_ports.dart';
import 'package:yoin/data/portability/harmony_stores.dart';
import 'package:yoin/data/sync/adapters/memory_copy_sync_adapters.dart';
import 'package:yoin/data/sync/adapters/memory_sync_adapters.dart';
import 'package:yoin/data/sync/adapters/settings_sync_adapter.dart';
import 'package:yoin/data/sync/adapters/track_rating_sync_adapter.dart';
import 'package:yoin/data/sync/sync_contracts.dart';
import 'package:yoin/data/sync/sync_database.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/local_ratings.dart';

final class PortabilityDevice {
  PortabilityDevice({List<PortabilityProfile> profiles = const []}) : profiles = [...profiles] {
    stores = HarmonyMemoryStores(
      store: kv,
      ratings: StoreTrackRatingAccess(LocalRatingStore(kv)),
      profileIds: () async => [for (final p in this.profiles) p.id],
    );
  }

  final kv = InMemoryStore();
  final List<PortabilityProfile> profiles;
  late final HarmonyMemoryStores stores;
  SyncDatabase? _db;

  Future<SyncDatabase> db() async {
    if (_db != null) return _db!;
    final db = SyncDatabase(MemorySyncDbFile());
    await db.open();
    return _db = db;
  }

  /// The adapters sync uses for what the golden vectors cover.
  Future<List<SyncAdapter>> adapters() async {
    final db = await this.db();
    return [
      SongNoteSyncAdapter(stores.memory),
      AlbumNoteSyncAdapter(stores.memory),
      TrackRatingSyncAdapter(stores.ratings),
      AlbumRatingSyncAdapter(stores.memory),
      AlbumReviewSyncAdapter(stores.memory),
      SettingsSyncAdapter(
        language: KeyValueTranslationLanguageGateway(kv),
        seam: KeyValueSeamStyleGateway(kv),
        tracked: (key) async => db.getLocalState(SyncKinds.setting, SyncFormat.globalScope, key)?.localHash != null,
      ),
      MemoryCopySyncAdapter(stores.copies),
      MemoryTitleSyncAdapter(stores.copies),
      AlbumTitleSyncAdapter(stores.memory),
    ];
  }

  Future<void> seed(Map<String, dynamic> domain) async {
    for (final n in (domain['songNotes'] as List).cast<Map<String, dynamic>>()) {
      await stores
          .memory(n['profileId'] as String)
          .putSongNote(
            SongNote(
              id: n['id'] as String,
              trackId: MediaId(n['provider'] as String, n['trackId'] as String),
              content: n['content'] as String,
              createdAt: n['createdAt'] as int,
              updatedAt: n['updatedAt'] as int,
              title: n['title'] as String,
              artist: n['artist'] as String,
              positionMs: n['positionMs'] as int?,
            ),
          );
    }
    for (final n in (domain['albumNotes'] as List).cast<Map<String, dynamic>>()) {
      await stores
          .memory(n['profileId'] as String)
          .putAlbumNote(
            AlbumNote(
              id: n['id'] as String,
              albumId: MediaId(n['provider'] as String, n['albumId'] as String),
              content: n['content'] as String,
              createdAt: n['createdAt'] as int,
              updatedAt: n['updatedAt'] as int,
              albumName: n['albumName'] as String,
              artist: n['artist'] as String,
            ),
          );
    }
    for (final r in (domain['trackRatings'] as List).cast<Map<String, dynamic>>()) {
      final profileId = r['profileId'] as String;
      await stores.ratings.edit(profileId, (rows) {
        rows[MediaId(r['provider'] as String, r['songId'] as String)] = LocalRating(
          profileId: profileId,
          songId: r['songId'] as String,
          provider: r['provider'] as String,
          rating: (r['rating'] as num?)?.toDouble(),
          serverRating: 0,
          updatedAt: r['updatedAt'] as int,
        );
      });
    }
    for (final r in (domain['albumRatings'] as List).cast<Map<String, dynamic>>()) {
      final id = MediaId(r['provider'] as String, r['albumId'] as String);
      await stores.memory(r['profileId'] as String).syncEdit((rows) {
        rows.albumRatings[id] = AlbumRatingRow(
          albumId: id,
          rating: (r['rating'] as num).toDouble(),
          review: r['review'] as String?,
          updatedAt: r['updatedAt'] as int,
          reviewUpdatedAt: r['reviewUpdatedAt'] as int?,
        );
      });
    }
    for (final t in (domain['albumTitles'] as List? ?? const []).cast<Map<String, dynamic>>()) {
      final id = MediaId(t['provider'] as String, t['albumId'] as String);
      await stores.memory(t['profileId'] as String).syncEdit((rows) {
        rows.titles[id] = MemoryTitleRow(albumId: id, title: t['title'] as String, updatedAt: t['updatedAt'] as int);
      });
    }
    for (final m in (domain['memories'] as List).cast<Map<String, dynamic>>()) {
      final id = MediaId(m['provider'] as String, m['entityId'] as String);
      await stores.copies(m['profileId'] as String).syncEdit((rows) {
        rows[id] = MemoryCopyRow(
          copy: m['copy'] as String,
          promptHash: m['promptHash'] as String,
          generatedAt: m['generatedAt'] as int,
          title: m['title'] as String?,
          titlePromptHash: m['titlePromptHash'] as String?,
        );
      });
    }
    for (final l in (domain['trackAlbumLinks'] as List).cast<Map<String, dynamic>>()) {
      await stores.links(l['profileId'] as String).putAll([
        TrackAlbumLink(
          trackId: MediaId(l['provider'] as String, l['trackId'] as String),
          albumId: MediaId(l['provider'] as String, l['albumId'] as String),
          source: l['source'] as String,
          updatedAt: l['updatedAt'] as int,
        ),
      ]);
    }
    final language = (domain['settings'] as Map?)?['translationLanguage'] as String?;
    if (language != null && language != 'English') await KeyValueTranslationLanguageGateway(kv).write(language);
  }

  /// The stores as the golden's neutral rows, each list sorted as Kotlin sorts it.
  Future<Map<String, Object?>> domain({bool albumRows = false}) async {
    final songNotes = <Map<String, Object?>>[];
    final albumNotes = <Map<String, Object?>>[];
    final ratings = <(String, Map<String, Object?>)>[];
    final albums = <(String, Map<String, Object?>)>[];
    final titles = <(String, Map<String, Object?>)>[];
    final memories = <(String, Map<String, Object?>)>[];
    final links = <(String, Map<String, Object?>)>[];
    for (final p in profiles) {
      final rows = await stores.memory(p.id).syncRead();
      for (final n in rows.songNotes) {
        songNotes.add({
          'id': n.id,
          'profileId': p.id,
          'provider': n.trackId.provider,
          'trackId': n.trackId.rawId,
          'content': n.content,
          'createdAt': n.createdAt,
          'updatedAt': n.updatedAt,
          'title': n.title,
          'artist': n.artist,
          'positionMs': n.positionMs,
        });
      }
      for (final n in rows.albumNotes) {
        albumNotes.add({
          'id': n.id,
          'profileId': p.id,
          'provider': n.albumId.provider,
          'albumId': n.albumId.rawId,
          'content': n.content,
          'createdAt': n.createdAt,
          'updatedAt': n.updatedAt,
          'albumName': n.albumName,
          'artist': n.artist,
        });
      }
      for (final r in (await stores.ratings.read(p.id)).values) {
        ratings.add((
          [p.id, r.provider, r.songId].join('|'),
          {'profileId': p.id, 'provider': r.provider, 'songId': r.songId, 'rating': r.rating, 'updatedAt': r.updatedAt},
        ));
      }
      for (final r in rows.albumRatings.values) {
        albums.add((
          [p.id, r.albumId.provider, r.albumId.rawId].join('|'),
          {
            'profileId': p.id,
            'provider': r.albumId.provider,
            'albumId': r.albumId.rawId,
            'rating': r.rating,
            'review': r.review,
            'reviewUpdatedAt': r.reviewUpdatedAt,
            'updatedAt': r.updatedAt,
          },
        ));
      }
      for (final t in rows.titles.values) {
        titles.add((
          [p.id, t.albumId.provider, t.albumId.rawId].join('|'),
          {
            'profileId': p.id,
            'provider': t.albumId.provider,
            'albumId': t.albumId.rawId,
            'title': t.title,
            'updatedAt': t.updatedAt,
          },
        ));
      }
      for (final MapEntry(key: id, value: m) in (await stores.copies(p.id).syncRead()).entries) {
        memories.add((
          [p.id, id.provider, memoryCopyEntityAlbum, id.rawId].join('|'),
          {
            'profileId': p.id,
            'provider': id.provider,
            'entityType': memoryCopyEntityAlbum,
            'entityId': id.rawId,
            'copy': m.copy,
            'promptHash': m.promptHash,
            'generatedAt': m.generatedAt,
            'title': m.title,
            'titlePromptHash': m.titlePromptHash,
          },
        ));
      }
      for (final l in (await stores.links(p.id).links()).values) {
        links.add((
          [p.id, l.trackId.provider, l.trackId.rawId].join('|'),
          {
            'profileId': p.id,
            'provider': l.trackId.provider,
            'trackId': l.trackId.rawId,
            'albumId': l.albumId?.rawId,
            'source': l.source,
            'updatedAt': l.updatedAt,
          },
        ));
      }
    }
    List<Map<String, Object?>> sorted(List<(String, Map<String, Object?>)> rows) => [
      for (final r in rows..sort((a, b) => a.$1.compareTo(b.$1))) r.$2,
    ];
    songNotes.sort((a, b) => (a['id'] as String).compareTo(b['id'] as String));
    albumNotes.sort((a, b) => (a['id'] as String).compareTo(b['id'] as String));
    return {
      'songNotes': songNotes,
      'albumNotes': albumNotes,
      'trackRatings': sorted(ratings),
      albumRows ? 'albumRows' : 'albumRatings': sorted(albums),
      'albumTitles': sorted(titles),
      'memories': sorted(memories),
      'trackAlbumLinks': sorted(links),
      if (!albumRows)
        'settings': {'translationLanguage': await KeyValueTranslationLanguageGateway(kv).stored() ?? 'English'},
    };
  }
}
