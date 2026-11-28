import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { asset } from '@kit.AssetStoreKit';
import { statfs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';

const CHANNEL = 'yoin/storage';
const PREFS_NAME = 'yoin_prefs';
/** Re-fetchable caches (lyrics and their translations, ~10 MiB): their own file, so a settings write never
 *  flushes them and a cache write never flushes the settings. */
const CACHE_PREFS_NAME = 'yoin_cache';
/** HarmonyOS Asset Store Kit rejects SECRET payloads outside 1..1024 bytes with 401. */
const ASSET_MAX_BYTES = 1024;
const CHUNK_MANIFEST_PREFIX = '__yoin_chunks_v1__:';

/**
 * Serves `yoin/storage` for Dart's `ChannelKeyValueStore` / `ChannelSecretStore`: plain values in a Preferences
 * file (`yoin_prefs`, or `yoin_cache` when the call's `file` names it), secrets (account credentials) in Asset Store Kit, which encrypts them with a device key and keeps them out
 * of backups. A null value deletes in both stores. `filesDir` answers the app's sandbox files folder (offline downloads),
 * `cacheDir` its cache folder (the stream cache), `cacheFreeBytes` the free space of the volume the cache folder is on
 * (the cover cache's budget: 2 % of it, like Coil's disk cache).
 */
export default class YoinStoragePlugin implements FlutterPlugin, MethodCallHandler {
  private context: common.Context | null = null;
  private channel: MethodChannel | null = null;
  private prefs: Map<string, Promise<preferences.Preferences>> = new Map();

  getUniqueClassName(): string {
    return 'YoinStoragePlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.context = binding.getApplicationContext();
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method === 'filesDir' || call.method === 'cacheDir') {
      // filesDir: the sandbox folder Dart keeps files in (offline downloads), removed with the app, never cleared as
      // cache. cacheDir: the stream cache's folder, which the system may empty when storage runs low.
      const context = this.context;
      if (context === null) {
        result.error('storage', 'storage plugin is not attached', null);
      } else {
        result.success(call.method === 'filesDir' ? context.filesDir : context.cacheDir);
      }
      return;
    }
    if (call.method === 'cacheFreeBytes') {
      const context = this.context;
      if (context === null) {
        result.error('storage', 'storage plugin is not attached', null);
        return;
      }
      try {
        result.success(statfs.getFreeSizeSync(context.cacheDir));
      } catch (e) {
        result.error('storage', `free size unreadable: ${JSON.stringify(e)}`, null);
      }
      return;
    }
    const key: Any = call.argument('key');
    if (typeof key !== 'string') {
      result.error('bad_args', `${call.method} needs a string key`, null);
      return;
    }
    const value: Any = call.argument('value');
    const text: string | null = typeof value === 'string' ? value as string : null;
    const file: Any = call.argument('file');
    const prefsName: string = file === CACHE_PREFS_NAME ? CACHE_PREFS_NAME : PREFS_NAME;
    let op: Promise<string | null>;
    switch (call.method) {
      case 'kvRead':
        op = this.kvRead(prefsName, key as string);
        break;
      case 'kvWrite':
        op = this.kvWrite(prefsName, key as string, text);
        break;
      case 'secretRead':
        op = this.secretRead(key as string);
        break;
      case 'secretWrite':
        op = this.secretWrite(key as string, text);
        break;
      default:
        result.notImplemented();
        return;
    }
    op.then((v: string | null) => result.success(v)).catch((e: BusinessError) => {
      result.error('storage', `${call.method} failed: ${e.code} ${e.message}`, null);
    });
  }

  // ---- Preferences ----

  private getPrefs(name: string): Promise<preferences.Preferences> {
    const open = this.prefs.get(name);
    if (open !== undefined) {
      return open;
    }
    const context = this.context;
    if (context === null) {
      return Promise.reject(new Error('storage plugin is not attached'));
    }
    const opened = preferences.getPreferences(context, name).catch((e: BusinessError) => {
      if (this.prefs.get(name) === opened) {
        this.prefs.delete(name);
      }
      throw e as Error;
    });
    this.prefs.set(name, opened);
    return opened;
  }

  private async kvRead(name: string, key: string): Promise<string | null> {
    const prefs = await this.getPrefs(name);
    if (!prefs.hasSync(key)) {
      return null;
    }
    const v = prefs.getSync(key, '');
    return typeof v === 'string' ? v as string : null;
  }

  private async kvWrite(name: string, key: string, value: string | null): Promise<string | null> {
    const prefs = await this.getPrefs(name);
    if (value === null) {
      prefs.deleteSync(key);
    } else {
      prefs.putSync(key, value);
    }
    await prefs.flush();
    return null;
  }

  // ---- Asset Store ----

  private async queryRaw(aliasKey: string): Promise<Uint8Array | null> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, utf8(aliasKey));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    let found: Array<asset.AssetMap>;
    try {
      found = await asset.query(query);
    } catch (e) {
      if ((e as BusinessError).code === asset.ErrorCode.NOT_FOUND) {
        return null;
      }
      throw e as Error;
    }
    if (found.length === 0) {
      return null;
    }
    const secret = found[0].get(asset.Tag.SECRET);
    return secret instanceof Uint8Array ? secret as Uint8Array : null;
  }

  private async putRaw(aliasKey: string, payload: Uint8Array): Promise<void> {
    const alias: asset.AssetMap = new Map();
    alias.set(asset.Tag.ALIAS, utf8(aliasKey));
    const attrs: asset.AssetMap = new Map();
    attrs.set(asset.Tag.ALIAS, utf8(aliasKey));
    attrs.set(asset.Tag.SECRET, payload);
    attrs.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    try {
      await asset.add(attrs);
    } catch (e) {
      if ((e as BusinessError).code !== asset.ErrorCode.DUPLICATED) {
        throw e as Error;
      }
      const update: asset.AssetMap = new Map();
      update.set(asset.Tag.SECRET, payload);
      await asset.update(alias, update);
    }
  }

  private async removeRaw(aliasKey: string): Promise<void> {
    const alias: asset.AssetMap = new Map();
    alias.set(asset.Tag.ALIAS, utf8(aliasKey));
    try {
      await asset.remove(alias);
    } catch (e) {
      if ((e as BusinessError).code !== asset.ErrorCode.NOT_FOUND) {
        throw e as Error;
      }
    }
  }

  private async existingChunkCount(key: string): Promise<number> {
    const raw = await this.queryRaw(key);
    if (raw === null) {
      return 0;
    }
    return parseChunkCount(fromUtf8(raw)) ?? 0;
  }

  private async secretRead(key: string): Promise<string | null> {
    const raw = await this.queryRaw(key);
    if (raw === null) {
      return null;
    }
    const text = fromUtf8(raw);
    const count = parseChunkCount(text);
    if (count === null) {
      return text;
    }
    const chunks: Uint8Array[] = [];
    for (let i = 0; i < count; i++) {
      const part = await this.queryRaw(chunkAlias(key, i));
      if (part === null) {
        return null;
      }
      chunks.push(part);
    }
    return fromUtf8(joinSecretChunks(chunks));
  }

  private async secretWrite(key: string, value: string | null): Promise<string | null> {
    const prevChunks = await this.existingChunkCount(key);
    // Asset Store rejects 0-byte secrets with 401; treat empty strings as deletion.
    if (value === null || value.length === 0) {
      await this.removeRaw(key);
      for (let i = 0; i < prevChunks; i++) {
        await this.removeRaw(chunkAlias(key, i));
      }
      return null;
    }
    const bytes = utf8(value);
    if (bytes.byteLength <= ASSET_MAX_BYTES) {
      await this.putRaw(key, bytes);
      for (let i = 0; i < prevChunks; i++) {
        await this.removeRaw(chunkAlias(key, i));
      }
      return null;
    }
    const chunks = splitSecretChunks(bytes, ASSET_MAX_BYTES);
    for (let i = 0; i < chunks.length; i++) {
      await this.putRaw(chunkAlias(key, i), chunks[i]);
    }
    await this.putRaw(key, utf8(`${CHUNK_MANIFEST_PREFIX}${chunks.length}`));
    for (let i = chunks.length; i < prevChunks; i++) {
      await this.removeRaw(chunkAlias(key, i));
    }
    return null;
  }
}

/** The Asset Store alias of chunk [index] of a long secret (also read by a personal build's Apple Music player). */
export function chunkAlias(key: string, index: number): string {
  return `${key}::chunk:${index}`;
}

export function parseChunkCount(text: string): number | null {
  if (!text.startsWith(CHUNK_MANIFEST_PREFIX)) {
    return null;
  }
  const n = Number(text.substring(CHUNK_MANIFEST_PREFIX.length));
  if (!Number.isInteger(n) || n <= 0) {
    return null;
  }
  return n;
}

export function splitSecretChunks(bytes: Uint8Array, maxBytes: number = ASSET_MAX_BYTES): Uint8Array[] {
  const chunks: Uint8Array[] = [];
  for (let offset = 0; offset < bytes.byteLength; offset += maxBytes) {
    const end = Math.min(offset + maxBytes, bytes.byteLength);
    chunks.push(bytes.slice(offset, end));
  }
  return chunks;
}

export function joinSecretChunks(chunks: Uint8Array[]): Uint8Array {
  let total = 0;
  for (let i = 0; i < chunks.length; i++) {
    total += chunks[i].byteLength;
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (let i = 0; i < chunks.length; i++) {
    out.set(chunks[i], offset);
    offset += chunks[i].byteLength;
  }
  return out;
}

function utf8(text: string): Uint8Array {
  return new util.TextEncoder().encodeInto(text);
}

function fromUtf8(bytes: Uint8Array): string {
  return util.TextDecoder.create('utf-8').decodeToString(bytes);
}
