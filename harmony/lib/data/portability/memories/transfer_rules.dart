/// The pure rules of a Memories transfer (:shared TransferRules.kt,
/// docs/cloud-sync.md「转移 Memories」): which ids carry over as they are,
/// copied note ids, ledger keys, how a review joins, how mentions follow
/// their song / album.
library;

import '../../notes/note_markup.dart';
import '../../sync/canonical_json.dart';
import '../../sync/sync_contracts.dart';
import 'memory_items.dart';
import 'transfer_models.dart';

/// Ids for copied notes: `mig-` + the first 16 hex of sha256(srcScope ␟ srcId ␟ dstScope).
/// Deterministic, so copying the same note into the same account again finds the
/// earlier copy instead of duplicating it, on any device; never the original id.
abstract final class MigrationIds {
  static const prefix = 'mig-';

  static String derive(String srcScope, String srcId, String dstScope) =>
      prefix + CanonicalJson.sha256Hex(SyncFormat.compositeKey([srcScope, srcId, dstScope])).substring(0, 16);
}

/// What earlier runs of a transfer did, per (source scope, target profile): item key -> result.
/// Values: `copied|<target key>`, `unmatched`, `matched|<target media id>`, `chosen|<target media id>`.
abstract class TransferLedger {
  static const copied = 'copied|';
  static const unmatched = 'unmatched';
  static const matched = 'matched|';
  static const chosen = 'chosen|';

  /// Android keeps the ledger in sync_meta, one row per item.
  static const metaPrefixRoot = 'transfer:';

  /// `transfer:<16 hex of sha256(srcScope ␟ dstProfileId)>:`; an item's row is this + its item key.
  static String metaPrefix(String srcScope, String dstProfileId) =>
      '$metaPrefixRoot${CanonicalJson.sha256Hex(SyncFormat.compositeKey([srcScope, dstProfileId])).substring(0, 16)}:';

  Future<Map<String, String>> read(String srcScope, String dstProfileId);

  Future<void> write(String srcScope, String dstProfileId, Map<String, String> entries);
}

/// For tests, previews and a device without a sync store.
final class InMemoryTransferLedger extends TransferLedger {
  final _entries = <(String, String), Map<String, String>>{};

  @override
  Future<Map<String, String>> read(String srcScope, String dstProfileId) async =>
      Map.of(_entries[(srcScope, dstProfileId)] ?? const {});

  @override
  Future<void> write(String srcScope, String dstProfileId, Map<String, String> entries) async {
    (_entries[(srcScope, dstProfileId)] ??= {}).addAll(entries);
  }
}

abstract final class TransferRules {
  /// memory_copy_cache.entityType of an album Memory.
  static const entityAlbum = 'album';

  static const providerSubsonic = 'subsonic';
  static const providerSpotify = 'spotify';
  static const providerAppleMusic = 'applemusic';

  /// Candidates kept for an uncertain match.
  static const maxCandidates = 3;

  /// The divider a joined review gets above the source's text.
  static String joinReviews(String target, String source, String sourceAccount) =>
      '${target.trimRight()}\n\n— 来自 $sourceAccount\n${source.trim()}';

  /// Whether [ref]'s id means the same song/album on [target]: same provider, and Spotify, an Apple Music
  /// catalog id (library ids "library:…" are per listener), or one Subsonic server.
  static bool compatible(TransferAccount source, TransferAccount target, SourceRef ref) {
    if (ref.provider != target.provider) return false;
    return switch (ref.provider) {
      providerSpotify => true,
      providerAppleMusic => !isAppleLibraryId(ref.id),
      providerSubsonic =>
        source.provider == target.provider &&
            source.serverKey != null &&
            target.serverKey != null &&
            source.serverKey!.toLowerCase() == target.serverKey!.toLowerCase(),
      _ => source.scope == target.scope,
    };
  }

  /// Apple Music library ids ("library:…", or a bare "i." / "l." resource id) are per listener.
  static bool isAppleLibraryId(String id) => id.startsWith('library:') || id.startsWith('i.') || id.startsWith('l.');

  /// A valid media id (non-empty provider without ':', non-empty id), else null.
  static TargetRef? targetOf(String provider, String id) =>
      provider.isEmpty || provider.contains(':') || id.isEmpty ? null : TargetRef(provider, id);

  /// "provider:id" back to a [TargetRef] (a ledger's `matched|` / `chosen|` value), or null.
  static TargetRef? parseTarget(String? value) {
    if (value == null) return null;
    final sep = value.indexOf(':');
    if (sep <= 0 || sep >= value.length - 1) return null;
    return targetOf(value.substring(0, sep), value.substring(sep + 1));
  }

  /// A stored id that may carry its provider prefix ("subsonic:123") as the raw id ("123").
  static String storedRawId(String provider, String stored) =>
      stored.startsWith('$provider:') ? stored.substring(provider.length + 1) : stored;

  /// [text] with each track / album mention whose item this transfer maps pointing at the target's id;
  /// names, other mentions (artists, unmapped items) and every other character as written.
  static String remapMentions(
    String text,
    TargetRef? Function(SourceRef) trackTarget,
    TargetRef? Function(SourceRef) albumTarget,
  ) {
    if (!NoteMarkup.hasMentions(text)) return text;
    return NoteMarkup.rewriteMentions(text, (mention) {
      final ref = SourceRef(mention.provider, mention.id);
      final to = switch (mention.kind) {
        MentionKind.track => trackTarget(ref),
        MentionKind.album => albumTarget(ref),
        MentionKind.artist => null,
      };
      if (to == null || (to.provider == mention.provider && to.id == mention.id)) return null;
      return mention.copyWith(provider: to.provider, id: to.id);
    });
  }

  static String trackKey(SourceRef ref) => 'track:$ref';
  static String albumKey(SourceRef ref) => 'album:$ref';
  static String songNoteKey(SourceNote note) => 'note:${note.id}';
  static String trackRatingKey(SourceTrackRating rating) => 'track_rating:${rating.track}';
  static String albumRatingKey(SourceAlbumRating rating) => 'album_rating:${rating.album}';
  static String albumReviewKey(SourceAlbumReview review) => 'album_review:${review.album}';
  static String albumNoteKey(SourceAlbumNote note) => 'album_note:${note.id}';
  static String albumTitleKey(SourceAlbumTitle title) => 'album_title:${title.album}';
  static String memoryTitleKey(SourceMemoryTitle title) =>
      'memory_title:${SyncFormat.compositeKey([title.provider, title.entityType, title.entityId])}';
  static String memoryCopyKey(SourceMemoryCopy copy) =>
      'memory_copy:${SyncFormat.compositeKey([copy.provider, copy.entityType, copy.entityId])}';
  static String linkKey(SourceTrackLink link) => 'link:${link.track}';

  /// A ledger value for a song / album match: `chosen|id` (picked), `matched|id`, else `unmatched`.
  static String ledgerValue(MatchStatus status, TargetRef? chosen) {
    if (chosen != null && status == MatchStatus.picked) return '${TransferLedger.chosen}$chosen';
    if (chosen != null && status == MatchStatus.matched) return '${TransferLedger.matched}$chosen';
    return TransferLedger.unmatched;
  }

  /// [items] without what [ledger] marks copied.
  static MemoryItems pendingItems(MemoryItems items, Map<String, String> ledger) {
    bool pending(String key) => !(ledger[key]?.startsWith(TransferLedger.copied) ?? false);
    return items.copyWith(
      songNotes: [
        for (final x in items.songNotes)
          if (pending(songNoteKey(x))) x,
      ],
      trackRatings: [
        for (final x in items.trackRatings)
          if (pending(trackRatingKey(x))) x,
      ],
      albumRatings: [
        for (final x in items.albumRatings)
          if (pending(albumRatingKey(x))) x,
      ],
      albumReviews: [
        for (final x in items.albumReviews)
          if (pending(albumReviewKey(x))) x,
      ],
      albumNotes: [
        for (final x in items.albumNotes)
          if (pending(albumNoteKey(x))) x,
      ],
      albumTitles: [
        for (final x in items.albumTitles)
          if (pending(albumTitleKey(x))) x,
      ],
      memoryTitles: [
        for (final x in items.memoryTitles)
          if (pending(memoryTitleKey(x))) x,
      ],
      memoryCopies: [
        for (final x in items.memoryCopies)
          if (pending(memoryCopyKey(x))) x,
      ],
      trackAlbumLinks: [
        for (final x in items.trackAlbumLinks)
          if (pending(linkKey(x))) x,
      ],
    );
  }

  static int countItems(MemoryItems items) =>
      items.songNotes.length +
      items.trackRatings.length +
      items.albumRatings.length +
      items.albumReviews.length +
      items.albumNotes.length +
      items.albumTitles.length +
      items.memoryTitles.length +
      items.memoryCopies.length +
      items.trackAlbumLinks.length;

  /// What a run would copy: items whose song / album is in one id space or has a chosen counterpart.
  static TransferCounts mappableCounts(
    TransferAccount source,
    TransferAccount target,
    MemoryItems items,
    bool Function(SourceRef) hasTrack,
    bool Function(SourceRef) hasAlbum,
  ) {
    bool track(SourceRef ref) => compatible(source, target, ref) || hasTrack(ref);
    bool album(SourceRef ref) => compatible(source, target, ref) || hasAlbum(ref);
    return TransferCounts(
      songNotes: items.songNotes.where((x) => track(x.track)).length,
      trackRatings: items.trackRatings.where((x) => track(x.track)).length,
      albumRatings: items.albumRatings.where((x) => album(x.album)).length,
      albumReviews: items.albumReviews.where((x) => album(x.album)).length,
      albumNotes: items.albumNotes.where((x) => album(x.album)).length,
      albumTitles: items.albumTitles.where((x) => album(x.album)).length,
      memoryTitles: items.memoryTitles.where((x) => album(SourceRef(x.provider, x.entityId))).length,
      memoryCopies: items.memoryCopies.where((x) => album(SourceRef(x.provider, x.entityId))).length,
      trackAlbumLinks: items.trackAlbumLinks.where((x) => track(x.track)).length,
    );
  }

  /// The songs and albums of the pending items that need matching (not [compatible]), in a stable order.
  static (List<SourceRef>, List<SourceRef>) needsMatching(
    TransferAccount source,
    TransferAccount target,
    MemoryItems pending,
  ) {
    final trackRefs = <SourceRef>{
      for (final n in pending.songNotes) n.track,
      for (final r in pending.trackRatings) r.track,
      for (final l in pending.trackAlbumLinks) l.track,
    };
    final tracks = [
      for (final ref in trackRefs)
        if (!compatible(source, target, ref)) ref,
    ];
    final albumRefs = <SourceRef>{
      ...pending.albums,
      for (final l in pending.trackAlbumLinks) SourceRef(l.track.provider, l.albumId),
    };
    final albums = [
      for (final ref in albumRefs)
        if (!compatible(source, target, ref)) ref,
    ];
    return (tracks, albums);
  }

  /// The album a [ref] song is linked to in [items], if any.
  static String? linkedAlbum(SourceRef ref, MemoryItems items) {
    for (final l in items.trackAlbumLinks) {
      if (l.track == ref) return l.albumId;
    }
    return null;
  }
}
