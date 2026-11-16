/// UPnP / DLNA wire formats (Android player/dlna/DlnaProtocol.kt): SSDP
/// replies, device descriptions, SOAP envelopes and replies, DIDL-Lite and
/// the H:MM:SS clock. Pure functions; the network lives in dlna_client.dart.
library;

import 'dart:io' show IOException;

import 'mini_xml.dart';

/// One UPnP service: its type URN and the control URL its actions post to.
final class DlnaService {
  const DlnaService(this.type, this.controlUrl);

  final String type;
  final Uri controlUrl;

  @override
  bool operator ==(Object other) => other is DlnaService && other.type == type && other.controlUrl == controlUrl;

  @override
  int get hashCode => Object.hash(type, controlUrl);
}

/// A renderer found on the LAN: anything that publishes AVTransport.
final class DlnaDevice {
  const DlnaDevice({
    required this.id,
    required this.name,
    this.manufacturer = '',
    this.modelName = '',
    required this.descriptionUrl,
    required this.avTransport,
    this.renderingControl,
    this.zoneGroupTopology,
  });

  /// The device UDN (`uuid:…`), or the description URL when it has none.
  final String id;
  final String name;
  final String manufacturer;
  final String modelName;
  final Uri descriptionUrl;
  final DlnaService avTransport;

  /// Volume, when the renderer offers it.
  final DlnaService? renderingControl;

  /// Sonos: where the household's groups are read (commands go to a group's coordinator).
  final DlnaService? zoneGroupTopology;

  @override
  bool operator ==(Object other) =>
      other is DlnaDevice &&
      other.id == id &&
      other.name == name &&
      other.descriptionUrl == descriptionUrl &&
      other.avTransport == avTransport &&
      other.renderingControl == renderingControl &&
      other.zoneGroupTopology == zoneGroupTopology;

  @override
  int get hashCode => Object.hash(id, name, descriptionUrl, avTransport, renderingControl, zoneGroupTopology);
}

/// A renderer command that failed. Neither the message nor [toString] carries
/// a stream URL (it may hold the account's token).
final class DlnaCommandException implements IOException {
  const DlnaCommandException(this.action, [this.code]);

  final String action;

  /// The UPnP error code (or HTTP status), when the renderer said one.
  final int? code;

  @override
  String toString() => 'DLNA $action failed${code == null ? '' : ' ($code)'}';
}

/// The Sonos group coordinator could not be found.
final class DlnaGroupException implements IOException {
  const DlnaGroupException();

  @override
  String toString() => 'Cannot find the Sonos group coordinator. Select the group coordinator or retry discovery.';
}

/// What a renderer is told about one song: the stream it pulls and the DIDL
/// metadata it shows.
final class RendererMedia {
  const RendererMedia({
    required this.id,
    required this.title,
    this.artist,
    this.album,
    this.artwork,
    required this.uri,
    this.mimeType,
    this.durationMs,
  });

  final String id;
  final String title;
  final String? artist;
  final String? album;
  final String? artwork;
  final String uri;
  final String? mimeType;
  final int? durationMs;
}

final _ssdpLine = RegExp(r'\r?\n');
final _xmlName = RegExp(r'^[A-Za-z][A-Za-z0-9]*$');
final _audioMime = RegExp(r'^audio/[A-Za-z0-9.+-]+$');
final _clock = RegExp(r'^(\d+):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?$');

abstract final class DlnaProtocol {
  /// [value] as an absolute http(s) URL with a host and no user info, or null.
  static Uri? httpUri(String value) {
    final uri = Uri.tryParse(value.trim());
    if (uri == null) return null;
    final scheme = uri.scheme.toLowerCase();
    if ((scheme != 'http' && scheme != 'https') || uri.host.isEmpty || uri.userInfo.isNotEmpty) return null;
    return uri;
  }

  /// The LOCATION of an SSDP `HTTP/1.1 200` reply, or null.
  static Uri? ssdpLocation(String response) {
    final lines = response.split(_ssdpLine);
    if (lines.isEmpty || !lines.first.toUpperCase().startsWith('HTTP/1.1 200')) return null;
    for (final line in lines.skip(1)) {
      final separator = line.indexOf(':');
      if (separator > 0 && line.substring(0, separator).trim().toLowerCase() == 'location') {
        return httpUri(line.substring(separator + 1));
      }
    }
    return null;
  }

  /// Every AVTransport renderer in a device description fetched from [location].
  static List<DlnaDevice> devices(String xml, Uri location) {
    final root = parseXml(xml);
    final urlBase = root.childText('URLBase');
    final base = (urlBase.isEmpty ? null : httpUri(urlBase)) ?? location;
    final rootDevice = root.child('device');
    final roomName = rootDevice != null && rootDevice.childText('manufacturer').toLowerCase().contains('sonos')
        ? rootDevice.childText('roomName')
        : '';
    DlnaService? topology;
    for (final service in root.descendants('service')) {
      final type = service.childText('serviceType');
      if (!type.startsWith('urn:schemas-upnp-org:service:ZoneGroupTopology:')) continue;
      final control = _resolve(base, service.childText('controlURL'));
      if (control != null) topology = DlnaService(type, control);
      break;
    }
    final out = <DlnaDevice>[];
    final seen = <String>{};
    for (final device in root.descendants('device')) {
      final services = <DlnaService>[];
      for (final service in device.child('serviceList')?.childrenNamed('service') ?? const <XmlElement>[]) {
        final type = service.childText('serviceType');
        final path = service.childText('controlURL');
        if (type.isEmpty || path.isEmpty) continue;
        final control = _resolve(base, path);
        if (control != null) services.add(DlnaService(type, control));
      }
      // Sonos ZonePlayer devices also publish AVTransport without a MediaRenderer device type.
      final transport = services
          .where((s) => s.type.startsWith('urn:schemas-upnp-org:service:AVTransport:'))
          .firstOrNull;
      if (transport == null) continue;
      final udn = device.childText('UDN');
      final id = udn.isEmpty ? location.toString() : udn;
      if (!seen.add(id)) continue;
      final friendly = device.childText('friendlyName');
      out.add(
        DlnaDevice(
          id: id,
          name: roomName.isNotEmpty ? roomName : (friendly.isEmpty ? location.host : friendly),
          manufacturer: device.childText('manufacturer'),
          modelName: device.childText('modelName'),
          descriptionUrl: location,
          avTransport: transport,
          renderingControl: services
              .where((s) => s.type.startsWith('urn:schemas-upnp-org:service:RenderingControl:'))
              .firstOrNull,
          zoneGroupTopology: topology,
        ),
      );
    }
    return out;
  }

  /// Sonos groups take AVTransport commands at their coordinator; never regroup a household.
  /// Null when [rendererId] is the coordinator itself.
  static Uri? coordinatorLocation(String xml, String rendererId) {
    var uuid = rendererId.startsWith('uuid:') ? rendererId.substring(5) : rendererId;
    if (uuid.endsWith('_MR')) uuid = uuid.substring(0, uuid.length - 3);
    final groups = parseXml(xml).descendants('ZoneGroup');
    final group = groups
        .where((g) => g.descendants('ZoneGroupMember').any((m) => m.attribute('UUID') == uuid))
        .firstOrNull;
    if (group == null) throw const DlnaGroupException();
    final coordinator = group.attribute('Coordinator');
    if (coordinator.isEmpty) throw const DlnaGroupException();
    if (coordinator == uuid) return null;
    final member = group.descendants('ZoneGroupMember').where((m) => m.attribute('UUID') == coordinator).firstOrNull;
    final location = member == null ? null : httpUri(member.attribute('Location'));
    if (location == null) throw const DlnaGroupException();
    return location;
  }

  static String envelope(DlnaService service, String action, Map<String, String> arguments) {
    if (!_xmlName.hasMatch(action) || !arguments.keys.every(_xmlName.hasMatch)) {
      throw ArgumentError('DLNA action and argument names must be plain XML names');
    }
    final body = StringBuffer()
      ..write('<?xml version="1.0" encoding="utf-8"?><s:Envelope ')
      ..write('xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" ')
      ..write('s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action ')
      ..write('xmlns:u="${escape(service.type)}">');
    arguments.forEach((key, value) => body.write('<$key>${escape(value)}</$key>'));
    body.write('</u:$action></s:Body></s:Envelope>');
    return body.toString();
  }

  /// The out arguments of [action]'s reply. A SOAP fault throws its UPnP error code.
  static Map<String, String> response(String xml, String action) {
    final root = parseXml(xml);
    final fault = root.localName == 'Fault' ? root : root.descendants('Fault').firstOrNull;
    if (fault != null) {
      final code = int.tryParse(fault.descendants('errorCode').firstOrNull?.text.trim() ?? '');
      throw DlnaCommandException(action, code);
    }
    final result = root.descendants('${action}Response').firstOrNull;
    if (result == null) throw DlnaCommandException(action);
    return {for (final e in result.children) e.localName: e.text.trim()};
  }

  static String didl(RendererMedia media) {
    final out = StringBuffer()
      ..write('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" ')
      ..write('xmlns:dc="http://purl.org/dc/elements/1.1/" ')
      ..write('xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="')
      ..write(escape(media.id))
      ..write('" parentID="0" restricted="1"><dc:title>')
      ..write(escape(media.title))
      ..write('</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>');
    if (media.artist case final artist?) out.write('<upnp:artist>${escape(artist)}</upnp:artist>');
    if (media.album case final album?) out.write('<upnp:album>${escape(album)}</upnp:album>');
    if (media.artwork case final art?) out.write('<upnp:albumArtURI>${escape(art)}</upnp:albumArtURI>');
    // A Subsonic original stream can be FLAC. Never falsely label unknown media as MP3.
    final mime = media.mimeType != null && _audioMime.hasMatch(media.mimeType!) ? media.mimeType! : '*';
    out.write('<res protocolInfo="http-get:*:$mime:*"');
    final duration = media.durationMs;
    if (duration != null && duration > 0) out.write(' duration="${formatTime(duration)}"');
    out.write('>${escape(media.uri)}</res></item></DIDL-Lite>');
    return out.toString();
  }

  /// `H+:MM:SS[.mmm]` in milliseconds; null for anything else (NOT_IMPLEMENTED…).
  static int? parseTime(String? value) {
    final m = _clock.firstMatch(value ?? '');
    if (m == null) return null;
    final hours = int.tryParse(m.group(1)!);
    if (hours == null || hours >= 1000000) return null;
    final millis = int.parse((m.group(4) ?? '').padRight(3, '0'));
    return ((hours * 60 + int.parse(m.group(2)!)) * 60 + int.parse(m.group(3)!)) * 1000 + millis;
  }

  static String formatTime(int positionMs) {
    final seconds = (positionMs < 0 ? 0 : positionMs) ~/ 1000;
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(seconds ~/ 3600)}:${two((seconds ~/ 60) % 60)}:${two(seconds % 60)}';
  }

  static String escape(String value) => value
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&apos;');

  static Uri? _resolve(Uri base, String path) {
    try {
      return httpUri(base.resolve(path.trim()).toString());
    } on FormatException {
      return null;
    }
  }
}
