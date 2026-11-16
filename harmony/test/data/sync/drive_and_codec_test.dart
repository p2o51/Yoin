import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/sync/drive/drive_app_data_client.dart';
import 'package:yoin/data/sync/engine/snapshot_codec.dart';
import 'package:yoin/data/sync/sync_contracts.dart';

class _Tokens implements SyncTokenProvider {
  final issued = <String>[];
  final invalidated = <String>[];
  int _n = 0;

  @override
  Future<String> token() async {
    final t = 'tok${++_n}';
    issued.add(t);
    return t;
  }

  @override
  Future<void> invalidate(String token) async => invalidated.add(token);
}

void main() {
  group('SnapshotCodec', () {
    test('should_readAndroidsFile_when_kotlinxWroteIt', () {
      // As kotlinx (encodeDefaults, explicitNulls = false) writes it on Android.
      const android =
          '{"format":"yoin-sync","schema":1,"epoch":0,"deviceId":"a1","deviceName":"Pixel 9","fileClass":"state",'
          '"shard":0,"writtenAt":5,"appVersionCode":25,"records":[{"k":"album_rating","kv":1,"s":"s-1",'
          '"id":"subsonic\\u001fal1","ts":4,"d":"a1","del":false,"v":{"albumId":"al1","provider":"subsonic",'
          '"rating":7.0}},{"k":"song_note","kv":1,"s":"s-1","id":"n1","ts":3,"d":"a1","pts":2,"pd":"b2","del":true}]}';
      final envelope = SnapshotCodec.decode(Uint8List.fromList(gzip.encode(utf8.encode(android))));
      expect(envelope.deviceName, 'Pixel 9');
      expect(envelope.records, hasLength(2));
      expect(envelope.records.first.key, 'subsonic\u001Fal1');
      expect(envelope.records.first.payload?['rating'], 7.0);
      expect(envelope.records.last.deleted, isTrue);
      expect(envelope.records.last.prevDevice, 'b2');
    });

    test('should_writeAndroidsFieldOrder_when_encoded', () {
      final bytes = SnapshotCodec.encode(
        const SyncEnvelope(
          deviceId: 'h1',
          deviceName: 'Mate',
          fileClass: 'state',
          writtenAt: 9,
          appVersionCode: 1,
          records: [WireRecord(kind: 'song_note', key: 'n', ts: 1, device: 'h1', deleted: true)],
        ),
      );
      expect(SnapshotCodec.isGzip(bytes), isTrue);
      expect(
        utf8.decode(gzip.decode(bytes)),
        '{"format":"yoin-sync","schema":1,"epoch":0,"deviceId":"h1","deviceName":"Mate","fileClass":"state",'
        '"shard":0,"writtenAt":9,"appVersionCode":1,"records":[{"k":"song_note","kv":1,"s":"","id":"n","ts":1,'
        '"d":"h1","del":true}]}',
      );
    });

    test('should_askForUpdate_when_schemaIsNewer', () {
      final bytes = Uint8List.fromList(
        utf8.encode('{"format":"yoin-sync","schema":2,"deviceId":"z","deviceName":"Future phone","records":[]}'),
      );
      expect(
        () => SnapshotCodec.decode(bytes),
        throwsA(isA<SyncSchemaTooNewException>().having((e) => e.deviceName, 'device', 'Future phone')),
      );
    });

    test('should_dropOnlyTheBadRecord_when_oneRecordUnreadable', () {
      final bytes = Uint8List.fromList(
        utf8.encode(
          '{"format":"yoin-sync","schema":1,"deviceId":"z","deviceName":"n","fileClass":"state","writtenAt":1,'
          '"appVersionCode":1,"records":[{"k":1},{"k":"a","id":"b","ts":1,"d":"z","v":{"x":1}}]}',
        ),
      );
      expect(SnapshotCodec.decode(bytes).records.single.kind, 'a');
    });

    test('should_refuse_when_notAYoinFile', () {
      expect(
        () => SnapshotCodec.decode(Uint8List.fromList(utf8.encode('[]'))),
        throwsA(isA<MalformedSyncFileException>()),
      );
    });
  });

  group('DriveAppDataClient', () {
    test('should_followPages_when_listing', () async {
      final client = MockClient((request) async {
        expect(request.headers['Authorization'], startsWith('Bearer '));
        expect(request.url.queryParameters['spaces'], 'appDataFolder');
        final page = request.url.queryParameters['pageToken'];
        return http.Response(
          jsonEncode({
            'files': [
              {
                'id': page == null ? 'a' : 'b',
                'version': '3',
                'appProperties': {'yoinKind': 'state'},
              },
            ],
            if (page == null) 'nextPageToken': 'p2',
          }),
          200,
        );
      });
      final files = await DriveAppDataClient(http: client, tokens: _Tokens()).list();
      expect(files.map((f) => f.id), ['a', 'b']);
      expect(files.first.version, 3);
      expect(files.first.appProperties['yoinKind'], 'state');
    });

    test('should_retryOnceWithFreshToken_when_401', () async {
      final tokens = _Tokens();
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        if (request.headers['Authorization'] == 'Bearer tok1') return http.Response('', 401);
        return http.Response(
          jsonEncode({
            'user': {'permissionId': 'p1', 'emailAddress': 'a@b.c'},
          }),
          200,
        );
      });
      final about = await DriveAppDataClient(http: client, tokens: tokens).about();
      expect(about.permissionId, 'p1');
      expect(about.email, 'a@b.c');
      expect(calls, 2);
      expect(tokens.invalidated, ['tok1']);
    });

    test('should_failUnauthorized_when_refreshedTokenAlsoRejected', () async {
      final client = MockClient((_) async => http.Response('', 401));
      await expectLater(DriveAppDataClient(http: client, tokens: _Tokens()).about(), throwsA(isA<SyncUnauthorized>()));
    });

    test('should_sendMultipartWithAppDataParent_when_creating', () async {
      late String body;
      final client = MockClient((request) async {
        expect(request.url.path, '/upload/drive/v3/files');
        expect(request.url.queryParameters['uploadType'], 'multipart');
        expect(request.headers['Content-Type'], startsWith('multipart/related; boundary='));
        body = latin1.decode(request.bodyBytes);
        return http.Response(jsonEncode({'id': 'id1', 'name': 'yoin-state-x.json.gz', 'version': '1'}), 200);
      });
      final file = await DriveAppDataClient(http: client, tokens: _Tokens()).create(
        fileId: 'id1',
        name: 'yoin-state-x.json.gz',
        appProperties: {'yoinKind': 'state'},
        mimeType: 'application/gzip',
        bytes: Uint8List.fromList([1, 2, 3]),
      );
      expect(file.id, 'id1');
      expect(body, contains('"parents":["appDataFolder"]'));
      expect(body, contains('"appProperties":{"yoinKind":"state"}'));
      expect(body, contains('Content-Type: application/gzip'));
    });

    test('should_reportAlreadyExists_when_409', () async {
      final client = MockClient((_) async => http.Response('{}', 409));
      await expectLater(
        DriveAppDataClient(
          http: client,
          tokens: _Tokens(),
        ).create(fileId: 'x', name: 'n', appProperties: const {}, mimeType: 'application/json', bytes: Uint8List(0)),
        throwsA(isA<SyncAlreadyExists>()),
      );
    });

    test('should_mapDriveReasons_when_requestsFail', () {
      http.Response error(int code, String reason) => http.Response(
        jsonEncode({
          'error': {
            'code': code,
            'message': 'm',
            'errors': [
              {'reason': reason},
            ],
          },
        }),
        code,
      );
      expect(DriveAppDataClient.errorFor(error(403, 'storageQuotaExceeded')), isA<SyncStorageFull>());
      expect(DriveAppDataClient.errorFor(error(403, 'accessNotConfigured')), isA<SyncMisconfigured>());
      expect(DriveAppDataClient.errorFor(error(403, 'insufficientPermissions')), isA<SyncUnauthorized>());
      expect(DriveAppDataClient.errorFor(error(403, 'userRateLimitExceeded')), isA<SyncRateLimited>());
      expect(DriveAppDataClient.errorFor(http.Response('', 503)), isA<SyncServerError>());
    });

    test('should_answerNull_when_downloadedFileGone', () async {
      final client = MockClient((_) async => http.Response('', 404));
      expect(await DriveAppDataClient(http: client, tokens: _Tokens()).download('gone'), isNull);
    });
  });
}
