/// The system's own output and cast panel (HarmonyOS AVCastPicker) and the
/// cast session behind it (AVSession cast → AVCastController), over the
/// `yoin/cast` method channel served by the ArkTS `YoinCastPlugin`
/// (ohos/entry/src/main/ets/yoin/cast/). The Android counterpart is the
/// Chromecast session (CastManager): when the session's output becomes a
/// remote device, Yoin hands its queue to it and plays it there.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import '../../playback/output/dlna_protocol.dart';
import '../../playback/output/renderer_transport.dart';

/// Where the system session plays: on this device (speaker, wired, Bluetooth) or a remote cast device.
enum CastCategory { local, remote }

/// AVSession ConnectionState.
enum CastConnection { connecting, connected, disconnected }

/// The session's output device as the system reports it.
final class CastOutput {
  const CastOutput({required this.connection, required this.category, required this.name, this.deviceType = 0});

  final CastConnection connection;
  final CastCategory category;

  /// The device's name (may be empty for the built-in speaker).
  final String name;

  /// AVSession DeviceType (0 local, 2 TV, 3 smart speaker, 10 Bluetooth…).
  final int deviceType;

  bool get connected => connection == CastConnection.connected;
  bool get isRemote => connected && category == CastCategory.remote;

  factory CastOutput.fromMap(Map<Object?, Object?> map) => CastOutput(
    connection: CastConnection.values.asNameMap()[map['state']] ?? CastConnection.disconnected,
    category: map['category'] == 'remote' ? CastCategory.remote : CastCategory.local,
    name: (map['name'] as String?) ?? '',
    deviceType: switch (map['deviceType']) {
      final int t => t,
      _ => 0,
    },
  );

  @override
  bool operator ==(Object other) =>
      other is CastOutput &&
      other.connection == connection &&
      other.category == category &&
      other.name == name &&
      other.deviceType == deviceType;

  @override
  int get hashCode => Object.hash(connection, category, name, deviceType);
}

abstract interface class CastHost {
  /// Whether this platform has a system output / cast picker.
  bool get hasPicker;

  /// Opens the system's output / cast picker; false when it could not.
  Future<bool> openPicker();

  /// The session's output device, now (when known) and on every change.
  Stream<CastOutput> get outputs;

  /// The cast session as a renderer (valid while [outputs] says remote).
  RendererTransport castTransport();
}

/// No system picker (Windows today): the Devices sheet lists this device and DLNA only.
final class NoCastHost implements CastHost {
  const NoCastHost();

  @override
  bool get hasPicker => false;

  @override
  Future<bool> openPicker() async => false;

  @override
  Stream<CastOutput> get outputs => const Stream.empty();

  @override
  RendererTransport castTransport() => throw UnsupportedError('No system cast on this platform');
}

/// HarmonyOS: `yoin/cast`. Native pushes `outputChanged(map)`; Dart calls
/// `openPicker`, `currentOutput`, `castLoad`, `castCommand` and `castState`.
final class ChannelCastHost implements CastHost {
  ChannelCastHost({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel;

  static const channelName = 'yoin/cast';

  final MethodChannel _channel;
  late final StreamController<CastOutput> _outputs = StreamController<CastOutput>.broadcast(onListen: _listen);
  CastOutput? _latest;
  bool _listening = false;

  @override
  bool get hasPicker => true;

  @override
  Future<bool> openPicker() async {
    try {
      return await _channel.invokeMethod<bool>('openPicker') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Stream<CastOutput> get outputs => Stream<CastOutput>.multi((controller) {
    final latest = _latest;
    if (latest != null) controller.add(latest);
    final sub = _outputs.stream.listen(controller.add, onError: controller.addError);
    controller.onCancel = sub.cancel;
  });

  void _listen() {
    if (_listening) return;
    _listening = true;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'outputChanged' && call.arguments is Map) {
        _push(CastOutput.fromMap((call.arguments as Map).cast<Object?, Object?>()));
      }
      return null;
    });
    _channel
        .invokeMethod<Map<Object?, Object?>>('currentOutput')
        .then((map) {
          if (map != null) _push(CastOutput.fromMap(map));
        })
        .catchError((Object _) {});
  }

  void _push(CastOutput output) {
    if (output == _latest) return;
    _latest = output;
    _outputs.add(output);
  }

  @override
  RendererTransport castTransport() => ChannelCastTransport(_channel);
}

/// The AVCastController as a [RendererTransport]: `castState` answers in
/// UPnP's words (PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING) so the
/// queue engine reads it like a speaker. Failures are [DlnaCommandException]s.
final class ChannelCastTransport implements RendererTransport {
  ChannelCastTransport(this._channel, {int Function()? nowMs})
    : _now = nowMs ?? (() => DateTime.now().millisecondsSinceEpoch);

  final MethodChannel _channel;
  final int Function() _now;
  Map<Object?, Object?>? _state;
  int _stateAtMs = 0;

  Future<T?> _call<T>(String method, [Object? arguments]) async {
    try {
      return await _channel.invokeMethod<T>(method, arguments);
    } on PlatformException {
      throw DlnaCommandException('Cast:$method');
    } on MissingPluginException {
      throw DlnaCommandException('Cast:$method');
    }
  }

  Future<void> _command(String command, [int? value]) =>
      _call<void>('castCommand', {'command': command, 'value': ?value});

  /// One read serves the poll's transport and position questions.
  Future<Map<Object?, Object?>> _read() async {
    final cached = _state;
    if (cached != null && _now() - _stateAtMs < 300) return cached;
    final map = await _call<Map<Object?, Object?>>('castState');
    if (map == null) throw const DlnaCommandException('Cast:castState');
    _state = map;
    _stateAtMs = _now();
    return map;
  }

  void _invalidate() => _state = null;

  @override
  Future<void> load(RendererMedia media) async {
    _invalidate();
    await _call<void>('castLoad', {
      'id': media.id,
      'title': media.title,
      'artist': media.artist,
      'album': media.album,
      'artworkUri': media.artwork,
      'uri': media.uri,
      'durationMs': media.durationMs,
    });
  }

  @override
  Future<void> play() async {
    _invalidate();
    await _command('play');
  }

  @override
  Future<void> pause() async {
    _invalidate();
    await _command('pause');
  }

  @override
  Future<void> stop() async {
    _invalidate();
    await _command('stop');
  }

  @override
  Future<void> seek(int positionMs) async {
    _invalidate();
    await _command('seek', positionMs);
  }

  @override
  Future<RendererPosition> position() async {
    final map = await _read();
    return RendererPosition(
      positionMs: _int(map['positionMs']),
      durationMs: _int(map['durationMs']),
      trackUri: map['mediaUri'] as String?,
    );
  }

  @override
  Future<RendererTransportInfo> transportInfo() async {
    final map = await _read();
    return RendererTransportInfo((map['state'] as String?) ?? RendererState.stopped, map['status'] as String?);
  }

  @override
  bool get hasVolume => true;

  @override
  Future<int> volume() async {
    final volume = _int((await _read())['volume']);
    if (volume == null) throw const DlnaCommandException('Cast:volume');
    return volume.clamp(0, 100);
  }

  @override
  Future<void> setVolume(int volume) async {
    _invalidate();
    await _command('setVolume', volume.clamp(0, 100));
  }
}

int? _int(Object? v) => switch (v) {
  final int i => i,
  final double d => d.round(),
  _ => null,
};
