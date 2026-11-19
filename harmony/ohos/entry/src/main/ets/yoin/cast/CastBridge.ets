import { Any } from '@ohos/flutter_ohos';
import { avSession } from '@kit.AVSessionKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN = 0x5909;
const TAG = 'YoinCast';

/** What Dart's `CastOutput.fromMap` reads. */
export type OutputListener = (output: Map<string, Any>) => void;

/**
 * The system side of the Devices sheet's cast row (Android: the Chromecast session in CastManager). Process-level,
 * like the playback host:
 * - the system's output / cast panel (AVCastPickerHelper, API 14+): speaker, wired, Bluetooth and any cast device the
 *   system offers this app's audio session;
 * - the session's output device (AVSession 'outputDeviceChange'), pushed to Dart;
 * - while that output is a remote cast device, its AVCastController as a renderer Dart drives (`castLoad`,
 *   `castCommand`, `castState`): Dart's queue engine hands it one song at a time, exactly like a DLNA speaker.
 */
export class CastBridge {
  private static instance: CastBridge | null = null;

  private session: avSession.AVSession | null = null;
  private listener: OutputListener | null = null;
  private output: Map<string, Any> | null = null;
  private controller: avSession.AVCastController | null = null;
  private controllerFor: string = '';
  private latest: avSession.AVPlaybackState | null = null;
  /** The song handed over and not yet started (start() needs the item; prepare() only loads it). */
  private pending: avSession.AVQueueItem | null = null;
  private current: avSession.AVQueueItem | null = null;
  private started: boolean = false;
  private failed: boolean = false;
  private ended: boolean = false;
  private itemSeq: number = 0;
  /** Monotonic sequence for outputDeviceChange events so stale async controller resolves cannot overwrite newer state. */
  private outputSeq: number = 0;

  static shared(): CastBridge {
    if (CastBridge.instance === null) {
      CastBridge.instance = new CastBridge();
    }
    return CastBridge.instance;
  }

  /** Dart's listener (the plugin), or null when the Flutter engine detached. */
  setListener(listener: OutputListener | null): void {
    this.listener = listener;
  }

  /** The output last reported, or null before any session existed. */
  currentOutput(): Map<string, Any> | null {
    return this.output;
  }

  /** Pulls up the system's output / cast picker. */
  async openPicker(context: common.Context): Promise<boolean> {
    try {
      const helper = new avSession.AVCastPickerHelper(context);
      const options: avSession.AVCastPickerOptions = { sessionType: 'audio' };
      await helper.select(options);
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'picker failed: %{public}s', JSON.stringify(e));
      return false;
    }
  }

  // ---- the session ----

  /** The playback session came up: follow where it plays. */
  attachSession(session: avSession.AVSession): void {
    if (this.session === session) {
      return;
    }
    this.session = session;
    try {
      session.on('outputDeviceChange', (state: avSession.ConnectionState, info: avSession.OutputDeviceInfo) => {
        this.onOutput(state, info);
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'outputDeviceChange not observed: %{public}s', JSON.stringify(e));
    }
    session.getOutputDevice().then((info: avSession.OutputDeviceInfo) => {
      this.onOutput(avSession.ConnectionState.STATE_CONNECTED, info);
    }).catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'getOutputDevice failed: %{public}s', JSON.stringify(e));
    });
  }

  /** The session is going away: so does the cast controller. */
  detachSession(session: avSession.AVSession): void {
    if (this.session !== session) {
      return;
    }
    try {
      session.off('outputDeviceChange');
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'outputDeviceChange off failed: %{public}s', JSON.stringify(e));
    }
    this.session = null;
    this.releaseController();
  }

  private onOutput(state: avSession.ConnectionState, info: avSession.OutputDeviceInfo): void {
    const seq = ++this.outputSeq;
    const device: avSession.DeviceInfo | undefined = info.devices.length > 0 ? info.devices[0] : undefined;
    const remote = device !== undefined && device.castCategory === avSession.AVCastCategory.CATEGORY_REMOTE;
    const map: Map<string, Any> = new Map();
    map.set('state', connectionName(state));
    map.set('category', remote ? 'remote' : 'local');
    map.set('name', device !== undefined ? device.deviceName : '');
    map.set('deviceType', device !== undefined ? device.deviceType : 0);
    const deviceId = device !== undefined ? device.deviceId : '';
    if (remote && state === avSession.ConnectionState.STATE_CONNECTED) {
      // The controller first, then Dart hears of it (its first castLoad needs the controller).
      this.ensureController(deviceId, seq).then((ready: boolean) => {
        if (seq !== this.outputSeq) {
          return;
        }
        if (!ready) {
          map.set('state', 'disconnected');
        }
        this.publish(map);
      });
      return;
    }
    if (!remote || state === avSession.ConnectionState.STATE_DISCONNECTED) {
      this.releaseController();
    }
    this.publish(map);
  }

  private publish(map: Map<string, Any>): void {
    this.output = map;
    const listener = this.listener;
    if (listener !== null) {
      listener(map);
    }
  }

  private async ensureController(deviceId: string, seq: number): Promise<boolean> {
    const session = this.session;
    if (session === null) {
      return false;
    }
    if (this.controller !== null && this.controllerFor === deviceId) {
      return true;
    }
    this.releaseController();
    try {
      const controller = await session.getAVCastController();
      if (seq !== this.outputSeq || this.session !== session) {
        controller.release().catch((e: BusinessError) => {
          hilog.warn(DOMAIN, TAG, 'stale controller release failed: %{public}s', JSON.stringify(e));
        });
        return false;
      }
      this.controller = controller;
      this.controllerFor = deviceId;
      controller.on('playbackStateChange', 'all', (state: avSession.AVPlaybackState) => {
        this.latest = state;
        if (state.state === avSession.PlaybackState.PLAYBACK_STATE_PLAY) {
          this.ended = false;
        }
      });
      controller.on('endOfStream', () => {
        this.ended = true;
      });
      controller.on('error', (e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'cast error: %{public}s', JSON.stringify(e));
        this.failed = true;
      });
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'no cast controller: %{public}s', JSON.stringify(e));
      this.controller = null;
      this.controllerFor = '';
      return false;
    }
  }

  private releaseController(): void {
    const controller = this.controller;
    this.controller = null;
    this.controllerFor = '';
    this.latest = null;
    this.pending = null;
    this.current = null;
    this.started = false;
    this.failed = false;
    this.ended = false;
    if (controller !== null) {
      try {
        controller.off('playbackStateChange');
        controller.off('endOfStream');
        controller.off('error');
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'controller off failed: %{public}s', JSON.stringify(e));
      }
      controller.release().catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'controller release failed: %{public}s', JSON.stringify(e));
      });
    }
  }

  // ---- the renderer Dart drives ----

  private requireController(): avSession.AVCastController {
    const controller = this.controller;
    if (controller === null) {
      throw new Error('no cast device');
    }
    return controller;
  }

  /** `castLoad`: prepares one song on the cast device; Play starts it. */
  async load(args: Map<Any, Any>): Promise<void> {
    const controller = this.requireController();
    const description: avSession.AVMediaDescription = {
      assetId: stringOf(args, 'id') ?? '',
      title: stringOf(args, 'title'),
      artist: stringOf(args, 'artist'),
      albumTitle: stringOf(args, 'album'),
      mediaUri: stringOf(args, 'uri'),
      mediaType: 'AUDIO',
      duration: numberOf(args, 'durationMs'),
      startPosition: 0
    };
    const art = stringOf(args, 'artworkUri');
    if (art !== undefined) {
      description.mediaImage = art;
      description.albumCoverUri = art;
    }
    const item: avSession.AVQueueItem = { itemId: ++this.itemSeq, description: description };
    this.failed = false;
    this.ended = false;
    this.started = false;
    this.latest = null;
    this.current = item;
    this.pending = item;
    await controller.prepare(item);
  }

  /** `castCommand`: play / pause / stop / seek (ms) / setVolume (0–100). */
  async command(name: string, value: number | undefined): Promise<void> {
    const controller = this.requireController();
    const pending = this.pending;
    if (name === 'seek' && pending !== null && pending.description !== undefined) {
      // Not started yet: it starts there.
      pending.description.startPosition = value ?? 0;
      return;
    }
    if (name === 'play' && pending !== null) {
      this.pending = null;
      this.started = true;
      await controller.start(pending);
      return;
    }
    const command: avSession.AVCastControlCommand = { command: 'play' };
    switch (name) {
      case 'play':
        command.command = 'play';
        break;
      case 'pause':
        command.command = 'pause';
        break;
      case 'stop':
        command.command = 'stop';
        break;
      case 'seek':
        command.command = 'seek';
        command.parameter = value ?? 0;
        break;
      case 'setVolume': {
        command.command = 'setVolume';
        const max = this.latest?.maxVolume ?? 100;
        command.parameter = Math.round((value ?? 0) * (max > 0 ? max : 100) / 100);
        break;
      }
      default:
        throw new Error(`unknown cast command ${name}`);
    }
    await controller.sendControlCommand(command);
  }

  /** `castState`: the cast device's state in UPnP words, for Dart's queue engine. */
  async state(): Promise<Map<string, Any>> {
    const controller = this.requireController();
    let state: avSession.AVPlaybackState | null = this.latest;
    try {
      state = await controller.getAVPlaybackState();
      this.latest = state;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'getAVPlaybackState failed: %{public}s', JSON.stringify(e));
    }
    const map: Map<string, Any> = new Map();
    const playing = state?.state === avSession.PlaybackState.PLAYBACK_STATE_PLAY;
    map.set('state', this.pending !== null ? 'STOPPED' : upnpState(state, this.ended));
    if (this.failed || state?.state === avSession.PlaybackState.PLAYBACK_STATE_ERROR) {
      map.set('status', 'ERROR_OCCURRED');
    }
    const position = state?.position;
    if (position !== undefined) {
      const elapsed = playing && position.updateTime > 0 ? Math.max(0, Date.now() - position.updateTime) : 0;
      map.set('positionMs', Math.max(0, Math.floor(position.elapsedTime + elapsed)));
    } else if (this.pending !== null) {
      map.set('positionMs', this.pending.description?.startPosition ?? 0);
    }
    const duration = state?.duration ?? this.current?.description?.duration;
    if (duration !== undefined && duration > 0) {
      map.set('durationMs', Math.floor(duration));
    }
    const uri = this.current?.description?.mediaUri;
    if (uri !== undefined) {
      map.set('mediaUri', uri);
    }
    const volume = state?.volume;
    if (volume !== undefined) {
      const max = state?.maxVolume ?? 100;
      map.set('volume', Math.round(volume * 100 / (max > 0 ? max : 100)));
    }
    return map;
  }
}

function connectionName(state: avSession.ConnectionState): string {
  switch (state) {
    case avSession.ConnectionState.STATE_CONNECTING:
      return 'connecting';
    case avSession.ConnectionState.STATE_CONNECTED:
      return 'connected';
    default:
      return 'disconnected';
  }
}

/**
 * AVSession's states as UPnP transport states (what Dart's DLNA engine reads). An 'endOfStream' since the last start
 * reads as STOPPED whatever state the controller still reports, so the queue engine sees the song end and moves on.
 */
function upnpState(state: avSession.AVPlaybackState | null, ended: boolean): string {
  if (ended) {
    return 'STOPPED';
  }
  switch (state?.state) {
    case avSession.PlaybackState.PLAYBACK_STATE_PLAY:
      return 'PLAYING';
    case avSession.PlaybackState.PLAYBACK_STATE_PAUSE:
      return 'PAUSED_PLAYBACK';
    case avSession.PlaybackState.PLAYBACK_STATE_BUFFERING:
    case avSession.PlaybackState.PLAYBACK_STATE_PREPARE:
    case avSession.PlaybackState.PLAYBACK_STATE_INITIAL:
      return 'TRANSITIONING';
    default:
      return 'STOPPED';
  }
}

function stringOf(map: Map<Any, Any>, key: string): string | undefined {
  const v: Any = map.get(key);
  return typeof v === 'string' ? v as string : undefined;
}

function numberOf(map: Map<Any, Any>, key: string): number | undefined {
  const v: Any = map.get(key);
  return typeof v === 'number' ? v as number : undefined;
}
