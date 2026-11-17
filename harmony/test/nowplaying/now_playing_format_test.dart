import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/nowplaying/now_playing_format.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/playback/playing_format.dart';
import 'package:yoin/playback/spatial_audio.dart';
import 'package:yoin/playback/track_codec.dart';
import 'package:yoin/ui/component/audio_format_tags.dart';

import '../support/playback_fakes.dart';

const _flac = AudioFormat(suffix: 'flac', bitRateKbps: 900, bitDepth: 16, sampleRateHz: 44100, channels: 2);
const _flac51 = AudioFormat(suffix: 'flac', bitRateKbps: 2400, bitDepth: 24, sampleRateHz: 48000, channels: 6);
const _mp3At320 = StreamDelivery(maxBitRateKbps: 320, format: 'mp3');
const _creds = SubsonicCredentials(baseUrl: 'https://home.test', username: 'u', password: 'p');

const _spatialReady = SpatialAudioState(supported: true, outputSupported: true, enabled: true, canChoose: true);

Track _track(AudioFormat? format, {String id = 's1'}) =>
    Track(id: MediaId.subsonic(id), title: 'T', audioFormat: format);

PlayingFormatEvent _decoded(
  String entryId, {
  String id = 'subsonic:s1',
  bool fromFile = false,
  String? mime,
  int? channels,
}) => PlayingFormatEvent(
  id: id,
  entryId: entryId,
  fromFile: fromFile,
  decoded: AudioFormat(contentType: mime, channels: channels),
);

/// A Subsonic-like source whose streams are 320 kbps mp3 transcodes.
class _TranscodingSource extends PlaybackFakeSource {
  @override
  Future<PlaybackHandle> handleFor(Track track) async =>
      DirectStream('https://stream.test/${track.id.rawId}?maxBitRate=320&format=mp3', delivery: _mp3At320);
}

void main() {
  group('tags', () {
    test('should_showWhatArrives_when_transcoded', () {
      expect(playingFormatTags(source: _flac, decoded: null, transcodeFormat: 'mp3', transcodeKbps: 320), ['MP3 320']);
      expect(transcodedFrom(_flac, 320), 'FLAC 16/44.1');
      expect(transcodedFrom(_flac, null), isNull);
    });

    test('should_takeDecodersCodecAndChannels_when_transcodeDecodes', () {
      expect(
        playingFormatTags(
          source: _flac51,
          decoded: const AudioFormat(contentType: 'audio/opus', channels: 6),
          transcodeFormat: 'mp3',
          transcodeKbps: 192,
        ),
        ['Opus 192', '5.1'],
      );
    });

    test('should_takeDecodersChannelCount_when_fileDecodes', () {
      expect(
        playingFormatTags(
          source: _flac,
          decoded: const AudioFormat(contentType: 'audio/flac', channels: 6),
        ),
        ['FLAC 16/44.1', '5.1'],
      );
    });

    test('should_showTheDecoder_when_serverTranscodedOnItsOwn', () {
      expect(
        playingFormatTags(
          source: _flac,
          decoded: const AudioFormat(contentType: 'audio/mpeg', bitRateKbps: 256, channels: 2),
        ),
        ['MP3 256'],
      );
    });

    test('should_fillGapsFromTheDecoder_when_serverSaysLittle', () {
      expect(
        playingFormatTags(
          source: const AudioFormat(suffix: 'flac'),
          decoded: const AudioFormat(contentType: 'audio/flac', sampleRateHz: 96000, bitDepth: 24, channels: 2),
        ),
        ['FLAC 24/96'],
      );
      expect(playingFormatTags(source: null, decoded: const AudioFormat(contentType: 'audio/mpeg', bitRateKbps: 320)), [
        'MP3 320',
      ]);
      expect(playingFormatTags(source: null, decoded: null), isEmpty);
    });
  });

  group('now playing format', () {
    test('should_showTheTranscode_when_streamIsTranscoded', () {
      final format = nowPlayingFormatOf(
        track: _track(_flac),
        entryId: 'e1',
        deliveries: const EntryDelivery(stream: _mp3At320),
        playing: _decoded('e1', mime: 'audio/mpeg', channels: 2),
        remote: false,
        spatial: const SpatialAudioState(),
      );
      expect(format.tags, ['MP3 320']);
      expect(format.transcodedFrom, 'FLAC 16/44.1');
    });

    test('should_showTheDownloadsQuality_when_fileOpened', () {
      const deliveries = EntryDelivery(stream: StreamDelivery.original, file: _mp3At320);
      final opened = nowPlayingFormatOf(
        track: _track(_flac),
        entryId: 'e1',
        deliveries: deliveries,
        playing: _decoded('e1', fromFile: true, mime: 'audio/mpeg', channels: 2),
        remote: false,
        spatial: const SpatialAudioState(),
      );
      expect(opened.tags, ['MP3 320']);
      expect(opened.transcodedFrom, 'FLAC 16/44.1');
      // Before the player says, the download wins: it opens whenever it is there.
      final pending = nowPlayingFormatOf(
        track: _track(_flac),
        entryId: 'e1',
        deliveries: deliveries,
        playing: null,
        remote: false,
        spatial: const SpatialAudioState(),
      );
      expect(pending.tags, ['MP3 320']);
      // A download removed meanwhile streams the file.
      final streamed = nowPlayingFormatOf(
        track: _track(_flac),
        entryId: 'e1',
        deliveries: deliveries,
        playing: _decoded('e1', mime: 'audio/flac', channels: 2),
        remote: false,
        spatial: const SpatialAudioState(),
      );
      expect(streamed.tags, ['FLAC 16/44.1']);
      expect(streamed.transcodedFrom, isNull);
    });

    test('should_ignoreAnotherEntrysReport_when_songChanged', () {
      final format = nowPlayingFormatOf(
        track: _track(_flac),
        entryId: 'e2',
        deliveries: null,
        playing: _decoded('e1', mime: 'audio/flac', channels: 6),
        remote: false,
        spatial: _spatialReady,
      );
      expect(format.tags, ['FLAC 16/44.1']);
      expect(format.spatialized, isFalse);
    });

    test('should_useStreamAndNoDecoder_when_speakerPlays', () {
      final format = nowPlayingFormatOf(
        track: _track(_flac51),
        entryId: 'e1',
        deliveries: const EntryDelivery(stream: _mp3At320, file: StreamDelivery.original),
        playing: _decoded('e1', fromFile: true, mime: 'audio/flac', channels: 6),
        remote: true,
        spatial: _spatialReady,
      );
      expect(format.tags, ['MP3 320']);
      expect(format.spatialized, isFalse, reason: 'a speaker spatializes (or not) on its own');
    });

    test('should_addSpatial_when_systemSpatializesMultichannel', () {
      NowPlayingFormat of(SpatialAudioState spatial) => nowPlayingFormatOf(
        track: _track(_flac51),
        entryId: 'e1',
        deliveries: const EntryDelivery(stream: StreamDelivery.original),
        playing: _decoded('e1', mime: 'audio/flac', channels: 6),
        remote: false,
        spatial: spatial,
      );
      expect(of(_spatialReady).tags, ['FLAC 24/48', '5.1']);
      expect(of(_spatialReady).spatialized, isTrue);
      expect(of(_spatialReady.copyWith(mode: SpatialAudioMode.off)).spatialized, isFalse);
      expect(of(const SpatialAudioState(supported: true, canChoose: true)).spatialized, isFalse);
    });
  });

  group('pipeline', () {
    late PlaybackFakeQueueEngine engine;
    late ProviderContainer c;

    Future<void> start(PlaybackFakeQueueEngine host) async {
      final values = InMemoryStore();
      final repo = ProfileRepository(values, InMemoryStore());
      await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
      c = ProviderContainer(
        overrides: [
          playbackEngineProvider.overrideWithValue(host),
          keyValueStoreProvider.overrideWithValue(values),
          profileRepositoryProvider.overrideWithValue(repo),
          activeSourceProvider.overrideWith((ref) async => _TranscodingSource()),
        ],
      );
      c.listen(playbackControllerProvider, (_, _) {});
      c.listen(nowPlayingFormatProvider, (_, _) {});
      await c.read(activeSourceProvider.future);
      await pumpEventQueue();
    }

    tearDown(() => c.dispose());

    test('should_carryTheTranscodeToNowPlaying_when_queueStarts', () async {
      engine = PlaybackFakeQueueEngine();
      await start(engine);
      await c.read(playbackControllerProvider.notifier).playTracks([_track(_flac)]);
      expect(engine.queue.single.delivery, _mp3At320);
      engine.echo(index: 0);
      await pumpEventQueue();
      final entryId = engine.queue.single.entryId!;
      expect(c.read(entryDeliveriesProvider)[entryId]?.stream, _mp3At320);
      expect(c.read(nowPlayingFormatProvider).tags, ['MP3 320']);
      expect(c.read(nowPlayingFormatProvider).transcodedFrom, 'FLAC 16/44.1');

      engine.event(_decoded(entryId, mime: 'audio/mpeg', channels: 2));
      await pumpEventQueue();
      expect(c.read(playingFormatProvider)?.entryId, entryId);
      expect(c.read(nowPlayingFormatProvider).tags, ['MP3 320']);
    });

    test('should_keepWhatArrives_when_uiRebuiltFromTheHost', () async {
      final track = _track(_flac);
      engine = PlaybackFakeQueueEngine()
        ..queue = [
          EngineItem(
            id: track.id.toString(),
            uri: '',
            title: 'T',
            entryId: 'e0',
            extra: TrackCodec.encode(track),
            delivery: _mp3At320,
          ),
        ];
      engine.snapshotToReturn = EngineSnapshot(
        items: engine.queue,
        state: const EngineState(
          phase: PlaybackPhase.ready,
          isPlaying: true,
          index: 0,
          currentEntryId: 'e0',
          order: [0],
        ),
      );
      await start(engine);
      await pumpEventQueue();
      expect(c.read(nowPlayingFormatProvider).tags, ['MP3 320']);
    });
  });

  group('tag row', () {
    testWidgets('should_readTranscodeAndSpatial_when_described', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Material(
            child: AudioFormatTagRow(
              tags: ['FLAC 24/48', '5.1'],
              accentTag: 'Spatial',
              description: 'Audio format FLAC 24/48 5.1 Spatial',
            ),
          ),
        ),
      );
      expect(find.text('Spatial'), findsOneWidget);
      expect(find.bySemanticsLabel('Audio format FLAC 24/48 5.1 Spatial'), findsOneWidget);
    });
  });
}
