/// What the default network runs over, from the `yoin/network` channel served
/// by the ArkTS `YoinNetworkPlugin` (ohos/entry/src/main/ets/yoin/network/):
/// `@ohos.net.connection` bearer types, pushed again on every change; and the
/// default network's own events (Android's default-network callback), which
/// retry failed covers when the network recovers.
library;

import 'dart:async';

import 'package:flutter/services.dart';

enum NetworkBearer {
  wifi,
  cellular,

  /// Ethernet, a VPN on its own, anything else.
  other,

  /// No default network.
  none;

  static NetworkBearer fromName(Object? name) => values.where((b) => b.name == name).firstOrNull ?? other;

  /// Android's NET_CAPABILITY_NOT_METERED, roughly: everything but mobile data.
  bool get isUnmetered => this != cellular;
}

/// One default-network callback (Android ConnectivityManager.NetworkCallback
/// on the default network); [network] is the network's id.
sealed class DefaultNetworkEvent<N extends Object> {
  const DefaultNetworkEvent(this.network);

  final N network;

  /// The event the platform pushed as `defaultNetworkEvent` ({kind, netId,
  /// validated?, blocked?}); null for anything else.
  static DefaultNetworkEvent<int>? fromMap(Object? arguments) {
    if (arguments is! Map) return null;
    final id = arguments['netId'];
    if (id is! int) return null;
    return switch (arguments['kind']) {
      'available' => DefaultNetworkAvailable(id),
      'capabilities' => DefaultNetworkCapabilities(id, validated: arguments['validated'] == true),
      'blocked' => DefaultNetworkBlocked(id, blocked: arguments['blocked'] == true),
      'lost' => DefaultNetworkLost(id),
      _ => null,
    };
  }
}

/// A default network arrived (also the replay of the current one on registration).
final class DefaultNetworkAvailable<N extends Object> extends DefaultNetworkEvent<N> {
  const DefaultNetworkAvailable(super.network);
}

/// The network's capabilities changed; [validated]: it reaches the internet.
final class DefaultNetworkCapabilities<N extends Object> extends DefaultNetworkEvent<N> {
  const DefaultNetworkCapabilities(super.network, {required this.validated});

  final bool validated;
}

/// This app's traffic on the network was blocked or unblocked.
final class DefaultNetworkBlocked<N extends Object> extends DefaultNetworkEvent<N> {
  const DefaultNetworkBlocked(super.network, {required this.blocked});

  final bool blocked;
}

/// The network went.
final class DefaultNetworkLost<N extends Object> extends DefaultNetworkEvent<N> {
  const DefaultNetworkLost(super.network);
}

abstract interface class NetworkBearerSource {
  /// The bearer now.
  Future<NetworkBearer> current();

  /// The default network's id now, or null when there is none.
  Future<int?> defaultNetwork();

  /// The default network's events from now on.
  Stream<DefaultNetworkEvent<int>> get defaultNetworkEvents;
}

/// Reads the bearer once, then keeps the value the platform pushes.
final class ChannelNetworkBearer implements NetworkBearerSource {
  ChannelNetworkBearer({MethodChannel channel = const MethodChannel('yoin/network')}) : _channel = channel {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'bearerChanged') _known = NetworkBearer.fromName(call.arguments);
      if (call.method == 'defaultNetworkEvent') {
        final event = DefaultNetworkEvent.fromMap(call.arguments);
        if (event != null) _events.add(event);
      }
    });
  }

  final MethodChannel _channel;
  NetworkBearer? _known;
  final _events = StreamController<DefaultNetworkEvent<int>>.broadcast();

  @override
  Stream<DefaultNetworkEvent<int>> get defaultNetworkEvents => _events.stream;

  @override
  Future<int?> defaultNetwork() async {
    try {
      final id = await _channel.invokeMethod<int>('defaultNetId');
      return id == null || id == 0 ? null : id;
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  @override
  Future<NetworkBearer> current() async {
    final known = _known;
    if (known != null) return known;
    try {
      return _known = NetworkBearer.fromName(await _channel.invokeMethod<String>('bearer'));
    } on MissingPluginException {
      // Tests and hosts without the plugin.
      return NetworkBearer.other;
    } on PlatformException {
      return NetworkBearer.other;
    }
  }
}
