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
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';

const CHANNEL = 'yoin/storage';
const PREFS_NAME = 'yoin_prefs';
/** Re-fetchable caches (lyrics and their translations, ~10 MiB): their own file, so a settings write never
 *  flushes them and a cache write never flushes the settings. */
const CACHE_PREFS_NAME = 'yoin_cache';

/**
 * Serves `yoin/storage` for Dart's `ChannelKeyValueStore` / `ChannelSecretStore`: plain values in a Preferences
 * file (`yoin_prefs`, or `yoin_cache` when the call's `file` names it), secrets (account credentials) in Asset Store Kit, which encrypts them with a device key and keeps them out
 * of backups. A null value deletes in both stores.
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
    const opened = preferences.getPreferences(context, name);
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

  private async secretRead(key: string): Promise<string | null> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, utf8(key));
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
    return secret instanceof Uint8Array ? fromUtf8(secret as Uint8Array) : null;
  }

  private async secretWrite(key: string, value: string | null): Promise<string | null> {
    const alias: asset.AssetMap = new Map();
    alias.set(asset.Tag.ALIAS, utf8(key));
    if (value === null) {
      try {
        await asset.remove(alias);
      } catch (e) {
        if ((e as BusinessError).code !== asset.ErrorCode.NOT_FOUND) {
          throw e as Error;
        }
      }
      return null;
    }
    const attrs: asset.AssetMap = new Map();
    attrs.set(asset.Tag.ALIAS, utf8(key));
    attrs.set(asset.Tag.SECRET, utf8(value));
    attrs.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    try {
      await asset.add(attrs);
    } catch (e) {
      if ((e as BusinessError).code !== asset.ErrorCode.DUPLICATED) {
        throw e as Error;
      }
      const update: asset.AssetMap = new Map();
      update.set(asset.Tag.SECRET, utf8(value));
      await asset.update(alias, update);
    }
    return null;
  }
}

function utf8(text: string): Uint8Array {
  return new util.TextEncoder().encodeInto(text);
}

function fromUtf8(bytes: Uint8Array): string {
  return util.TextDecoder.create('utf-8').decodeToString(bytes);
}
