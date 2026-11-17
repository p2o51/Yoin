/// The user's other accounts, as Memories and Home's "On Your Other Accounts"
/// read them (Android ui/memories/MemoryOtherProfiles.kt +
/// ui/home/HomeOtherProfiles.kt): every account Yoin could switch to — not
/// the one in use, nothing standing in the way — with its own rows (Memory
/// store, activity log, track ratings), its covers resolved against its own
/// server, and the one-off "this switches accounts" notice both surfaces
/// share.
///
/// Android reads another account's album details from its disk detail cache
/// only. HarmonyOS keeps no per-account detail cache on disk, so an album is
/// read through that account's own source instead: once per session, a few at
/// a time, and an account whose server can't be reached stops being asked
/// (its memories still qualify by their reviews and notes, as Android's do
/// without a cached detail).
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../data/profile/profile.dart';
import '../data/source/music_source.dart';
import '../home/home_account_switcher.dart' show switchHomeAccountIn;
import '../playback/local_ratings.dart';
import '../platform/storage/stores.dart';
import '../settings/account_identity.dart';
import '../settings/settings_view_model.dart';
import '../shell/bar_suppression.dart';
import '../theme/tokens.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';

/// The accounts Yoin could switch to (Android usableOtherProfiles): not the
/// one in use, and readable (an account whose credentials are lost has to be
/// fixed on its page first).
List<AccountCard> usableOtherAccounts(List<AccountCard> cards) => [
  for (final c in cards)
    if (!c.isActive && !c.credentialsMissing) c,
];

/// One other account and what Memories and Home read from it.
class OtherProfileSource {
  OtherProfileSource({
    required this.profile,
    required this.store,
    required this.log,
    required Future<Map<MediaId, LocalRating>> Function() ratings,
    MusicSource? source,
    this.albumTimeout = const Duration(seconds: 4),
  }) : _ratings = ratings,
       _source = source;

  final MemoryOtherProfile profile;

  /// The account's Memory rows (scores, reviews, notes, plays, titles).
  final MemoryStore store;

  /// The account's activity log (plays and visits).
  final ActivityLog log;
  final Future<Map<MediaId, LocalRating>> Function() _ratings;

  /// That account's own source: covers and album details. Null when none
  /// could be built (then nothing is read and covers stay unresolved).
  final MusicSource? _source;

  /// How long one album read may take before the account is given up on.
  final Duration albumTimeout;

  final Map<MediaId, Future<Album?>> _albums = {};
  bool _unreachable = false;

  String get id => profile.id;
  String get provider => profile.provider;

  Future<Map<MediaId, LocalRating>> ratings() => _ratings();

  /// That account's single-song lookup (what places a scored song on its
  /// album, track_album_links.dart); null without a source or a lookup.
  MusicTrackLookup? get trackLookup => _source?.trackLookup;

  /// [ref] as a URL that needs no source (a Subsonic cover id becomes this
  /// account's own cover URL at [size]); null when it can't be resolved.
  CoverRef? coverFor(CoverRef? ref, {int size = 600}) {
    if (ref == null) return null;
    if (ref is CoverUrl) return ref;
    final url = _source?.resolveCoverUrl(ref, size: size);
    return url == null ? null : CoverUrl(url);
  }

  /// The album's detail, read once per session; null when it can't be read.
  /// After a network failure or a timeout the account isn't asked again.
  Future<Album?> album(MediaId id) {
    final source = _source;
    if (source == null || id.provider != provider) return Future.value();
    return _albums.putIfAbsent(id, () async {
      if (_unreachable) return null;
      try {
        return await source.library.getAlbum(id).timeout(albumTimeout);
      } on TimeoutException {
        _unreachable = true;
      } on SourceNetworkException {
        _unreachable = true;
      } catch (_) {
        // This album only.
      }
      return null;
    });
  }

  void dispose() => _source?.dispose();
}

/// The other accounts, oldest first (Settings' order), each with its rows.
/// Rebuilt when the account in use or any account changes.
final otherProfileSourcesProvider = FutureProvider<List<OtherProfileSource>>((ref) async {
  await ref.watch(activeSourceProvider.future);
  ref.watch(accountsRevisionProvider);
  final repo = ref.watch(profileRepositoryProvider);
  final kv = ref.watch(keyValueStoreProvider);
  final profiles = await repo.profiles();
  final active = await repo.activeProfile();
  final shapes = assignAvatarShapes([for (final p in profiles) p.id]);
  final out = <OtherProfileSource>[];
  ref.onDispose(() {
    for (final s in out) {
      s.dispose();
    }
  });
  for (final p in profiles) {
    if (p.id == active?.id) continue;
    ProfileCredentials? credentials;
    try {
      credentials = await repo.credentials(p.id);
    } catch (_) {
      credentials = null;
    }
    final card = accountCardFor(p, credentials, isActive: false, avatarShape: shapes[p.id] ?? 0);
    if (usableOtherAccounts([card]).isEmpty || credentials == null) continue;
    MusicSource? source;
    try {
      source = ref.read(musicSourceFactoryProvider)(credentials);
    } catch (_) {
      source = null; // no factory (tests), or a provider this build can't open
    }
    out.add(
      OtherProfileSource(
        profile: MemoryOtherProfile(id: p.id, name: card.title, provider: p.provider, shape: card.avatarShape),
        store: MemoryStore.shared(kv, p.id),
        log: StoredActivityLog(kv, profileId: p.id),
        ratings: () => LocalRatingStore(kv).read(p.id),
        source: source,
      ),
    );
  }
  return out;
});

// ---------------------------------------------------------------- switching

/// Whether the "this switches accounts" notice has been shown; once per
/// install, one flag for Home's shelf and Memories (Android
/// OtherProfilesNoticeStore).
class OtherProfilesNoticeStore {
  const OtherProfilesNoticeStore(this._values);

  final KeyValueStore _values;

  static const key = 'home_other_profiles.switch_notice_seen';

  Future<bool> seen() async {
    try {
      return await _values.read(key) == 'true';
    } catch (_) {
      return false;
    }
  }

  Future<void> markSeen() async {
    try {
      await _values.write(key, 'true');
    } catch (_) {
      // Asked again next time; nothing else depends on it.
    }
  }
}

/// What a switch is for: the notice's body and button follow it.
enum AccountSwitchPurpose { play, openAlbum }

/// Asks before switching to [owner] the first time (switching stops what is
/// playing), then runs [proceed]; straight through once it has been seen
/// (Android MemorySwitchGate / OtherProfileSwitchSheet). [subject] is the
/// album's, playlist's or song's name for the notice's body.
Future<void> requestAccountSwitch(
  BuildContext context, {
  required MemoryOtherProfile owner,
  required String subject,
  required AccountSwitchPurpose purpose,
  required VoidCallback proceed,
}) async {
  final container = ProviderScope.containerOf(context, listen: false);
  final notice = OtherProfilesNoticeStore(container.read(keyValueStoreProvider));
  if (await notice.seen()) {
    proceed();
    return;
  }
  if (!context.mounted) return;
  final confirmed = await showAccountSwitchNotice(context, owner: owner, subject: subject, purpose: purpose);
  if (confirmed != true) return;
  await notice.markSeen();
  proceed();
}

/// How long the switch waits for the new account's source (Android
/// OtherProfileSourceWaitMs).
const otherProfileSourceWait = Duration(seconds: 10);

/// Makes [profileId] the account in use (as Home's switcher does: the
/// outgoing playback let go, the account's server asked, a failure said in
/// the shell's snackbar), then hands [onLanded] its source once it is up and
/// is [provider]'s. Nothing runs when the switch doesn't land or the source
/// never comes.
Future<void> switchAccountThen(
  ProviderContainer container,
  String profileId, {
  required String provider,
  required FutureOr<void> Function(MusicSource source) onLanded,
}) async {
  if (!await switchHomeAccountIn(container, profileId)) return;
  MusicSource? source;
  try {
    source = await container.read(activeSourceProvider.future).timeout(otherProfileSourceWait);
  } catch (_) {
    return;
  }
  if (source == null || source.provider != provider) return;
  final active = await container.read(profileRepositoryProvider).activeProfile();
  if (active?.id != profileId) return;
  await onLanded(source);
}

/// The one-off notice (Android AccountSwitchNoticeSheet): a sheet with the
/// title, one line, Cancel and the confirm button. True when confirmed.
Future<bool?> showAccountSwitchNotice(
  BuildContext context, {
  required MemoryOtherProfile owner,
  required String subject,
  required AccountSwitchPurpose purpose,
}) => showModalBottomSheet<bool>(
  context: context,
  useSafeArea: false,
  showDragHandle: true,
  backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
  builder: (_) => ChromeBarSuppression(
    child: AccountSwitchNoticeSheet(owner: owner, subject: subject, purpose: purpose),
  ),
);

class AccountSwitchNoticeSheet extends StatelessWidget {
  const AccountSwitchNoticeSheet({
    super.key,
    required this.owner,
    required this.subject,
    required this.purpose,
    this.onAnswer,
  });

  final MemoryOtherProfile owner;
  final String subject;
  final AccountSwitchPurpose purpose;

  /// The answer (true: switch); null pops the sheet's route with it.
  final ValueChanged<bool>? onAnswer;

  @override
  Widget build(BuildContext context) {
    final strings = MemoriesStrings.of(context);
    final theme = Theme.of(context);
    final opens = purpose == AccountSwitchPurpose.openAlbum;
    void answer(bool yes) => onAnswer != null ? onAnswer!(yes) : Navigator.of(context).pop(yes);
    return Padding(
      padding: EdgeInsets.fromLTRB(
        OtherProfileTokens.noticeSide,
        OtherProfileTokens.noticeTop,
        OtherProfileTokens.noticeSide,
        OtherProfileTokens.noticeBottom + MediaQuery.paddingOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: OtherProfileTokens.noticeGap,
        children: [
          Text(
            strings.switchTitle(owner.name),
            style: theme.textTheme.titleLarge!.copyWith(color: theme.colorScheme.onSurface),
          ),
          Text(
            opens ? strings.switchBodyAlbum(subject) : strings.switchBodyPlay(subject),
            style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          Padding(
            padding: const EdgeInsets.only(top: OtherProfileTokens.noticeButtonsTop),
            // Side by side at the end, as on Android; stacked (confirm on top)
            // when a narrow phone or a large font can't fit both in one row.
            child: OverflowBar(
              alignment: MainAxisAlignment.end,
              overflowAlignment: OverflowBarAlignment.end,
              overflowDirection: VerticalDirection.up,
              spacing: OtherProfileTokens.noticeButtonGap,
              overflowSpacing: OtherProfileTokens.noticeButtonGap,
              children: [
                TextButton(onPressed: () => answer(false), child: Text(strings.switchCancel)),
                FilledButton(
                  onPressed: () => answer(true),
                  child: Text(opens ? strings.switchConfirmAlbum : strings.switchConfirmPlay),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
