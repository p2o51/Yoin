import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { CastBridge } from './CastBridge';

const CHANNEL = 'yoin/cast';

/**
 * Serves `yoin/cast` for Dart's `ChannelCastHost` (lib/platform/cast/):
 * - `openPicker`: the system's output / cast panel (AVCastPickerHelper). Answers whether it opened.
 * - `currentOutput`: the session's output device as last reported, or null.
 * - `castLoad` {id, title, artist, album, artworkUri, uri, durationMs}, `castCommand` {command, value},
 *   `castState`: the cast device as a renderer, while the output is remote.
 * Native pushes `outputChanged(map)` whenever the session's output device changes.
 */
export default class YoinCastPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinCastPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    const channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    channel.setMethodCallHandler(this);
    this.channel = channel;
    CastBridge.shared().setListener((output: Map<string, Any>) => {
      this.channel?.invokeMethod('outputChanged', output);
    });
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    CastBridge.shared().setListener(null);
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    const bridge = CastBridge.shared();
    switch (call.method) {
      case 'openPicker':
        bridge.openPicker(this.context).then((opened: boolean) => result.success(opened));
        return;
      case 'currentOutput':
        result.success(bridge.currentOutput());
        return;
      case 'castLoad': {
        const args: Any = call.args;
        if (!(args instanceof Map)) {
          result.error('cast', 'castLoad needs a map', null);
          return;
        }
        bridge.load(args as Map<Any, Any>)
          .then(() => result.success(null))
          .catch((e: Error) => result.error('cast', `castLoad failed: ${e.message}`, null));
        return;
      }
      case 'castCommand': {
        const name: Any = call.argument('command');
        const value: Any = call.argument('value');
        if (typeof name !== 'string') {
          result.error('cast', 'castCommand needs a command', null);
          return;
        }
        bridge.command(name as string, typeof value === 'number' ? value as number : undefined)
          .then(() => result.success(null))
          .catch((e: Error) => result.error('cast', `castCommand failed: ${e.message}`, null));
        return;
      }
      case 'castState':
        bridge.state()
          .then((map: Map<string, Any>) => result.success(map))
          .catch((e: Error) => result.error('cast', `castState failed: ${e.message}`, null));
        return;
      default:
        result.notImplemented();
    }
  }
}
