import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/track_codec.dart';

void main() {
  test('should_roundTripTrack_when_encoded', () {
    final t = Track(
      id: MediaId.subsonic('s1'),
      title: 'Song',
      artist: 'A',
      artistId: MediaId.subsonic('ar'),
      album: 'Al',
      albumId: MediaId.subsonic('al'),
      coverArt: const CoverSourceRelative('c1'),
      durationSec: 201,
      trackNumber: 3,
      userRating: 4,
      isStarred: true,
    );
    final back = TrackCodec.decode(TrackCodec.encode(t))!;
    expect(back.id, t.id);
    expect(back.albumId, t.albumId);
    expect(back.coverArt, t.coverArt);
    expect(back.durationSec, 201);
    expect(back.userRating, 4);
    expect(back.isStarred, isTrue);
  });

  test('should_keepReplayGainAndFormat_when_encoded', () {
    final t = Track(
      id: MediaId.subsonic('s1'),
      audioFormat: const AudioFormat(suffix: 'flac', bitDepth: 24, sampleRateHz: 96000, channels: 6, bitRateKbps: 4000),
      replayGain: const ReplayGain(trackGainDb: -9.6, albumGainDb: -8.1, trackPeak: 0.9886, albumPeak: 0.99),
    );
    final back = TrackCodec.decode(TrackCodec.encode(t))!;
    expect(back.replayGain, t.replayGain);
    expect(back.audioFormat!.suffix, 'flac');
    expect(back.audioFormat!.bitDepth, 24);
    expect(back.audioFormat!.sampleRateHz, 96000);
    expect(back.audioFormat!.channels, 6);
    expect(back.audioFormat!.bitRateKbps, 4000);
    final plain = TrackCodec.decode(TrackCodec.encode(Track(id: MediaId.subsonic('s2'))))!;
    expect(plain.replayGain, isNull);
    expect(plain.audioFormat, isNull);
  });

  test('should_buildSlimTrack_when_itemHasNoExtra', () {
    final t = TrackCodec.fromItem(const EngineItem(id: 'subsonic:9', uri: '', title: 'Nine', artist: 'B', durationMs: 61500))!;
    expect(t.id, MediaId.subsonic('9'));
    expect(t.title, 'Nine');
    expect(t.durationSec, 61);
  });

  test('should_beNull_when_garbage', () {
    expect(TrackCodec.decode('{oops'), isNull);
    expect(TrackCodec.fromItem(const EngineItem(id: 'not-a-media-id', uri: '', title: '')), isNull);
  });

  test('should_roundTripContext_when_attachedToExtra', () {
    final extra = TrackCodec.encode(Track(id: MediaId.subsonic('s1'), title: 'Song'));
    final ctx = AlbumPlaybackContext(
      albumId: MediaId.subsonic('al'),
      name: 'Blue',
      artist: 'Joni',
      artistId: MediaId.subsonic('ar'),
      cover: const CoverSourceRelative('c'),
    );
    final withCtx = PlaybackContextCodec.attach(extra, ctx);
    final back = PlaybackContextCodec.read(withCtx) as AlbumPlaybackContext;
    expect(back.albumId, ctx.albumId);
    expect(back.name, 'Blue');
    expect(back.artistId, ctx.artistId);
    expect(back.cover, ctx.cover);
    expect(TrackCodec.decode(withCtx)!.title, 'Song', reason: 'the track still decodes');
    expect(PlaybackContextCodec.read(extra), isA<NoPlaybackContext>());
    expect(PlaybackContextCodec.attach(extra, const NoPlaybackContext()), extra);
  });

  test('should_keepEveryCreditedArtist_when_songNamesSeveral', () {
    final track = Track(
      id: MediaId.subsonic('s1'),
      artist: 'A & B',
      artists: [ArtistCredit('A', MediaId.subsonic('a')), const ArtistCredit('B', null)],
    );
    final back = TrackCodec.decode(TrackCodec.encode(track))!;
    expect(back.artists, track.artists);
    expect(TrackCodec.decode(TrackCodec.encode(Track(id: MediaId.subsonic('s2'))))!.artists, isEmpty);
  });
}
