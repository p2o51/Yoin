/// The demo phone's library (Android ui/tips/TipDemoData.kt): made-up albums
/// with made-up covers ([DemoArtwork]), so the real components have
/// something to show. Names are the same as Android's.
library;

import 'package:flutter/foundation.dart';

import '../data/model/model.dart';
import '../nowplaying/lyrics_logic.dart';
import '../ui/component/artwork_image.dart';

/// One made-up album: its name, artist and cover.
@immutable
final class DemoAlbum {
  const DemoAlbum(this.name, this.artist, this.cover);
  final String name;
  final String artist;
  final int cover;

  String get coverUrl => DemoArtwork.url(cover);
  CoverRef get coverRef => CoverUrl(coverUrl);
}

const demoAlbums = [
  DemoAlbum('Harbour Lights', 'Mira Sol', 0),
  DemoAlbum('Paper Moons', 'The Lanterns', 1),
  DemoAlbum('Low Tide', 'Ines Varga', 2),
  DemoAlbum('Night Garden', 'Okto', 3),
  DemoAlbum('Golden Hour', 'Mira Sol', 4),
  DemoAlbum('Blue Rooms', 'Halden', 5),
];

const demoTrackTitles = [
  'Slow Light',
  'Northbound',
  'Salt and Glass',
  'Weathervane',
  'Quiet Hours',
  'Lanterns',
  'Undertow',
  'Afterglow',
];

Track demoTrack(int index, [DemoAlbum album = const DemoAlbum('Harbour Lights', 'Mira Sol', 0)]) => Track(
  id: MediaId.subsonic('tip-track-${album.cover}-$index'),
  title: demoTrackTitles[index % demoTrackTitles.length],
  artist: album.artist,
  artistId: MediaId.subsonic('tip-artist-${album.artist}'),
  album: album.name,
  albumId: MediaId.subsonic('tip-album-${album.name}'),
  coverArt: album.coverRef,
  durationSec: 180 + index * 23,
  trackNumber: index + 1,
  year: 2024,
);

/// Library's albums: every album, then again backwards (12 cells).
final List<Album> demoLibraryAlbums = [
  for (final (i, album) in [...demoAlbums, ...demoAlbums.reversed].indexed)
    Album(
      id: MediaId.subsonic('tip-album-$i'),
      name: album.name,
      artist: album.artist,
      coverArt: album.coverRef,
      songCount: 8,
      durationSec: 2400,
      year: 2024,
    ),
];

/// A song's synced lines (Android's demo lyrics, TipNowPlayingScenes demoLyrics),
/// four seconds apart, each with a translation for the Translate key to show.
const demoLyrics = LyricSheet([
  LyricRow('Streetlights hum a slower song', startMs: 0, translation: '街灯哼着一首更慢的歌'),
  LyricRow('I keep the window open wide', startMs: 4000, translation: '我把窗开得很大'),
  LyricRow('The harbour folds into the dark', startMs: 8000, translation: '港口折进夜色'),
  LyricRow('And every light is passing by', startMs: 12000, translation: '每一盏灯都在经过'),
  LyricRow('Hold the evening, let it stay', startMs: 16000, translation: '留住这个夜晚，让它停一会'),
  LyricRow('Slow light, slow light', startMs: 20000, translation: '慢慢的光，慢慢的光'),
  LyricRow('Carry me the long way home', startMs: 24000, translation: '带我绕远路回家'),
  LyricRow('Over water, under stone', startMs: 28000, translation: '越过水面，穿过石下'),
]);

/// A memory's title and its excerpt (Android TipHomeScenes DemoMemoriesPage).
const demoMemoryTitle = 'Night walks in the rain';
const demoMemoryExcerpt = 'The second side is the one I keep coming back to.';
