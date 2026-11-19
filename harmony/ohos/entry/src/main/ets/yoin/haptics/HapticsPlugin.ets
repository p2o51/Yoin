import { Any, FlutterPlugin, FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { vibrator } from '@kit.SensorServiceKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/haptics';
const TAG = 'YoinHaptics';
const DOMAIN = 0x5906;

/** One named haptic: the first preset this motor supports, at its intensity (0–100). */
interface Preset {
  ids: string[];
  intensity: number;
}

/**
 * Android `YoinHaptics.performX` → `@ohos.vibrator` presets. Each lists its closest HarmonyOS effect first and a
 * plainer one after it; `haptic.clock.timer` (API 8) is on every motor. Usage `touch`, so the system's touch
 * feedback switch mutes them like Android's View haptics.
 */
const PRESETS: Map<string, Preset> = new Map<string, Preset>([
  // KEYBOARD_TAP: a light, soft tap
  ['click', { ids: ['haptic.effect.soft', 'haptic.clock.timer'], intensity: 70 } as Preset],
  // CLOCK_TICK
  ['tick', { ids: ['haptic.clock.timer'], intensity: 100 } as Preset],
  // TEXT_HANDLE_MOVE: the faintest tick
  ['lightTick', { ids: ['haptic.clock.timer'], intensity: 45 } as Preset],
  // CONFIRM
  ['confirm', { ids: ['haptic.notice.success', 'haptic.effect.hard', 'haptic.clock.timer'], intensity: 100 } as Preset],
  // REJECT
  ['reject', { ids: ['haptic.notice.fail', 'haptic.effect.sharp', 'haptic.clock.timer'], intensity: 100 } as Preset],
  // LONG_PRESS
  ['longPress', { ids: ['haptic.effect.hard', 'haptic.clock.timer'], intensity: 100 } as Preset],
  // CONTEXT_CLICK: a firm click
  ['contextClick', { ids: ['haptic.effect.sharp', 'haptic.clock.timer'], intensity: 85 } as Preset],
  // DRAG_START: picking something up
  ['dragStart', { ids: ['haptic.effect.hard', 'haptic.clock.timer'], intensity: 80 } as Preset],
  // SEGMENT_TICK: a dragged item crossing into the next slot
  ['segmentTick', { ids: ['haptic.clock.timer'], intensity: 80 } as Preset],
  // SEGMENT_FREQUENT_TICK: many small things in quick succession
  ['frequentTick', { ids: ['haptic.clock.timer'], intensity: 35 } as Preset],
  // GESTURE_THRESHOLD_ACTIVATE: a drag passing a limit
  ['threshold', { ids: ['haptic.effect.sharp', 'haptic.clock.timer'], intensity: 70 } as Preset],
  // TOGGLE_ON / TOGGLE_OFF: the on side firmer than the off side
  ['toggleOn', { ids: ['haptic.effect.sharp', 'haptic.clock.timer'], intensity: 90 } as Preset],
  ['toggleOff', { ids: ['haptic.effect.soft', 'haptic.clock.timer'], intensity: 60 } as Preset],
]);

/**
 * Serves `yoin/haptics` for Dart's `ChannelHapticsHost` (lib/platform/haptics/haptics_host.dart):
 *  - `capabilities` → {patterns}: whether the motor plays custom patterns (HD haptics);
 *  - `perform` {name}: one named haptic as a preset;
 *  - `pattern` {pulses: [{at, intensity, frequency, duration, rise}]}: one custom `VibratorPattern` (the groove
 *    award's beats, Android's `VibrationEffect.Composition`);
 *  - `cancel`: stops whatever is vibrating.
 * Needs ohos.permission.VIBRATE (module.json5). Every call answers at once; a refused vibration is only logged.
 */
export default class YoinHapticsPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;

  /** Resolved effect id per name (null = none supported), read once. */
  private resolved: Map<string, string | null> = new Map();

  getUniqueClassName(): string {
    return 'YoinHapticsPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'capabilities': {
        const caps: Map<string, Any> = new Map();
        caps.set('patterns', YoinHapticsPlugin.hdHaptics());
        result.success(caps);
        break;
      }
      case 'perform': {
        const name: Any = call.argument('name');
        if (typeof name === 'string') {
          this.perform(name as string);
        }
        result.success(null);
        break;
      }
      case 'pattern':
        this.pattern(call.argument('pulses'));
        result.success(null);
        break;
      case 'cancel':
        vibrator.stopVibration().catch((e: BusinessError) => {
          hilog.warn(DOMAIN, TAG, 'stop failed: %{public}d', e.code);
        });
        result.success(null);
        break;
      default:
        result.notImplemented();
    }
  }

  private static hdHaptics(): boolean {
    try {
      return vibrator.isHdHapticSupported();
    } catch (e) {
      return false;
    }
  }

  private effectFor(name: string): string | null {
    if (this.resolved.has(name)) {
      return this.resolved.get(name) ?? null;
    }
    const preset = PRESETS.get(name);
    let id: string | null = null;
    if (preset !== undefined) {
      for (const candidate of preset.ids) {
        try {
          if (vibrator.isSupportEffectSync(candidate)) {
            id = candidate;
            break;
          }
        } catch (e) {
          // an unknown id on this motor: try the next one
        }
      }
    }
    this.resolved.set(name, id);
    return id;
  }

  private perform(name: string): void {
    const id = this.effectFor(name);
    const preset = PRESETS.get(name);
    if (id === null || preset === undefined) {
      return;
    }
    const effect: vibrator.VibratePreset = {
      type: 'preset',
      effectId: id,
      count: 1,
      intensity: preset.intensity,
    };
    const attribute: vibrator.VibrateAttribute = { id: 0, usage: 'touch' };
    vibrator.startVibration(effect, attribute).catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'preset %{public}s failed: %{public}d', id, e.code);
    });
  }

  /** The beat plan as one pattern: transients for the ticks, continuous events for spins and rises. */
  private pattern(value: Any): void {
    if (!(value instanceof Array)) {
      return;
    }
    const builder = new vibrator.VibratorPatternBuilder();
    let events = 0;
    const pulses: Array<Any> = value as Array<Any>;
    pulses.forEach((entry: Any) => {
      if (!(entry instanceof Map)) {
        return;
      }
      const map: Map<Any, Any> = entry as Map<Any, Any>;
      const at = clamp(readNumber(map, 'at', 0), 0, 1800000);
      const intensity = clamp(readNumber(map, 'intensity', 100), 0, 100);
      const frequency = clamp(readNumber(map, 'frequency', 50), 0, 100);
      const duration = readNumber(map, 'duration', 0);
      try {
        if (duration > 0) {
          const clampedDuration = clamp(Math.round(duration), 1, 5000);
          const param: vibrator.ContinuousParam = { intensity: intensity, frequency: frequency };
          if (map.get('rise') === true && clampedDuration >= 3) {
            // HarmonyOS VibratorPatternBuilder requires 4–16 curve points; swell from 0 to full over the event.
            param.points = riseCurvePoints(clampedDuration);
          }
          builder.addContinuousEvent(at, clampedDuration, param);
        } else {
          const param: vibrator.TransientParam = { intensity: intensity, frequency: frequency };
          builder.addTransientEvent(at, param);
        }
        events++;
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'pattern event dropped: %{public}s', JSON.stringify(e));
      }
    });
    if (events === 0) {
      return;
    }
    try {
      const effect: vibrator.VibrateFromPattern = { type: 'pattern', pattern: builder.build() };
      const attribute: vibrator.VibrateAttribute = { id: 0, usage: 'touch' };
      vibrator.startVibration(effect, attribute).catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'pattern failed: %{public}d', e.code);
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'pattern refused: %{public}s', JSON.stringify(e));
    }
  }
}

function readNumber(map: Map<Any, Any>, key: string, fallback: number): number {
  const v: Any = map.get(key);
  return typeof v === 'number' ? v as number : fallback;
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}

/** 4-point monotonic swell curve required by HarmonyOS `VibratorPatternBuilder.addContinuousEvent` (4–16 points). */
export function riseCurvePoints(durationMs: number): vibrator.VibratorCurvePoint[] {
  return [
    { time: 0, intensity: 0 },
    { time: Math.round(durationMs / 3), intensity: 0.33 },
    { time: Math.round((durationMs * 2) / 3), intensity: 0.67 },
    { time: durationMs, intensity: 1 },
  ];
}
