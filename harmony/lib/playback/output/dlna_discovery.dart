/// SSDP discovery of DLNA renderers (Android player/dlna/DlnaDiscovery.kt):
/// two M-SEARCH rounds for MediaRenderer and AVTransport to
/// 239.255.255.250:1900, a 4 s listening window, at most 32 replies, then
/// every description read in parallel. Explicit and bounded: nothing keeps
/// multicast running while Yoin is idle.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'dlna_client.dart';
import 'dlna_protocol.dart';

/// Sends the M-SEARCH and collects reply LOCATIONs (tests answer in memory).
typedef SsdpSearch = Future<Set<Uri>> Function();

const _ssdpGroup = '239.255.255.250';
const _ssdpPort = 1900;
const _searchTargets = ['urn:schemas-upnp-org:device:MediaRenderer:1', 'urn:schemas-upnp-org:service:AVTransport:1'];

/// Untrusted multicast traffic must not create unlimited IO: at most this many descriptions.
const int ssdpMaxLocations = 32;

/// The M-SEARCH datagram for [target].
String ssdpSearchMessage(String target) =>
    'M-SEARCH * HTTP/1.1\r\nHOST: $_ssdpGroup:$_ssdpPort\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: $target\r\n\r\n';

/// The real search over a UDP socket (TTL 2).
Future<Set<Uri>> udpSsdpSearch({Duration window = const Duration(seconds: 4)}) async {
  final socket = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
  final locations = <Uri>{};
  final done = Completer<void>();
  Timer? deadline;
  late final StreamSubscription<RawSocketEvent> sub;
  void finish() {
    if (!done.isCompleted) done.complete();
  }

  try {
    socket.multicastHops = 2;
    sub = socket.listen(
      (event) {
        if (event != RawSocketEvent.read) return;
        Datagram? packet;
        while ((packet = socket.receive()) != null) {
          final location = DlnaProtocol.ssdpLocation(utf8.decode(packet!.data, allowMalformed: true));
          if (location != null) locations.add(location);
          if (locations.length >= ssdpMaxLocations) {
            finish();
            return;
          }
        }
      },
      onError: (Object _) => finish(),
      onDone: finish,
    );
    final group = InternetAddress(_ssdpGroup);
    for (var round = 0; round < 2; round++) {
      for (final target in _searchTargets) {
        socket.send(ascii.encode(ssdpSearchMessage(target)), group, _ssdpPort);
      }
    }
    deadline = Timer(window, finish);
    await done.future;
    await sub.cancel();
  } finally {
    deadline?.cancel();
    socket.close();
  }
  return locations;
}

class DlnaDiscovery {
  DlnaDiscovery(this.client, {SsdpSearch? search}) : _search = search ?? udpSsdpSearch;

  final DlnaClient client;
  final SsdpSearch _search;

  /// The renderers on the LAN, by name. A description that can't be read is skipped.
  Future<List<DlnaDevice>> discover() async {
    final locations = (await _search()).take(ssdpMaxLocations);
    final described = await Future.wait([
      for (final location in locations) client.describe(location).catchError((Object _) => const <DlnaDevice>[]),
    ]);
    final seen = <String>{};
    final devices = [
      for (final list in described)
        for (final device in list)
          if (seen.add(device.id)) device,
    ];
    devices.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    return devices;
  }
}
