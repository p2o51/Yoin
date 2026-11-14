/// What the default network runs over, from the `yoin/network` channel served
/// by the ArkTS `YoinNetworkPlugin` (ohos/entry/src/main/ets/yoin/network/):
/// `@ohos.net.connection` bearer types, pushed again on every change.
library;

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

abstract interface class NetworkBearerSource {
  /// The bearer now.
  Future<NetworkBearer> current();
}

/// Reads the bearer once, then keeps the value the platform pushes.
final class ChannelNetworkBearer implements NetworkBearerSource {
  ChannelNetworkBearer({MethodChannel channel = const MethodChannel('yoin/network')}) : _channel = channel {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'bearerChanged') _known = NetworkBearer.fromName(call.arguments);
    });
  }

  final MethodChannel _channel;
  NetworkBearer? _known;

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
