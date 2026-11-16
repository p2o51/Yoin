import { BackupExtensionAbility, BundleVersion } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BackupTransfer, TransferCount } from './BackupTransfer';

const DOMAIN = 0x590a;
const TAG = 'YoinBackup';

/** onBackupEx / onRestoreEx's answer to the backup service. */
interface ErrorInfo {
  type: string;
  errorCode: number;
  errorInfo: string;
}

/**
 * Yoin's part in Phone Clone and Huawei Cloud backup (Android YoinBackupAgent + Auto Backup's
 * data_extraction_rules.xml). backup_config.json names what the system copies: the payload folder and the
 * playlist backups. Before a backup, the carried values of the Preferences files go into the payload
 * (BackupScope.ets); after a restore they go back, and the NeoDB marker tells the next launch that this data came
 * from another phone, so the old phone's NeoDB dirty flags are dropped (Dart NeoDbRestoreGuard).
 *
 * Runs in the backup service's extension process. Phone Clone and a restore during setup bring the data back before
 * Yoin first starts on the new phone, so nothing in the app holds the Preferences files open meanwhile. A Yoin that
 * is running would save its in-memory copy over the restored file at its next write (docs/backup.md, "Not yet
 * checked on a device").
 */
export default class YoinBackupExtension extends BackupExtensionAbility {
  async onBackupEx(backupInfo: string): Promise<string> {
    try {
      const count: TransferCount = await BackupTransfer.exportPayload(this.appContext());
      hilog.info(DOMAIN, TAG, 'backup payload: %{public}d values, %{public}d cached', count.prefs, count.cache);
      return answer(0, '');
    } catch (e) {
      const message = `${(e as Error).message ?? e}`;
      hilog.error(DOMAIN, TAG, 'backup payload failed: %{public}s', message);
      return answer(1, message);
    }
  }

  async onRestoreEx(bundleVersion: BundleVersion, restoreInfo: string): Promise<string> {
    hilog.info(DOMAIN, TAG, 'restore from %{public}s (%{public}d)', bundleVersion.name, bundleVersion.code);
    let ctx: common.Context | null = null;
    try {
      // Inside the try: a context that can't be had is an ErrorInfo answer, not a rejected promise.
      ctx = this.appContext();
      const count: TransferCount | null = await BackupTransfer.importPayload(ctx);
      if (count === null) {
        // Only files came back (or the backup is from an Android phone: its Room database isn't ours to read).
        hilog.info(DOMAIN, TAG, 'restore: no payload');
        return answer(0, '');
      }
      // The values are another phone's: the next launch drops its NeoDB dirty flags.
      BackupTransfer.markRestored(ctx);
      hilog.info(DOMAIN, TAG, 'restored %{public}d values, %{public}d cached', count.prefs, count.cache);
      return answer(0, '');
    } catch (e) {
      const message = `${(e as Error).message ?? e}`;
      hilog.error(DOMAIN, TAG, 'restore failed: %{public}s', message);
      if (ctx !== null) {
        BackupTransfer.afterFailedImport(ctx);
      }
      return answer(1, message);
    }
  }

  /**
   * The payload is only needed while the system copies it (scenario 1, a backup) or until it is read back (2, a
   * restore; one that failed to import leaves nothing behind either).
   *
   * onRelease is API 20; on API 18–19 (compatibleSdkVersion 18) it is never called, so after a backup the payload
   * stays in `files/backup/` until the next backup or restore replaces or deletes it. A restore deletes it itself.
   */
  async onRelease(scenario: number): Promise<void> {
    try {
      BackupTransfer.removePayload(this.appContext());
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'payload cleanup failed: %{public}s', `${(e as Error).message ?? e}`);
    }
  }

  /** The application context: `/data/storage/el2/base/{files,preferences}`, where the app keeps its data. */
  private appContext(): common.Context {
    return this.context.getApplicationContext();
  }
}

function answer(code: number, info: string): string {
  const result: ErrorInfo = { type: 'ErrorInfo', errorCode: code, errorInfo: info };
  return JSON.stringify(result);
}
