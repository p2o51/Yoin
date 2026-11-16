import {
  Any,
  EventChannel,
  EventSink,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult,
  StreamHandler
} from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { EngineRepeat } from './PlaybackEngine';
import { YoinPlaybackHost } from './PlaybackHost';
import { QueueItem, StreamCacheEpoch } from './QueueItem';

const METHOD_CHANNEL = 'yoin/playback';
const EVENT_CHANNEL = 'yoin/playback/events';

/**
 * Serves `yoin/playback` (commands) and `yoin/playback/events` (state and system events) for Dart's
 * `ChannelPlaybackEngine`. Playback itself lives in the process-level [YoinPlaybackHost], so a Flutter engine
 * detach (ability destroyed, engine re-created) only unplugs the channels: the music, the AVSession and the
 * continuous task carry on.
 */
export default class YoinPlaybackPlugin implements FlutterPlugin, MethodCallHandler, StreamHandler {
  private context: common.UIAbilityContext;
  private methodChannel: MethodChannel | null = null;
  private eventChannel: EventChannel | null = null;
  private sink: EventSink | null = null;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinPlaybackPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    YoinPlaybackHost.bind(this.context);
    this.methodChannel = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL);
    this.methodChannel.setMethodCallHandler(this);
    this.eventChannel = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL);
    this.eventChannel.setStreamHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    // Dart's stream cache server goes with this engine: queued items stream from their server from now on.
    StreamCacheEpoch.end();
    this.methodChannel?.setMethodCallHandler(null);
    this.methodChannel = null;
    this.eventChannel = null;
    this.unplug();
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    const host = YoinPlaybackHost.bind(this.context);
    const engine = host.engine;
    try {
      switch (call.method) {
        case 'setQueue':
          engine.setQueue(
            QueueItem.listFrom(call.argument('items')),
            numberArg(call, 'startIndex', 0),
            numberArg(call, 'startPositionMs', 0),
            boolArg(call, 'playWhenReady', true),
            repeatArg(call),
            optionalBool(call, 'shuffle'),
            optionalNumber(call, 'revision'));
          break;
        case 'insert':
          engine.insert(numberArg(call, 'at', 0), QueueItem.listFrom(call.argument('items')),
            numberArg(call, 'revision', 0));
          break;
        case 'removeAt':
          engine.removeAt(numberArg(call, 'index', -1), numberArg(call, 'revision', 0));
          break;
        case 'move':
          engine.move(numberArg(call, 'from', -1), numberArg(call, 'to', -1), numberArg(call, 'revision', 0));
          break;
        case 'setRepeat': {
          const repeat = repeatArg(call);
          if (repeat !== null) {
            engine.setRepeat(repeat);
          }
          break;
        }
        case 'setShuffle':
          engine.setShuffle(boolArg(call, 'shuffle', false));
          break;
        case 'setFavorite': {
          const id: Any = call.argument('id');
          if (typeof id === 'string') {
            engine.setFavorite(id as string, optionalBool(call, 'favorite'));
          }
          break;
        }
        case 'setGains':
          engine.setGains(gainsArg(call));
          break;
        case 'getSnapshot':
          result.success(host.snapshotMap());
          return;
        case 'play':
          engine.play();
          break;
        case 'pause':
          engine.pause();
          break;
        case 'seekTo':
          engine.seekTo(numberArg(call, 'positionMs', 0));
          break;
        case 'skipTo':
          engine.skipTo(numberArg(call, 'index', -1));
          break;
        case 'next':
          engine.next();
          break;
        case 'previous':
          engine.previous();
          break;
        case 'stop':
          engine.stop();
          break;
        case 'setRemote':
          host.session.setRemote(call.argument('state'));
          break;
        default:
          result.notImplemented();
          return;
      }
      result.success(null);
    } catch (e) {
      result.error('playback', `${call.method} failed: ${e}`, null);
    }
  }

  /** The first event is the current state, so a late listener never waits for the next change. */
  onListen(args: Any, events: EventSink): void {
    this.sink = events;
    YoinPlaybackHost.bind(this.context).attachSink(events);
  }

  onCancel(args: Any): void {
    this.unplug();
  }

  private unplug(): void {
    const sink = this.sink;
    this.sink = null;
    if (sink !== null) {
      YoinPlaybackHost.current()?.detachSink(sink);
    }
  }
}

function numberArg(call: MethodCall, key: string, fallback: number): number {
  const v: Any = call.argument(key);
  return typeof v === 'number' ? v as number : fallback;
}

function boolArg(call: MethodCall, key: string, fallback: boolean): boolean {
  const v: Any = call.argument(key);
  return typeof v === 'boolean' ? v as boolean : fallback;
}

function optionalBool(call: MethodCall, key: string): boolean | null {
  const v: Any = call.argument(key);
  return typeof v === 'boolean' ? v as boolean : null;
}

function optionalNumber(call: MethodCall, key: string): number | null {
  const v: Any = call.argument(key);
  return typeof v === 'number' ? v as number : null;
}

/** `gains`: entry id → 0–1 volume; malformed pairs are skipped. */
function gainsArg(call: MethodCall): Map<string, number> {
  const out: Map<string, number> = new Map();
  const v: Any = call.argument('gains');
  if (v instanceof Map) {
    (v as Map<Any, Any>).forEach((gain: Any, id: Any) => {
      if (typeof id === 'string' && typeof gain === 'number') {
        out.set(id as string, gain as number);
      }
    });
  }
  return out;
}

function repeatArg(call: MethodCall): EngineRepeat | null {
  const v: Any = call.argument('repeat');
  if (v === 'off' || v === 'one' || v === 'all') {
    return v as EngineRepeat;
  }
  return null;
}
