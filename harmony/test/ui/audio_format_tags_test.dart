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
}
