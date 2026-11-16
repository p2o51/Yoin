/// Home's "On Your Other Accounts" (Android ui/home/HomeOtherProfiles.kt,
/// owner 2026-11-04,「在你的其他资料里」): the albums and playlists the
/// user's other accounts play most, from Yoin's own logs, so it needs no
/// network and shows at once. A tap switches to that account and plays it;
/// the first tap ever says so first, since switching stops what is playing.
///
/// Android counts every play in its activity table. HarmonyOS's activity log
/// keeps one row per album or playlist (its newest), so an album's count is
/// its plays in Yoin (the Memory store's) and a playlist counts once; the
/// window, the per-account cap and the turn-taking are Android's.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../detail/detail_cache.dart';
import '../detail/detail_play.dart';
import '../memories/memories_ui_state.dart';
import '../memories/other_profiles.dart';
import '../playback/playback_controller.dart';
import '../settings/account_identity.dart';
import '../theme/tokens.dart';
import 'home_selection.dart';
import 'home_header.dart';
import 'home_metrics.dart';
import 'home_shelves.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_motion.dart';
import 'widgets/home_text.dart';

/// At most this many items from one account (Android OtherProfilesPerProfile).
const int otherProfilesPerProfile = 6;

/// At most this many items in all (Android OtherProfilesTotal).
const int otherProfilesTotal = 12;

/// Plays this long ago or newer count (Android OtherProfilesWindowMs, 90 days).
const int otherProfilesWindowMs = 90 * 24 * 60 * 60 * 1000;

/// The cover size asked of another account's server (Android OtherProfilesCoverSize).
const int otherProfilesCoverSize = 320;

enum OtherProfileItemKind { album, playlist }

/// One album or playlist another account plays often.
@immutable
final class OtherProfileItem {
  const OtherProfileItem({
    required this.profile,
    required this.kind,
    required this.id,
    required this.title,
    required this.subtitle,
    this.cover,
  });

  /// The account it is from (name, mark).
  final MemoryOtherProfile profile;
  final OtherProfileItemKind kind;

  /// The album's or playlist's id on that account's provider.
  final MediaId id;
  final String title;
  final String subtitle;

  /// Resolved against that account's own server (a URL).
  final CoverRef? cover;

  String get stableId => 'other-profile:${profile.id}:${kind.name}:${id.rawId}';

  @override
  bool operator ==(Object other) =>
      other is OtherProfileItem &&
      other.profile == profile &&
      other.kind == kind &&
      other.id == id &&
      other.title == title &&
      other.subtitle == subtitle &&
      other.cover == cover;

  @override
  int get hashCode => Object.hash(profile, kind, id, title, subtitle, cover);
}

/// One account's album or playlist and how often it played lately (Android
/// OtherProfilePlayAggregate).
@immutable
final class OtherProfilePlays {
  const OtherProfilePlays({
    required this.profile,
    required this.kind,
    required this.id,
    required this.title,
    required this.subtitle,
    required this.playCount,
    required this.lastAt,
    this.cover,
  });

  final MemoryOtherProfile profile;
  final OtherProfileItemKind kind;
  final MediaId id;
  final String title;
  final String subtitle;
  final int playCount;
  final int lastAt;
  final CoverRef? cover;
}

/// [log]'s albums and playlists played since [sinceMs], most played first
/// (then the newest): an album counts its plays in Yoin from [albumPlays],
/// a playlist once. Visits never count.
List<OtherProfilePlays> otherProfilePlays({
  required MemoryOtherProfile profile,
  required List<ActivityEvent> log,
  required Map<MediaId, int> albumPlays,
  required int sinceMs,
  CoverRef? Function(CoverRef? cover)? coverFor,
}) {
  final out = <OtherProfilePlays>[];
  for (final e in log) {
    if (e.actionType != ActivityActionType.played || e.timestampMs < sinceMs) continue;
    final kind = switch (e.entityType) {
      ActivityEntityType.album => OtherProfileItemKind.album,
      ActivityEntityType.playlist => OtherProfileItemKind.playlist,
      _ => null,
    };
    if (kind == null || e.entityId.isEmpty || e.title.trim().isEmpty) continue;
    final id = MediaId(profile.provider, e.entityId);
    final cover = CoverRef.fromStorageKey(e.coverArtKey);
    out.add(
      OtherProfilePlays(
        profile: profile,
        kind: kind,
        id: id,
        title: e.title,
        subtitle: e.subtitle,
        playCount: kind == OtherProfileItemKind.album ? (albumPlays[id] ?? 1).clamp(1, 1 << 30) : 1,
        lastAt: e.timestampMs,
        cover: coverFor == null ? cover : coverFor(cover),
      ),
    );
  }
  out.sort((a, b) {
    final c = b.playCount.compareTo(a.playCount);
    return c != 0 ? c : b.lastAt.compareTo(a.lastAt);
  });
  return out;
}

/// The shelf (Android buildOtherProfileItems): from each account's rows
/// (most played first), at most [perProfile] each, the accounts taking turns
/// (the one with the most played item first) up to [total].
List<OtherProfileItem> buildOtherProfileItems(
  List<List<OtherProfilePlays>> accounts, {
  int perProfile = otherProfilesPerProfile,
  int total = otherProfilesTotal,
}) {
  final lists = <({List<OtherProfileItem> items, int top, int lastAt, int order})>[];
  for (final rows in accounts) {
    final seen = <String>{};
    final list = <OtherProfileItem>[];
    for (final row in rows) {
      if (list.length >= perProfile) break;
      final item = OtherProfileItem(
        profile: row.profile,
        kind: row.kind,
        id: row.id,
        title: row.title,
        subtitle: row.subtitle,
        cover: row.cover,
      );
      if (seen.add(item.stableId)) list.add(item);
    }
    if (list.isNotEmpty) {
      lists.add((items: list, top: rows.first.playCount, lastAt: rows.first.lastAt, order: lists.length));
    }
  }
  // The account whose top item played most leads the turns (then the newest).
  lists.sort((a, b) {
    var c = b.top.compareTo(a.top);
    if (c != 0) return c;
    c = b.lastAt.compareTo(a.lastAt);
    return c != 0 ? c : a.order.compareTo(b.order);
  });
  final order = [for (final l in lists) l.items];
  final out = <OtherProfileItem>[];
  for (var index = 0; out.length < total && order.any((l) => index < l.length); index++) {
    for (final list in order) {
      if (out.length >= total) break;
      if (index < list.length) out.add(list[index]);
    }
  }
  return out;
}

/// The shelf's items for the account in use's Home; empty with no other
/// account. Rebuilt with the other accounts (a switch, an edit).
final otherProfileItemsProvider = FutureProvider<List<OtherProfileItem>>((ref) async {
  final sources = await ref.watch(otherProfileSourcesProvider.future);
  if (sources.isEmpty) return const [];
  final since = ref.read(homeClockProvider)().millisecondsSinceEpoch - otherProfilesWindowMs;
  final accounts = await Future.wait([
    for (final s in sources)
      () async {
        try {
          final log = await s.log.recent();
          final plays = {for (final p in await s.store.albumPlays()) p.albumId: p.playCount};
          return otherProfilePlays(
            profile: s.profile,
            log: log,
            albumPlays: plays,
            sinceMs: since,
            coverFor: (cover) => s.coverFor(cover, size: otherProfilesCoverSize),
          );
        } catch (_) {
          return const <OtherProfilePlays>[]; // that account adds nothing
        }
      }(),
  ]);
  return buildOtherProfileItems(accounts);
});

/// Plays [item] once its account is in use (Android OtherProfilesViewModel
/// .play): its album's or playlist's tracks, read through that account.
Future<void> playOtherProfileItem(ProviderContainer container, OtherProfileItem item) async {
  final cache = container.read(detailCacheProvider);
  final controller = container.read(playbackControllerProvider.notifier);
  switch (item.kind) {
    case OtherProfileItemKind.album:
      final album = await cache.album(item.id);
      final tracks = album?.tracks ?? const <Track>[];
      if (album == null || tracks.isEmpty) return;
      await controller.playTracks(tracks, context: albumPlaybackContext(album));
    case OtherProfileItemKind.playlist:
      final playlist = await cache.playlist(item.id);
      final tracks = playlist?.tracks ?? const <Track>[];
      if (playlist == null || tracks.isEmpty) return;
      await controller.playTracks(tracks, context: playlistPlaybackContext(playlist, playlist.coverArt ?? item.cover));
  }
}

/// A tap: the one-off notice the first time, then the switch, then play.
Future<void> openOtherProfileItem(BuildContext context, OtherProfileItem item) async {
  final container = ProviderScope.containerOf(context, listen: false);
  await requestAccountSwitch(
    context,
    owner: item.profile,
    subject: item.title,
    purpose: AccountSwitchPurpose.play,
    proceed: () => unawaited(
      switchAccountThen(
        container,
        item.profile.id,
        provider: item.id.provider,
        onLanded: (_) => playOtherProfileItem(container, item),
      ),
    ),
  );
}

/// On Your Other Accounts: what the other accounts play most, the Recently
/// Added card's size and type with the account's name under the title.
/// Nothing on the cover (owner: no badge).
class OtherProfilesSection extends StatelessWidget {
  const OtherProfilesSection({super.key, required this.items, required this.frame});

  final List<OtherProfileItem> items;
  final HomeFeedFrame frame;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Padding(
        padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
        child: HomeSectionTitle(HomeStrings.of(context).sectionOtherProfiles),
      ),
      const SizedBox(height: HomeSectionMetrics.shelfSpacing),
      HomeShelf(
        height: shelfCardHeight(context),
        padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
        itemCount: items.length,
        itemBuilder: (context, i) => Align(
          alignment: Alignment.topCenter,
          child: OtherProfileCard(key: ValueKey(items[i].stableId), item: items[i]),
        ),
      ),
    ],
  );
}

/// One shelf card: the cover on its backdrop, the title, then the account's
/// mark before its name (so the line reads as "another account", not an artist).
class OtherProfileCard extends StatelessWidget {
  const OtherProfileCard({super.key, required this.item});

  final OtherProfileItem item;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return SizedBox(
      width: HomeTokens.recentlyAddedAlbumCover,
      child: ElasticPress(
        onTap: () => openOtherProfileItem(context, item),
        child: Semantics(
          button: true,
          label: '${item.title}, ${item.profile.name}',
          excludeSemantics: true,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              BackdropCover(
                cover: item.cover,
                kind: item.kind == OtherProfileItemKind.playlist ? HomeCardKind.playlist : HomeCardKind.album,
                size: HomeTokens.recentlyAddedAlbumCover,
              ),
              const SizedBox(height: HomeShelfMetrics.albumCardCaptionGap),
              SeamText(
                item.title,
                style: text.bodySmall!.copyWith(fontWeight: FontWeight.w500, color: colors.onSurface),
              ),
              Row(
                spacing: OtherProfileTokens.shelfMarkGap,
                children: [
                  AccountMark(
                    shapeIndex: item.profile.shape,
                    identity: ServiceIdentity.forProvider(item.profile.provider),
                    size: OtherProfileTokens.shelfMark,
                  ),
                  Flexible(
                    child: SeamText(
                      item.profile.name,
                      style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
