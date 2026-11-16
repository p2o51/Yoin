import { Any, EventSink } from '@ohos/flutter_ohos';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { EngineListener, EngineSnapshot, FormatListener, PlaybackEngine, PlayingFormat } from './PlaybackEngine';
import { QueueItem } from './QueueItem';
import { SystemSession } from './SystemSession';
import { NetworkWatch } from './NetworkWatch';

const DOMAIN = 0x5901;
const TAG = 'YoinPlaybackHost';
/** An "open Now Playing" request waits this long for Dart to listen (Android NOW_PLAYING_REQUEST_TTL: 30 s). */
const OPEN_REQUEST_TTL_MS = 30000;

/**
 * The process-level owner of playback (Android's PlaybackService): one engine and one system session for the
 * life of the process, independent of the Flutter engine and plugin attach/detach. The plugin only plugs the
 * event sink in and out; while no Dart listens, events are dropped (state is re-read on listen).
 */
export class YoinPlaybackHost {
  private static instance: YoinPlaybackHost | null = null;

  readonly engine: PlaybackEngine;
  readonly session: SystemSession;
  private context: common.UIAbilityContext;
  private sink: EventSink | null = null;
  private openRequestedAt: number = 0;
  private listener: EngineListener = (s: EngineSnapshot) => this.sendState(s);
  private formatListener: FormatListener = (f: PlayingFormat) => {
    this.lastFormat = f;
    this.sendFormat(f);
  };
  /** The last decoded format, sent again to a newly listening Dart (a re-created UI). */
  private lastFormat: PlayingFormat | null = null;
  /** A network came up: Dart sends the plays it queued offline. */
  private network: NetworkWatch = new NetworkWatch();

  private constructor(context: common.UIAbilityContext) {
    this.context = context;
    this.engine = new PlaybackEngine();
    this.engine.addListener(this.listener);
    this.engine.addFormatListener(this.formatListener);
    this.session = new SystemSession(context, this.engine,
      (name: string, id: string) => this.sendCommand(name, id));
    this.network.start(() => this.sendNetworkAvailable());
  }

  /** The host, created on first use; a newer ability context takes over the session. */
  static bind(context: common.UIAbilityContext): YoinPlaybackHost {
    const existing = YoinPlaybackHost.instance;
    if (existing === null) {
      const host = new YoinPlaybackHost(context);
      YoinPlaybackHost.instance = host;
      return host;
    }
    if (existing.context !== context) {
      existing.context = context;
      existing.session.rebind(context);
    }
    return existing;
  }

  static current(): YoinPlaybackHost | null {
    return YoinPlaybackHost.instance;
  }

  /**
   * The ability is going away. While the user wants sound the host keeps playing (the session and continuous task
   * stay with the process as long as HarmonyOS allows); otherwise everything is torn down.
   */
  static onAbilityDestroy(context: common.UIAbilityContext): void {
    const host = YoinPlaybackHost.instance;
    if (host === null || host.context !== context) {
      return;
    }
    host.sink = null;
    if (host.engine.isActive()) {
      hilog.info(DOMAIN, TAG, 'ability destroyed while playing: keeping playback');
      return;
    }
    host.session.release();
    host.engine.release();
    host.network.stop();
    YoinPlaybackHost.instance = null;
  }

  attachSink(sink: EventSink): void {
    this.sink = sink;
    this.sendState(this.engine.snapshot());
    if (this.lastFormat !== null) {
      this.sendFormat(this.lastFormat);
    }
    if (this.openRequestedAt > 0 && Date.now() - this.openRequestedAt <= OPEN_REQUEST_TTL_MS) {
      this.sendOpenNowPlaying();
    }
    this.openRequestedAt = 0;
  }

  /** Unplugs [sink] (only if it is still the current one: a newer engine may have plugged its own). */
  detachSink(sink: EventSink): void {
    if (this.sink === sink) {
      this.sink = null;
    }
  }

  /** The system card / notification was tapped: Dart opens Now Playing (now, or when it next listens). */
  requestOpenNowPlaying(): void {
    if (this.sink !== null) {
      this.sendOpenNowPlaying();
    } else {
      this.openRequestedAt = Date.now();
    }
  }

  /** `getSnapshot`: the queue (without stream URLs), its order and the current state. */
  snapshotMap(): Map<string, Any> {
    const map: Map<string, Any> = new Map();
    map.set('items', this.engine.queueItems().map((item: QueueItem) => item.toMap()));
    map.set('state', stateMap(this.engine.snapshot()));
    return map;
  }

  private sendState(s: EngineSnapshot): void {
    const sink = this.sink;
    if (sink !== null) {
      sink.success(stateMap(s));
    }
  }

  private sendCommand(name: string, id: string): void {
    const sink = this.sink;
    if (sink === null) {
      hilog.info(DOMAIN, TAG, 'session command %{public}s with no UI: dropped', name);
      return;
    }
    const map: Map<string, Any> = new Map();
    map.set('type', 'command');
    map.set('name', name);
    map.set('id', id);
    sink.success(map);
  }

  /**
   * What the player decodes for the entry it just opened (Dart's `PlayingFormatEvent`); with no Dart listening it
   * waits in [lastFormat] for the next sink.
   */
  private sendFormat(f: PlayingFormat): void {
    const sink = this.sink;
    if (sink === null) {
      return;
    }
    const map: Map<string, Any> = new Map();
    map.set('type', 'playingFormat');
    map.set('id', f.id);
    map.set('entryId', f.entryId);
    map.set('fromFile', f.fromFile);
    map.set('mime', f.decoded.mime.length > 0 ? f.decoded.mime : null);
    map.set('channels', f.decoded.channels);
    map.set('sampleRateHz', f.decoded.sampleRateHz);
    map.set('bitDepth', f.decoded.bitDepth);
    map.set('bitRate', f.decoded.bitRate);
    sink.success(map);
  }

  /** No Dart listening: dropped (Dart flushes on resume anyway). */
  private sendNetworkAvailable(): void {
    const sink = this.sink;
    if (sink === null) {
      return;
    }
    const map: Map<string, Any> = new Map();
    map.set('type', 'networkAvailable');
    sink.success(map);
  }

  private sendOpenNowPlaying(): void {
    const sink = this.sink;
    if (sink === null) {
      return;
    }
    const map: Map<string, Any> = new Map();
    map.set('type', 'openNowPlaying');
    sink.success(map);
  }
}

/** What Dart's `EngineState.fromMap` reads. */
export function stateMap(s: EngineSnapshot): Map<string, Any> {
  const map: Map<string, Any> = new Map();
  map.set('type', 'state');
  map.set('phase', s.phase);
  map.set('isPlaying', s.isPlaying);
  map.set('playWhenReady', s.playWhenReady);
  map.set('index', s.index);
  map.set('currentId', s.currentId);
  map.set('currentEntryId', s.currentEntryId);
  map.set('positionMs', s.positionMs);
  map.set('sampledAtMs', s.sampledAtMs);
  map.set('durationMs', s.durationMs);
  map.set('errorMessage', s.errorMessage);
  map.set('errorCode', s.errorCode);
  map.set('repeat', s.repeat);
  map.set('shuffle', s.shuffle);
  map.set('revision', s.revision);
  map.set('repeatPlays', s.repeatPlays);
  map.set('tick', s.tick);
  if (s.order !== null) {
    map.set('order', s.order);
  }
  return map;
}
