import '../data/memory/memory_store.dart';

/// Where NeoDB stands for one album, as the rate sheet's and the Memories
/// diary's last line read it (Android AlbumNeoDbSync).
enum AlbumNeoDbSync {
  /// Not read yet: the line is left out.
  unknown,

  /// No NeoDB account: the sheet's line offers sign-in.
  signedOut,

  /// Signed in, nothing written for this album yet.
  idle,

  /// A change waits: it goes when the sheet closes.
  pending,
  syncing,
  synced,
  failed,
}

/// [row]'s NeoDB state for an account that is [configured] or not: the dirty flags decide.
AlbumNeoDbSync albumNeoDbSync({required bool configured, required AlbumRatingRow? row}) {
  if (!configured) return AlbumNeoDbSync.signedOut;
  if (row != null && (row.ratingNeedsSync || row.reviewNeedsSync)) return AlbumNeoDbSync.pending;
  if (row != null && (row.rating > 0 || (row.review?.trim().isNotEmpty ?? false))) return AlbumNeoDbSync.synced;
  return AlbumNeoDbSync.idle;
}
