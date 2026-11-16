/// The Windows side of every platform seam, as pure-Dart stand-ins until a
/// Windows runner exists (harmony/docs/windows.md lists what each one needs).
/// Nothing here talks to a method channel: a desktop build with no native
/// plugins still starts, browses a Subsonic server and keeps its settings.
///
/// - Storage: plain values in a JSON file under the app-data folder.
/// - Secrets: kept for the session only (never written in the clear); the
///   real one is DPAPI / Credential Manager.
/// - Playback: unsupported (a clear error the player shows), cards: none,
///   window: the system title bar, keep-awake / toast: nothing, network:
///   "unmetered", names: the pure-Dart sort forms.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../applemusic/apple_music_auth.dart';
import '../network/network_bearer.dart';
import '../playback/playback_engine.dart';
import '../storage/stores.dart';
import '../window/window_controls.dart';

/// Where a desktop build keeps its files: `%APPDATA%\Yoin` on Windows,
/// `$XDG_DATA_HOME/yoin` or `~/.local/share/yoin` elsewhere, the temp folder
/// as a last resort. path_provider's getApplicationSupportDirectory replaces
/// this once the Windows build takes packages.
Directory desktopAppDataDirectory({Map<String, String>? environment, bool? windows}) {
  final env = environment ?? Platform.environment;
  final isWindows = windows ?? Platform.isWindows;
  final separator = isWindows ? r'\' : '/';
  String join(String a, String b) => a.endsWith(separator) ? '$a$b' : '$a$separator$b';
  final appData = env['APPDATA'];
  if (isWindows && appData != null && appData.isNotEmpty) return Directory(join(appData, 'Yoin'));
  final xdg = env['XDG_DATA_HOME'];
  if (xdg != null && xdg.isNotEmpty) return Directory(join(xdg, 'yoin'));
  final home = env['HOME'] ?? env['USERPROFILE'];
  if (home != null && home.isNotEmpty) return Directory(join(join(join(home, '.local'), 'share'), 'yoin'));
  return Directory(join(Directory.systemTemp.path, 'yoin'));
}

/// [KeyValueStore] in one JSON file (`prefs.json`): read once, then every
/// write goes through a queue that replaces the file atomically (temp file +
/// rename), so a crash mid-write never leaves half a file.
final class FileKeyValueStore implements KeyValueStore {
  FileKeyValueStore(Directory directory) : _file = File('${directory.path}${Platform.pathSeparator}prefs.json');

  final File _file;
  Map<String, String>? _values;
  Future<void> _queue = Future.value();

  Future<Map<String, String>> _load() async {
    final loaded = _values;
    if (loaded != null) return loaded;
    var values = <String, String>{};
    try {
      if (await _file.exists()) {
        final raw = jsonDecode(await _file.readAsString());
        if (raw is Map) {
          values = {
            for (final e in raw.entries)
              if (e.key is String && e.value is String) e.key as String: e.value as String,
          };
        }
      }
    } on FormatException {
      // A damaged file starts over (the old one is replaced on the next write).
    } on FileSystemException {
      // Unreadable: start empty.
    }
    return _values ??= values;
  }

  @override
  Future<String?> read(String key) async => (await _load())[key];

  @override
  Future<void> write(String key, String? value) {
    final next = _queue.then((_) async {
      final values = await _load();
      if (value == null) {
        values.remove(key);
      } else {
        values[key] = value;
      }
      await _file.parent.create(recursive: true);
      final temp = File('${_file.path}.tmp');
      await temp.writeAsString(jsonEncode(values), flush: true);
      await temp.rename(_file.path);
    });
    _queue = next.catchError((Object _) {});
    return next;
  }
}

/// Secrets for this session only. Writing account passwords and tokens to a
/// plain file would be worse than asking again; the Windows build stores them
/// with DPAPI (flutter_secure_storage's Windows backend) behind this interface.
final class SessionSecretStore implements SecretStore {
  final Map<String, String> _values = {};

  @override
  Future<String?> read(String key) async => _values[key];

  @override
  Future<void> write(String key, String? value) async {
    if (value == null) {
      _values.remove(key);
    } else {
      _values[key] = value;
    }
  }
}

/// No audio engine yet: the queue never starts. Anything that would start
/// sound throws [UnsupportedError] (the player shows its playback error);
/// stopping, pausing and settings changes are harmless no-ops, so account
/// switches and the player's own housekeeping still run.
final class UnsupportedPlaybackEngine implements QueuePlaybackEngine {
  const UnsupportedPlaybackEngine();

  static const message =
      'Playback is not available in this build yet: the Windows engine (media_kit + System Media '
      'Transport Controls) is still to come — see harmony/docs/windows.md.';

  Never _unsupported() => throw UnsupportedError(message);

  @override
  Stream<EngineState> get states => Stream.value(const EngineState());

  @override
  Stream<EngineEvent> get events => const Stream.empty();

  @override
  Future<void> setQueue(List<EngineItem> items, {int startIndex = 0, int startPositionMs = 0, bool playWhenReady = true}) async =>
      _unsupported();

  @override
  Future<void> startQueue(
    List<EngineItem> items, {
    required int revision,
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
  }) async => _unsupported();

  @override
  Future<void> play() async => _unsupported();

  @override
  Future<void> insert(int at, List<EngineItem> items, {required int revision}) async => _unsupported();

  @override
  Future<void> pause() async {}

  @override
  Future<void> seekTo(int positionMs) async {}

  @override
  Future<void> skipTo(int index) async {}

  @override
  Future<void> next() async {}

  @override
  Future<void> previous() async {}

  @override
  Future<void> stop() async {}

  @override
  Future<void> removeAt(int index, {required int revision}) async {}

  @override
  Future<void> move(int from, int to, {required int revision}) async {}

  @override
  Future<void> setRepeat(EngineRepeat repeat) async {}

  @override
  Future<void> setShuffle(bool shuffle) async {}

  @override
  Future<void> setFavorite(String id, bool? favorite) async {}

  @override
  Future<void> setGains(Map<String, double> gainsByEntryId) async {}

  @override
  Future<EngineSnapshot?> snapshot() async => null;
}

/// Apple Music sign-in runs in ArkWeb on HarmonyOS; Windows needs a WebView2
/// page (webview_windows) for MusicKit JS. Until then it says so.
final class UnsupportedAppleMusicAuth implements AppleMusicAuth {
  const UnsupportedAppleMusicAuth();

  @override
  Future<AppleMusicAuthResult> authorize(String endpoint) async => throw const AppleMusicAuthException(
    AppleMusicAuthFailure.unavailable,
    detail: 'Apple Music sign-in is not available on this build yet',
  );
}

/// A desktop is on Wi-Fi or Ethernet as far as streaming quality goes
/// (Windows' metered-connection flag comes with connectivity_plus / WinRT later).
final class FixedNetworkBearer implements NetworkBearerSource {
  const FixedNetworkBearer([this.bearer = NetworkBearer.other]);

  final NetworkBearer bearer;

  @override
  Future<NetworkBearer> current() async => bearer;
}

/// Keep-awake and the system toast: nothing yet (Windows:
/// SetThreadExecutionState via wakelock_plus; a toast via local_notifier).
final class NoWindowControls implements WindowControls {
  const NoWindowControls();

  @override
  Future<void> setKeepScreenOn(bool on) async {}

  @override
  Future<void> showToast(String message) async {}
}
