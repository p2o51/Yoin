import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { promptAction, window } from '@kit.ArkUI';
import { deviceInfo } from '@kit.BasicServicesKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/window';
const TAG = 'YoinWindow';
const DOMAIN = 0x5906;

/** How long a system toast stays (Android Toast.LENGTH_SHORT). */
const TOAST_MS = 2000;

/**
 * The window's caption on 2in1 / free windows: the system title bar is hidden so the app's own ground runs to the
 * window's top edge, and only the system's three buttons stay, floating at the top right. Dart gets the caption
 * (`caption` → {active, height, buttonsLeft, buttonsWidth} in vp) to keep its content clear of the buttons and to
 * turn that strip into the window's drag handle (`startMoving`, and `toggleMaximize` on a double tap). A change of
 * the button area is pushed as `captionChanged`.
 */
export class YoinWindowHost {
  private static win: window.Window | null = null;
  private static active = false;
  private static listeners: Array<() => void> = [];

  /** Called once the main window's content is loaded (EntryAbility). */
  static attach(win: window.Window, tries: number = 0): void {
    YoinWindowHost.win = win;
    if (deviceInfo.deviceType !== '2in1') {
      return;
    }
    try {
      win.setWindowDecorVisible(false);
      YoinWindowHost.active = true;
      win.on('windowTitleButtonRectChange', () => YoinWindowHost.notify());
      win.on('windowStatusChange', () => YoinWindowHost.notify());
      hilog.info(DOMAIN, TAG, 'caption: title bar hidden, buttons kept');
    } catch (e) {
      YoinWindowHost.active = false;
      // The content may not be up yet when the window first shows (1300002): try again shortly.
      if (tries < 8) {
        setTimeout(() => YoinWindowHost.attach(win, tries + 1), 250);
        return;
      }
      hilog.warn(DOMAIN, TAG, 'caption setup failed: %{public}s', JSON.stringify(e));
    }
    YoinWindowHost.notify();
  }

  static onChange(listener: () => void): void {
    YoinWindowHost.listeners.push(listener);
  }

  private static notify(): void {
    YoinWindowHost.listeners.forEach((l) => l());
  }

  static caption(): Record<string, Any> {
    const win = YoinWindowHost.win;
    if (!YoinWindowHost.active || win === null) {
      return { 'active': false };
    }
    try {
      const rect = win.getTitleButtonRect();
      const fullScreen = win.getWindowStatus() === window.WindowStatusType.FULL_SCREEN;
      // getTitleButtonRect: `right` is the margin from the window's right edge, `top` from its top (vp).
      return {
        'active': !fullScreen,
        'height': Math.max(win.getWindowDecorHeight(), rect.top * 2 + rect.height),
        'buttonsRight': rect.right,
        'buttonsWidth': rect.width,
      };
    } catch (e) {
      return { 'active': true, 'height': 37, 'buttonsRight': 0, 'buttonsWidth': 160 };
    }
  }

  /** Now Playing holds the screen on while it plays (Android View.keepScreenOn). */
  static setKeepScreenOn(on: boolean): void {
    const win = YoinWindowHost.win;
    if (win === null) {
      return;
    }
    win.setWindowKeepScreenOn(on).catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'setWindowKeepScreenOn failed: %{public}d', e.code);
    });
  }

  /**
   * A system toast for news the app's own snackbar can't show (the app is behind the lock screen or 播控中心);
   * top-most so it sits above other windows.
   */
  static showToast(message: string): void {
    const win = YoinWindowHost.win;
    if (win === null || message.length === 0) {
      return;
    }
    try {
      win.getUIContext().getPromptAction().showToast({
        message: message,
        duration: TOAST_MS,
        showMode: promptAction.ToastShowMode.TOP_MOST
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'showToast failed: %{public}s', JSON.stringify(e));
    }
  }

  static startMoving(): void {
    YoinWindowHost.win?.startMoving().catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'startMoving failed: %{public}d', e.code);
    });
  }

  static toggleMaximize(): void {
    const win = YoinWindowHost.win;
    if (win === null) {
      return;
    }
    try {
      const status = win.getWindowStatus();
      if (status === window.WindowStatusType.MAXIMIZE || status === window.WindowStatusType.FULL_SCREEN) {
        win.recover().catch((e: BusinessError) => hilog.warn(DOMAIN, TAG, 'recover failed: %{public}d', e.code));
      } else {
        win.maximize().catch((e: BusinessError) => hilog.warn(DOMAIN, TAG, 'maximize failed: %{public}d', e.code));
      }
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'toggleMaximize failed: %{public}s', JSON.stringify(e));
    }
  }
}

export default class YoinWindowPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;

  getUniqueClassName(): string {
    return 'YoinWindowPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
    YoinWindowHost.onChange(() => this.channel?.invokeMethod('captionChanged', YoinWindowHost.caption()));
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'caption':
        result.success(YoinWindowHost.caption());
        return;
      case 'startMoving':
        YoinWindowHost.startMoving();
        result.success(null);
        return;
      case 'toggleMaximize':
        YoinWindowHost.toggleMaximize();
        result.success(null);
        return;
      case 'setKeepScreenOn': {
        const on: Any = call.argument('on');
        YoinWindowHost.setKeepScreenOn(on === true);
        result.success(null);
        return;
      }
      case 'showToast': {
        const message: Any = call.argument('message');
        YoinWindowHost.showToast(typeof message === 'string' ? message as string : '');
        result.success(null);
        return;
      }
      default:
        result.notImplemented();
    }
  }
}
