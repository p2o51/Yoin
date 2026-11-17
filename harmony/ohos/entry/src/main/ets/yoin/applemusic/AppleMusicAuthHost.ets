import { MethodResult } from '@ohos/flutter_ohos';
import { http } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';

const DOMAIN = 0x5901;
const TAG = 'YoinAppleAuth';

/** Whether the ArkWeb sign-in overlay is showing (Index observes this). */
export const AM_AUTH_ACTIVE = 'yoinAmAuthActive';
/** The developer-token service URL the overlay fetches the token from. */
export const AM_AUTH_ENDPOINT = 'yoinAmAuthEndpoint';

/** The result of a sign-in that the Web page proxy reports. */
export interface AppleAuthToken {
  musicUserToken: string;
  storefront: string;
}

/**
 * Owns the one in-flight Apple Music sign-in: the Dart `MethodResult` to answer,
 * and the AppStorage flags that show the ArkWeb overlay (served by the Index
 * page). The overlay and its Web proxy call [resolve]/[reject] when MusicKit JS
 * returns a Music User Token, is cancelled, or the page fails.
 *
 * No token (developer token or Music User Token) is ever logged in full: the
 * developer token is masked (first 6 chars + length), the user token never
 * printed.
 */
export class AppleMusicAuthHost {
  private static pending: MethodResult | null = null;

  /** Starts a sign-in for [endpoint], showing the overlay. One at a time. */
  static start(endpoint: string, result: MethodResult): void {
    if (AppleMusicAuthHost.pending !== null) {
      result.error('busy', 'A sign-in is already in progress', null);
      return;
    }
    AppleMusicAuthHost.pending = result;
    AppStorage.setOrCreate(AM_AUTH_ENDPOINT, endpoint);
    AppStorage.setOrCreate(AM_AUTH_ACTIVE, true);
    hilog.info(DOMAIN, TAG, 'sign-in started');
  }

  /** The user signed in: hand the token back to Dart and hide the overlay. */
  static resolve(musicUserToken: string, storefront: string): void {
    const result = AppleMusicAuthHost.finish();
    if (result === null) {
      return;
    }
    const reply: Record<string, string> = { 'musicUserToken': musicUserToken, 'storefront': storefront };
    hilog.info(DOMAIN, TAG, 'sign-in succeeded (storefront=%{public}s)', storefront);
    result.success(reply);
  }

  /**
   * The sign-in did not produce a token. [code] is one of the Dart side's
   * failure codes (`cancelled`, `auth_failed`, `page_failed`, `developer_token`).
   * [message] is kept short and never carries a token.
   */
  static reject(code: string, message: string): void {
    const result = AppleMusicAuthHost.finish();
    if (result === null) {
      return;
    }
    hilog.info(DOMAIN, TAG, 'sign-in failed: %{public}s', code);
    result.error(code, message, null);
  }

  /** The user backed out of the overlay. */
  static cancel(): void {
    AppleMusicAuthHost.reject('cancelled', 'Sign-in was cancelled');
  }

  /** The Flutter engine detached mid-flight: nothing can answer the overlay. */
  static detach(): void {
    const result = AppleMusicAuthHost.finish();
    result?.error('unavailable', 'The app view went away', null);
  }

  private static finish(): MethodResult | null {
    const result = AppleMusicAuthHost.pending;
    AppleMusicAuthHost.pending = null;
    AppStorage.setOrCreate(AM_AUTH_ACTIVE, false);
    AppStorage.setOrCreate(AM_AUTH_ENDPOINT, '');
    return result;
  }

  /**
   * Fetches a developer token from [endpoint] (the Worker), the same service
   * Dart's AppleMusicDeveloperTokenProvider uses. Returns the raw JWT; throws
   * on a bad response. Only a masked form is ever logged.
   */
  static async fetchDeveloperToken(endpoint: string): Promise<string> {
    const request = http.createHttp();
    try {
      const response = await request.request(endpoint, {
        method: http.RequestMethod.GET,
        header: { 'Accept': 'application/json' } as Record<string, string>,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 20000,
        readTimeout: 20000,
      });
      if (response.responseCode !== 200) {
        throw new Error(`developer token service HTTP ${response.responseCode}`);
      }
      const body = response.result;
      if (typeof body !== 'string') {
        throw new Error('developer token service returned non-text');
      }
      const parsed = JSON.parse(body as string) as Record<string, string>;
      const token = parsed.developerToken;
      if (typeof token !== 'string' || token.length === 0 || token.split('.').length !== 3) {
        throw new Error('developer token service returned no usable token');
      }
      hilog.info(DOMAIN, TAG, 'developer token fetched: %{public}s', mask(token));
      return token;
    } catch (e) {
      const err = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'developer token fetch failed: %{public}d', err.code ?? -1);
      throw new Error('developer token unavailable');
    } finally {
      request.destroy();
    }
  }
}

/** First 6 characters and the length only; never the whole token. */
function mask(token: string): string {
  return `${token.substring(0, 6)}…(${token.length})`;
}
