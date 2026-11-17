import 'dart:typed_data';

import 'package:yoin/data/sync/sync_contracts.dart';

/// An in-memory Drive appDataFolder shared by several fake devices.
class FakeDrive {
  final Map<String, FakeDriveFile> files = {};
  int _ids = 0;
  String permissionId = 'perm-1';
  String? email = 'me@example.com';

  /// Thrown by the next call of any client, then cleared.
  SyncTransportException? failNext;

  String newId() => 'f${++_ids}';
}

class FakeDriveFile {
  FakeDriveFile(this.id, this.name, this.appProperties, this.bytes);
  final String id;
  final String name;
  final Map<String, String> appProperties;
  Uint8List bytes;
  int version = 1;
}

class FakeDriveTransport implements SyncTransport {
  FakeDriveTransport(this.drive);
  final FakeDrive drive;
  int calls = 0;

  void _check() {
    calls++;
    final fail = drive.failNext;
    if (fail != null) {
      drive.failNext = null;
      throw fail;
    }
  }

  RemoteFile _remote(FakeDriveFile f) => RemoteFile(
    id: f.id,
    name: f.name,
    version: f.version,
    modifiedTime: 0,
    size: f.bytes.length,
    appProperties: Map.of(f.appProperties),
  );

  @override
  Future<GoogleAccountInfo> about() async {
    _check();
    return GoogleAccountInfo(permissionId: drive.permissionId, email: drive.email, displayName: 'Me');
  }

  @override
  Future<RemoteFile> create({
    required String fileId,
    required String name,
    required Map<String, String> appProperties,
    required String mimeType,
    required Uint8List bytes,
  }) async {
    _check();
    if (drive.files.containsKey(fileId)) throw SyncAlreadyExists(fileId);
    final f = FakeDriveFile(fileId, name, appProperties, bytes);
    drive.files[fileId] = f;
    return _remote(f);
  }

  @override
  Future<bool> delete(String fileId) async {
    _check();
    drive.files.remove(fileId);
    return true;
  }

  @override
  Future<Uint8List?> download(String fileId) async {
    _check();
    return drive.files[fileId]?.bytes;
  }

  @override
  Future<List<String>> generateIds(int count) async {
    _check();
    return [for (var i = 0; i < count; i++) drive.newId()];
  }

  @override
  Future<List<RemoteFile>> list() async {
    _check();
    return [for (final f in drive.files.values) _remote(f)];
  }

  @override
  Future<RemoteFile?> update(String fileId, Uint8List bytes) async {
    _check();
    final f = drive.files[fileId];
    if (f == null) return null;
    f.bytes = bytes;
    f.version++;
    return _remote(f);
  }
}
