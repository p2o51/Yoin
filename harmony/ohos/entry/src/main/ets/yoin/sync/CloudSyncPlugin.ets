import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common, Want } from '@kit.AbilityKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/cloud_sync';
const TAG = 'YoinCloudSync';
const DOMAIN = 0x5908;

/**
 * Serves `yoin/cloud_sync` for Dart's `ChannelCloudSyncHost` (lib/platform/sync/). There are no Google Play
 * services on HarmonyOS, so cloud sync signs in to Google with OAuth 2.0 for native apps: Dart listens on a loopback
 * port, this plugin opens Google's page in the system browser, and Google sends the browser back to that port.
 * - `openBrowser` {url}: the system browser through a view-data Want (fallback: openLink). Answers whether it opened.
 * - `holdWhileAway` / `releaseHold`: a transient task (backgroundTaskManager.requestSuspendDelay) so the app is not
 *   suspended while the user signs in, and the loopback redirect is still served when the browser comes back.
 * - `deviceName`: the market name ("HUAWEI Mate 70 Air"), as other devices list this one.
 * - `filesDir`: where the sync store's file lives (the ability's filesDir).
 */
export default class YoinCloudSyncPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private readonly context: common.UIAbilityContext;
  private delayId: number = -1;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinCloudSyncPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.release();
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'openBrowser': {
        const url: Any = call.argument('url');
        if (typeof url !== 'string') {
          result.error('bad_args', 'openBrowser needs url', null);
          return;
        }
        this.openBrowser(url as string)
          .then((ok: boolean) => result.success(ok))
          .catch(() => result.success(false));
        return;
      }
      case 'holdWhileAway':
        this.hold();
        result.success(null);
        return;
      case 'releaseHold':
        this.release();
        result.success(null);
        return;
      case 'deviceName':
        result.success(YoinCloudSyncPlugin.deviceName());
        return;
      case 'filesDir':
        result.success(this.context.filesDir);
        return;
      default:
        result.notImplemented();
    }
  }

  private async openBrowser(url: string): Promise<boolean> {
    const want: Want = {
      action: 'ohos.want.action.viewData',
      entities: ['entity.system.browsable'],
      uri: url
    };
    try {
      await this.context.startAbility(want);
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'browser Want failed: %{public}d', (e as BusinessError).code);
    }
    try {
      await this.context.openLink(url, { appLinkingOnly: false });
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'openLink failed: %{public}d', (e as BusinessError).code);
      return false;
    }
  }

  /** Asks not to be suspended while the user is in the browser (ends itself when the system says time is up). */
  private hold(): void {
    this.release();
    try {
      const info = backgroundTaskManager.requestSuspendDelay('Google sign-in for cloud sync', () => {
        hilog.info(DOMAIN, TAG, 'sign-in hold expired');
        this.release();
      });
      this.delayId = info.requestId;
    } catch (e) {
      this.delayId = -1;
      hilog.warn(DOMAIN, TAG, 'requestSuspendDelay failed: %{public}d', (e as BusinessError).code);
    }
  }

  private release(): void {
    if (this.delayId < 0) {
      return;
    }
    try {
      backgroundTaskManager.cancelSuspendDelay(this.delayId);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'cancelSuspendDelay failed: %{public}d', (e as BusinessError).code);
    }
    this.delayId = -1;
  }

  private static deviceName(): string {
    const market = deviceInfo.marketName;
    if (market && market.length > 0) {
      return market;
    }
    return deviceInfo.productModel ?? '';
  }
}
