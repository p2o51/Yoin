import { webview } from '@kit.ArkWeb';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { AppleMusicAuthHost, AM_AUTH_ENDPOINT } from './AppleMusicAuthHost';

const DOMAIN = 0x5901;
const TAG = 'YoinAppleAuth';
const PAGE = 'applemusic_auth.html';

/**
 * The object the ArkWeb page talks to (registerJavaScriptProxy as
 * `yoinAuthBridge`). MusicKit JS calls [onToken] with the Music User Token and
 * storefront, [onError] when sign-in fails, [onReady] when the button is live.
 * Every method hands straight to [AppleMusicAuthHost]; no token is logged.
 */
class AuthBridge {
  onToken(musicUserToken: string, storefront: string): void {
    if (typeof musicUserToken === 'string' && musicUserToken.length > 0) {
      AppleMusicAuthHost.resolve(musicUserToken, typeof storefront === 'string' && storefront.length > 0 ? storefront : 'us');
    } else {
      AppleMusicAuthHost.reject('auth_failed', 'Sign-in returned no token');
    }
  }

  onError(code: string, _message: string): void {
    const mapped = code === 'page_failed' || code === 'developer_token' ? code : 'auth_failed';
    AppleMusicAuthHost.reject(mapped, 'Sign-in did not finish');
  }

  onReady(): void {
    hilog.info(DOMAIN, TAG, 'sign-in page ready');
  }
}

/**
 * The Apple Music sign-in surface: a full-screen ArkWeb page running MusicKit
 * JS v3. The developer token is fetched from the Worker (never put in the URL)
 * and injected with runJavaScript. `multiWindowAccess` + `onWindowNew` let
 * Apple's own sign-in window (the Apple ID page) render on top. Shown by the
 * Index page while AppStorage `yoinAmAuthActive` is true.
 */
@Component
export struct AppleMusicAuthView {
  private controller: webview.WebviewController = new webview.WebviewController();
  private readonly bridge: AuthBridge = new AuthBridge();
  @State private popupActive: boolean = false;
  @State private loading: boolean = true;
  // Pre-declared so its controller is attached before Apple's window.open fires.
  private popupController: webview.WebviewController = new webview.WebviewController();
  private developerToken: string = '';
  private pageLoaded: boolean = false;
  private injected: boolean = false;

  aboutToAppear(): void {
    const endpoint = AppStorage.get<string>(AM_AUTH_ENDPOINT) ?? '';
    if (endpoint.length === 0) {
      AppleMusicAuthHost.reject('developer_token', 'No token service configured');
      return;
    }
    AppleMusicAuthHost.fetchDeveloperToken(endpoint)
      .then((token: string) => {
        this.developerToken = token;
        this.injectIfReady();
      })
      .catch((e: BusinessError | Error) => {
        hilog.warn(DOMAIN, TAG, 'developer token failed');
        AppleMusicAuthHost.reject('developer_token', 'The developer-token service is unavailable');
      });
  }

  private injectIfReady(): void {
    if (this.injected || !this.pageLoaded || this.developerToken.length === 0) {
      return;
    }
    this.injected = true;
    this.loading = false;
    try {
      // The token goes in as a JSON string argument, never in the page URL.
      this.controller.runJavaScript(`window.__yoinStartAuth(${JSON.stringify(this.developerToken)})`);
    } catch (e) {
      AppleMusicAuthHost.reject('page_failed', 'Could not start sign-in');
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      // The MusicKit page.
      Web({ src: $rawfile(PAGE), controller: this.controller })
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .imageAccess(true)
        .mixedMode(MixedMode.None)
        .multiWindowAccess(true)
        .allowWindowOpenMethod(true)
        .width('100%')
        .height('100%')
        .zIndex(1)
        .onControllerAttached(() => {
          try {
            this.controller.registerJavaScriptProxy(this.bridge, 'yoinAuthBridge', ['onToken', 'onError', 'onReady']);
          } catch (e) {
            hilog.warn(DOMAIN, TAG, 'proxy registration failed');
          }
        })
        .onPageEnd(() => {
          this.pageLoaded = true;
          this.injectIfReady();
        })
        .onErrorReceive((event) => {
          // Only the main frame's own failure is fatal; sub-resource errors are ignored.
          if (event?.request?.isMainFrame()) {
            AppleMusicAuthHost.reject('page_failed', 'The sign-in page could not load');
          }
        })
        .onWindowNew((event) => {
          // Apple's sign-in window: hand it the pre-declared popup Web (its
          // controller is attached from the start). This ArkWeb build does not
          // auto-navigate the handed-over window, so load the target URL into it.
          const target = event.targetUrl ?? '';
          const scheme = target.indexOf('://') >= 0 ? target.substring(0, target.indexOf('://')) : target.substring(0, 8);
          hilog.info(DOMAIN, TAG, 'window new -> popup (scheme=%{public}s len=%{public}d)', scheme, target.length);
          this.popupActive = true;
          event.handler.setWebController(this.popupController);
          if (target.length > 0 && target !== 'about:blank' && (target.startsWith('https://') || target.startsWith('http://'))) {
            try {
              this.popupController.loadUrl(target);
            } catch (e) {
              hilog.warn(DOMAIN, TAG, 'popup loadUrl failed');
            }
          }
        })

      // Apple ID sign-in window. Always attached and rendered (never Hidden, so
      // the controller is fully ready when Apple's window.open fires); kept
      // behind the MusicKit page by zIndex until a popup opens, then raised.
      Web({ src: '', controller: this.popupController })
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .imageAccess(true)
        .multiWindowAccess(true)
        .allowWindowOpenMethod(true)
        .width('100%')
        .height('100%')
        .zIndex(this.popupActive ? 2 : 0)
        .onControllerAttached(() => hilog.info(DOMAIN, TAG, 'popup web ready'))
        .onPageEnd(() => hilog.info(DOMAIN, TAG, 'popup page loaded'))
        .onErrorReceive((event) => {
          if (event?.request?.isMainFrame()) {
            hilog.warn(DOMAIN, TAG, 'popup load error: %{public}d', event?.error?.getErrorCode() ?? -1);
          }
        })
        .onWindowExit(() => { this.popupActive = false; })

      if (this.loading && !this.popupActive) {
        Column() {
          LoadingProgress().width(44).height(44)
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .hitTestBehavior(HitTestMode.Transparent)
      }

      // Close affordance: back out of sign-in.
      Button({ type: ButtonType.Circle, stateEffect: true }) {
        Text('✕').fontSize(20).fontColor(Color.White)
      }
      .backgroundColor('#99000000')
      .width(40)
      .height(40)
      .zIndex(3)
      .margin({ top: 48, left: 16 })
      .onClick(() => AppleMusicAuthHost.cancel())
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.White)
  }
}
