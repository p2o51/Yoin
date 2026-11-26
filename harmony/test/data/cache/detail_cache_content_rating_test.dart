// The explicit / clean mark in the detail cache (Android DetailCacheContentRatingTest, PR #343): tracks and
// albums keep it, a row from before it reads as unknown, and an Apple Music album / artist row from before it
// is a miss (read again) while a Subsonic one is still served.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/detail_cache_json.dart';
import 'package:yoin/data/cache/detail_cache_store.dart';
import 'package:yoin/data/model/model.dart';

Album _album(MediaId id, {ContentRating? rating, List<ContentRating?> tracks = const []}) => Album(
  id: id,
  name: 'DAMN.',
  artist: 'Kendrick Lamar',
  contentRating: rating,
  tracks: [
    for (final (i, r) in tracks.indexed)
      Track(id: MediaId(id.provider, '${id.rawId}-t$i'), title: 'Song $i', contentRating: r),
  ],
);

void main() {
  late Directory temp;
  setUp(() => temp = Directory.systemTemp.createTempSync('yoin_detail_rating'));
  tearDown(() => temp.deleteSync(recursive: true));

  DetailCacheStore store() => DetailCacheStore(() async => temp, clock: () => 1000);

  /// Rewrites every stored row as a build from before PR #343 wrote it: no format, no ratings.
  void makeRowsLegacy() {
    Object? strip(Object? json) => switch (json) {
      final Map<String, Object?> m => {
        for (final e in m.entries)
          if (e.key != 'fv' && e.key != 'cr') e.key: strip(e.value),
      },
      final List<Object?> l => [for (final e in l) strip(e)],
      _ => json,
    };
    for (final f in Directory('${temp.path}/detail_cache').listSync().whereType<File>()) {
      if (f.path.endsWith('index.json')) continue;
      final raw = (jsonDecode(f.readAsStringSync()) as Map).cast<String, Object?>();
      f.writeAsStringSync(jsonEncode({...raw, 'data': strip(raw['data'])}));
    }
  }

  group('DetailCacheJson', () {
    test('should_keepEveryRating_when_roundTripped', () {
      final a = _album(
        MediaId.subsonic('al1'),
        rating: ContentRating.explicit,
        tracks: [ContentRating.explicit, ContentRating.clean, null],
      );
      final back = DetailCacheJson.albumFrom(DetailCacheJson.album(a))!;
      expect(back.contentRating, ContentRating.explicit);
      expect(back.isExplicit, isTrue);
      expect([for (final t in back.tracks) t.contentRating], [ContentRating.explicit, ContentRating.clean, null]);
      expect(DetailCacheJson.track(a.tracks.first)['cr'], 'Explicit');
    });

    test('should_readUnknown_when_ratingMissingOrUnknownName', () {
      final json = DetailCacheJson.album(_album(MediaId.subsonic('al1'), tracks: [null]));
      expect(json.containsKey('cr'), isFalse);
      expect(DetailCacheJson.albumFrom(json)!.contentRating, isNull);
      expect(DetailCacheJson.albumFrom({...json, 'cr': 'Restricted'})!.contentRating, isNull);
      expect(DetailCacheJson.trackFrom({'id': 'subsonic:t1', 'cr': 42})?.contentRating, isNull);
    });

    test('should_markFormat_when_albumOrArtistWritten', () {
      final a = _album(MediaId(MediaId.providerAppleMusic, '1'));
      expect(DetailCacheFormat.of(DetailCacheJson.album(a)), DetailCacheFormat.current);
      final artist = ArtistDetail(id: MediaId(MediaId.providerAppleMusic, '9'), name: 'K', albums: [a]);
      expect(DetailCacheFormat.of(DetailCacheJson.artist(artist)), DetailCacheFormat.current);
      expect(DetailCacheFormat.of(const {'id': 'x'}), DetailCacheFormat.legacy);
    });

    test('should_serveLegacyRowOnlyWithoutRatedProvider_when_formatChecked', () {
      expect(DetailCacheFormat.serves(DetailCacheFormat.legacy, MediaId.providerSubsonic), isTrue);
      expect(DetailCacheFormat.serves(DetailCacheFormat.legacy, MediaId.providerAppleMusic), isFalse);
      expect(DetailCacheFormat.serves(DetailCacheFormat.legacy, MediaId.providerSpotify), isFalse);
      expect(DetailCacheFormat.serves(DetailCacheFormat.rated, MediaId.providerAppleMusic), isTrue);
    });
  });

  group('DetailCacheStore', () {
    test('should_serveRating_when_rowReopened', () async {
      final id = MediaId(MediaId.providerAppleMusic, '1440');
      await store().writeAlbum(
        'p1',
        id.toString(),
        _album(id, rating: ContentRating.clean, tracks: [ContentRating.explicit]),
      );
      final album = await (await store().readAlbum('p1', id.toString()))!.value();
      expect(album!.contentRating, ContentRating.clean);
      expect(album.tracks.single.isExplicit, isTrue);
    });

    test('should_missAppleMusicAlbum_when_rowPredatesRatings', () async {
      final id = MediaId(MediaId.providerAppleMusic, '1440');
      await store().writeAlbum('p1', id.toString(), _album(id, rating: ContentRating.explicit));
      makeRowsLegacy();
      final s = store();
      expect(await (await s.readAlbum('p1', id.toString()))!.value(), isNull);
      // The stale row is gone, so the page reads the service again and writes a fresh one.
      expect(await s.readAlbum('p1', id.toString()), isNull);
    });

    test('should_missAppleMusicArtist_when_rowPredatesRatings', () async {
      final id = MediaId(MediaId.providerAppleMusic, '5468');
      final artist = ArtistDetail(id: id, name: 'Kendrick Lamar', albums: [_album(MediaId(id.provider, '1'))]);
      await store().writeArtist('p1', id.toString(), artist);
      makeRowsLegacy();
      expect(await (await store().readArtist('p1', id.toString()))!.value(), isNull);
    });

    test('should_serveSubsonicRowsUnrated_when_rowsPredateRatings', () async {
      final id = MediaId.subsonic('al1');
      await store().writeAlbum(
        'p1',
        id.toString(),
        _album(id, rating: ContentRating.explicit, tracks: [ContentRating.explicit]),
      );
      final artistId = MediaId.subsonic('ar1');
      await store().writeArtist('p1', artistId.toString(), ArtistDetail(id: artistId, name: 'K', albums: [_album(id)]));
      makeRowsLegacy();
      final album = await (await store().readAlbum('p1', id.toString()))!.value();
      expect(album, isNotNull);
      expect(album!.contentRating, isNull);
      expect(album.tracks.single.contentRating, isNull);
      expect(await (await store().readArtist('p1', artistId.toString()))!.value(), isNotNull);
    });

    test('should_serveLegacyPlaylist_when_rowPredatesRatings', () async {
      final id = MediaId(MediaId.providerAppleMusic, 'p.1');
      final playlist = Playlist(
        id: id,
        name: 'Mix',
        tracks: [Track(id: MediaId(id.provider, '7'), contentRating: ContentRating.explicit)],
      );
      await store().writePlaylist('p1', id.toString(), playlist);
      makeRowsLegacy();
      final back = await (await store().readPlaylist('p1', id.toString()))!.value();
      expect(back!.tracks.single.contentRating, isNull);
    });
  });
}
