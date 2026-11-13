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
import { EngineListener, EngineSnapshot, PlaybackEngine } from './PlaybackEngine';
import { QueueItem } from './QueueItem';
import { SystemSession } from './SystemSession';

const METHOD_CHANNEL = 'yoin/playback';
const EVENT_CHANNEL = 'yoin/playback/events';

/**
 * Serves `yoin/playback` (commands) and `yoin/playback/events` (state stream) for Dart's `ChannelPlaybackEngine`.
 * The ability context (not the application context) is needed: AVSession and continuous tasks are tied to a UIAbility.
 */
export default class YoinPlaybackPlugin implements FlutterPlugin, MethodCallHandler, StreamHandler {
  private context: common.UIAbilityContext;
  private engine: PlaybackEngine | null = null;
  private session: SystemSession | null = null;
  private methodChannel: MethodChannel | null = null;
  private eventChannel: EventChannel | null = null;
  private sink: EventSink | null = null;
  private listener: EngineListener = (s: EngineSnapshot) => this.send(s);

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinPlaybackPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    const engine = new PlaybackEngine();
    engine.addListener(this.listener);
    this.engine = engine;
    this.session = new SystemSession(this.context, engine);
    this.methodChannel = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL);
    this.methodChannel.setMethodCallHandler(this);
    this.eventChannel = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL);
    this.eventChannel.setStreamHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.methodChannel?.setMethodCallHandler(null);
    this.methodChannel = null;
    this.eventChannel = null;
    this.sink = null;
    this.session?.release();
    this.session = null;
    this.engine?.release();
    this.engine = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    const engine = this.engine;
    if (engine === null) {
      result.error('detached', 'playback engine is not attached', null);
      return;
    }
    try {
      switch (call.method) {
        case 'setQueue':
          engine.setQueue(
            QueueItem.listFrom(call.argument('items')),
            numberArg(call, 'startIndex', 0),
            numberArg(call, 'startPositionMs', 0),
            boolArg(call, 'playWhenReady', true));
          break;
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
    const engine = this.engine;
    if (engine !== null) {
      this.send(engine.snapshot());
    }
  }

  onCancel(args: Any): void {
    this.sink = null;
  }

  private send(s: EngineSnapshot): void {
    const sink = this.sink;
    if (sink === null) {
      return;
    }
    const map: Map<string, Any> = new Map();
    map.set('phase', s.phase);
    map.set('isPlaying', s.isPlaying);
    map.set('index', s.index);
    map.set('currentId', s.currentId);
    map.set('positionMs', s.positionMs);
    map.set('sampledAtMs', s.sampledAtMs);
    map.set('durationMs', s.durationMs);
    map.set('errorMessage', s.errorMessage);
    sink.success(map);
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
