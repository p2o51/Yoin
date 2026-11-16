import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/subsonic/subsonic_mappers.dart';
import 'package:yoin/ui/component/meta_line.dart';

/// Android SubsonicReplayGainVersionTest, ported to the JSON mappers.
void main() {
  Map<String, Object?> song(String fields) => (jsonDecode('{"id":"s1"$fields}') as Map).cast<String, Object?>();
  Map<String, Object?> album(String fields) =>
      (jsonDecode('{"id":"al","name":"再生 (全景声)"$fields}') as Map).cast<String, Object?>();

  group('replayGain', () {
    test('should_parseEveryReplayGainField_when_serverSendsThem', () {
      final track = trackFromJson(
        song(
          ',"replayGain":{"trackGain":-9.6,"albumGain":-8.1,"trackPeak":0.9886,"albumPeak":0.99,'
          '"baseGain":0,"fallbackGain":-6}',
        ),
      )!;
      expect(
        track.replayGain,
        const ReplayGain(trackGainDb: -9.6, albumGainDb: -8.1, trackPeak: 0.9886, albumPeak: 0.99, fallbackGainDb: -6),
        reason: 'a 0 dB base gain is no gain',
      );
    });

    test('should_keepTrackGainOnly_when_albumGainIsMissing', () {
      final track = trackFromJson(song(',"replayGain":{"trackGain":1.0,"trackPeak":0.335}'))!;
      expect(track.replayGain, const ReplayGain(trackGainDb: 1.0, trackPeak: 0.335));
    });

    test('should_haveNoReplayGain_when_serverOmitsTheObject', () {
      expect(trackFromJson(song(''))!.replayGain, isNull);
    });

    test('should_haveNoReplayGain_when_objectIsEmptyOrAllZero', () {
      expect(trackFromJson(song(',"replayGain":{}'))!.replayGain, isNull);
      expect(
        trackFromJson(song(',"replayGain":{"trackGain":0,"albumGain":0,"trackPeak":0,"albumPeak":0}'))!.replayGain,
        isNull,
      );
    });

    test('should_keepZeroGain_when_itHasAPeak', () {
      final track = trackFromJson(song(',"replayGain":{"trackGain":0,"trackPeak":0.5}'))!;
      expect(track.replayGain, const ReplayGain(trackGainDb: 0, trackPeak: 0.5));
    });

    test('should_readNumbers_when_serverSendsThemAsStrings', () {
      final track = trackFromJson(song(',"replayGain":{"trackGain":"-7.5","trackPeak":"0.8"}'))!;
      expect(track.replayGain, const ReplayGain(trackGainDb: -7.5, trackPeak: 0.8));
    });

    test('should_ignoreGarbage_when_replayGainIsNotAnObject', () {
      expect(trackFromJson(song(',"replayGain":"loud"'))!.replayGain, isNull);
    });

    test('should_keepTheGain_when_trackIsCopied', () {
      final track = trackFromJson(song(',"replayGain":{"trackGain":-3}'))!;
      expect(track.copyWith(isStarred: true).replayGain, track.replayGain);
    });
  });

  group('version', () {
    test('should_parseVersionAndStripItFromTheName_when_serverAppendsIt', () {
      final mapped = albumFromJson(album(',"version":"全景声"'))!;
      expect(mapped.version, '全景声');
      expect(mapped.name, '再生 (全景声)');
      expect(albumTitleWithoutVersion(mapped.name, mapped.version), '再生');
    });

    test('should_haveNoVersion_when_serverOmitsIt', () {
      final mapped = albumFromJson(album(''))!;
      expect(mapped.version, isNull);
      expect(albumTitleWithoutVersion(mapped.name, mapped.version), '再生 (全景声)');
    });

    test('should_keepTheName_when_itIsOnlyTheVersion', () {
      expect(albumTitleWithoutVersion(' (Deluxe)', 'Deluxe'), ' (Deluxe)');
      expect(albumTitleWithoutVersion('Blue (Deluxe)', ' Deluxe '), 'Blue');
      expect(albumTitleWithoutVersion('Blue (deluxe)', 'Deluxe'), 'Blue (deluxe)', reason: 'only an exact suffix');
    });
  });

  group('format', () {
    test('should_haveNoFormat_when_m4aFieldsAreAllZero', () {
      final track = trackFromJson(
        song(',"suffix":"m4a","contentType":"audio/mp4","bitRate":0,"bitDepth":0,"samplingRate":0,"channelCount":0'),
      )!;
      expect(track.audioFormat, isNull);
    });

    test('should_keepAacAndAlac_when_m4aHasRealFields', () {
      final aac = trackFromJson(song(',"suffix":"m4a","bitRate":256,"samplingRate":44100,"channelCount":2'))!;
      expect(aac.audioFormat!.codec, 'aac');
      final alac = trackFromJson(song(',"suffix":"m4a","bitRate":1411,"bitDepth":24,"samplingRate":96000'))!;
      expect(alac.audioFormat!.codec, 'alac');
      expect(trackFromJson(song(',"suffix":"flac","bitRate":0'))!.audioFormat, isNotNull);
    });
  });
}
