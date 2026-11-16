import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { preferences } from '@kit.ArkData';
import {
  CACHE_FILE,
  CARRIED_CACHE_PREFIXES,
  CARRIED_PREF_KEYS,
  CARRIED_PREF_PREFIXES,
  isCarried,
  PAYLOAD_DIR,
  PAYLOAD_FILE,
  PAYLOAD_FORMAT,
  PREFS_FILE,
  RESTORE_MARKER
} from './BackupScope';

/** What the backup holds of the two Preferences files (only string values: Dart writes nothing else). */
interface BackupPayload {
  format: string;
  createdAt: number;
  prefs: Record<string, string>;
  cache: Record<string, string>;
}

/** How many values went out or came in. */
export interface TransferCount {
  prefs: number;
  cache: number;
}

/**
 * The backup's payload, both ways. Every path is under the APPLICATION context's filesDir / preferences
 * (`/data/storage/el2/base/...`), the folders YoinStoragePlugin and Dart's AppFilesHost use — not the backup
 * extension's own module folders (`.../haps/entry/...`).
 */
export class BackupTransfer {
  static payloadPath(ctx: common.Context): string {
    return `${ctx.filesDir}/${PAYLOAD_DIR}/${PAYLOAD_FILE}`;
  }

  static markerPath(ctx: common.Context): string {
    return `${ctx.filesDir}/${RESTORE_MARKER}`;
  }

  /**
   * Writes the carried values of `yoin_prefs` and `yoin_cache` into the payload file the backup picks up.
   * An older payload goes first, so a failed export never carries stale data.
   */
  static async exportPayload(ctx: common.Context): Promise<TransferCount> {
    BackupTransfer.removePayload(ctx);
    const prefs = BackupTransfer.carriedValues(
      await preferences.getPreferences(ctx, PREFS_FILE), CARRIED_PREF_KEYS, CARRIED_PREF_PREFIXES);
    const cache = BackupTransfer.carriedValues(
      await preferences.getPreferences(ctx, CACHE_FILE), [], CARRIED_CACHE_PREFIXES);
    const payload: BackupPayload = {
      format: PAYLOAD_FORMAT,
      createdAt: Date.now(),
      prefs: prefs,
      cache: cache
    };
    const dir = `${ctx.filesDir}/${PAYLOAD_DIR}`;
    if (!fileIo.accessSync(dir)) {
      fileIo.mkdirSync(dir);
    }
    BackupTransfer.writeAtomically(BackupTransfer.payloadPath(ctx), JSON.stringify(payload));
    return { prefs: Object.keys(prefs).length, cache: Object.keys(cache).length };
  }

  /**
   * After the system put the payload back: the carried keys of both Preferences files are replaced by the
   * backup's (as Android replaces yoin-database whole — what this install held of them goes; its settings,
   * downloads and secrets stay), then the payload is deleted. Null when no payload came back.
   */
  static async importPayload(ctx: common.Context): Promise<TransferCount | null> {
    const path = BackupTransfer.payloadPath(ctx);
    if (!fileIo.accessSync(path)) {
      return null;
    }
    const payload = JSON.parse(fileIo.readTextSync(path)) as BackupPayload;
    if (payload === null || payload === undefined || payload.format !== PAYLOAD_FORMAT || !payload.prefs) {
      throw new Error('not a Yoin backup payload');
    }
    const prefs = await BackupTransfer.replace(ctx, PREFS_FILE, CARRIED_PREF_KEYS, CARRIED_PREF_PREFIXES,
      payload.prefs);
    const cache = await BackupTransfer.replace(ctx, CACHE_FILE, [], CARRIED_CACHE_PREFIXES, payload.cache ?? {});
    BackupTransfer.removePayload(ctx);
    return { prefs: prefs, cache: cache };
  }

  /** Leaves [RESTORE_MARKER] for the next launch (Android NeoDbRestoreGuard.markRestored). */
  static markRestored(ctx: common.Context): void {
    const marker = BackupTransfer.markerPath(ctx);
    const dir = marker.substring(0, marker.lastIndexOf('/'));
    if (!fileIo.accessSync(dir)) {
      fileIo.mkdirSync(dir, true);
    }
    const file = fileIo.openSync(marker, fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY);
    fileIo.closeSync(file);
  }

  /**
   * An import that came back failing may have replaced some values already (the settings file before the caches'
   * one), the old phone's NeoDB dirty flags among them, so the marker is left all the same (Android marks every
   * restore) and the payload goes: onRelease is API 20, and a payload left behind would be read again by the next
   * restore. Best effort: nothing here throws.
   */
  static afterFailedImport(ctx: common.Context): void {
    try {
      if (!fileIo.accessSync(BackupTransfer.payloadPath(ctx))) {
        return;
      }
    } catch (e) {
      return;
    }
    try {
      BackupTransfer.markRestored(ctx);
    } catch (e) {
      // The guard just won't run; the restore already reported its failure.
    }
    try {
      BackupTransfer.removePayload(ctx);
    } catch (e) {
      // Left for the next backup or restore to replace.
    }
  }

  /** Deletes the payload (after a backup was taken, or once it was read back). */
  static removePayload(ctx: common.Context): void {
    const path = BackupTransfer.payloadPath(ctx);
    if (fileIo.accessSync(path)) {
      fileIo.unlinkSync(path);
    }
    const temp = `${path}.tmp`;
    if (fileIo.accessSync(temp)) {
      fileIo.unlinkSync(temp);
    }
  }

  private static carriedValues(store: preferences.Preferences, keys: string[],
    prefixes: string[]): Record<string, string> {
    const all = store.getAllSync() as Record<string, preferences.ValueType>;
    const out: Record<string, string> = {};
    for (const key of Object.keys(all)) {
      const value = all[key];
      if (typeof value === 'string' && isCarried(key, keys, prefixes)) {
        out[key] = value as string;
      }
    }
    return out;
  }

  private static async replace(ctx: common.Context, name: string, keys: string[], prefixes: string[],
    values: Record<string, string>): Promise<number> {
    const store = await preferences.getPreferences(ctx, name);
    const all = store.getAllSync() as Record<string, preferences.ValueType>;
    for (const key of Object.keys(all)) {
      if (isCarried(key, keys, prefixes)) {
        store.deleteSync(key);
      }
    }
    let count = 0;
    for (const key of Object.keys(values)) {
      const value = values[key];
      // Only what this build carries: a newer build's extra keys, or anything else, stay out.
      if (typeof value === 'string' && isCarried(key, keys, prefixes)) {
        store.putSync(key, value as string);
        count++;
      }
    }
    await store.flush();
    return count;
  }

  private static writeAtomically(path: string, text: string): void {
    const temp = `${path}.tmp`;
    const file = fileIo.openSync(temp, fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC | fileIo.OpenMode.WRITE_ONLY);
    try {
      fileIo.writeSync(file.fd, text);
      fileIo.fsyncSync(file.fd);
    } finally {
      fileIo.closeSync(file);
    }
    fileIo.renameSync(temp, path);
  }
}
