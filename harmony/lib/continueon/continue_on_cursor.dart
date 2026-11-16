import 'package:flutter/foundation.dart';

import '../data/model/model.dart';
import '../playback/now_playing_state.dart';
import '../playback/playback_context.dart';

/// What one Continue On hand-off carries from the device playing to the one
/// taking over (Android player/continueon/ContinueOnCursor.kt, same keys and
/// values): a light cursor, never media or credentials. The other device
/// reads the songs again from its own copy of the same account.
///
/// Ids are the provider's raw ids ([MediaId.rawId]); [provider] names whose.
/// [accountKey] is the account's id as every device knows it (AccountKeys);
/// null when this device has none for it (Apple Music not syncing), and the
/// other side then goes by [provider].
///
/// [pause] is how the other side asks this device to stop once the person
/// says "Continue here" (owner 2026-11-08): the system sends nothing back.
@immutable
final class ContinueOnCursor {
  const ContinueOnCursor({
    required this.provider,
    required this.accountKey,
    required this.trackId,
    required this.title,
    required this.artist,
    required this.album,
    required this.cover,
    required this.queueIds,
    required this.index,
    required this.positionMs,
    required this.capturedAtMs,
    required this.playing,
    required this.repeatMode,
    required this.shuffle,
    required this.context,
    required this.deviceName,
    required this.pause,
  });

  final String provider;
  final String? accountKey;
  final String trackId;
  final String? title;
  final String? artist;
  final String? album;

  /// [CoverRef.storageKey] of the track's cover: an id or a public image URL.
  final String? cover;

  /// A window of the queue around the track, in play order; [index] is the track's place in it.
  final List<String> queueIds;
  final int index;
  final int positionMs;

  /// Wall clock when [positionMs] was read: a playing track has moved on by the time the other side starts.
  final int capturedAtMs;
  final bool playing;

  /// Android's Player.REPEAT_MODE_*: 0 off, 1 one, 2 all ([repeatModeCode]).
  final int repeatMode;
  final bool shuffle;
  final ContinueOnContext? context;

  /// What the other side calls this device ("HUAWEI Mate 70 Air").
  final String? deviceName;
  final ContinueOnPauseChannel? pause;

  static const int version = 1;

  /// A hand-off's data stays well under the system's cap (Android 50 KB, HarmonyOS
  /// wantParam 100 KB); the queue window keeps under it.
  static const int maxExtrasBytes = 40000;
  static const int maxQueue = 200;
  static const int _maxText = 200;

  /// A cursor older than this is a stale hand-off: its position is no longer worth catching up.
  static const int maxDriftMs = 10 * 60000;

  /// Never start a hand-off in the track's last moments (it would end at once).
  static const int endGuardMs = 3000;
  static const int _maxPauseHosts = 4;

  /// A pause token: URL-safe Base64, as [ContinueOnPauseServer] makes it.
  static final RegExp _pauseToken = RegExp(r'^[A-Za-z0-9_-]{1,128}$');

  // [0-9], not \d: a digit of another script is a name to look up.
  static final RegExp _ipv4 = RegExp(r'^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$');
  static final RegExp _ipv6 = RegExp(r'^[0-9A-Fa-f:.]{2,45}$');

  static const String _prefix = 'yoin.continueOn.';
  static const String keyVersion = '${_prefix}v';
  static const String keyProvider = '${_prefix}provider';
  static const String keyAccount = '${_prefix}account';
  static const String keyTrack = '${_prefix}track';
  static const String keyTitle = '${_prefix}title';
  static const String keyArtist = '${_prefix}artist';
  static const String keyAlbum = '${_prefix}album';
  static const String keyCover = '${_prefix}cover';
  static const String keyQueue = '${_prefix}queue';
  static const String keyIndex = '${_prefix}index';
  static const String keyPosition = '${_prefix}position';
  static const String keyCapturedAt = '${_prefix}capturedAt';
  static const String keyPlaying = '${_prefix}playing';
  static const String keyRepeat = '${_prefix}repeat';
  static const String keyShuffle = '${_prefix}shuffle';
  static const String keyContextKind = '${_prefix}contextKind';
  static const String keyContextId = '${_prefix}contextId';
  static const String keyContextName = '${_prefix}contextName';
  static const String keyDevice = '${_prefix}device';
  static const String keyPauseHosts = '${_prefix}pauseHosts';
  static const String keyPausePort = '${_prefix}pausePort';
  static const String keyPauseToken = '${_prefix}pauseToken';

  /// Every key a cursor may put in a hand-off.
  static const List<String> keys = [
    keyVersion, keyProvider, keyAccount, keyTrack, keyTitle, keyArtist, keyAlbum, keyCover, //
    keyQueue, keyIndex, keyPosition, keyCapturedAt, keyPlaying, keyRepeat, keyShuffle, //
    keyContextKind, keyContextId, keyContextName, keyDevice, keyPauseHosts, keyPausePort, keyPauseToken,
  ];

  /// Where [positionMs] has got to at [nowMs], within the track as far as is
  /// known ([durationMs] 0 = unknown). A cursor read more than [maxDriftMs]
  /// ago is not caught up: the device has long been in another song, so this
  /// one starts where the card shows it.
  int positionAt(int nowMs, {int durationMs = 0}) {
    final since = playing ? (nowMs - capturedAtMs).clamp(0, 1 << 62) : 0;
    final moved = since > maxDriftMs ? 0 : since;
    final position = (positionMs + moved).clamp(0, 1 << 62);
    if (durationMs <= 0) return position;
    final last = (durationMs - endGuardMs).clamp(0, 1 << 62);
    return position > last ? last : position;
  }

  /// [queueIds] from [trackId] on. The cursor's own track is at [index];
  /// another one (the device has moved on since the cursor was read) is
  /// looked for after it first, then before it, and stands alone when the
  /// queue doesn't hold it.
  List<String> queueFrom(String trackId) {
    if (trackId == this.trackId) return queueIds.sublist(index.clamp(0, queueIds.length));
    final tail = queueIds.sublist(index.clamp(0, queueIds.length));
    final later = tail.indexOf(trackId);
    final at = later >= 0 ? index + later : queueIds.indexOf(trackId);
    return at >= 0 ? queueIds.sublist(at) : [trackId];
  }

  /// Flattened for the hand-off's data (strings, ints, booleans and string lists only).
  Map<String, Object> toEntries() => {
    keyVersion: version,
    keyProvider: provider,
    keyAccount: ?accountKey,
    keyTrack: trackId,
    keyTitle: ?_cap(title),
    keyArtist: ?_cap(artist),
    keyAlbum: ?_cap(album),
    keyCover: ?cover,
    keyQueue: List<String>.of(queueIds),
    keyIndex: index,
    keyPosition: positionMs,
    keyCapturedAt: capturedAtMs,
    keyPlaying: playing,
    keyRepeat: repeatMode,
    keyShuffle: shuffle,
    if (context case final ctx?) ...{
      keyContextKind: ctx.kind.wireName,
      keyContextId: ?ctx.id,
      keyContextName: ?_cap(ctx.name),
    },
    keyDevice: ?_cap(deviceName),
    if (pause case final channel?) ...{
      keyPauseHosts: List<String>.of(channel.hosts),
      keyPausePort: channel.port,
      keyPauseToken: channel.token,
    },
  };

  /// The cursor in [entries] (a hand-off's data), or null when they hold
  /// none, or one from a newer Yoin whose format this one doesn't know, or
  /// one that doesn't make sense.
  static ContinueOnCursor? fromEntries(Map<String, Object?> entries) {
    if (_int(entries[keyVersion]) != version) return null;
    final provider = _nonBlank(entries[keyProvider]);
    final trackId = _nonBlank(entries[keyTrack]);
    if (provider == null || trackId == null) return null;
    final queue = [
      for (final id in _strings(entries[keyQueue]))
        if (id.trim().isNotEmpty) id,
    ];
    final rawIndex = _int(entries[keyIndex]) ?? -1;
    // A queue that doesn't hold the track at its index is the track alone.
    final holds = rawIndex >= 0 && rawIndex < queue.length && queue[rawIndex] == trackId;
    final kind = ContinueOnContextKind.fromWire(_string(entries[keyContextKind]));
    // Another app can start Yoin with any data, so the pause channel is only
    // ever a few written-out addresses (never a name to look up) and a token
    // in the alphabet Yoin makes them in: nothing here can aim the PAUSE line
    // elsewhere or add lines to it.
    final pauseHosts = _strings(entries[keyPauseHosts]).where(isNumericAddress).take(_maxPauseHosts).toList();
    final pausePort = _int(entries[keyPausePort]) ?? 0;
    final pauseToken = _string(entries[keyPauseToken]);
    final pause =
        pauseHosts.isNotEmpty &&
            pausePort >= 1 &&
            pausePort <= 65535 &&
            pauseToken != null &&
            _pauseToken.hasMatch(pauseToken)
        ? ContinueOnPauseChannel(hosts: pauseHosts, port: pausePort, token: pauseToken)
        : null;
    final position = _int(entries[keyPosition]) ?? 0;
    return ContinueOnCursor(
      provider: provider,
      accountKey: _string(entries[keyAccount]),
      trackId: trackId,
      title: _string(entries[keyTitle]),
      artist: _string(entries[keyArtist]),
      album: _string(entries[keyAlbum]),
      cover: _string(entries[keyCover]),
      queueIds: holds ? queue : [trackId],
      index: holds ? rawIndex : 0,
      positionMs: position < 0 ? 0 : position,
      capturedAtMs: _int(entries[keyCapturedAt]) ?? 0,
      playing: entries[keyPlaying] == true,
      repeatMode: _int(entries[keyRepeat]) ?? 0,
      shuffle: entries[keyShuffle] == true,
      context: kind == null
          ? null
          : ContinueOnContext(kind, _string(entries[keyContextId]), _string(entries[keyContextName])),
      deviceName: _string(entries[keyDevice]),
      pause: pause,
    );
  }

  /// The part of [queue] a cursor carries: at most [maxQueue] ids around
  /// [index], the track's own place kept, trimmed further while the ids
  /// alone would pass [maxExtrasBytes] (about half of it, leaving the rest to
  /// the other fields). Returns the window and the track's index in it.
  static (List<String>, int) queueWindow(List<String> queue, int index) {
    if (index < 0 || index >= queue.length) return (const [], -1);
    final last = queue.length - 1;
    var before = _min(index, maxQueue ~/ 4);
    var after = _min(last - index, maxQueue - 1 - before);
    // A short tail leaves room for more of what came before.
    before = _min(index, maxQueue - 1 - after);
    int bytes() {
      var sum = 0;
      for (var i = index - before; i <= index + after; i++) {
        sum += queue[i].length * 2 + 8;
      }
      return sum;
    }

    while (bytes() > maxExtrasBytes ~/ 2 && before + after > 0) {
      if (after >= before) {
        after--;
      } else {
        before--;
      }
    }
    return (queue.sublist(index - before, index + after + 1), before);
  }

  /// An IPv4 or IPv6 address written out, which connects without a name lookup.
  static bool isNumericAddress(String host) {
    final v4 = _ipv4.firstMatch(host);
    if (v4 != null) return [for (var i = 1; i <= 4; i++) int.parse(v4.group(i)!)].every((n) => n <= 255);
    return host.contains(':') && _ipv6.hasMatch(host);
  }

  ContinueOnCursor copyWith({
    String? provider,
    Object? accountKey = _keep,
    String? trackId,
    List<String>? queueIds,
    int? index,
    int? positionMs,
    int? capturedAtMs,
    bool? playing,
    Object? context = _keep,
    Object? pause = _keep,
  }) => ContinueOnCursor(
    provider: provider ?? this.provider,
    accountKey: identical(accountKey, _keep) ? this.accountKey : accountKey as String?,
    trackId: trackId ?? this.trackId,
    title: title,
    artist: artist,
    album: album,
    cover: cover,
    queueIds: queueIds ?? this.queueIds,
    index: index ?? this.index,
    positionMs: positionMs ?? this.positionMs,
    capturedAtMs: capturedAtMs ?? this.capturedAtMs,
    playing: playing ?? this.playing,
    repeatMode: repeatMode,
    shuffle: shuffle,
    context: identical(context, _keep) ? this.context : context as ContinueOnContext?,
    deviceName: deviceName,
    pause: identical(pause, _keep) ? this.pause : pause as ContinueOnPauseChannel?,
  );

  @override
  bool operator ==(Object other) =>
      other is ContinueOnCursor &&
      other.provider == provider &&
      other.accountKey == accountKey &&
      other.trackId == trackId &&
      other.title == title &&
      other.artist == artist &&
      other.album == album &&
      other.cover == cover &&
      listEquals(other.queueIds, queueIds) &&
      other.index == index &&
      other.positionMs == positionMs &&
      other.capturedAtMs == capturedAtMs &&
      other.playing == playing &&
      other.repeatMode == repeatMode &&
      other.shuffle == shuffle &&
      other.context == context &&
      other.deviceName == deviceName &&
      other.pause == pause;

  @override
  int get hashCode => Object.hash(
    provider,
    accountKey,
    trackId,
    title,
    artist,
    album,
    cover,
    Object.hashAll(queueIds),
    index,
    positionMs,
    capturedAtMs,
    playing,
    repeatMode,
    shuffle,
    context,
    deviceName,
    pause,
  );

  @override
  String toString() => 'ContinueOnCursor($provider:$trackId @$positionMs, ${queueIds.length} queued)';
}

const Object _keep = Object();

int _min(int a, int b) => a < b ? a : b;

/// At most [ContinueOnCursor._maxText] UTF-16 units, never half a surrogate pair.
String? _cap(String? text) {
  if (text == null || text.length <= ContinueOnCursor._maxText) return text;
  var end = ContinueOnCursor._maxText;
  final unit = text.codeUnitAt(end - 1);
  if (unit >= 0xD800 && unit <= 0xDBFF) end--;
  return text.substring(0, end);
}

String? _string(Object? value) => value is String ? value : null;

String? _nonBlank(Object? value) => value is String && value.trim().isNotEmpty ? value : null;

/// An int as the platform delivers one (a whole double too).
int? _int(Object? value) => switch (value) {
  final int n => n,
  final double d when d == d.roundToDouble() && d.isFinite => d.toInt(),
  _ => null,
};

List<String> _strings(Object? value) => value is List
    ? [
        for (final v in value)
          if (v is String) v,
      ]
    : const [];

/// Where the queue came from, so the other side can read it in one go.
@immutable
final class ContinueOnContext {
  const ContinueOnContext(this.kind, this.id, this.name);

  final ContinueOnContextKind kind;
  final String? id;
  final String? name;

  @override
  bool operator ==(Object other) =>
      other is ContinueOnContext && other.kind == kind && other.id == id && other.name == name;

  @override
  int get hashCode => Object.hash(kind, id, name);
}

enum ContinueOnContextKind {
  album('Album'),
  playlist('Playlist'),
  artist('Artist'),
  likedSongs('LikedSongs');

  const ContinueOnContextKind(this.wireName);

  /// The name in the hand-off (Android's enum name).
  final String wireName;

  static ContinueOnContextKind? fromWire(String? name) => values.where((k) => k.wireName == name).firstOrNull;
}

/// This device on the local network, waiting for one "pause" that carries [token].
@immutable
final class ContinueOnPauseChannel {
  const ContinueOnPauseChannel({required this.hosts, required this.port, required this.token});

  final List<String> hosts;
  final int port;
  final String token;

  @override
  bool operator ==(Object other) =>
      other is ContinueOnPauseChannel && listEquals(other.hosts, hosts) && other.port == port && other.token == token;

  @override
  int get hashCode => Object.hash(Object.hashAll(hosts), port, token);
}

/// Android's Player.REPEAT_MODE_* for [mode], as the hand-off carries it.
int repeatModeCode(RepeatMode mode) => switch (mode) {
  RepeatMode.off => 0,
  RepeatMode.one => 1,
  RepeatMode.all => 2,
};

/// [repeatModeCode] read back; anything unknown is off.
RepeatMode repeatModeOf(int code) => switch (code) {
  1 => RepeatMode.one,
  2 => RepeatMode.all,
  _ => RepeatMode.off,
};

/// The raw id in a context id, which may be raw or a full `provider:raw` MediaId string.
String rawContextId(String id, String provider) => id.startsWith('$provider:') ? id.substring(provider.length + 1) : id;

/// Where a play started, as the hand-off carries it; null for none.
ContinueOnContext? cursorContextOf(PlaybackContext context) => switch (context) {
  NoPlaybackContext() => null,
  AlbumPlaybackContext(:final albumId, :final name) => ContinueOnContext(
    ContinueOnContextKind.album,
    albumId.rawId,
    name,
  ),
  PlaylistPlaybackContext(:final playlistId, :final name) => ContinueOnContext(
    ContinueOnContextKind.playlist,
    playlistId.rawId,
    name,
  ),
  ArtistPlaybackContext(:final artistId, :final name) => ContinueOnContext(
    ContinueOnContextKind.artist,
    artistId.rawId,
    name,
  ),
  FavoritesPlaybackContext() => const ContinueOnContext(ContinueOnContextKind.likedSongs, null, null),
};

/// A hand-off's context as the player keeps it (Android toActivityContext);
/// one without an id (but Liked songs) is none.
PlaybackContext playbackContextOf(ContinueOnContext? context, String provider) {
  if (context == null) return const NoPlaybackContext();
  final raw = context.id == null ? null : rawContextId(context.id!, provider);
  final id = raw == null || raw.trim().isEmpty ? null : MediaId(provider, raw);
  final name = context.name ?? '';
  return switch (context.kind) {
    ContinueOnContextKind.album =>
      id == null ? const NoPlaybackContext() : AlbumPlaybackContext(albumId: id, name: name),
    ContinueOnContextKind.playlist =>
      id == null ? const NoPlaybackContext() : PlaylistPlaybackContext(playlistId: id, name: name),
    ContinueOnContextKind.artist =>
      id == null ? const NoPlaybackContext() : ArtistPlaybackContext(artistId: id, name: name),
    ContinueOnContextKind.likedSongs => const FavoritesPlaybackContext(),
  };
}
