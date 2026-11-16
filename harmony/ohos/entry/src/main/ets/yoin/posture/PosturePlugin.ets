import { Any, FlutterPlugin, FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { display, window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/posture';
const TAG = 'YoinPosture';
const DOMAIN = 0x5906;

/**
 * The window's posture for Dart's `ChannelDisplayPostureHost` (lib/platform/posture/display_posture.dart): what
 * Android reads from Jetpack WindowManager's FoldingFeature and WindowInsets.displayCutout
 * (ui/experience/WindowAdaptiveRuntime.kt). Flutter's displayFeatures can't stand in: the embedder leaves folds out
 * (its crease reading is marked unreliable) and builds its cutouts once, in display coordinates.
 *
 * `read` → {foldStatus, foldDisplayMode, creaseRects: [{left, top, width, height}], leftCutout?, rightCutout?}, every
 * rect in the main window's vp (logical px):
 *  - foldStatus / foldDisplayMode: `display.getFoldStatus()` / `getFoldDisplayMode()` codes (0 on a slab);
 *  - creaseRects: `display.getCurrentFoldCreaseRegion()`, moved from display px into the window;
 *  - left / rightCutout: the window's `TYPE_CUTOUT` avoid area on its side edges (the camera band a landscape phone
 *    keeps its Button Group in).
 * The same map is pushed as `posture` on a fold status or display mode change, a display change (rotation), a
 * window size change and an avoid-area change. No permission.
 */
export class YoinPostureHost {
  private static win: window.Window | null = null;
  private static listeners: Array<() => void> = [];
  private static watching = false;

  /** The main window, once its content is up (EntryAbility). */
  static attach(win: window.Window): void {
    YoinPostureHost.win = win;
    try {
      win.on('windowSizeChange', () => YoinPostureHost.notify());
      win.on('avoidAreaChange', (options: window.AvoidAreaOptions) => {
        if (options.type === window.AvoidAreaType.TYPE_CUTOUT) {
          YoinPostureHost.notify();
        }
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'window listeners failed: %{public}s', JSON.stringify(e));
    }
    YoinPostureHost.watchDisplay();
    YoinPostureHost.notify();
  }

  private static watchDisplay(): void {
    if (YoinPostureHost.watching) {
      return;
    }
    YoinPostureHost.watching = true;
    try {
      display.on('change', () => YoinPostureHost.notify());
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'display change listener failed: %{public}s', JSON.stringify(e));
    }
    if (!YoinPostureHost.foldable()) {
      return;
    }
    try {
      display.on('foldStatusChange', () => YoinPostureHost.notify());
      display.on('foldDisplayModeChange', () => YoinPostureHost.notify());
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'fold listeners failed: %{public}s', JSON.stringify(e));
    }
  }

  static onChange(listener: () => void): void {
    YoinPostureHost.listeners.push(listener);
  }

  /** A detached engine stops listening (a re-attached one adds its own). */
  static offChange(listener: () => void): void {
    YoinPostureHost.listeners = YoinPostureHost.listeners.filter((l) => l !== listener);
  }

  private static notify(): void {
    YoinPostureHost.listeners.forEach((l) => l());
  }

  private static foldable(): boolean {
    try {
      return display.isFoldable();
    } catch (e) {
      return false;
    }
  }

  private static density(): number {
    try {
      const d = display.getDefaultDisplaySync().densityPixels;
      return d > 0 ? d : 1;
    } catch (e) {
      return 1;
    }
  }

  private static rectVp(left: number, top: number, width: number, height: number, density: number):
    Record<string, number> {
    return { 'left': left / density, 'top': top / density, 'width': width / density, 'height': height / density };
  }

  static read(): Record<string, Any> {
    const density = YoinPostureHost.density();
    const none: Array<Record<string, number>> = [];
    const result: Record<string, Any> = { 'foldStatus': 0, 'foldDisplayMode': 0, 'creaseRects': none };
    const win = YoinPostureHost.win;
    // The window's origin on the display, for the crease (display px) → window conversion.
    let originX = 0;
    let originY = 0;
    if (win !== null) {
      try {
        const rect = win.getWindowProperties().windowRect;
        originX = rect.left;
        originY = rect.top;
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'window rect unavailable: %{public}s', JSON.stringify(e));
      }
    }
    if (YoinPostureHost.foldable()) {
      try {
        const status: number = display.getFoldStatus();
        const mode: number = display.getFoldDisplayMode();
        result['foldStatus'] = status;
        result['foldDisplayMode'] = mode;
        const region = display.getCurrentFoldCreaseRegion();
        const creases: Array<Record<string, number>> = [];
        for (const r of region.creaseRects) {
          if (r.width > 0 && r.height > 0) {
            creases.push(YoinPostureHost.rectVp(r.left - originX, r.top - originY, r.width, r.height, density));
          }
        }
        result['creaseRects'] = creases;
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'fold reading failed: %{public}s', JSON.stringify(e));
      }
    }
    if (win !== null) {
      try {
        const area = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_CUTOUT);
        const l = area.leftRect;
        if (l.width > 0 && l.height > 0) {
          result['leftCutout'] = YoinPostureHost.rectVp(l.left, l.top, l.width, l.height, density);
        }
        const r = area.rightRect;
        if (r.width > 0 && r.height > 0) {
          result['rightCutout'] = YoinPostureHost.rectVp(r.left, r.top, r.width, r.height, density);
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'cutout avoid area unavailable: %{public}s', JSON.stringify(e));
      }
    }
    return result;
  }
}

export default class YoinPosturePlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private readonly push: () => void = () => this.channel?.invokeMethod('posture', YoinPostureHost.read());

  getUniqueClassName(): string {
    return 'YoinPosturePlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
    YoinPostureHost.onChange(this.push);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    YoinPostureHost.offChange(this.push);
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method === 'read') {
      result.success(YoinPostureHost.read());
    } else {
      result.notImplemented();
    }
  }
}
