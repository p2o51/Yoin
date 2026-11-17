/// A remote renderer as the queue engine drives it (Android DlnaTransport):
/// hand it one song's stream, then play / pause / seek and read back where it
/// is. DLNA speakers (SOAP over HTTP) and the system's cast device
/// (HarmonyOS AVCastController) both sit behind it.
library;

import 'dlna_protocol.dart';

/// Where the renderer is in its current song.
final class RendererPosition {
  const RendererPosition({this.positionMs, this.durationMs, this.trackUri});

  final int? positionMs;
  final int? durationMs;

  /// The stream it plays now; a different one means someone else took the renderer over.
  final String? trackUri;
}

/// UPnP transport states: PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT.
final class RendererTransportInfo {
  const RendererTransportInfo(this.state, [this.status]);

  final String state;

  /// `ERROR_OCCURRED` when the renderer cannot play the stream.
  final String? status;
}

/// The UPnP transport states the engine reads.
abstract final class RendererState {
  static const playing = 'PLAYING';
  static const paused = 'PAUSED_PLAYBACK';
  static const stopped = 'STOPPED';
  static const transitioning = 'TRANSITIONING';
  static const noMedia = 'NO_MEDIA_PRESENT';
  static const errorStatus = 'ERROR_OCCURRED';
}

abstract interface class RendererTransport {
  /// SetAVTransportURI: the renderer pulls [media]'s stream itself.
  Future<void> load(RendererMedia media);
  Future<void> play();
  Future<void> pause();
  Future<void> stop();
  Future<void> seek(int positionMs);
  Future<RendererPosition> position();
  Future<RendererTransportInfo> transportInfo();

  /// Whether [volume] / [setVolume] work (RenderingControl present).
  bool get hasVolume;

  /// 0–100.
  Future<int> volume();
  Future<void> setVolume(int volume);
}

/// A SOAP acknowledgement may precede the renderer stopping its audio
/// pipeline: Stop, then wait (5 s at most) until it reports STOPPED or
/// NO_MEDIA_PRESENT. Throws [DlnaCommandException] ('ConfirmStop') otherwise.
Future<void> stopAndConfirm(
  RendererTransport transport, {
  Duration timeout = const Duration(seconds: 5),
  Duration interval = const Duration(milliseconds: 250),
  Future<void> Function(Duration)? sleep,
  int Function()? nowMs,
}) async {
  final wait = sleep ?? Future<void>.delayed;
  final now = nowMs ?? () => DateTime.now().millisecondsSinceEpoch;
  await transport.stop();
  final deadline = now() + timeout.inMilliseconds;
  while (true) {
    final state = (await transport.transportInfo()).state;
    if (state == RendererState.stopped || state == RendererState.noMedia) return;
    if (now() >= deadline) throw const DlnaCommandException('ConfirmStop');
    await wait(interval);
  }
}
