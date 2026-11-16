/// The playback diagnostics log (Settings › Help and about › Export log;
/// Android player/PlayTrace.kt, iOS's PlayTrace): the last [playTraceCapacity]
/// lines of playback commands, starts per provider, player errors, the native
/// engine's events and the decoder's formats, kept in memory and in
/// cacheDir/playtrace.log so a log survives a relaunch. Nothing leaves the
/// device unless the user shares the export.
///
/// Callers log paths, statuses and ids only; [PlayTrace.redact] cuts anything
/// shaped like a token or a Subsonic credential, [PlayTrace.describe] is how a
/// stream URL may be logged and [PlayTrace.maskHosts] is how an error message
/// may be.
library;

import 'dart:collection';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Lines kept (Android PlayTrace.CAPACITY).
const int playTraceCapacity = 2000;

/// The log's file in the cache folder.
const String playTraceFileName = 'playtrace.log';

/// The export's folder in the cache folder: the share sheet's (SharePlugin.ets writes there too).
const String playTraceExportDir = 'shared';

/// Every export is named `yoin-log-<time>.txt`; earlier ones are deleted.
const String playTraceExportPrefix = 'yoin-log-';

/// The log. Inert until [install]ed: lines logged before (and in tests that
/// never install one) are dropped, as on Android.
final class PlayTrace {
  PlayTrace({this.capacity = playTraceCapacity, DateTime Function()? now}) : _now = now ?? DateTime.now;

  /// One kept in memory only, installed at once (tests).
  PlayTrace.memory({this.capacity = playTraceCapacity, DateTime Function()? now})
    : _now = now ?? DateTime.now,
      _ring = PlayTraceRing(capacity);

  /// The app's log: main.dart installs it with the cache folder.
  static final PlayTrace shared = PlayTrace();

  final int capacity;
  final DateTime Function() _now;
  PlayTraceRing? _ring;

  bool get installed => _ring != null;

  /// Starts keeping lines: in memory at once, and in [folder]'s
  /// [playTraceFileName] once it resolves (the previous run's tail comes
  /// first, then what was logged meanwhile). A second call does nothing.
  void install(Future<Directory?> Function() folder) {
    if (_ring != null) return;
    final ring = _ring = PlayTraceRing(capacity);
    folder().then((dir) {
      if (dir != null) ring.attach(File('${dir.path}${Platform.pathSeparator}$playTraceFileName'));
    }, onError: (Object _) {});
  }

  /// One line, stamped with the local time ([atMs], epoch ms, when the event
  /// happened earlier: the native engine's queued lines), redacted.
  void log(String message, {int? atMs}) {
    final ring = _ring;
    if (ring == null) return;
    final at = atMs == null ? _now() : DateTime.fromMillisecondsSinceEpoch(atMs);
    ring.append('${stamp(at)} ${redact(message)}');
  }

  /// The lines kept, oldest first.
  List<String> get lines => _ring?.snapshot() ?? const [];

  /// The log as [folder]/shared/yoin-log-<time>.txt with [header] (the app
  /// version and the device) on top; earlier exports are deleted.
  Future<File> writeExport(Directory folder, {required List<String> header}) async {
    final dir = Directory('${folder.path}${Platform.pathSeparator}$playTraceExportDir');
    await dir.create(recursive: true);
    await for (final entry in dir.list()) {
      final name = entry.uri.pathSegments.isEmpty ? '' : entry.uri.pathSegments.last;
      if (entry is File && name.startsWith(playTraceExportPrefix)) {
        try {
          await entry.delete();
        } on FileSystemException {
          // Still held by a share target: left.
        }
      }
    }
    final file = File('${dir.path}${Platform.pathSeparator}$playTraceExportPrefix${fileStamp(_now())}.txt');
    await file.writeAsString('${[...header, ...lines].join('\n')}\n', flush: true);
    return file;
  }

  /// `MM-dd HH:mm:ss.SSS`, local time (Android's stamp).
  static String stamp(DateTime t) =>
      '${_two(t.month)}-${_two(t.day)} ${_two(t.hour)}:${_two(t.minute)}:${_two(t.second)}.'
      '${t.millisecond.toString().padLeft(3, '0')}';

  /// `yyyyMMdd-HHmmss` for the export's name.
  static String fileStamp(DateTime t) =>
      '${t.year.toString().padLeft(4, '0')}${_two(t.month)}${_two(t.day)}-${_two(t.hour)}${_two(t.minute)}${_two(t.second)}';

  static String _two(int v) => v.toString().padLeft(2, '0');

  static final _tokenPattern = RegExp(r'(access_token=|refresh_token=|code=|Bearer )[^\s&#"]+');
  static final _credentialPattern = RegExp(r'([?&](?:u|t|s|p|apiKey|token|salt)=)[^\s&#"]+');
  static final _userInfoPattern = RegExp(r'(://)[^/\s@]+@');

  /// Anything shaped like a bearer or OAuth token, a Subsonic credential
  /// parameter or a URL's user:password is cut.
  static String redact(String s) => s
      .replaceAllMapped(_tokenPattern, (m) => '${m[1]}…')
      .replaceAllMapped(_credentialPattern, (m) => '${m[1]}…')
      .replaceAllMapped(_userInfoPattern, (m) => '${m[1]}…@');

  static final _authorityPattern = RegExp(r'(://)[^/\s?#"]+');
  static final _hostPattern = RegExp(
    r'\[[0-9a-fA-F:.]+\]|\b(?:\d{1,3}\.){3}\d{1,3}\b|\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b',
  );

  /// An error message as it may be logged: a URL's authority, a host name
  /// and an IPv4 / IPv6 literal become "…", so a network failure never names
  /// the user's server. Dotted names that are not hosts are cut as well.
  static String maskHosts(String? s) =>
      (s ?? '').replaceAllMapped(_authorityPattern, (m) => '${m[1]}…').replaceAll(_hostPattern, '…');

  static const _safeQuery = {'id', 'maxBitRate', 'format', 'timeOffset', 'estimateContentLength'};

  /// A stream URL as it may be logged (iOS AVAudioOutput.describe): scheme,
  /// the endpoint and the song / delivery parameters, never the server's
  /// address, the credentials (`u`, `t`, `s`, `p`, `apiKey`) or anything unknown.
  static String describe(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null) return '<url>';
    final items = uri.query.isEmpty
        ? const <String>[]
        : [
            for (final item in uri.query.split('&'))
              if (_safeQuery.contains(item.split('=').first)) item,
          ];
    final endpoint = uri.pathSegments.lastWhere((s) => s.isNotEmpty, orElse: () => '');
    return '${uri.scheme}://…/$endpoint${items.isEmpty ? '' : '?${items.join('&')}'}';
  }
}

/// [PlayTrace]'s lines (Android PlayTraceRing): the last [capacity] in memory,
/// appended to the attached file as they come, the file cut back to
/// [capacity] lines each time [capacity] more have been written. When the
/// file is attached the previous run's tail is read back so it comes first.
/// Synchronous: the Dart isolate is its one thread.
final class PlayTraceRing {
  PlayTraceRing(this.capacity, [File? file]) : _file = file;

  final int capacity;
  File? _file;
  final _lines = ListQueue<String>();
  RandomAccessFile? _out;
  int _written = 0;
  bool _opened = false;

  /// Starts writing to [file] (the first one only); what was kept meanwhile
  /// goes after its tail.
  void attach(File file) {
    if (_file != null) return;
    _file = file;
    _open();
  }

  void append(String line) {
    _open();
    _lines.add(line);
    while (_lines.length > capacity) {
      _lines.removeFirst();
    }
    _write(line);
    _written += 1;
    if (_written >= capacity) _compact();
  }

  List<String> snapshot() {
    _open();
    return List.of(_lines);
  }

  void _write(String line) {
    try {
      _out?.writeStringSync('$line\n');
    } on FileSystemException {
      // A full or vanished cache: the memory copy stands.
    }
  }

  void _open() {
    final file = _file;
    if (_opened || file == null) return;
    _opened = true;
    final kept = List.of(_lines);
    try {
      final previous = file.existsSync()
          ? file.readAsLinesSync().where((l) => l.isNotEmpty).toList()
          : const <String>[];
      _lines
        ..clear()
        ..addAll(previous.length > capacity ? previous.sublist(previous.length - capacity) : previous)
        ..addAll(kept);
      while (_lines.length > capacity) {
        _lines.removeFirst();
      }
      _out = file.openSync(mode: FileMode.append);
      kept.forEach(_write);
    } on Object {
      // Unreadable (or not UTF-8): kept in memory only.
      _out = null;
    }
  }

  void _compact() {
    _written = 0;
    final file = _file;
    if (file == null) return;
    try {
      _out?.closeSync();
      file.writeAsStringSync('${_lines.join('\n')}\n', flush: true);
      _out = file.openSync(mode: FileMode.append);
    } on Object {
      _out = null;
    }
  }
}

/// The log the playback chain writes to; tests override it with [PlayTrace.memory].
final playTraceProvider = Provider<PlayTrace>((ref) => PlayTrace.shared);
