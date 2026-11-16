/// DLNA over HTTP (Android player/dlna/DlnaClient.kt): device descriptions,
/// SOAP actions on AVTransport / RenderingControl, and the Sonos coordinator
/// lookup. Plain dart:io, so it runs on HarmonyOS and Windows alike.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'dlna_protocol.dart';
import 'mini_xml.dart';
import 'renderer_transport.dart';

/// One HTTP exchange: status and body (at most [maxXmlChars] bytes, else it throws).
typedef DlnaHttpResponse = ({int status, String body});

/// The HTTP the client speaks; tests answer it in memory.
typedef DlnaHttp =
    Future<DlnaHttpResponse> Function(String method, Uri url, {Map<String, String> headers, String? body});

/// The renderers' HTTP: 4 s to connect, 8 s for the whole exchange, no
/// redirects, bodies capped at 1 MiB (Android OkHttp settings).
DlnaHttp ioDlnaHttp([HttpClient? client]) {
  final http = client ?? (HttpClient()..connectionTimeout = const Duration(seconds: 4));
  return (method, url, {headers = const {}, body}) async {
    Future<DlnaHttpResponse> exchange() async {
      final request = await http.openUrl(method, url);
      request.followRedirects = false;
      headers.forEach(request.headers.set);
      if (body != null) {
        final bytes = utf8.encode(body);
        request.contentLength = bytes.length;
        request.add(bytes);
      }
      final response = await request.close();
      final buffer = BytesBuilder(copy: false);
      await for (final chunk in response) {
        buffer.add(chunk);
        if (buffer.length > maxXmlChars) throw const HttpException('DLNA XML too large');
      }
      return (status: response.statusCode, body: utf8.decode(buffer.takeBytes(), allowMalformed: true));
    }

    return exchange().timeout(const Duration(seconds: 8));
  };
}

class DlnaClient {
  DlnaClient({DlnaHttp? http}) : _http = http ?? ioDlnaHttp();

  final DlnaHttp _http;

  /// The renderers a description at [location] lists.
  Future<List<DlnaDevice>> describe(Uri location) async {
    final DlnaHttpResponse response;
    try {
      response = await _http('GET', location);
    } catch (_) {
      throw const DlnaCommandException('Describe');
    }
    if (response.status < 200 || response.status >= 300) throw DlnaCommandException('Describe', response.status);
    return DlnaProtocol.devices(response.body, location);
  }

  /// Sonos: the group coordinator [device] belongs to (itself when it leads,
  /// or when it is no Sonos). Throws [DlnaGroupException].
  Future<DlnaDevice> resolveCoordinator(DlnaDevice device) async {
    final topology = device.zoneGroupTopology;
    if (topology == null) return device;
    try {
      final response = await action(topology, 'GetZoneGroupState', includeInstance: false);
      final location = DlnaProtocol.coordinatorLocation(response['ZoneGroupState'] ?? '', device.id);
      if (location == null) return device;
      final described = await describe(location);
      if (described.isEmpty) throw const DlnaGroupException();
      return described.first;
    } catch (_) {
      throw const DlnaGroupException();
    }
  }

  /// One SOAP action; the reply's out arguments. Never lets an HTTP error (it
  /// may carry a credential-bearing URL) through: every failure is a [DlnaCommandException].
  Future<Map<String, String>> action(
    DlnaService service,
    String name, {
    Map<String, String> arguments = const {},
    bool includeInstance = true,
  }) async {
    final body = DlnaProtocol.envelope(service, name, {if (includeInstance) 'InstanceID': '0', ...arguments});
    try {
      final response = await _http(
        'POST',
        service.controlUrl,
        headers: {'SOAPACTION': '"${service.type}#$name"', 'Content-Type': 'text/xml; charset=utf-8'},
        body: body,
      );
      final result = DlnaProtocol.response(response.body, name);
      if (response.status < 200 || response.status >= 300) throw DlnaCommandException(name, response.status);
      return result;
    } on DlnaCommandException {
      rethrow;
    } catch (_) {
      throw DlnaCommandException(name);
    }
  }
}

/// One DLNA renderer as a [RendererTransport].
final class DlnaRendererTransport implements RendererTransport {
  DlnaRendererTransport(this.client, this.device);

  final DlnaClient client;
  final DlnaDevice device;

  @override
  Future<void> load(RendererMedia media) async {
    if (DlnaProtocol.httpUri(media.uri) == null) throw ArgumentError('DLNA requires an HTTP stream');
    await client.action(
      device.avTransport,
      'SetAVTransportURI',
      arguments: {'CurrentURI': media.uri, 'CurrentURIMetaData': DlnaProtocol.didl(media)},
    );
  }

  @override
  Future<void> play() => client.action(device.avTransport, 'Play', arguments: const {'Speed': '1'});

  @override
  Future<void> pause() => client.action(device.avTransport, 'Pause');

  @override
  Future<void> stop() => client.action(device.avTransport, 'Stop');

  @override
  Future<void> seek(int positionMs) => client.action(
    device.avTransport,
    'Seek',
    arguments: {'Unit': 'REL_TIME', 'Target': DlnaProtocol.formatTime(positionMs)},
  );

  @override
  Future<RendererPosition> position() async {
    final values = await client.action(device.avTransport, 'GetPositionInfo');
    return RendererPosition(
      positionMs: DlnaProtocol.parseTime(values['RelTime']),
      durationMs: DlnaProtocol.parseTime(values['TrackDuration']),
      trackUri: values['TrackURI'],
    );
  }

  @override
  Future<RendererTransportInfo> transportInfo() async {
    final values = await client.action(device.avTransport, 'GetTransportInfo');
    return RendererTransportInfo(values['CurrentTransportState'] ?? '', values['CurrentTransportStatus']);
  }

  @override
  bool get hasVolume => device.renderingControl != null;

  @override
  Future<int> volume() async {
    final control = device.renderingControl;
    if (control == null) throw const DlnaCommandException('GetVolume');
    final values = await client.action(control, 'GetVolume', arguments: const {'Channel': 'Master'});
    final volume = int.tryParse(values['CurrentVolume'] ?? '');
    if (volume == null) throw const DlnaCommandException('GetVolume');
    return volume.clamp(0, 100);
  }

  @override
  Future<void> setVolume(int volume) async {
    final control = device.renderingControl;
    if (control == null) throw const DlnaCommandException('SetVolume');
    await client.action(
      control,
      'SetVolume',
      arguments: {'Channel': 'Master', 'DesiredVolume': volume.clamp(0, 100).toString()},
    );
  }
}
