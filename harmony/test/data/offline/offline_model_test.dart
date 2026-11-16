import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/offline/offline_model.dart';

OfflineSong _song(
  String id,
  List<OfflineCollection> collections, {
  OfflineSongState state = OfflineSongState.done,
  int order = 0,
  int bytes = 10,
}) => OfflineSong(
  profileId: 'p1',
  songId: MediaId.subsonic(id).toString(),
  track: Track(id: MediaId.subsonic(id), title: id),
  quality: DownloadQuality.kbps320,
  collections: collections,
  order: order,
  state: state,
  bytes: bytes,
);

const _album = OfflineCollection(kind: OfflineCollectionKind.album, id: 'subsonic:al1', title: 'Hymn');
const _playlist = OfflineCollection(kind: OfflineCollectionKind.playlist, id: 'subsonic:pl1', title: 'Night');

void main() {
  test('should_readDownloadsAsAlbumsAndPlaylists_newestFirst_when_songsShareThem', () {
    final library = OfflineLibrary([
      _song('a', [_album], order: 0),
      _song('b', [_album, _playlist], order: 1),
      _song('c', [_playlist], order: 2, state: OfflineSongState.downloading, bytes: 3),
    ]);
    expect([for (final e in library.entries) e.collection.key], [_playlist.key, _album.key]);
    expect(library.statusOf(_album.key), const OfflineDownloaded());
    expect(library.statusOf(_playlist.key), const OfflineDownloading(1, 2));
    expect(library.statusOf('album:none'), const OfflineNone());
    expect(library.entries.first.bytes, 13);
    expect(library.bytes, 23);
    expect(library.isDownloaded(MediaId.subsonic('c').toString()), isFalse);
    expect(library.isDownloaded(MediaId.subsonic('b').toString()), isTrue);
  });

  test('should_sayFailed_when_everySongThatIsNotDoneFailed', () {
    final library = OfflineLibrary([
      _song('a', [_album]),
      _song('b', [_album], state: OfflineSongState.failed),
    ]);
    expect(library.statusOf(_album.key), const OfflineFailed());
    final stillGoing = OfflineLibrary([
      _song('a', [_album], state: OfflineSongState.failed),
      _song('b', [_album], state: OfflineSongState.queued),
    ]);
    expect(stillGoing.statusOf(_album.key), const OfflineDownloading(0, 2));
  });

  test('should_goWithItsLastDownload_when_collectionsAreRemoved', () {
    final song = _song('a', [_album, _playlist]);
    final kept = song.withoutCollection(_album.key)!;
    expect(kept.collections, [_playlist]);
    expect(kept.withoutCollection(_playlist.key), isNull);
    // Adding again refreshes rather than doubling.
    expect(kept.withCollection(_playlist).collections, [_playlist]);
  });

  test('should_restartAnUnfinishedDownload_when_theIndexIsReadBack', () {
    final downloading = _song('a', [_album], state: OfflineSongState.downloading, bytes: 40);
    final back = OfflineSong.fromJson(downloading.toJson())!;
    expect(back.state, OfflineSongState.queued);
    expect(back.bytes, 0);
    final done = OfflineSong.fromJson(_song('b', [_album, _playlist]).toJson())!;
    expect(done.state, OfflineSongState.done);
    expect(done.collections, [_album, _playlist]);
    expect(done.track.title, 'b');
    expect(done.quality, DownloadQuality.kbps320);
  });

  test('should_neverKeepASignedUrl_when_theCoverIsOne', () {
    final playlist = OfflineCollection.playlist(
      Playlist(id: MediaId.subsonic('pl'), name: 'x'),
      cover: const CoverUrl('https://srv/rest/getCoverArt?t=secret'),
    );
    expect(playlist.cover, isNull);
    final song = OfflineCollection.song(Track(id: MediaId.subsonic('s'), coverArt: const CoverSourceRelative('c1')));
    expect(song.cover, 'c1');
    expect(song.key, 'song:${MediaId.subsonic('s')}');
  });

  test('should_askForAnMp3_when_theQualityIsCapped', () {
    expect(DownloadQuality.original.format, isNull);
    expect(DownloadQuality.kbps192.format, 'mp3');
    expect(DownloadQuality.fromKey('128'), DownloadQuality.kbps128);
    expect(const DownloadSettings().quality, DownloadQuality.kbps320);
    expect(const DownloadSettings().overMobileData, isFalse);
  });

  test('should_reportUnknownProgress_when_theLengthIsUnknown', () {
    final song = _song('a', [_album], state: OfflineSongState.downloading, bytes: 50);
    expect(song.percent, -1);
    expect(song.copyWith(totalBytes: 200).percent, 25);
  });
}
