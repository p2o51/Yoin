/// After a restore from another phone (Phone Clone, a cloud restore), the
/// album scores and reviews arrive with that phone's NeoDB dirty flags
/// (`AlbumRatingRow.ratingNeedsSync` / `reviewNeedsSync`). The old phone may
/// still push those rows itself, and this one would push the same snapshot
/// again later, over anything newer written on NeoDB since. The flags belong
/// to the device that made the edit, so a restore drops them (Android
/// data/integration/neodb/NeoDbRestoreGuard.kt).
///
/// The ArkTS backup extension (ohos/entry/src/main/ets/yoin/backup/) leaves
/// [marker] under the app's files folder once it has put the restored values
/// back. That folder isn't in backup_config.json, so the marker can only come
/// from this device's restore. The next launch clears the flags, then the
/// marker.
library;

import 'dart:io';

abstract final class NeoDbRestoreGuard {
  /// Under the app's files folder (ArkTS BackupScope.RESTORE_MARKER).
  static const String markerPath = 'neodb/restored_pending_clear';

  static File marker(Directory filesDir) => File('${filesDir.path}${Platform.pathSeparator}$markerPath');

  /// A restore just finished on this device (written by the ArkTS backup
  /// extension on HarmonyOS; here for tests and any other host).
  static Future<void> markRestored(Directory filesDir) async {
    final file = marker(filesDir);
    await file.parent.create(recursive: true);
    await file.create();
  }

  /// After a restore, [clearPending] drops every NeoDB dirty flag; the marker
  /// goes only once that succeeded, so a failed clear is tried again next
  /// launch. No marker: nothing happens.
  static Future<void> clearIfRestored(Directory filesDir, Future<void> Function() clearPending) async {
    final file = marker(filesDir);
    if (!await file.exists()) return;
    await clearPending();
    await file.delete();
  }
}
