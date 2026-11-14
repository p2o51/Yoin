import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { QueueItem } from './QueueItem';

/**
 * What a player tells the engine. State names are AVPlayer's ('idle', 'initialized', 'prepared',
 * 'playing', 'paused', 'completed', …); every player reports the same names so the engine's state
 * machine stays one.
 */
export interface EnginePlayerEvents {
  onState: (state: string) => void;
  onError: (e: BusinessError) => void;
  onTime: (ms: number) => void;
  onSeekDone: (ms: number) => void;
  onDuration: (ms: number) => void;
  onInterrupt: (event: audio.InterruptEvent) => void;
  /** The output device went away (headphones unplugged). */
  onDeviceGone: () => void;
}

/** The part of a media player the engine drives (AVPlayer's shape). */
export interface EnginePlayer {
  state(): string;
  /** Sets the item's source; the player then reports 'initialized'. */
  setSource(item: QueueItem): Promise<void>;
  /** Music usage, then prepare; the player then reports 'prepared'. */
  prepareForMusic(): Promise<void>;
  play(): Promise<void>;
  pause(): Promise<void>;
  seek(ms: number): void;
  reset(): Promise<void>;
  release(): Promise<void>;
  currentTime(): number;
  duration(): number;
  setVolume(volume: number): void;
}

/** Makes a player that reports to [events]. */
export type EnginePlayerFactory = (events: EnginePlayerEvents) => Promise<EnginePlayer>;

/** The system AVPlayer as an [EnginePlayer]: every call and event passes straight through. */
export class AvEnginePlayer implements EnginePlayer {
  private readonly p: media.AVPlayer;

  constructor(p: media.AVPlayer, events: EnginePlayerEvents) {
    this.p = p;
    p.on('stateChange', (state: media.AVPlayerState) => events.onState(state));
    p.on('error', (e: BusinessError) => events.onError(e));
    p.on('timeUpdate', (time: number) => events.onTime(time));
    p.on('seekDone', (time: number) => events.onSeekDone(time));
    p.on('durationUpdate', (duration: number) => events.onDuration(duration));
    p.on('audioInterrupt', (event: audio.InterruptEvent) => events.onInterrupt(event));
    // Headphones unplugged / Bluetooth gone (Android setHandleAudioBecomingNoisy).
    p.on('audioOutputDeviceChangeWithInfo', (info: audio.AudioStreamDeviceChangeInfo) => {
      if (info.changeReason === audio.AudioStreamDeviceChangeReason.REASON_OLD_DEVICE_UNAVAILABLE) {
        events.onDeviceGone();
      }
    });
  }

  static create(events: EnginePlayerEvents): Promise<EnginePlayer> {
    return media.createAVPlayer().then((p: media.AVPlayer) => new AvEnginePlayer(p, events) as EnginePlayer);
  }

  state(): string {
    return this.p.state;
  }

  async setSource(item: QueueItem): Promise<void> {
    if (item.headerCount > 0) {
      await this.p.setMediaSource(media.createMediaSourceWithUrl(item.uri, item.headers));
    } else {
      this.p.url = item.uri;
    }
  }

  prepareForMusic(): Promise<void> {
    // Usage must be set before prepare(); MUSIC gets the music focus strategy and the AUDIO_PLAYBACK task.
    const info: audio.AudioRendererInfo = {
      usage: audio.StreamUsage.STREAM_USAGE_MUSIC,
      rendererFlags: 0
    };
    this.p.audioRendererInfo = info;
    return this.p.prepare();
  }

  play(): Promise<void> {
    return this.p.play();
  }

  pause(): Promise<void> {
    return this.p.pause();
  }

  seek(ms: number): void {
    this.p.seek(ms, media.SeekMode.SEEK_CLOSEST);
  }

  reset(): Promise<void> {
    return this.p.reset();
  }

  release(): Promise<void> {
    return this.p.release();
  }

  currentTime(): number {
    return this.p.currentTime;
  }

  duration(): number {
    return this.p.duration;
  }

  setVolume(volume: number): void {
    this.p.setVolume(volume);
  }
}

/**
 * Players for URI schemes AVPlayer doesn't play. Nothing is registered in a normal build; a
 * personal build registers its own (e.g. `yoin-am` for Apple Music, docs/applemusic.md).
 */
export class EnginePlayers {
  private static external: Map<string, EnginePlayerFactory> = new Map();

  static register(scheme: string, factory: EnginePlayerFactory): void {
    EnginePlayers.external.set(scheme, factory);
  }

  /** The registered scheme of [uri], or '' (AVPlayer). */
  static kindOf(uri: string): string {
    const colon = uri.indexOf(':');
    const scheme = colon > 0 ? uri.substring(0, colon) : '';
    return EnginePlayers.external.has(scheme) ? scheme : '';
  }

  static factory(kind: string): EnginePlayerFactory | undefined {
    return EnginePlayers.external.get(kind);
  }
}
