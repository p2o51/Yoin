import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { AbilityConstant, common, Want, wantConstant } from '@kit.AbilityKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { connection } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/continue_on';
const DOMAIN = 0x5909;
const TAG = 'YoinContinueOn';

/** Every key a Continue On cursor puts in the wantParam starts so (Dart ContinueOnCursor.keys). */
const CURSOR_PREFIX = 'yoin.continueOn.';
const CURSOR_VERSION_KEY = 'yoin.continueOn.v';

/** How long onContinue waits for Dart's cursor before it hands over the last snapshot. */
const DART_ANSWER_MS = 1500;

/** One Dart reply to `handoff`, passed to [done] whatever it is. */
class DartReply implements MethodResult {
  private readonly done: (value: Any) => void;

  constructor(done: (value: Any) => void) {
    this.done = done;
  }

  success(result: Any): void {
    this.done(result);
  }

  error(errorCode: string, errorMessage: string, errorDetails: Any): void {
    hilog.warn(DOMAIN, TAG, 'handoff answer failed: %{public}s', errorCode);
    this.done(null);
  }

  notImplemented(): void {
    this.done(null);
  }
}

/**
 * Continue On on HarmonyOS: 应用接续 (app continuation) between the user's own devices, signed in to one HUAWEI ID
 * (harmony/docs/continue-on.md; Android 17's cross-device hand-off on the other side of the port).
 *
 * - Offering: the ability is `continuable` (module.json5) but starts INACTIVE; Dart turns the mission ACTIVE while a
 *   song is loaded (setMissionContinueState) and keeps a snapshot of its cursor here.
 * - Handing off: the system calls EntryAbility.onContinue on this device when the user picks Yoin on the other one.
 *   [fillHandoff] asks Dart for a fresh cursor (with a pause channel) and falls back to the snapshot, writes it into
 *   the wantParam under `yoin.continueOn.*`, keeps the page stack out (Flutter has one page) and keeps this app
 *   running: it plays on until the person taps "Continue here" over there.
 * - Taking over: the other device's EntryAbility starts (or comes back) with launchReason CONTINUATION and the
 *   cursor in want.parameters; [deliverFrom] hands it to Dart, which shows the card. A cold start's waits for `ready`.
 */
export class ContinueOnHost {
  private static context: common.UIAbilityContext | null = null;
  private static channel: MethodChannel | null = null;
  private static ready = false;
  private static pending: Map<string, Any> | null = null;
  private static offered = false;
  private static snapshot: Map<string, Any> | null = null;

  /** The ability that offers the hand-off; the mission starts INACTIVE until Dart says a song is loaded. */
  static bind(context: common.UIAbilityContext): void {
    ContinueOnHost.context = context;
    ContinueOnHost.applyState();
  }

  static attach(channel: MethodChannel): void {
    ContinueOnHost.channel = channel;
    ContinueOnHost.ready = false;
  }

  static detach(): void {
    ContinueOnHost.channel = null;
    ContinueOnHost.ready = false;
  }

  static markReady(): void {
    ContinueOnHost.ready = true;
    const pending: Map<string, Any> | null = ContinueOnHost.pending;
    ContinueOnHost.pending = null;
    if (pending !== null) {
      ContinueOnHost.send(pending);
    }
  }

  static setOffered(offered: boolean, snapshot: Map<string, Any> | null): void {
    const changed = ContinueOnHost.offered !== offered;
    ContinueOnHost.offered = offered;
    ContinueOnHost.snapshot = offered ? snapshot : null;
    if (changed) {
      ContinueOnHost.applyState();
    }
  }

  /** Says again whether the hand-off is offered (the system can forget it across a background stay). */
  static applyState(): void {
    const context = ContinueOnHost.context;
    if (context === null) {
      return;
    }
    const state = ContinueOnHost.offered ? AbilityConstant.ContinueState.ACTIVE : AbilityConstant.ContinueState.INACTIVE;
    try {
      context.setMissionContinueState(state).catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'setMissionContinueState failed: %{public}d', e.code);
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'setMissionContinueState threw: %{public}d', (e as BusinessError).code);
    }
  }

  /** onContinue on the device handing off: the cursor into [wantParam]. Always AGREE (with no song, Yoin opens). */
  static async fillHandoff(wantParam: Record<string, Object>): Promise<AbilityConstant.OnContinueResult> {
    // Flutter draws one page: there is no ArkUI page stack to carry, and the target opens Index as usual.
    wantParam[wantConstant.Params.SUPPORT_CONTINUE_PAGE_STACK_KEY] = false;
    // Keep playing here until the person taps "Continue here" on the other device (owner 2026-11-08).
    wantParam[wantConstant.Params.SUPPORT_CONTINUE_SOURCE_EXIT_KEY] = false;
    const fresh: Map<string, Any> | null = await ContinueOnHost.askDart();
    const entries: Map<string, Any> | null = fresh ?? ContinueOnHost.snapshot;
    let count = 0;
    if (entries !== null) {
      entries.forEach((value: Any, key: string) => {
        if (!key.startsWith(CURSOR_PREFIX) || !ContinueOnHost.carriable(value)) {
          return;
        }
        wantParam[key] = value as Object;
        count++;
      });
    }
    hilog.info(DOMAIN, TAG, 'handing off (%{public}s, %{public}d fields)', fresh !== null ? 'fresh' : 'snapshot', count);
    return AbilityConstant.OnContinueResult.AGREE;
  }

  /** On the device taking over: a CONTINUATION start's cursor to Dart (once). */
  static deliverFrom(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    if (launchParam.launchReason !== AbilityConstant.LaunchReason.CONTINUATION) {
      return;
    }
    const params = want.parameters;
    if (params === undefined) {
      return;
    }
    const map: Map<string, Any> = new Map();
    Object.keys(params).forEach((key: string) => {
      if (key.startsWith(CURSOR_PREFIX)) {
        map.set(key, params[key] as Any);
      }
    });
    if (!map.has(CURSOR_VERSION_KEY)) {
      hilog.info(DOMAIN, TAG, 'continued with no cursor');
      return;
    }
    hilog.info(DOMAIN, TAG, 'continued with a cursor (%{public}d fields)', map.size);
    if (ContinueOnHost.channel !== null && ContinueOnHost.ready) {
      ContinueOnHost.send(map);
    } else {
      ContinueOnHost.pending = map;
    }
  }

  /** The addresses of the Wi‑Fi / Ethernet networks this device is on: never a VPN's, never mobile data's. */
  static localAddresses(): string[] {
    const out: string[] = [];
    try {
      connection.getAllNetsSync().forEach((net: connection.NetHandle) => {
        try {
          const caps = connection.getNetCapabilitiesSync(net);
          const bearers = caps.bearerTypes ?? [];
          const local = bearers.indexOf(connection.NetBearType.BEARER_WIFI) >= 0 ||
            bearers.indexOf(connection.NetBearType.BEARER_ETHERNET) >= 0;
          if (!local || bearers.indexOf(connection.NetBearType.BEARER_VPN) >= 0) {
            return;
          }
          const props = connection.getConnectionPropertiesSync(net);
          (props.linkAddresses ?? []).forEach((link: connection.LinkAddress) => {
            const address = link.address?.address;
            if (address && out.indexOf(address) < 0) {
              out.push(address);
            }
          });
        } catch (e) {
          hilog.warn(DOMAIN, TAG, 'network read failed: %{public}d', (e as BusinessError).code);
        }
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'networks unreadable: %{public}d', (e as BusinessError).code);
    }
    return out;
  }

  static deviceName(): string {
    const market = deviceInfo.marketName;
    if (market && market.length > 0) {
      return market;
    }
    return deviceInfo.productModel ?? '';
  }

  private static askDart(): Promise<Map<string, Any> | null> {
    const channel = ContinueOnHost.channel;
    if (channel === null || !ContinueOnHost.ready) {
      return Promise.resolve(null);
    }
    return new Promise<Map<string, Any> | null>((resolve: (value: Map<string, Any> | null) => void) => {
      let settled = false;
      const finish = (value: Any): void => {
        if (settled) {
          return;
        }
        settled = true;
        resolve(value instanceof Map ? value as Map<string, Any> : null);
      };
      const timer = setTimeout(() => finish(null), DART_ANSWER_MS);
      try {
        channel.invokeMethod('handoff', null, new DartReply((value: Any) => {
          clearTimeout(timer);
          finish(value);
        }));
      } catch (e) {
        clearTimeout(timer);
        finish(null);
      }
    });
  }

  /** What a wantParam can carry: strings, numbers, booleans and string lists. */
  private static carriable(value: Any): boolean {
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      return true;
    }
    return Array.isArray(value);
  }

  private static send(map: Map<string, Any>): void {
    ContinueOnHost.channel?.invokeMethod('received', map);
  }
}

/**
 * Serves `yoin/continue_on` for Dart's `ChannelContinueOnHost` (lib/platform/continueon/):
 * `ready`, `setOffered` {offered, snapshot}, `localAddresses`, `deviceName`; it calls Dart's `handoff` (the cursor
 * for a hand-off now) and `received` (a cursor this device was continued with).
 */
export default class YoinContinueOnPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;

  getUniqueClassName(): string {
    return 'YoinContinueOnPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
    ContinueOnHost.attach(this.channel);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
    ContinueOnHost.detach();
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'ready':
        ContinueOnHost.markReady();
        result.success(null);
        return;
      case 'setOffered': {
        const offered: Any = call.argument('offered');
        const snapshot: Any = call.argument('snapshot');
        ContinueOnHost.setOffered(offered === true, snapshot instanceof Map ? snapshot as Map<string, Any> : null);
        result.success(null);
        return;
      }
      case 'localAddresses':
        result.success(ContinueOnHost.localAddresses());
        return;
      case 'deviceName':
        result.success(ContinueOnHost.deviceName());
        return;
      default:
        result.notImplemented();
    }
  }
}
