/// Two-way sync between an account's album rows ([MemoryStore], Android
/// `album_ratings`) and NeoDB's Mark + Review (Android NeoDBSyncService.kt).
///
/// - Score: Yoin's 0–10 (0.1 steps) goes to NeoDB's whole `rating_grade`
///   rounded; a pull keeps the local decimals when the local score is newer.
/// - Words: Yoin's one album text goes by its length (owner R2, 2026-10-06):
///   up to [neoDbShortCommentMax] characters it is the Mark's short comment and
///   the remote Review goes; longer, it is the Review's body and the short
///   comment is cleared; cleared, both go. A pull takes the Review first, the
///   short comment otherwise.
/// - The Mark POST overwrites the whole Mark: it is read first and its tags,
///   comment and visibility merged back, so other clients' fields stay.
/// - The album ↔ item map ([NeoDbMappings]); a miss searches "name artist".
/// - Signed out, every call fails with [NeoDbNotSignedIn]; nothing is sent.
library;

import '../memory/memory_store.dart';
import '../model/model.dart';
import 'neodb_api.dart';
import 'neodb_config.dart';
import 'neodb_models.dart';
import 'neodb_voices.dart';

/// Yoin's album text goes to NeoDB as the Mark's short comment up to this
/// many characters, as a Review beyond.
const int neoDbShortCommentMax = 360;

/// [text] fits NeoDB's short comment (counted as code points).
bool isNeoDbShortComment(String text) => text.runes.length <= neoDbShortCommentMax;

final class NeoDbNotSignedIn implements Exception {
  const NeoDbNotSignedIn();
  @override
  String toString() => 'NeoDB is not signed in';
}

final class NeoDbAlbumNotFound implements Exception {
  const NeoDbAlbumNotFound();
  @override
  String toString() => "Couldn't find the album on NeoDB";
}

class NeoDbSyncService {
  NeoDbSyncService({
    required NeoDbApi api,
    required NeoDbConfigStore config,
    required NeoDbMappings mappings,
    required MemoryStore Function(String profileId) memory,
    int Function()? clock,
  }) : _api = api,
       _config = config,
       _mappings = mappings,
       _memory = memory,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final NeoDbApi _api;
  final NeoDbConfigStore _config;
  final NeoDbMappings _mappings;
  final MemoryStore Function(String profileId) _memory;
  final int Function() _clock;

  Future<bool> isConfigured() async => await _config.session() != null;

  /// Sends [album]'s score and words to NeoDB when either waits (its dirty
  /// flags). Clears only the flags of what is still what was sent: the album
  /// page and the Memories diary both push, and an edit made during the
  /// network calls keeps its flag. A failed delete leaves the flag (retried).
  Future<void> pushAlbum(String profileId, Album album) async {
    final session = await _config.session();
    if (session == null) throw const NeoDbNotSignedIn();
    final store = _memory(profileId);
    final local = await store.albumRatingRow(album.id);
    if (local == null || (!local.ratingNeedsSync && !local.reviewNeedsSync)) return;

    final itemUuid = await _resolveAlbumUuid(album, session);
    if (itemUuid == null) throw const NeoDbAlbumNotFound();

    final text = local.review ?? '';
    final short = text.trim().isNotEmpty && isNeoDbShortComment(text);
    // The Mark carries the score and, for a short text, the text itself.
    final existing = await _api.getShelfItem(session.instance, session.token, itemUuid);
    await _api.postShelfMark(
      session.instance,
      session.token,
      itemUuid,
      ShelfMarkRequest(
        shelfType: existing?.shelfType ?? 'complete',
        visibility: existing?.visibility ?? 0,
        ratingGrade: local.ratingNeedsSync || existing?.ratingGrade == null
            ? local.rating.round().clamp(0, 10)
            : existing!.ratingGrade,
        commentText: !local.reviewNeedsSync
            ? existing?.commentText
            // Long or cleared: the short comment goes ("" says so on purpose).
            : (short ? text : ''),
        tags: existing?.tags ?? const [],
      ),
    );

    if (local.reviewNeedsSync) {
      String? newUuid;
      if (text.trim().isEmpty || short) {
        // Deleted by item (the API has no review uuid); 404 = already gone.
        try {
          await _api.deleteReview(session.instance, session.token, itemUuid);
        } on NeoDbException catch (e) {
          if (e.code != 404) rethrow;
        }
        newUuid = null;
      } else {
        final posted = await _api.postReview(
          session.instance,
          session.token,
          itemUuid,
          ReviewRequest(visibility: 0, title: album.name, body: text),
        );
        newUuid = posted.uuid ?? local.neoDbReviewUuid;
      }
      await store.markReviewPushed(album.id, sentReview: local.review, reviewUuid: newUuid);
    }
    if (local.ratingNeedsSync) await store.markRatingPushed(album.id, sentRating: local.rating);
  }

  /// Brings NeoDB's Mark and Review into [album]'s row. A local change that
  /// still waits (its flag) is kept over the remote; a review written on
  /// NeoDB first is found by item. Nothing anywhere: no row is made.
  Future<AlbumRatingRow?> pullAlbum(String profileId, Album album) async {
    final session = await _config.session();
    if (session == null) throw const NeoDbNotSignedIn();
    final itemUuid = await _resolveAlbumUuid(album, session);
    if (itemUuid == null) return null;
    final remote = await _api.getShelfItem(session.instance, session.token, itemUuid);
    final store = _memory(profileId);
    final existing = await store.albumRatingRow(album.id);

    final double mergedRating = existing?.ratingNeedsSync == true
        ? existing!.rating
        : (remote?.ratingGrade?.toDouble() ?? existing?.rating ?? 0);

    ReviewResponse? review;
    try {
      review = await _api.getMyReviewForItem(session.instance, session.token, itemUuid);
    } catch (_) {
      review = null;
    }
    final reviewUuid = review == null ? null : (review.uuid ?? existing?.neoDbReviewUuid);
    final reviewBody = review?.body;
    final remoteText = (reviewBody != null && reviewBody.trim().isNotEmpty)
        ? reviewBody
        : (remote?.commentText != null && remote!.commentText!.trim().isNotEmpty ? remote.commentText : null);
    final String? mergedReview = existing?.reviewNeedsSync == true
        ? existing!.review
        : remoteText ?? (review != null ? reviewBody : existing?.review);
    final String? mergedUuid = existing?.reviewNeedsSync == true
        ? existing!.neoDbReviewUuid
        : (review != null ? reviewUuid : existing?.neoDbReviewUuid);

    final hasAny = mergedRating > 0 || (mergedReview != null && mergedReview.trim().isNotEmpty) || existing != null;
    if (!hasAny) return null;
    final now = _clock();
    final blank = mergedReview == null || mergedReview.trim().isEmpty;
    final resolved = AlbumRatingRow(
      albumId: album.id,
      rating: mergedRating.clamp(0.0, 10.0).toDouble(),
      review: mergedReview,
      updatedAt: now,
      // The written date moves only when the pulled words differ.
      reviewUpdatedAt: blank ? null : (mergedReview == existing?.review ? (existing?.reviewUpdatedAt ?? now) : now),
      ratingNeedsSync: existing?.ratingNeedsSync ?? false,
      reviewNeedsSync: existing?.reviewNeedsSync ?? false,
      neoDbReviewUuid: mergedUuid,
    );
    await store.putPulledAlbumRating(resolved);
    return resolved;
  }

  /// The NeoDB item for [album]: the cached mapping, else a search whose
  /// best match (NeoDbVoicesParsing.pickAlbumItem) is cached.
  Future<String?> _resolveAlbumUuid(Album album, NeoDbSession session) async {
    final cached = await _mappings.find(album.id);
    if (cached != null) return cached;
    final query = NeoDbVoicesParsing.query(album.name, album.artist).trim();
    if (query.isEmpty) return null;
    final hits = await _api.searchAlbum(session.instance, session.token, query);
    final uuid = NeoDbVoicesParsing.pickAlbumItem(album.name, album.artist, hits)?.uuid;
    if (uuid == null) return null;
    await _mappings.put(album.id, uuid);
    return uuid;
  }
}
