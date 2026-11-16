import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/output/dlna_client.dart';
import 'package:yoin/playback/output/dlna_discovery.dart';
import 'package:yoin/playback/output/dlna_manager.dart';
import 'package:yoin/playback/output/dlna_protocol.dart';
import 'package:yoin/playback/output/remote_queue_engine.dart';
import 'package:yoin/playback/output/renderer_transport.dart';

import 'output_fakes.dart';

DlnaDevice _device(String id, {String name = 'Speaker', DlnaService? topology}) => DlnaDevice(
  id: id,
  name: name,
  descriptionUrl: Uri.parse('http://10.0.0.9/$id.xml'),
  avTransport: DlnaService('urn:schemas-upnp-org:service:AVTransport:1', Uri.parse('http://10.0.0.9/$id/av')),
  zoneGroupTopology: topology,
);

String _describe(String udn, String name) =>
    '<root><device><friendlyName>$name</friendlyName><UDN>$udn</UDN>'
    '<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>'
    '<controlURL>/av</controlURL></service></serviceList></device></root>';

void main() {
  group('DlnaClient', () {
    test('should_postASoapActionWithItsHeader_when_callingAnAction', () async {
      final requests = <(String, Uri, Map<String, String>, String?)>[];
      final client = DlnaClient(
        http: (method, url, {headers = const {}, body}) async {
          requests.add((method, url, headers, body));
          return (
            status: 200,
            body:
                '<Envelope><Body><GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState>'
                '</GetTransportInfoResponse></Body></Envelope>',
          );
        },
      );
      final transport = DlnaRendererTransport(client, _device('uuid:a'));
      final info = await transport.transportInfo();
      expect(info.state, RendererState.playing);
      final (method, url, headers, body) = requests.single;
      expect(method, 'POST');
      expect(url.toString(), 'http://10.0.0.9/uuid:a/av');
      expect(headers['SOAPACTION'], '"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo"');
      expect(body, contains('<InstanceID>0</InstanceID>'));
    });

    test('should_throwACommandErrorWithoutTheUrl_when_theNetworkFails', () async {
      final client = DlnaClient(http: (method, url, {headers = const {}, body}) async => throw Exception('$url'));
      final transport = DlnaRendererTransport(client, _device('uuid:a'));
      await expectLater(
        transport.play(),
        throwsA(isA<DlnaCommandException>().having((e) => e.toString(), 'text', isNot(contains('10.0.0.9')))),
      );
    });

    test('should_refuseANonHttpStream_when_loading', () async {
      final client = DlnaClient(http: (method, url, {headers = const {}, body}) async => (status: 200, body: ''));
      final transport = DlnaRendererTransport(client, _device('uuid:a'));
      expect(
        () => transport.load(const RendererMedia(id: 'x', title: 't', uri: 'file:///x.flac')),
        throwsArgumentError,
      );
    });

    test('should_returnTheCoordinator_when_aSonosRoomIsGrouped', () async {
      final topology = DlnaService(
        'urn:schemas-upnp-org:service:ZoneGroupTopology:1',
        Uri.parse('http://10.0.0.1/zgt'),
      );
      final client = DlnaClient(
        http: (method, url, {headers = const {}, body}) async {
          if (url.path == '/zgt') {
            final state = DlnaProtocol.escape(
              '<ZoneGroups><ZoneGroup Coordinator="RINCON_B">'
              '<ZoneGroupMember UUID="RINCON_A" Location="http://10.0.0.1/a.xml"/>'
              '<ZoneGroupMember UUID="RINCON_B" Location="http://10.0.0.2/b.xml"/></ZoneGroup></ZoneGroups>',
            );
            return (
              status: 200,
              body:
                  '<Envelope><Body><GetZoneGroupStateResponse><ZoneGroupState>$state</ZoneGroupState>'
                  '</GetZoneGroupStateResponse></Body></Envelope>',
            );
          }
          return (status: 200, body: _describe('uuid:RINCON_B_MR', 'Kitchen'));
        },
      );
      final coordinator = await client.resolveCoordinator(_device('uuid:RINCON_A_MR', topology: topology));
      expect(coordinator.id, 'uuid:RINCON_B_MR');
    });
  });

  group('DlnaDiscovery', () {
    test('should_describeEveryReplyOnceAndSortByName_when_searching', () async {
      final client = DlnaClient(
        http: (method, url, {headers = const {}, body}) async {
          return switch (url.host) {
            '10.0.0.1' => (status: 200, body: _describe('uuid:b', 'bedroom')),
            '10.0.0.2' => (status: 200, body: _describe('uuid:a', 'Attic')),
            '10.0.0.3' => (status: 200, body: _describe('uuid:a', 'Attic again')),
            _ => throw Exception('unreachable'),
          };
        },
      );
      final discovery = DlnaDiscovery(
        client,
        search: () async => {
          Uri.parse('http://10.0.0.1/d.xml'),
          Uri.parse('http://10.0.0.2/d.xml'),
          Uri.parse('http://10.0.0.3/d.xml'),
          Uri.parse('http://10.0.0.4/d.xml'),
        },
      );
      final devices = await discovery.discover();
      expect(devices.map((d) => d.name), ['Attic', 'bedroom']);
    });

    test('should_askForRenderersAndTransports_when_buildingTheSearch', () {
      final message = ssdpSearchMessage('urn:schemas-upnp-org:device:MediaRenderer:1');
      expect(message, startsWith('M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n'));
      expect(message, contains('MAN: "ssdp:discover"'));
      expect(message, endsWith('ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n'));
    });
  });

  group('DlnaManager', () {
    late FakeClock clock;
    late Map<String, FakeRenderer> renderers;
    late DlnaManager manager;

    setUp(() {
      clock = FakeClock();
      renderers = {};
      manager = DlnaManager(
        client: DlnaClient(http: (method, url, {headers = const {}, body}) async => throw Exception('no network')),
        discovery: DlnaDiscovery(
          DlnaClient(http: (method, url, {headers = const {}, body}) async => (status: 200, body: '')),
          search: () async => throw Exception('no multicast'),
        ),
        engine: (onFailure) => RemoteQueueEngine(
          onFailure: onFailure,
          nowMs: clock.call,
          pollInterval: null,
          random: Random(1),
          sleep: clock.sleep,
        ),
        transportFor: (client, device) => renderers.putIfAbsent(device.id, FakeRenderer.new),
      );
    });

    Future<void> connect(DlnaDevice device) => manager.connect(
      device,
      [streamItem(0), streamItem(1)],
      index: 0,
      positionMs: 0,
      playWhenReady: true,
      repeat: EngineRepeat.all,
      shuffle: false,
      revision: 1,
    );

    test('should_reportADiscoveryError_when_theSearchFails', () async {
      await manager.discover();
      expect(manager.error.value, DlnaError.discovery);
      expect(manager.isDiscovering.value, isFalse);
    });

    test('should_selectTheRenderer_when_itPlays', () async {
      await connect(_device('uuid:a'));
      expect(manager.connectedDevice.value?.id, 'uuid:a');
      expect(manager.error.value, isNull);
      expect(renderers['uuid:a']!.calls, ['load:subsonic:t0', 'play']);
    });

    test('should_stopTheOldSpeakerFirst_when_switchingToAnotherOne', () async {
      await connect(_device('uuid:a'));
      await connect(_device('uuid:b'));
      expect(renderers['uuid:a']!.calls.last, 'stop');
      expect(manager.connectedDevice.value?.id, 'uuid:b');
    });

    test('should_reportAGroupError_when_theSonosCoordinatorCantBeFound', () async {
      final topology = DlnaService(
        'urn:schemas-upnp-org:service:ZoneGroupTopology:1',
        Uri.parse('http://10.0.0.1/zgt'),
      );
      await expectLater(connect(_device('uuid:a', topology: topology)), throwsA(isA<DlnaGroupException>()));
      expect(manager.error.value, DlnaError.group);
      expect(manager.connectedDevice.value, isNull);
    });

    test('should_letGo_when_aFailedHandOverIsStoppedOnTheSpeaker', () async {
      renderers['uuid:a'] = FakeRenderer()..failing.add('play');
      await expectLater(connect(_device('uuid:a')), throwsA(isA<DlnaCommandException>()));
      expect(manager.error.value, DlnaError.playback);
      expect(manager.connectedDevice.value, isNull);
      expect(renderers['uuid:a']!.calls, contains('stop'));
    });

    test('should_keepTheSpeakerAndSayStopUnconfirmed_when_aFailedHandOverCantBeStopped', () async {
      // The speaker plays, then stops answering: neither the hand-over nor the Stop is confirmed.
      renderers['uuid:a'] = FakeRenderer()..failing.add('transportInfo');
      await expectLater(connect(_device('uuid:a')), throwsA(isA<DlnaCommandException>()));
      expect(manager.error.value, DlnaError.stopUnconfirmed);
      expect(manager.connectedDevice.value?.id, 'uuid:a');
    });

    test('should_sayStopUnconfirmed_when_disconnectingFromASpeakerThatKeepsPlaying', () async {
      await connect(_device('uuid:a'));
      renderers['uuid:a']!.ignoreStop = true;
      await expectLater(manager.disconnect(), throwsA(isA<DlnaCommandException>()));
      expect(manager.error.value, DlnaError.stopUnconfirmed);
      expect(manager.connectedDevice.value?.id, 'uuid:a');
    });
  });
}
