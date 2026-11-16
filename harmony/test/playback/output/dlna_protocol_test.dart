import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/playback/output/dlna_protocol.dart';
import 'package:yoin/playback/output/mini_xml.dart';

const _description = '''<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://192.168.1.20:1400/</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
    <friendlyName>192.168.1.20 - Sonos One</friendlyName>
    <manufacturer>Sonos, Inc.</manufacturer>
    <modelName>Sonos One</modelName>
    <roomName>Living Room</roomName>
    <UDN>uuid:RINCON_A</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType>
        <controlURL>/ZoneGroupTopology/Control</controlURL>
      </service>
    </serviceList>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Living Room - Media Renderer</friendlyName>
        <UDN>uuid:RINCON_A_MR</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
          </service>
        </serviceList>
      </device>
    </deviceList>
  </device>
</root>''';

const _plainRenderer = '''<root>
  <device>
    <friendlyName>Kitchen &amp; Bar</friendlyName>
    <manufacturer>Acme</manufacturer>
    <UDN>uuid:kitchen</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>ctl/av</controlURL>
      </service>
    </serviceList>
  </device>
</root>''';

void main() {
  group('mini xml', () {
    test('should_readNamespacedElementsAndEntities_when_parsing', () {
      final root = parseXml(
        '<s:Envelope xmlns:s="x"><s:Body><a b="1 &lt; 2">x &amp; y<![CDATA[<raw>]]></a></s:Body></s:Envelope>',
      );
      expect(root.localName, 'Envelope');
      final a = root.descendants('a').single;
      expect(a.attribute('b'), '1 < 2');
      expect(a.text, 'x & y<raw>');
    });

    test('should_refuseTheDocument_when_itCarriesADtd', () {
      expect(() => parseXml('<!DOCTYPE x [<!ENTITY a "b">]><x>&a;</x>'), throwsFormatException);
    });

    test('should_fail_when_tagsDoNotMatch', () {
      expect(() => parseXml('<a><b></a>'), throwsFormatException);
    });
  });

  group('DlnaProtocol', () {
    test('should_acceptOnlyHttpUrlsWithAHostAndNoUserInfo_when_checkingUris', () {
      expect(DlnaProtocol.httpUri(' http://h:1/x '), isNotNull);
      expect(DlnaProtocol.httpUri('https://h/x'), isNotNull);
      expect(DlnaProtocol.httpUri('file:///x'), isNull);
      expect(DlnaProtocol.httpUri('yoin-am:123'), isNull);
      expect(DlnaProtocol.httpUri('http://u:p@h/x'), isNull);
    });

    test('should_readTheLocation_when_ssdpReplyIs200', () {
      final location = DlnaProtocol.ssdpLocation(
        'HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation: http://10.0.0.2:49152/desc.xml\r\n\r\n',
      );
      expect(location.toString(), 'http://10.0.0.2:49152/desc.xml');
      expect(DlnaProtocol.ssdpLocation('NOTIFY * HTTP/1.1\r\nLOCATION: http://10.0.0.2/\r\n'), isNull);
    });

    test('should_listTheSonosRendererByRoomName_when_descriptionHasEmbeddedDevices', () {
      final devices = DlnaProtocol.devices(
        _description,
        Uri.parse('http://192.168.1.20:1400/xml/device_description.xml'),
      );
      expect(devices, hasLength(1));
      final sonos = devices.single;
      expect(sonos.id, 'uuid:RINCON_A_MR');
      expect(sonos.name, 'Living Room');
      expect(sonos.avTransport.controlUrl.toString(), 'http://192.168.1.20:1400/MediaRenderer/AVTransport/Control');
      expect(sonos.renderingControl, isNotNull);
      expect(sonos.zoneGroupTopology!.controlUrl.path, '/ZoneGroupTopology/Control');
    });

    test('should_resolveRelativeControlUrlsAgainstTheLocation_when_thereIsNoUrlBase', () {
      final device = DlnaProtocol.devices(_plainRenderer, Uri.parse('http://10.0.0.5:8080/dev/desc.xml')).single;
      expect(device.name, 'Kitchen & Bar');
      expect(device.avTransport.controlUrl.toString(), 'http://10.0.0.5:8080/dev/ctl/av');
      expect(device.renderingControl, isNull);
      expect(device.zoneGroupTopology, isNull);
    });

    test('should_findTheCoordinatorLocation_when_rendererIsAGroupMember', () {
      const state =
          '<ZoneGroupState><ZoneGroups>'
          '<ZoneGroup Coordinator="RINCON_B"><ZoneGroupMember UUID="RINCON_A" Location="http://10.0.0.1:1400/a.xml"/>'
          '<ZoneGroupMember UUID="RINCON_B" Location="http://10.0.0.2:1400/b.xml"/></ZoneGroup>'
          '</ZoneGroups></ZoneGroupState>';
      expect(DlnaProtocol.coordinatorLocation(state, 'uuid:RINCON_A_MR').toString(), 'http://10.0.0.2:1400/b.xml');
      expect(DlnaProtocol.coordinatorLocation(state, 'uuid:RINCON_B_MR'), isNull);
      expect(() => DlnaProtocol.coordinatorLocation(state, 'uuid:RINCON_Z'), throwsA(isA<DlnaGroupException>()));
    });

    test('should_escapeArgumentsAndRefuseOddNames_when_buildingAnEnvelope', () {
      final service = DlnaService('urn:schemas-upnp-org:service:AVTransport:1', Uri.parse('http://h/c'));
      final body = DlnaProtocol.envelope(service, 'SetAVTransportURI', {'CurrentURI': 'http://h/s?a=1&b=<2>'});
      expect(body, contains('<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">'));
      expect(body, contains('<CurrentURI>http://h/s?a=1&amp;b=&lt;2&gt;</CurrentURI>'));
      expect(() => DlnaProtocol.envelope(service, 'Bad Action', const {}), throwsArgumentError);
    });

    test('should_returnOutArgumentsOrThrowTheFaultCode_when_readingAReply', () {
      const ok =
          '<s:Envelope xmlns:s="x"><s:Body><u:GetPositionInfoResponse xmlns:u="y">'
          '<RelTime>0:01:02</RelTime><TrackURI>http://h/s?a=1&amp;b=2</TrackURI>'
          '</u:GetPositionInfoResponse></s:Body></s:Envelope>';
      final values = DlnaProtocol.response(ok, 'GetPositionInfo');
      expect(values['RelTime'], '0:01:02');
      expect(values['TrackURI'], 'http://h/s?a=1&b=2');
      const fault =
          '<s:Envelope xmlns:s="x"><s:Body><s:Fault><detail><UPnPError>'
          '<errorCode>710</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>';
      expect(
        () => DlnaProtocol.response(fault, 'Seek'),
        throwsA(isA<DlnaCommandException>().having((e) => e.code, 'code', 710)),
      );
    });

    test('should_neverLabelUnknownMediaAsMp3_when_writingDidl', () {
      final didl = DlnaProtocol.didl(
        const RendererMedia(
          id: 'subsonic:1',
          title: 'A & B',
          artist: 'Artist',
          uri: 'http://h/stream?id=1&t=x',
          durationMs: 61000,
        ),
      );
      expect(didl, contains('<dc:title>A &amp; B</dc:title>'));
      expect(didl, contains('protocolInfo="http-get:*:*:*"'));
      expect(didl, contains('duration="00:01:01"'));
      expect(didl, contains('>http://h/stream?id=1&amp;t=x</res>'));
      final flac = DlnaProtocol.didl(const RendererMedia(id: '1', title: 't', uri: 'http://h', mimeType: 'audio/flac'));
      expect(flac, contains('http-get:*:audio/flac:*'));
    });

    test('should_roundTripTheClock_when_parsingAndFormatting', () {
      expect(DlnaProtocol.parseTime('1:02:03.5'), 3723500);
      expect(DlnaProtocol.parseTime('00:00:07'), 7000);
      expect(DlnaProtocol.parseTime('NOT_IMPLEMENTED'), isNull);
      expect(DlnaProtocol.parseTime('0:61:00'), isNull);
      expect(DlnaProtocol.formatTime(3723999), '01:02:03');
      expect(DlnaProtocol.formatTime(-5), '00:00:00');
    });
  });
}
