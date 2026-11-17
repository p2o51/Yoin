import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { AppleMusicAuthHost } from './AppleMusicAuthHost';

const CHANNEL = 'yoin/applemusic_auth';

/**
 * Serves `yoin/applemusic_auth` for Dart's `ChannelAppleMusicAuth`. The one
 * method, `authorize(endpoint)`, hands off to [AppleMusicAuthHost], which shows
 * the ArkWeb sign-in overlay (served by the Index page) and answers this call's
 * `MethodResult` once MusicKit JS returns a Music User Token — or an error code
 * on cancel, refusal or a page failure. No token crosses this channel inbound,
 * and the reply carries only the Music User Token and storefront.
 */
export default class YoinAppleMusicAuthPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;

  getUniqueClassName(): string {
    return 'YoinAppleMusicAuthPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
    // Nothing can drive the overlay once the engine is gone.
    AppleMusicAuthHost.detach();
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method !== 'authorize') {
      result.notImplemented();
      return;
    }
    const endpoint: Any = call.argument('endpoint');
    if (typeof endpoint !== 'string' || (endpoint as string).length === 0) {
      result.error('bad_args', 'authorize needs an endpoint', null);
      return;
    }
    AppleMusicAuthHost.start(endpoint as string, result);
  }
}
