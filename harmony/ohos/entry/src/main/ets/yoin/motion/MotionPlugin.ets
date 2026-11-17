import { Any, FlutterPlugin, FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { accessibility } from '@kit.AccessibilityKit';
import { BusinessError, commonEventManager, deviceInfo, power, settings } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/motion';
const TAG = 'YoinMotion';
const DOMAIN = 0x5906;

/** accessibility.isAnimationReduceEnabledSync / onAnimationReduceStateChange arrived in API 23. */
const ANIMATION_REDUCE_API = 23;

/** power.DevicePowerMode.MODE_CUSTOM_POWER_SAVE (API 20): a number, so API 18 builds don't reach for the member. */
const MODE_CUSTOM_POWER_SAVE = 650;

/**
 * The system's "less motion" settings for Dart's `ChannelSystemMotionHost` (lib/platform/motion/system_motion.dart),
 * which folds them into the app root's `disableAnimations`: Android reduces Yoin's motion under the system's "Remove
 * animations" (an animator duration scale of 0, followed while Yoin runs through a ContentObserver) and power saving
 * (ui/experience/MotionRuntime.kt resolveMotionProfile). The flutter_ohos embedder forwards none of these to Flutter.
 *
 * `read` → {animationReduce, animatorDurationScale, powerSave}:
 *  - animationReduce: Accessibility › 减弱动画, `accessibility.isAnimationReduceEnabledSync()` (API 23+; false below);
 *  - animatorDurationScale: Developer options' animator duration scale, `settings.display.ANIMATOR_DURATION_SCALE`,
 *    from the device-wide domain or else the user's (1 when unset or unreadable; 0 = animations off);
 *  - powerSave: `power.getPowerMode()` is a power-saving mode (省电模式, 超级省电, a custom saving mode).
 * The same map is pushed as `motion` whenever any changes (`onAnimationReduceStateChange`,
 * `settings.registerKeyObserver`, the POWER_SAVE_MODE_CHANGED common event). No permission: only the USER_SECURE
 * settings domain needs one to read or observe.
 */
export default class YoinMotionPlugin implements FlutterPlugin, MethodCallHandler {
  private readonly context: common.UIAbilityContext;
  private channel: MethodChannel | null = null;
  private observedScaleDomains: string[] = [];
  private observingReduce = false;
  private powerSubscriber: commonEventManager.CommonEventSubscriber | null = null;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinMotionPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
    this.watch();
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
    this.unwatch();
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method === 'read') {
      result.success(this.read());
    } else {
      result.notImplemented();
    }
  }

  /** The settings now. */
  private read(): Map<string, Any> {
    const map: Map<string, Any> = new Map();
    map.set('animationReduce', YoinMotionPlugin.animationReduce());
    map.set('animatorDurationScale', this.animatorDurationScale());
    map.set('powerSave', YoinMotionPlugin.powerSave());
    return map;
  }

  private static animationReduce(): boolean {
    if (deviceInfo.sdkApiVersion < ANIMATION_REDUCE_API) {
      return false;
    }
    try {
      return accessibility.isAnimationReduceEnabledSync();
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'animation reduce read failed: %{public}s', JSON.stringify(e));
      return false;
    }
  }

  /** Where the scale may be kept: the device-wide domain first, then the user's. */
  private static scaleDomains(): string[] {
    return [settings.domainName.DEVICE_SHARED, settings.domainName.USER_PROPERTY];
  }

  /** The first domain that holds the scale (an unset key reads as the '' default), else 1. */
  private animatorDurationScale(): number {
    for (const domain of YoinMotionPlugin.scaleDomains()) {
      try {
        const raw = settings.getValueSync(this.context, settings.display.ANIMATOR_DURATION_SCALE, '', domain);
        if (raw.length === 0) {
          continue;
        }
        const scale = Number.parseFloat(raw);
        if (Number.isFinite(scale) && scale >= 0) {
          return scale;
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'animator scale read failed (%{public}s): %{public}s', domain, JSON.stringify(e));
      }
    }
    return 1;
  }

  private static powerSave(): boolean {
    try {
      const mode: number = power.getPowerMode();
      return mode === power.DevicePowerMode.MODE_POWER_SAVE || mode === power.DevicePowerMode.MODE_EXTREME_POWER_SAVE ||
        mode === MODE_CUSTOM_POWER_SAVE;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'power mode read failed: %{public}s', JSON.stringify(e));
      return false;
    }
  }

  private push(): void {
    this.channel?.invokeMethod('motion', this.read());
  }

  /** A named callback, as onAnimationReduceStateChange asks (an anonymous one leaks a native object per call). */
  private onReduceChange: (enabled: boolean) => void = (_: boolean) => {
    this.push();
  };

  private watch(): void {
    if (deviceInfo.sdkApiVersion >= ANIMATION_REDUCE_API) {
      try {
        accessibility.onAnimationReduceStateChange(this.onReduceChange);
        this.observingReduce = true;
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'animation reduce listener failed: %{public}s', JSON.stringify(e));
      }
    }
    for (const domain of YoinMotionPlugin.scaleDomains()) {
      try {
        const observing = settings.registerKeyObserver(this.context, settings.display.ANIMATOR_DURATION_SCALE, domain,
          (_: BusinessError) => {
            // Re-read either way: a failed read falls back to the default.
            this.push();
          });
        if (observing) {
          this.observedScaleDomains.push(domain);
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'animator scale observer failed (%{public}s): %{public}s', domain, JSON.stringify(e));
      }
    }
    try {
      const subscriber = commonEventManager.createSubscriberSync({
        events: [commonEventManager.Support.COMMON_EVENT_POWER_SAVE_MODE_CHANGED],
      });
      commonEventManager.subscribe(subscriber, (err: BusinessError) => {
        if (err) {
          hilog.warn(DOMAIN, TAG, 'power mode event failed: %{public}s', JSON.stringify(err));
          return;
        }
        this.push();
      });
      this.powerSubscriber = subscriber;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'power mode subscribe failed: %{public}s', JSON.stringify(e));
    }
  }

  private unwatch(): void {
    if (this.observingReduce) {
      this.observingReduce = false;
      try {
        accessibility.offAnimationReduceStateChange(this.onReduceChange);
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'animation reduce unlisten failed: %{public}s', JSON.stringify(e));
      }
    }
    for (const domain of this.observedScaleDomains) {
      try {
        settings.unregisterKeyObserver(this.context, settings.display.ANIMATOR_DURATION_SCALE, domain);
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'animator scale unobserve failed: %{public}s', JSON.stringify(e));
      }
    }
    this.observedScaleDomains = [];
    const subscriber = this.powerSubscriber;
    this.powerSubscriber = null;
    if (subscriber !== null) {
      try {
        commonEventManager.unsubscribe(subscriber);
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'power mode unsubscribe failed: %{public}s', JSON.stringify(e));
      }
    }
  }
}
