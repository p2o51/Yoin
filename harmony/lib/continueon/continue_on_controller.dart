/// Continue On, both ends (Android player/continueon/ContinueOn.kt; on
/// HarmonyOS 应用接续, harmony/docs/continue-on.md).
///
/// Handing off: while a song is loaded, the app tells the system it may be
/// continued elsewhere ([ContinueOnHost.setOffered]) and, when the system
/// asks, gives it a [ContinueOnCursor] with a local pause channel that stays
/// while the song does.
///
/// Taking over: a hand-off this device receives shows a small card
/// ([ContinueOnPrompt]) instead of playing at once (owner 2026-11-08).
/// "Continue here" ([ContinueOnController.confirm]) switches to the same
/// account here, asks the other device to pause, reads the songs back and
/// starts from the song and the spot the other device stopped at, then
/// opens Now Playing.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/profile/profile.dart';
import '../data/source/music_source.dart';
import '../data/sync/cloud_sync_providers.dart';
import '../data/sync/identity/account_keys.dart';
import '../platform/continueon/continue_on_host.dart';
import '../playback/now_playing_state.dart';
import '../playback/play_mode.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import '../playback/playhead.dart';
import '../playback/system_requests.dart';
import 'continue_on_cursor.dart';
import 'continue_on_pause_channel.dart';

/// What a received hand-off's card shows.
@immutable
final class ContinueOnPrompt {
  const ContinueOnPrompt({
    required this.cursor,
    required this.account,
    this.coverUrl,
    this.stage = ContinueOnStage.asking,
  });

  final ContinueOnCursor cursor;
  final ContinueOnAccount account;
  final String? coverUrl;
  final ContinueOnStage stage;

  String? get title => cursor.title;
  String? get artist => cursor.artist;
  String? get album => cursor.album;
  String? get deviceName => cursor.deviceName;
  int get positionMs => cursor.positionMs;
  bool get playing => cursor.playing;

  ContinueOnPrompt copyWith({ContinueOnAccount? account, Object? coverUrl = _keep, ContinueOnStage? stage}) =>
      ContinueOnPrompt(
        cursor: cursor,
        account: account ?? this.account,
        coverUrl: identical(coverUrl, _keep) ? this.coverUrl : coverUrl as String?,
        stage: stage ?? this.stage,
      );
}

const Object _keep = Object();

/// Which account here a hand-off belongs to.
sealed class ContinueOnAccount {
  const ContinueOnAccount();
}

/// Still matching the other device's account to one here.
final class ContinueOnAccountLooking extends ContinueOnAccount {
  const ContinueOnAccountLooking();

  @override
  bool operator ==(Object other) => other is ContinueOnAccountLooking;

  @override
  int get hashCode => 1;
}

final class ContinueOnAccountFound extends ContinueOnAccount {
  const ContinueOnAccountFound(this.profileId, this.name);
  final String profileId;
  final String name;

  @override
  bool operator ==(Object other) =>
      other is ContinueOnAccountFound && other.profileId == profileId && other.name == name;

  @override
  int get hashCode => Object.hash(profileId, name);
}

/// This device has no copy of that account.
final class ContinueOnAccountMissing extends ContinueOnAccount {
  const ContinueOnAccountMissing();

  @override
  bool operator ==(Object other) => other is ContinueOnAccountMissing;

  @override
  int get hashCode => 2;
}

enum ContinueOnStage {
  asking,
  starting,

  /// Playing here, but the other device couldn't be reached to pause.
  otherStillPlaying,

  /// The songs couldn't be read here.
  failed,
}

/// The player as Continue On uses it (PlaybackController in the app; a fake in tests).
abstract interface class ContinueOnPlayer {
  NowPlayingState get state;

  /// Where the current song is now (ms).
  int livePositionMs();
  Future<void> pause();
  Future<void> start(
    List<Track> tracks, {
    required int startIndex,
    required PlaybackContext context,
    required int startPositionMs,
    required bool startPaused,
  });
  Future<void> setMode(PlayMode mode);

  /// Opens Now Playing (as a tap on the system's media card does).
  void openNowPlaying();
}

/// The accounts as Continue On uses them (ProfileRepository + the active source in the app; a fake in tests).
abstract interface class ContinueOnAccounts {
  Future<List<Profile>> profiles();
  Future<String?> activeProfileId();

  /// The account's id as every device knows it (AccountKeys); null when there is none.
  Future<String?> accountKey(Profile profile);

  /// Makes [profileId] the active account (Settings' switch). Throws when it can't be saved.
  Future<void> switchTo(String profileId);

  /// The active account's source, once it is built; null when there is none (in time).
  Future<MusicSource?> activeSource();

  /// [cover] as a loadable image for [profileId]'s account, or null.
  Future<String?> coverUrl(String profileId, CoverRef cover, int sizePx);
}

/// Asks the device that handed off to pause.
typedef ContinueOnAskToPause = Future<PauseAnswer> Function(ContinueOnPauseChannel channel, String trackId);

final continueOnHostProvider = Provider<ContinueOnHost>((ref) => const NoContinueOnHost());

final continueOnPlayerProvider = Provider<ContinueOnPlayer>((ref) => _AppPlayer(ref));

final continueOnAccountsProvider = Provider<ContinueOnAccounts>((ref) => _AppAccounts(ref));

/// How long the "your other device may still be playing" note stays (Android STILL_PLAYING_NOTE_MS).
final continueOnStillPlayingNoteProvider = Provider<Duration>((ref) => ContinueOnController.stillPlayingNote);

final continueOnAskToPauseProvider = Provider<ContinueOnAskToPause>((ref) => requestContinueOnPause);

/// Whether this device should offer the hand-off now: a song is loaded here
/// (HarmonyOS has no cast or Spotify Connect playing elsewhere to rule out).
final continueOnOfferedProvider = Provider<bool>(
  (ref) => ref.watch(playbackControllerProvider.select((s) => s.current != null)),
);

/// Changes whenever what the hand-off would carry does (another song, play /
/// pause, a seek, the queue, the play mode, the account).
final continueOnHandoffShapeProvider = Provider<Object>((ref) {
  final s = ref.watch(playbackControllerProvider);
  return Object.hash(
    s.currentEntryId,
    s.playWhenReady,
    s.positionMs,
    s.sampledAtMs,
    s.queue.length,
    s.repeat,
    s.shuffle,
  );
});

/// The active account's source: a change closes this device's pause port.
final continueOnActiveSourceProvider = Provider<Object?>((ref) => ref.watch(activeSourceProvider).value);

final continueOnProvider = NotifierProvider<ContinueOnController, ContinueOnPrompt?>(ContinueOnController.new);

class ContinueOnController extends Notifier<ContinueOnPrompt?> {
  static const int coverPx = 300;

  /// Read one by one, a queue stops at the song and the ones after it (a hand-off is about now).
  static const int maxSongReads = 30;
  static const int parallelReads = 6;
  static const Duration sourceWait = Duration(seconds: 10);
  static const Duration stillPlayingNote = Duration(seconds: 6);
  static const int _maxHosts = 3;

  ContinueOnHost get _host => ref.read(continueOnHostProvider);
  ContinueOnPlayer get _player => ref.read(continueOnPlayerProvider);
  ContinueOnAccounts get _accounts => ref.read(continueOnAccountsProvider);
  PlaybackClock get _clock => ref.read(playbackClockProvider);

  StreamSubscription<Map<String, Object?>>? _received;
  ContinueOnPauseServer? _pauseServer;
  Timer? _noteTimer;

  /// Bumped by every new card, answer and dismissal: older work then stops touching the card.
  int _job = 0;
  String? _deviceName;
  bool _deviceNameRead = false;
  bool _offered = false;
  int _snapshotSeq = 0;

  /// The active account's cross-device id, and whose it is.
  String? _activeKeyProfile;
  String? _activeKey;

  @override
  ContinueOnPrompt? build() {
    final host = ref.watch(continueOnHostProvider);
    _pauseServer = ContinueOnPauseServer(onPause: _pauseForHandoff, stillNeeded: () => _offered);
    if (host.supported) {
      _received = host.received.listen(receive);
      host.handoffRequests = handoffEntries;
      ref.listen(continueOnOfferedProvider, (_, offered) => _syncOffered(offered), fireImmediately: true);
      ref.listen(continueOnHandoffShapeProvider, (_, _) {
        if (_offered) unawaited(_sendSnapshot());
      });
      // Another account's pause port must not stop this one's playback.
      ref.listen(continueOnActiveSourceProvider, (previous, next) {
        if (previous != null && !identical(previous, next)) unawaited(_pauseServer?.close());
        _activeKeyProfile = null;
      });
      unawaited(host.ready().catchError((Object e) => debugPrint('YoinContinueOn: ready failed (${e.runtimeType})')));
    }
    ref.onDispose(() {
      _received?.cancel();
      _noteTimer?.cancel();
      host.handoffRequests = null;
      unawaited(_pauseServer?.close());
    });
    return null;
  }

  // ---- Handing off -------------------------------------------------------------------------

  void _syncOffered(bool offered) {
    _offered = offered;
    if (offered) {
      unawaited(_sendSnapshot());
    } else {
      _snapshotSeq++;
      unawaited(_host.setOffered(false).catchError((Object e) => debugPrint('YoinContinueOn: ${e.runtimeType}')));
    }
  }

  /// The cursor the system hands over should Dart not answer its request in time (no pause channel).
  Future<void> _sendSnapshot() async {
    final seq = ++_snapshotSeq;
    Map<String, Object?>? entries;
    try {
      entries = (await _cursor(withPause: false))?.toEntries();
    } catch (e) {
      debugPrint('YoinContinueOn: no snapshot (${e.runtimeType})');
    }
    if (seq != _snapshotSeq || !ref.mounted || !_offered) return;
    try {
      await _host.setOffered(true, snapshot: entries);
    } catch (e) {
      debugPrint('YoinContinueOn: offer failed (${e.runtimeType})');
    }
  }

  /// The cursor for the hand-off the system is asking for, flattened; null
  /// when nothing is playing here. The pause channel is opened for a song
  /// that plays: the other device may take the hand-off much later and then
  /// asks which song this device has got to.
  Future<Map<String, Object?>?> handoffEntries() async => (await _cursor(withPause: true))?.toEntries();

  Future<ContinueOnCursor?> _cursor({required bool withPause}) async {
    if (!ref.read(continueOnOfferedProvider)) return null;
    final player = _player;
    final state = player.state;
    final track = state.current;
    if (track == null) return null;
    final accounts = _accounts;
    final profileId = await accounts.activeProfileId();
    if (profileId == null) return null;
    final profile = (await accounts.profiles()).where((p) => p.id == profileId).firstOrNull;
    // Kept for the active account (as Android does): it decrypts the stored credentials and reads the sync store.
    final String? accountKey;
    if (_activeKeyProfile == profileId) {
      accountKey = _activeKey;
    } else {
      accountKey = profile == null ? null : await _keyOf(profile);
      _activeKeyProfile = profileId;
      _activeKey = accountKey;
    }
    final provider = track.id.provider;
    final queue = state.queue;
    final (List<String>, int) window =
        state.index >= 0 && state.index < queue.length && queue[state.index].id == track.id
        ? ContinueOnCursor.queueWindow([for (final t in queue) t.id.rawId], state.index)
        : ([track.id.rawId], 0);
    // Spotify needs no pause from here: playing on the other device moves Spotify Connect over.
    final pause = withPause && state.playWhenReady && provider != MediaId.providerSpotify
        ? await _openPauseChannel()
        : null;
    // The state may have moved on while the account and the port were read.
    final now = player.state;
    if (now.current?.id != track.id) return null;
    return ContinueOnCursor(
      provider: provider,
      accountKey: accountKey,
      trackId: track.id.rawId,
      title: track.title,
      artist: track.artist,
      album: track.album,
      cover: track.coverArt?.storageKey,
      queueIds: window.$1.isEmpty ? [track.id.rawId] : window.$1,
      index: window.$2 < 0 ? 0 : window.$2,
      positionMs: player.livePositionMs(),
      capturedAtMs: _clock.wallMs(),
      playing: now.playWhenReady,
      repeatMode: repeatModeCode(now.repeat),
      shuffle: now.shuffle,
      context: cursorContextOf(now.context),
      deviceName: await _readDeviceName(),
      pause: pause,
    );
  }

  Future<String?> _readDeviceName() async {
    if (!_deviceNameRead) {
      try {
        _deviceName = await _host.deviceName();
      } catch (_) {
        _deviceName = null;
      }
      _deviceNameRead = true;
    }
    return _deviceName;
  }

  Future<ContinueOnPauseChannel?> _openPauseChannel() async {
    final List<String> hosts;
    try {
      hosts = localPauseHosts(await _host.localAddresses());
    } catch (_) {
      return null;
    }
    if (hosts.isEmpty) return null;
    final opened = await _pauseServer?.open();
    if (opened == null) return null;
    return ContinueOnPauseChannel(hosts: hosts, port: opened.$1, token: opened.$2);
  }

  /// The other device took over: pause here and say which song this device is
  /// on by now (the cursor it holds may name an earlier one) and where in it;
  /// null with no song loaded.
  Future<PausedAt?> _pauseForHandoff(String trackId) async {
    final player = _player;
    final track = player.state.current;
    final position = player.livePositionMs();
    await player.pause();
    debugPrint('YoinContinueOn: paused for a hand-off (same track: ${track?.id.rawId == trackId})');
    return track == null ? null : PausedAt(track.id.rawId, position);
  }

  /// [ContinueOnAccounts.accountKey], or null when it can't be read.
  Future<String?> _keyOf(Profile profile) async {
    try {
      return await _accounts.accountKey(profile);
    } catch (e) {
      debugPrint('YoinContinueOn: no account key for ${profile.provider} (${e.runtimeType})');
      return null;
    }
  }

  // ---- Taking over -------------------------------------------------------------------------

  /// A hand-off arrived: show its card (a newer hand-off replaces an older one).
  /// False when [entries] hold no cursor.
  bool receive(Map<String, Object?> entries) {
    final cursor = ContinueOnCursor.fromEntries(entries);
    if (cursor == null) return false;
    final job = ++_job;
    _noteTimer?.cancel();
    state = ContinueOnPrompt(cursor: cursor, account: const ContinueOnAccountLooking());
    unawaited(_lookUp(cursor, job));
    return true;
  }

  Future<void> _lookUp(ContinueOnCursor cursor, int job) async {
    Profile? profile;
    try {
      profile = await _matchProfile(cursor);
    } catch (_) {
      profile = null;
    }
    final account = profile == null
        ? const ContinueOnAccountMissing()
        : ContinueOnAccountFound(profile.id, profile.displayName);
    String? coverUrl;
    final cover = CoverRef.fromStorageKey(cursor.cover);
    if (profile != null && cover != null) {
      try {
        coverUrl = await _accounts.coverUrl(profile.id, cover, coverPx);
      } catch (_) {
        coverUrl = null;
      }
    }
    if (job != _job) return;
    _update(cursor, (p) => p.copyWith(account: account, coverUrl: coverUrl));
  }

  /// "Not now": the card goes, and the other device plays on.
  void dismiss() {
    _job++;
    _noteTimer?.cancel();
    state = null;
  }

  /// "Continue here".
  void confirm() {
    final prompt = state;
    if (prompt == null || prompt.stage != ContinueOnStage.asking) return;
    final account = prompt.account;
    if (account is! ContinueOnAccountFound) return;
    final cursor = prompt.cursor;
    final job = ++_job;
    state = prompt.copyWith(stage: ContinueOnStage.starting);
    unawaited(_confirm(cursor, account.profileId, job));
  }

  Future<void> _confirm(ContinueOnCursor cursor, String profileId, int job) async {
    _TakeOver? outcome;
    try {
      outcome = await _takeOver(cursor, profileId, job);
    } catch (e, st) {
      debugPrint('YoinContinueOn: hand-off failed (${e.runtimeType})\n$st');
      outcome = null;
    }
    if (job != _job || !ref.mounted) return;
    switch (outcome) {
      case null:
        _update(cursor, (p) => p.copyWith(stage: ContinueOnStage.failed));
      case _TakeOver.paused:
        state = null;
      case _TakeOver.otherStillPlaying:
        _update(cursor, (p) => p.copyWith(stage: ContinueOnStage.otherStillPlaying));
        _noteTimer?.cancel();
        _noteTimer = Timer(ref.read(continueOnStillPlayingNoteProvider), () {
          if (job == _job && ref.mounted) _update(cursor, (_) => null);
        });
    }
  }

  Future<_TakeOver?> _takeOver(ContinueOnCursor cursor, String profileId, int job) async {
    final accounts = _accounts;
    // 1. The same account here. Before the other device is asked to stop: a switch that fails leaves it playing.
    if (await accounts.activeProfileId() != profileId) {
      try {
        await accounts.switchTo(profileId);
      } catch (e) {
        debugPrint('YoinContinueOn: account switch failed (${e.runtimeType})');
        return null;
      }
    }
    if (await accounts.activeProfileId() != profileId) return null;
    final source = await accounts.activeSource().timeout(sourceWait, onTimeout: () => null);
    if (source == null || job != _job) return null;
    // 2. The other device stops, and says which song it is on by now and exactly where.
    final spotify = cursor.provider == MediaId.providerSpotify;
    PauseAnswer? answer;
    if (!spotify && cursor.playing) {
      final channel = cursor.pause;
      answer = channel == null
          ? const PauseAnswerUnreachable()
          : await ref.read(continueOnAskToPauseProvider)(channel, cursor.trackId);
    }
    final paused = answer is PauseAnswerPaused ? answer : null;
    final playingId = paused?.trackId ?? cursor.trackId;
    // 3. The songs, read again from this device's copy of the account.
    final queue = await _restoreQueue(source, cursor, playingId);
    if (queue == null) return null;
    // The account may have changed meanwhile: this hand-off belongs to the one it chose.
    if (await accounts.activeProfileId() != profileId || !identical(await accounts.activeSource(), source)) {
      return null;
    }
    if (job != _job) return null;
    final track = queue.tracks[queue.index];
    final durationMs = (track.durationSec ?? 0) * 1000;
    // Where the other device said it stopped, when the song that starts here is the one it stopped in.
    final pausedAt = paused?.positionMs;
    final position = pausedAt != null && track.id.rawId == playingId
        ? pausedAt
        : cursor.positionAt(_clock.wallMs(), durationMs: durationMs);
    // 4. Play from there, as the other device did.
    final player = _player;
    await player.start(
      queue.tracks,
      startIndex: queue.index,
      context: queue.context,
      startPositionMs: position,
      startPaused: !cursor.playing,
    );
    await player.setMode(PlayMode.of(repeatModeOf(cursor.repeatMode), cursor.shuffle));
    player.openNowPlaying();
    return answer is PauseAnswerUnreachable ? _TakeOver.otherStillPlaying : _TakeOver.paused;
  }

  Future<Profile?> _matchProfile(ContinueOnCursor cursor) async {
    final candidates = [
      for (final p in await _accounts.profiles())
        if (p.provider == cursor.provider) p,
    ];
    final keys = <String, String?>{for (final p in candidates) p.id: await _keyOf(p)};
    final wanted = cursor.accountKey;
    if (wanted != null) {
      final match = candidates.where((p) => keys[p.id] == wanted).firstOrNull;
      if (match != null) return match;
    }
    // The account's id isn't known on one side (Apple Music not syncing there): the only account of
    // that service here that could be it, else the one in use.
    final open = wanted != null
        ? [
            for (final p in candidates)
              if (keys[p.id] == null) p,
          ]
        : candidates;
    if (open.length == 1) return open.single;
    final active = await _accounts.activeProfileId();
    return open.where((p) => p.id == active).firstOrNull;
  }

  void _update(ContinueOnCursor cursor, ContinueOnPrompt? Function(ContinueOnPrompt) change) {
    final current = state;
    if (current == null || !identical(current.cursor, cursor)) return;
    state = change(current);
  }

  /// The queue the other device was playing, from [trackId] on (the song it
  /// is on by now, which need not be the cursor's any more): its album,
  /// playlist or Liked songs read in one go when it came from one and the
  /// song is in it, else the song and what came next, read one by one. A
  /// song that can't be read here falls back to the cursor's own.
  Future<_RestoredQueue?> _restoreQueue(MusicSource source, ContinueOnCursor cursor, String trackId) async {
    _ContextRead? whole;
    try {
      whole = await _readContext(source, cursor);
    } catch (_) {
      whole = null;
    }
    return await _restoreFrom(source, cursor, whole, trackId) ??
        (trackId != cursor.trackId ? await _restoreFrom(source, cursor, whole, cursor.trackId) : null);
  }

  Future<_RestoredQueue?> _restoreFrom(
    MusicSource source,
    ContinueOnCursor cursor,
    _ContextRead? whole,
    String trackId,
  ) async {
    if (whole != null) {
      final index = whole.tracks.indexWhere((t) => t.id.rawId == trackId);
      if (index >= 0) return _RestoredQueue(whole.tracks, index, whole.context);
    }
    final lookup = source.trackLookup;
    if (lookup == null) return null;
    final ids = cursor.queueFrom(trackId).take(maxSongReads).toList();
    final tracks = List<Track?>.filled(ids.length, null);
    var next = 0;
    Future<void> worker() async {
      while (next < ids.length) {
        final i = next++;
        try {
          tracks[i] = await lookup.getTrack(MediaId(cursor.provider, ids[i]));
        } catch (_) {
          tracks[i] = null;
        }
      }
    }

    await Future.wait([for (var w = 0; w < parallelReads && w < ids.length; w++) worker()]);
    // The song itself must be there; a later one that can't be read is left out.
    if (tracks.isEmpty || tracks.first == null) return null;
    return _RestoredQueue(tracks.whereType<Track>().toList(), 0, playbackContextOf(cursor.context, cursor.provider));
  }

  Future<_ContextRead?> _readContext(MusicSource source, ContinueOnCursor cursor) async {
    final context = cursor.context;
    if (context == null) return null;
    final library = source.library;
    final raw = context.id == null ? null : rawContextId(context.id!, cursor.provider);
    final id = raw == null || raw.trim().isEmpty ? null : MediaId(cursor.provider, raw);
    switch (context.kind) {
      case ContinueOnContextKind.album:
        if (id == null) return null;
        final album = await library.getAlbum(id);
        if (album == null) return null;
        return _ContextRead(
          album.tracks,
          AlbumPlaybackContext(
            albumId: album.id,
            name: album.name,
            artist: album.artist,
            artistId: album.artistId,
            cover: album.coverArt,
          ),
        );
      case ContinueOnContextKind.playlist:
        if (id == null) return null;
        final playlist = await library.getPlaylist(id);
        if (playlist == null) return null;
        return _ContextRead(
          playlist.tracks,
          PlaylistPlaybackContext(
            playlistId: playlist.id,
            name: playlist.name,
            owner: playlist.owner,
            cover: playlist.coverArt,
          ),
        );
      case ContinueOnContextKind.likedSongs:
        return _ContextRead((await library.getStarred()).tracks, const FavoritesPlaybackContext());
      case ContinueOnContextKind.artist:
        return null;
    }
  }
}

enum _TakeOver { paused, otherStillPlaying }

final class _RestoredQueue {
  const _RestoredQueue(this.tracks, this.index, this.context);
  final List<Track> tracks;
  final int index;
  final PlaybackContext context;
}

final class _ContextRead {
  const _ContextRead(this.tracks, this.context);
  final List<Track> tracks;
  final PlaybackContext context;
}

/// The addresses to put in a pause channel, from the Wi-Fi / Ethernet's own
/// (ContinueOnHost.localAddresses): written-out ones only, no loopback or
/// link-local, IPv4 first (what a home router is surest to route between its
/// own devices), no zone, at most three.
List<String> localPauseHosts(List<String> addresses) {
  final usable = <String>[];
  for (final raw in addresses) {
    final address = raw.split('%').first.trim();
    if (!ContinueOnCursor.isNumericAddress(address)) continue;
    final lower = address.toLowerCase();
    final loopback = lower.startsWith('127.') || lower == '::1';
    final linkLocal =
        lower.startsWith('169.254.') ||
        lower.startsWith('fe8') ||
        lower.startsWith('fe9') ||
        lower.startsWith('fea') ||
        lower.startsWith('feb');
    if (loopback || linkLocal || lower == '0.0.0.0' || lower == '::') continue;
    if (!usable.contains(address)) usable.add(address);
  }
  usable.sort((a, b) => (a.contains(':') ? 1 : 0).compareTo(b.contains(':') ? 1 : 0));
  return usable.take(ContinueOnController._maxHosts).toList();
}

// ---- The app's player and accounts ---------------------------------------------------------

final class _AppPlayer implements ContinueOnPlayer {
  _AppPlayer(this._ref);
  final Ref _ref;

  PlaybackController get _controller => _ref.read(playbackControllerProvider.notifier);

  @override
  NowPlayingState get state => _ref.read(playbackControllerProvider);

  @override
  int livePositionMs() {
    final now = _ref.read(playbackClockProvider).wallMs();
    final state = this.state;
    final head = _ref.read(playheadProvider);
    if (head.entryId != null && head.entryId == state.currentEntryId) return head.positionAt(now);
    return state.positionAt(now);
  }

  @override
  Future<void> pause() => _controller.pause();

  @override
  Future<void> start(
    List<Track> tracks, {
    required int startIndex,
    required PlaybackContext context,
    required int startPositionMs,
    required bool startPaused,
  }) => _controller.playTracks(
    tracks,
    startIndex: startIndex,
    context: context,
    startPositionMs: startPositionMs,
    startPaused: startPaused,
  );

  @override
  Future<void> setMode(PlayMode mode) => _controller.setMode(mode);

  @override
  void openNowPlaying() => _ref.read(nowPlayingOpenRequestsProvider.notifier).request();
}

final class _AppAccounts implements ContinueOnAccounts {
  _AppAccounts(this._ref);
  final Ref _ref;

  @override
  Future<List<Profile>> profiles() => _ref.read(profileRepositoryProvider).profiles();

  @override
  Future<String?> activeProfileId() async => (await _ref.read(profileRepositoryProvider).activeProfile())?.id;

  @override
  Future<String?> accountKey(Profile profile) {
    final repo = _ref.read(profileRepositoryProvider);
    final sync = _ref.read(cloudSyncProvider);
    return AccountKeys(binding: sync.bindingFor, credentials: (p) => repo.credentials(p.id)).of(profile);
  }

  @override
  Future<void> switchTo(String profileId) async {
    await _ref.read(profileRepositoryProvider).setActive(profileId);
    _ref.invalidate(activeSourceProvider);
  }

  @override
  Future<MusicSource?> activeSource() => _ref.read(activeSourceProvider.future);

  @override
  Future<String?> coverUrl(String profileId, CoverRef cover, int sizePx) async {
    if (await activeProfileId() == profileId) {
      return (await activeSource())?.resolveCoverUrl(cover, size: sizePx);
    }
    final credentials = await _ref.read(profileRepositoryProvider).credentials(profileId);
    if (credentials == null) return null;
    final source = _ref.read(musicSourceFactoryProvider)(credentials);
    try {
      return source.resolveCoverUrl(cover, size: sizePx);
    } finally {
      source.dispose();
    }
  }
}
