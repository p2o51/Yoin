import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { QueueItem } from './QueueItem';

/** A downloaded song's address ([QueueItem.localUri], Dart's offline downloads): `file://` + a path in the app's sandbox. */
const FILE_SCHEME = 'file://';

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
  /** The player started (true) or stopped (false) buffering; players that don't say leave it out. */
  onBuffering?: (start: boolean) => void;
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

/**
 * What the decoder reports for the audio track it opened (AVPlayer.getTrackDescription), for Dart's
 * `PlayingFormatEvent`: Now Playing's tags take the decoder's channel count and codec over the server's word.
 * Unknown numbers are 0, an unknown codec ''.
 */
export class DecodedFormat {
  mime: string = '';
  channels: number = 0;
  sampleRateHz: number = 0;
  bitDepth: number = 0;
  /** bit/s. */
  bitRate: number = 0;
}

/** Makes a player that reports to [events]. */
export type EnginePlayerFactory = (events: EnginePlayerEvents) => Promise<EnginePlayer>;

/** The system AVPlayer as an [EnginePlayer]: every call and event passes straight through. */
export class AvEnginePlayer implements EnginePlayer {
  private readonly p: media.AVPlayer;
  /** The downloaded file the player reads now; closed when the source changes or the player goes. */
  private file: fs.File | null = null;

  constructor(p: media.AVPlayer, events: EnginePlayerEvents) {
    this.p = p;
    p.on('stateChange', (state: media.AVPlayerState) => events.onState(state));
    p.on('error', (e: BusinessError) => events.onError(e));
    p.on('timeUpdate', (time: number) => events.onTime(time));
    p.on('seekDone', (time: number) => events.onSeekDone(time));
    p.on('durationUpdate', (duration: number) => events.onDuration(duration));
    p.on('audioInterrupt', (event: audio.InterruptEvent) => events.onInterrupt(event));
    // A stall while playing is the playback log's underrun (Android AudioOutputLog).
    p.on('bufferingUpdate', (type: media.BufferingInfoType, value: number) => {
      const buffering = events.onBuffering;
      if (buffering === undefined) {
        return;
      }
      if (type === media.BufferingInfoType.BUFFERING_START) {
        buffering(true);
      } else if (type === media.BufferingInfoType.BUFFERING_END) {
        buffering(false);
      }
    });
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
    this.closeFile();
    const local = item.localUri;
    if (local !== undefined && local.startsWith(FILE_SCHEME)) {
      // A downloaded song plays from its file, with no network (AVPlayer takes a local file as a descriptor).
      // A file removed since the queue was built streams instead (Android OfflineFirstDataSource).
      let file: fs.File | null = null;
      try {
        file = fs.openSync(decodeURI(local.substring(FILE_SCHEME.length)), fs.OpenMode.READ_ONLY);
      } catch (e) {
        file = null;
      }
      if (file !== null) {
        this.file = file;
        const src: media.AVFileDescriptor = { fd: file.fd };
        this.p.fdSrc = src;
        return;
      }
    }
    if (item.viaCache()) {
      // Dart's stream cache: it adds the server's headers itself.
      this.p.url = item.streamUri();
    } else if (item.headerCount > 0) {
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

  async reset(): Promise<void> {
    await this.p.reset();
    this.closeFile();
  }

  async release(): Promise<void> {
    await this.p.release();
    this.closeFile();
  }

  private closeFile(): void {
    const file = this.file;
    this.file = null;
    if (file !== null) {
      try {
        fs.closeSync(file);
      } catch (e) {
        // Already closed with the player: nothing to do.
      }
    }
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

  /** Whether the source set last is the downloaded file (not the stream). */
  openedFile(): boolean {
    return this.file !== null;
  }

  /** The audio track as the decoder describes it; null when it lists none (prepared / playing / paused only). */
  async decodedFormat(): Promise<DecodedFormat | null> {
    const tracks: Array<media.MediaDescription> = await this.p.getTrackDescription();
    for (let i = 0; i < tracks.length; i++) {
      const track: media.MediaDescription = tracks[i];
      if (readNumber(track[media.MediaDescriptionKey.MD_KEY_TRACK_TYPE]) !== media.MediaType.MEDIA_TYPE_AUD) {
        continue;
      }
      const f = new DecodedFormat();
      const mime: Object = track[media.MediaDescriptionKey.MD_KEY_CODEC_MIME];
      f.mime = typeof mime === 'string' ? mime as string : '';
      f.channels = readNumber(track[media.MediaDescriptionKey.MD_KEY_AUD_CHANNEL_COUNT]);
      f.sampleRateHz = readNumber(track[media.MediaDescriptionKey.MD_KEY_AUD_SAMPLE_RATE]);
      f.bitDepth = readNumber(track[media.MediaDescriptionKey.MD_KEY_AUD_SAMPLE_DEPTH]);
      f.bitRate = readNumber(track[media.MediaDescriptionKey.MD_KEY_BITRATE]);
      return f;
    }
    return null;
  }

  /**
   * Yoin's spatial switch for this source: [none] plays it with the audio effect chain bypassed (EFFECT_NONE),
   * so the system's spatial renderer leaves it alone and the mixer folds its channels down to stereo; otherwise
   * the default effects (spatial audio where the system has it on). Settable in prepared / playing / paused /
   * completed only; a refusal keeps the default.
   */
  setAudioEffectNone(none: boolean): void {
    const state = this.p.state;
    if (state !== 'prepared' && state !== 'playing' && state !== 'paused' && state !== 'completed') {
      return;
    }
    const mode = none ? audio.AudioEffectMode.EFFECT_NONE : audio.AudioEffectMode.EFFECT_DEFAULT;
    if (this.p.audioEffectMode === mode) {
      return;
    }
    try {
      this.p.audioEffectMode = mode;
    } catch (e) {
      // Not in a state that takes it: the next prepared source sets it again.
    }
  }
}

/** A description value as a number; anything else is 0 (unknown). */
function readNumber(value: Object | undefined): number {
  return typeof value === 'number' ? value as number : 0;
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
