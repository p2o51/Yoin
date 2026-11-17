import { webview } from '@kit.ArkWeb';
import { BuilderNode, FrameNode, NodeController, UIContext } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { AppleMusicAuthHost, AM_AUTH_ENDPOINT } from './AppleMusicAuthHost';

const DOMAIN = 0x5901;
const TAG = 'YoinAppleAuth';
const PAGE = 'applemusic_auth.html';

/**
 * The https address the sign-in page is served under. Nothing is fetched from
 * it: [AppleMusicAuthView] intercepts this exact URL and answers with the
 * rawfile page. The page needs a real https origin because Apple's consent page
 * talks back to its opener with `postMessage(…, <opener origin>)`, and a
 * `resource://rawfile` page has the opaque origin `null`, which no message can
 * target (the "Allow" button then never enables). Yoin's own site, so Apple's
 * referrer names the app.
 */
const AUTH_URL = 'https://yoin.gojyuplus.com/applemusic/signin';

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

/** Scheme and host of [url] only — never its path or query (Apple puts tokens there). */
function hostOf(url: string): string {
  const start = url.indexOf('://');
  if (start < 0) {
    return url.substring(0, 12);
  }
  const rest = url.substring(start + 3);
  const end = rest.search(/[/?#]/);
  return `${url.substring(0, start)}://${end < 0 ? rest : rest.substring(0, end)}`;
}

/** What the popup's builder needs: its own controller and a way to close. */
class PopupParams {
  controller: webview.WebviewController;
  onExit: () => void;

  constructor(controller: webview.WebviewController, onExit: () => void) {
    this.controller = controller;
    this.onExit = onExit;
  }
}

/**
 * Apple's sign-in window (the Apple ID page, then the consent page). Built
 * imperatively in `onWindowNew` with a controller nobody has attached yet, so
 * the engine can bind the window it already created for `window.open` to it —
 * navigation and `window.opener` both come from that window. No `loadUrl`:
 * loading the URL ourselves makes a fresh navigation with no opener.
 */
@Builder
function popupWebBuilder(params: PopupParams) {
  Column() {
    Web({ src: '', controller: params.controller })
      .javaScriptAccess(true)
      .domStorageAccess(true)
      .imageAccess(true)
      .mixedMode(MixedMode.None)
      .width('100%')
      .height('100%')
      .onPageBegin((event) => {
        hilog.info(DOMAIN, TAG, 'popup page begin: %{public}s', hostOf(event?.url ?? ''));
      })
      .onPageEnd((event) => {
        hilog.info(DOMAIN, TAG, 'popup page end: %{public}s', hostOf(event?.url ?? ''));
        // Diagnostics only: does the window still know its opener?
        try {
          params.controller.runJavaScript('String(!!window.opener)')
            .then((hasOpener: string) => hilog.info(DOMAIN, TAG, 'popup opener: %{public}s', hasOpener))
            .catch(() => hilog.info(DOMAIN, TAG, 'popup opener: unknown'));
        } catch (e) {
          hilog.info(DOMAIN, TAG, 'popup opener: unknown');
        }
      })
      .onErrorReceive((event) => {
        if (event?.request?.isMainFrame()) {
          hilog.warn(DOMAIN, TAG, 'popup load error: %{public}d', event?.error?.getErrorCode() ?? -1);
        }
      })
      .onWindowExit(() => {
        hilog.info(DOMAIN, TAG, 'popup closed by page');
        params.onExit();
      })
  }
  .width('100%')
  .height('100%')
  .backgroundColor(Color.White)
}

/** Hosts one already-built popup node in a NodeContainer. */
class PopupNodeController extends NodeController {
  private readonly node: BuilderNode<[PopupParams]>;

  constructor(node: BuilderNode<[PopupParams]>) {
    super();
    this.node = node;
  }

  makeNode(_uiContext: UIContext): FrameNode | null {
    return this.node.getFrameNode();
  }

  dispose(): void {
    this.node.dispose();
  }
}

/**
 * The Apple Music sign-in surface: a full-screen ArkWeb page running MusicKit
 * JS v3, served under [AUTH_URL]. The developer token is fetched from the
 * Worker (never put in the URL) and injected with runJavaScript.
 * `multiWindowAccess` + `onWindowNew` let Apple's own sign-in window render on
 * top. Shown by the Index page while AppStorage `yoinAmAuthActive` is true.
 */
@Component
export struct AppleMusicAuthView {
  private controller: webview.WebviewController = new webview.WebviewController();
  private readonly bridge: AuthBridge = new AuthBridge();
  @State private loading: boolean = true;
  @State private popup: PopupNodeController | null = null;
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

  aboutToDisappear(): void {
    this.closePopup();
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

  private openPopup(event: OnWindowNewEvent): void {
    // One sign-in window at a time; a second window.open replaces the first.
    this.closePopup();
    hilog.info(DOMAIN, TAG, 'window new -> popup (%{public}s)', hostOf(event.targetUrl ?? ''));
    const popupController = new webview.WebviewController();
    // Order matters. ArkWeb marks a Web as this window's popup when the Web is
    // constructed, by looking its controller up among the ones handed to
    // setWebController. So: hand the fresh controller over first (synchronously,
    // or the render process blocks), then build the Web around it. Building
    // first gives an ordinary blank Web; an already-attached controller is
    // refused outright.
    event.handler.setWebController(popupController);
    const node = new BuilderNode<[PopupParams]>(this.getUIContext());
    node.build(wrapBuilder(popupWebBuilder), new PopupParams(popupController, () => this.closePopup()));
    this.popup = new PopupNodeController(node);
  }

  private closePopup(): void {
    const popup = this.popup;
    if (popup === null) {
      return;
    }
    this.popup = null;
    popup.dispose();
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      // The MusicKit page.
      Web({ src: AUTH_URL, controller: this.controller })
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .imageAccess(true)
        .mixedMode(MixedMode.None)
        .multiWindowAccess(true)
        .allowWindowOpenMethod(true)
        .width('100%')
        .height('100%')
        .onControllerAttached(() => {
          try {
            this.controller.registerJavaScriptProxy(this.bridge, 'yoinAuthBridge', ['onToken', 'onError', 'onReady']);
          } catch (e) {
            hilog.warn(DOMAIN, TAG, 'proxy registration failed');
          }
        })
        .onInterceptRequest((event) => {
          // Serve the rawfile page under AUTH_URL (exact match, main frame only).
          const request = event?.request;
          if (request === undefined || !request.isMainFrame() || request.getRequestUrl() !== AUTH_URL) {
            return null;
          }
          const response = new WebResourceResponse();
          response.setResponseData($rawfile(PAGE));
          response.setResponseMimeType('text/html');
          response.setResponseEncoding('utf-8');
          response.setResponseCode(200);
          response.setReasonMessage('OK');
          response.setResponseIsReady(true);
          return response;
        })
        .onPageEnd(() => {
          this.pageLoaded = true;
          this.injectIfReady();
        })
        .onErrorReceive((event) => {
          // Only the main frame's own failure is fatal; sub-resource errors are ignored.
          if (event?.request?.isMainFrame()) {
            hilog.warn(DOMAIN, TAG, 'page load error: %{public}d', event?.error?.getErrorCode() ?? -1);
            AppleMusicAuthHost.reject('page_failed', 'The sign-in page could not load');
          }
        })
        .onWindowNew((event) => this.openPopup(event))

      if (this.popup !== null) {
        NodeContainer(this.popup)
          .width('100%')
          .height('100%')
      }

      if (this.loading && this.popup === null) {
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
      .margin({ top: 48, left: 16 })
      .onClick(() => AppleMusicAuthHost.cancel())
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.White)
  }
}
