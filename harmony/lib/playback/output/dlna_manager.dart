/// DLNA renderers (Android player/dlna/DlnaManager.kt): the speakers found
/// on the LAN, the one Yoin plays on, and the last failure. Discovery is
/// explicit and bounded; connecting resolves a Sonos group to its
/// coordinator and only hands back to the phone once a Stop is confirmed.
library;

import 'package:flutter/foundation.dart';

import '../../platform/playback/playback_engine.dart';
import 'dlna_client.dart';
import 'dlna_discovery.dart';
import 'dlna_protocol.dart';
import 'remote_queue_engine.dart';
import 'renderer_transport.dart';

/// The renderer failure categories; the UI owns the localized copy.
enum DlnaError { discovery, group, stopUnconfirmed, playback }

class DlnaManager {
  DlnaManager({
    DlnaClient? client,
    DlnaDiscovery? discovery,
    RemoteQueueEngine Function(void Function(String?) onFailure)? engine,
    RendererTransport Function(DlnaClient client, DlnaDevice device)? transportFor,
  }) : client = client ?? DlnaClient() {
    this.discovery = discovery ?? DlnaDiscovery(this.client);
    player = (engine ?? (onFailure) => RemoteQueueEngine(onFailure: onFailure))((message) {
      // A recovered renderer clears whatever was shown (Android sets null on success).
      error.value = message == null ? null : DlnaError.playback;
    });
    _transportFor = transportFor ?? DlnaRendererTransport.new;
  }

  final DlnaClient client;
  late final DlnaDiscovery discovery;

  /// The queue engine that plays on the connected renderer.
  late final RemoteQueueEngine player;
  late final RendererTransport Function(DlnaClient, DlnaDevice) _transportFor;

  final ValueNotifier<List<DlnaDevice>> devices = ValueNotifier(const []);
  final ValueNotifier<bool> isDiscovering = ValueNotifier(false);
  final ValueNotifier<DlnaDevice?> connectedDevice = ValueNotifier(null);
  final ValueNotifier<DlnaError?> error = ValueNotifier(null);

  Future<void> _discovering = Future.value();
  Future<void> _connection = Future.value();

  /// One search at a time; a second call waits for the first, then searches again.
  Future<void> discover() => _discovering = _discovering.then((_) async {
    isDiscovering.value = true;
    try {
      devices.value = await discovery.discover();
      if (connectedDevice.value == null) error.value = null;
    } catch (_) {
      error.value = DlnaError.discovery;
    } finally {
      isDiscovering.value = false;
    }
  });

  /// Moves [items] onto [device] (its Sonos coordinator), stopping a
  /// different renderer first. Throws on failure; [error] says why. A Play
  /// may have reached the renderer even if its reply was lost, so a failed
  /// hand-over is undone only once a Stop is acknowledged.
  Future<void> connect(
    DlnaDevice device,
    List<EngineItem> items, {
    required int index,
    required int positionMs,
    required bool playWhenReady,
    required EngineRepeat repeat,
    required bool shuffle,
    required int revision,
    List<int>? order,
  }) => _serial(() async {
    final DlnaDevice target;
    try {
      target = await client.resolveCoordinator(device);
    } catch (_) {
      error.value = DlnaError.group;
      throw const DlnaGroupException();
    }
    final old = connectedDevice.value;
    if (old != null && old.id == target.id && player.hasDevice) {
      // Already connected to this coordinator: refresh the queue in place rather than
      // constructing a second transport (RemoteQueueEngine.connect rejects a new instance).
      try {
        await player.startQueue(
          items,
          revision: revision,
          startIndex: index,
          startPositionMs: positionMs,
          playWhenReady: playWhenReady,
          repeat: repeat,
          shuffle: shuffle,
        );
        error.value = null;
        return;
      } catch (_) {
        await _recoverFailedConnection(target);
        if (error.value != DlnaError.stopUnconfirmed) error.value = DlnaError.playback;
        throw const DlnaCommandException('Connect');
      }
    }
    if (old != null && old.id != target.id) {
      try {
        await player.disconnect(stopRemote: true);
        connectedDevice.value = null;
      } catch (_) {
        error.value = DlnaError.stopUnconfirmed;
        throw const DlnaCommandException('Stop');
      }
    }
    try {
      await player.connect(
        _transportFor(client, target),
        items,
        startIndex: index,
        startPositionMs: positionMs,
        playWhenReady: playWhenReady,
        repeat: repeat,
        shuffle: shuffle,
        revision: revision,
        order: order,
      );
      connectedDevice.value = target;
      error.value = null;
    } catch (_) {
      await _recoverFailedConnection(target);
      if (error.value != DlnaError.stopUnconfirmed) error.value = DlnaError.playback;
      throw const DlnaCommandException('Connect');
    }
  });

  Future<void> _recoverFailedConnection(DlnaDevice target) async {
    var stopped = true;
    try {
      await player.disconnect(stopRemote: true);
    } catch (_) {
      stopped = false;
    }
    connectedDevice.value = stopped || !player.hasDevice ? null : target;
    if (!stopped && player.hasDevice) {
      player.connectionFailed();
      error.value = DlnaError.stopUnconfirmed;
    }
  }

  /// Lets go of the renderer; with [stopRemote] only after a confirmed Stop.
  Future<void> disconnect({bool stopRemote = true}) => _serial(() async {
    try {
      await player.disconnect(stopRemote: stopRemote);
      connectedDevice.value = null;
      error.value = null;
    } catch (_) {
      error.value = DlnaError.stopUnconfirmed;
      throw const DlnaCommandException('Stop');
    }
  });

  Future<void> _serial(Future<void> Function() op) {
    final result = _connection.then((_) => op());
    _connection = result.then((_) {}, onError: (Object _) {});
    return result;
  }
}
