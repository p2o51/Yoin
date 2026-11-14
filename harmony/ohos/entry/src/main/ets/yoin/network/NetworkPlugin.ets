import { FlutterPlugin, FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { connection } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/network';
const TAG = 'YoinNetwork';
const DOMAIN = 0x5906;

/**
 * Serves `yoin/network` for Dart's `ChannelNetworkBearer` (lib/platform/network/): `bearer()` answers what the
 * default network runs over — 'wifi', 'cellular', 'other' (Ethernet, VPN alone, …) or 'none' — and
 * `bearerChanged(bearer)` is pushed whenever the default network appears, changes capabilities or is lost. Needs
 * ohos.permission.GET_NETWORK_INFO (module.json5). Streaming quality picks its Wi‑Fi or mobile choice by it.
 */
export default class YoinNetworkPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private netConnection: connection.NetConnection | null = null;
  private last: string = '';

  getUniqueClassName(): string {
    return 'YoinNetworkPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
    this.watch();
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
    this.netConnection?.unregister((_: BusinessError) => {});
    this.netConnection = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method === 'bearer') {
      result.success(YoinNetworkPlugin.currentBearer());
    } else {
      result.notImplemented();
    }
  }

  /** The default network's bearer, read now. */
  static currentBearer(): string {
    try {
      const handle = connection.getDefaultNetSync();
      if (handle.netId === 0) {
        return 'none';
      }
      return YoinNetworkPlugin.bearerOf(connection.getNetCapabilitiesSync(handle));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'bearer read failed: %{public}s', JSON.stringify(e));
      return 'other';
    }
  }

  /** Wi‑Fi wins over cellular when both are listed (a Wi‑Fi network the system routes some traffic around). */
  static bearerOf(caps: connection.NetCapabilities): string {
    const types = caps.bearerTypes;
    if (types.indexOf(connection.NetBearType.BEARER_WIFI) >= 0) {
      return 'wifi';
    }
    if (types.indexOf(connection.NetBearType.BEARER_CELLULAR) >= 0) {
      return 'cellular';
    }
    return 'other';
  }

  private push(): void {
    const bearer = YoinNetworkPlugin.currentBearer();
    if (bearer === this.last) {
      return;
    }
    this.last = bearer;
    this.channel?.invokeMethod('bearerChanged', bearer);
  }

  /** Follows the default network: any change re-reads it and tells Dart when the bearer moved. */
  private watch(): void {
    try {
      const conn = connection.createNetConnection();
      conn.on('netAvailable', () => this.push());
      conn.on('netCapabilitiesChange', () => this.push());
      conn.on('netLost', () => this.push());
      conn.on('netUnavailable', () => this.push());
      conn.register((e: BusinessError) => {
        if (e) {
          hilog.warn(DOMAIN, TAG, 'register failed: %{public}d', e.code);
        }
      });
      this.netConnection = conn;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'watch failed: %{public}s', JSON.stringify(e));
    }
  }
}
