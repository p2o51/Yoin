import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/ui/component/audio_format_tags.dart';

void main() {
  test('should_showDepthAndRate_when_lossless', () {
    expect(audioFormatTags(const AudioFormat(suffix: 'flac', bitDepth: 24, sampleRateHz: 96000)), ['FLAC 24/96']);
    expect(audioFormatTags(const AudioFormat(suffix: 'flac', sampleRateHz: 44100)), ['FLAC 44.1']);
  });

  test('should_showBitRate_when_lossy', () {
    expect(audioFormatTags(const AudioFormat(suffix: 'mp3', bitRateKbps: 320)), ['MP3 320']);
    expect(audioFormatTags(const AudioFormat(suffix: 'opus', bitRateKbps: 192)), ['Opus 192']);
  });

  test('should_addLayout_when_multichannel', () {
    expect(audioFormatTags(const AudioFormat(suffix: 'flac', channels: 6)), ['FLAC', '5.1']);
    expect(audioFormatTags(const AudioFormat(suffix: 'flac', channels: 2)), ['FLAC']);
  });

  test('should_showNothing_when_noCodec', () {
    expect(audioFormatTags(null), isEmpty);
    expect(audioFormatTags(const AudioFormat(bitRateKbps: 320)), isEmpty);
  });

  test('should_showDolbyAtmosAlone_when_eac3Joc', () {
    expect(audioFormatTags(const AudioFormat(contentType: 'audio/eac3-joc', channels: 6, bitRateKbps: 768)), [
      'Dolby Atmos',
    ]);
    expect(const AudioFormat(contentType: 'audio/eac3-joc').isDolbyAtmos, isTrue);
  });

  test('should_nameDolbyCodecs_when_plainAc3OrEac3', () {
    expect(audioFormatTags(const AudioFormat(contentType: 'audio/eac3', channels: 6, bitRateKbps: 640)), [
      'E-AC-3 640',
      '5.1',
    ]);
    expect(codecLabel('ac3'), 'AC-3');
    expect(codecLabel('ac4'), 'AC-4');
  });

  test('should_tellAlacFromAac_when_m4aHasADepth', () {
    expect(audioFormatTags(const AudioFormat(suffix: 'm4a', bitDepth: 24, sampleRateHz: 96000)), ['ALAC 24/96']);
    expect(audioFormatTags(const AudioFormat(suffix: 'm4a', bitRateKbps: 256)), ['AAC 256']);
  });

  test('should_readTheContentType_when_suffixMissing', () {
    expect(audioFormatTags(const AudioFormat(contentType: 'audio/flac', bitDepth: 16, sampleRateHz: 44100)), [
      'FLAC 16/44.1',
    ]);
    expect(audioFormatTags(const AudioFormat(suffix: 'ogg', bitRateKbps: 160)), ['Vorbis 160']);
    expect(audioFormatTags(const AudioFormat(suffix: 'wv', sampleRateHz: 48000)), ['WavPack 48']);
  });
}
