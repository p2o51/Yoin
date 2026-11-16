import { Any, FlutterPlugin, FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { accessibility } from '@kit.AccessibilityKit';
import { BusinessError, deviceInfo, settings } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/motion';
const TAG = 'YoinMotion';
const DOMAIN = 0x5906;

/** accessibility.isAnimationReduceEnabledSync / onAnimationReduceStateChange arrived in API 23. */
const ANIMATION_REDUCE_API = 23;

/**
 * The system's "less motion" settings for Dart's `ChannelSystemMotionHost` (lib/platform/motion/system_motion.dart),
 * which folds them into the app root's `disableAnimations`: Android follows the system's "Remove animations" (an
 * animator duration scale of 0) while Yoin runs (ui/experience/MotionRuntime.kt, a ContentObserver on
 * Settings.Global.ANIMATOR_DURATION_SCALE). The flutter_ohos embedder forwards neither of these to Flutter.
 *
 * `read` → {animationReduce, animatorDurationScale}:
 *  - animationReduce: Accessibility › 减弱动画, `accessibility.isAnimationReduceEnabledSync()` (API 23+; false below);
 *  - animatorDurationScale: Developer options' animator duration scale, `settings.display.ANIMATOR_DURATION_SCALE`
 *    (1 when unset or unreadable; 0 = animations off).
 * The same map is pushed as `motion` whenever either changes (`onAnimationReduceStateChange`,
 * `settings.registerKeyObserver`). No permission.
 */
export default class YoinMotionPlugin implements FlutterPlugin, MethodCallHandler {
  private readonly context: common.UIAbilityContext;
  private channel: MethodChannel | null = null;
  private observingScale = false;
  private observingReduce = false;

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

  private animatorDurationScale(): number {
    try {
      const raw = settings.getValueSync(this.context, settings.display.ANIMATOR_DURATION_SCALE, '1',
        settings.domainName.DEVICE_SHARED);
      const scale = Number.parseFloat(raw);
      return Number.isFinite(scale) && scale >= 0 ? scale : 1;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'animator scale read failed: %{public}s', JSON.stringify(e));
      return 1;
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
    try {
      this.observingScale = settings.registerKeyObserver(this.context, settings.display.ANIMATOR_DURATION_SCALE,
        settings.domainName.DEVICE_SHARED, (_: BusinessError) => {
          // Re-read either way: a failed read falls back to the default.
          this.push();
        });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'animator scale observer failed: %{public}s', JSON.stringify(e));
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
    if (this.observingScale) {
      this.observingScale = false;
      try {
        settings.unregisterKeyObserver(this.context, settings.display.ANIMATOR_DURATION_SCALE,
          settings.domainName.DEVICE_SHARED);
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'animator scale unobserve failed: %{public}s', JSON.stringify(e));
      }
    }
  }
}
