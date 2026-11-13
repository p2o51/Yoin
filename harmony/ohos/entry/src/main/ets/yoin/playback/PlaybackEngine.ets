import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { QueueItem } from './QueueItem';

const DOMAIN = 0x5901;
const TAG = 'YoinPlayback';

/** Below this position `previous` goes to the previous item; above it restarts the track (ExoPlayer's default). */
const RESTART_THRESHOLD_MS = 3000;
/** Position samples while playing; Dart extrapolates in between. */
const TICK_MS = 1000;
/** Volume while another app holds shared focus and asks us to duck. */
const DUCK_VOLUME = 0.2;

/** Mirrors Dart's `EnginePhase` names exactly (they are sent by name). */
export type EnginePhase = 'idle' | 'loading' | 'ready' | 'ended' | 'error';

/** What the Dart side's `EngineState.fromMap` reads. */
export class EngineSnapshot {
  phase: EnginePhase = 'idle';
  isPlaying: boolean = false;
  index: number = -1;
  currentId: string | null = null;
  positionMs: number = 0;
  sampledAtMs: number = 0;
  durationMs: number | null = null;
  errorMessage: string | null = null;
}

export type EngineListener = (snapshot: EngineSnapshot) => void;

/**
 * The queue and one AVPlayer. It owns the queue (not Dart) so lock screen, 播控中心 and headset keys keep working
 * with the UI gone. Loads are serialised through [opChain] and tagged with a [generation] so a fast skip can never
 * act on a stale source's state events.
 */
export class PlaybackEngine {
  private items: QueueItem[] = [];
  private index: number = -1;
  private phase: EnginePhase = 'idle';
  private playing: boolean = false;
  /** The user's intent (ExoPlayer's playWhenReady): survives loads and auto-advance. */
  private playWhenReady: boolean = false;
  private pendingStartMs: number = 0;
  private durationMs: number | null = null;
  private lastPositionMs: number = 0;
  private errorMessage: string | null = null;
  /** Bumped by every load/stop; events for an older generation are ignored. */
  private generation: number = 0;
  /** Generation whose source is currently set on the player. */
  private sourceGeneration: number = -1;
  private opChain: Promise<void> = Promise.resolve();
  private player: media.AVPlayer | null = null;
  private playerPromise: Promise<media.AVPlayer> | null = null;
  private ticker: number = -1;
  /** Set when the system paused us for a transient interruption; a resume hint then restarts playback. */
  private resumeOnFocusGain: boolean = false;
  private listeners: EngineListener[] = [];
  private released: boolean = false;

  addListener(listener: EngineListener): void {
    this.listeners.push(listener);
  }

  removeListener(listener: EngineListener): void {
    this.listeners = this.listeners.filter((l: EngineListener) => l !== listener);
  }

  currentItem(): QueueItem | null {
    return this.index >= 0 && this.index < this.items.length ? this.items[this.index] : null;
  }

  snapshot(): EngineSnapshot {
    const s = new EngineSnapshot();
    s.phase = this.phase;
    s.isPlaying = this.playing;
    s.index = this.index;
    const item = this.currentItem();
    s.currentId = item !== null ? item.id : null;
    s.positionMs = Math.max(0, Math.floor(this.readPosition()));
    s.sampledAtMs = Date.now();
    s.durationMs = this.durationMs !== null ? Math.floor(this.durationMs) : null;
    s.errorMessage = this.errorMessage;
    return s;
  }

  setQueue(items: QueueItem[], startIndex: number, startPositionMs: number, playWhenReady: boolean): void {
    if (items.length === 0) {
      this.stop();
      return;
    }
    this.items = items;
    this.playWhenReady = playWhenReady;
    this.resumeOnFocusGain = false;
    const index = Math.min(Math.max(0, Math.floor(startIndex)), items.length - 1);
    this.load(index, Math.max(0, startPositionMs));
  }

  play(): void {
    this.playWhenReady = true;
    this.resumeOnFocusGain = false;
    if (this.items.length === 0) {
      return;
    }
    if (this.phase === 'ended') {
      this.load(this.index, 0);
      return;
    }
    if (this.phase === 'error') {
      this.load(this.index, this.lastPositionMs);
      return;
    }
    const p = this.player;
    if (this.phase === 'ready' && p !== null && this.isSourceCurrent() && canPlay(p.state)) {
      p.play().catch((e: BusinessError) => this.onCommandFailed('play', e));
    } else {
      // Still loading: the 'prepared' handler reads playWhenReady.
      this.emit();
    }
  }

  pause(): void {
    this.playWhenReady = false;
    this.resumeOnFocusGain = false;
    this.pauseInternal();
  }

  seekTo(positionMs: number): void {
    const target = Math.max(0, Math.floor(positionMs));
    this.lastPositionMs = target;
    const p = this.player;
    if (this.phase === 'loading') {
      this.pendingStartMs = target;
    } else if (p !== null && this.isSourceCurrent() && canSeek(p.state)) {
      if (this.phase === 'ended') {
        this.phase = 'ready';
      }
      p.seek(target, media.SeekMode.SEEK_CLOSEST);
    }
    this.emit();
  }

  skipTo(index: number): void {
    if (index >= 0 && index < this.items.length) {
      this.load(Math.floor(index), 0);
    }
  }

  next(): void {
    if (this.index + 1 < this.items.length) {
      this.load(this.index + 1, 0);
    }
  }

  previous(): void {
    if (this.items.length === 0) {
      return;
    }
    if (this.index > 0 && this.readPosition() <= RESTART_THRESHOLD_MS) {
      this.load(this.index - 1, 0);
    } else {
      this.seekTo(0);
    }
  }

  /** Ends playback and drops the queue; listeners see index -1 and tear down the AVSession and background task. */
  stop(): void {
    this.generation++;
    this.sourceGeneration = -1;
    this.items = [];
    this.index = -1;
    this.phase = 'idle';
    this.playing = false;
    this.playWhenReady = false;
    this.resumeOnFocusGain = false;
    this.durationMs = null;
    this.lastPositionMs = 0;
    this.pendingStartMs = 0;
    this.errorMessage = null;
    this.stopTicker();
    this.enqueue(async (): Promise<void> => {
      const p = this.player;
      if (p !== null && p.state !== 'idle' && p.state !== 'released') {
        await p.reset();
      }
    });
    this.emit();
  }

  /** Called when the engine detaches: stops and frees the AVPlayer. */
  release(): void {
    this.stop();
    this.released = true;
    this.enqueue(async (): Promise<void> => {
      const p = this.player;
      this.player = null;
      this.playerPromise = null;
      if (p !== null) {
        await p.release();
      }
    });
    this.listeners = [];
  }

  // ---- loading ----

  private load(index: number, startMs: number): void {
    const gen = ++this.generation;
    const item = this.items[index];
    this.index = index;
    this.phase = 'loading';
    this.playing = false;
    this.durationMs = item.durationMs !== undefined ? item.durationMs : null;
    this.lastPositionMs = startMs;
    this.pendingStartMs = startMs;
    this.errorMessage = null;
    this.stopTicker();
    this.emit();
    this.enqueue(async (): Promise<void> => {
      if (gen !== this.generation || this.released) {
        return;
      }
      const p = await this.ensurePlayer();
      if (gen !== this.generation) {
        return;
      }
      if (p.state !== 'idle') {
        await p.reset();
      }
      if (gen !== this.generation) {
        return;
      }
      this.sourceGeneration = gen;
      if (item.headerCount > 0) {
        await p.setMediaSource(media.createMediaSourceWithUrl(item.uri, item.headers));
      } else {
        p.url = item.uri;
      }
    }, gen);
  }

  /** Runs [op] after every earlier op; a failure only surfaces when it belongs to the current generation. */
  private enqueue(op: () => Promise<void>, gen?: number): void {
    this.opChain = this.opChain.then(op).catch((e: BusinessError) => {
      if (gen !== undefined && gen === this.generation) {
        this.fail(e);
      } else {
        hilog.warn(DOMAIN, TAG, 'background op failed: %{public}s', describe(e));
      }
    });
  }

  private ensurePlayer(): Promise<media.AVPlayer> {
    if (this.playerPromise === null) {
      this.playerPromise = media.createAVPlayer().then((p: media.AVPlayer) => {
        this.attach(p);
        this.player = p;
        return p;
      });
    }
    return this.playerPromise;
  }

  private attach(p: media.AVPlayer): void {
    p.on('stateChange', (state: media.AVPlayerState) => this.onPlayerState(p, state));
    p.on('error', (e: BusinessError) => {
      if (this.isSourceCurrent()) {
        this.fail(e);
      }
    });
    p.on('timeUpdate', (time: number) => {
      if (this.isSourceCurrent() && this.phase !== 'loading') {
        this.lastPositionMs = time;
      }
    });
    p.on('seekDone', (time: number) => {
      if (this.isSourceCurrent() && this.phase !== 'loading') {
        this.lastPositionMs = time;
        this.emit();
      }
    });
    p.on('durationUpdate', (duration: number) => {
      if (this.isSourceCurrent() && duration > 0 && duration !== this.durationMs) {
        this.durationMs = duration;
        this.emit();
      }
    });
    p.on('audioInterrupt', (event: audio.InterruptEvent) => this.onInterrupt(event));
  }

  private onPlayerState(p: media.AVPlayer, state: media.AVPlayerState): void {
    if (!this.isSourceCurrent()) {
      return;
    }
    switch (state) {
      case 'initialized': {
        // Usage must be set before prepare(); MUSIC gets the music focus strategy and the AUDIO_PLAYBACK task.
        const info: audio.AudioRendererInfo = {
          usage: audio.StreamUsage.STREAM_USAGE_MUSIC,
          rendererFlags: 0
        };
        p.audioRendererInfo = info;
        const gen = this.generation;
        p.prepare().catch((e: BusinessError) => {
          if (gen === this.generation) {
            this.fail(e);
          }
        });
        break;
      }
      case 'prepared': {
        this.phase = 'ready';
        if (p.duration > 0) {
          this.durationMs = p.duration;
        }
        if (this.pendingStartMs > 0) {
          p.seek(this.pendingStartMs, media.SeekMode.SEEK_CLOSEST);
        }
        this.pendingStartMs = 0;
        if (this.playWhenReady) {
          p.play().catch((e: BusinessError) => this.onCommandFailed('play', e));
        }
        this.emit();
        break;
      }
      case 'playing':
        this.playing = true;
        this.startTicker();
        this.emit();
        break;
      case 'paused':
        this.playing = false;
        this.stopTicker();
        this.emit();
        break;
      case 'completed':
        this.playing = false;
        this.stopTicker();
        if (this.index + 1 < this.items.length) {
          this.load(this.index + 1, 0);
        } else {
          this.phase = 'ended';
          if (this.durationMs !== null) {
            this.lastPositionMs = this.durationMs;
          }
          this.emit();
        }
        break;
      default:
        break;
    }
  }

  /**
   * INTERRUPT_FORCE: the system already paused/ducked the stream; we only mirror it. INTERRUPT_SHARE: the system
   * leaves it to us, so pause, duck and resume here.
   */
  private onInterrupt(event: audio.InterruptEvent): void {
    const p = this.player;
    if (event.forceType === audio.InterruptForceType.INTERRUPT_FORCE) {
      switch (event.hintType) {
        case audio.InterruptHint.INTERRUPT_HINT_PAUSE:
          this.resumeOnFocusGain = this.playing || this.resumeOnFocusGain;
          this.playWhenReady = false;
          this.playing = false;
          this.stopTicker();
          this.emit();
          break;
        case audio.InterruptHint.INTERRUPT_HINT_STOP:
          this.resumeOnFocusGain = false;
          this.playWhenReady = false;
          this.playing = false;
          this.stopTicker();
          this.emit();
          break;
        default:
          break;
      }
      return;
    }
    switch (event.hintType) {
      case audio.InterruptHint.INTERRUPT_HINT_RESUME:
        if (this.resumeOnFocusGain) {
          this.play();
        }
        break;
      case audio.InterruptHint.INTERRUPT_HINT_PAUSE:
        if (this.playing) {
          this.resumeOnFocusGain = true;
          this.playWhenReady = false;
          this.pauseInternal();
        }
        break;
      case audio.InterruptHint.INTERRUPT_HINT_STOP:
        this.pause();
        break;
      case audio.InterruptHint.INTERRUPT_HINT_DUCK:
        if (p !== null) {
          p.setVolume(DUCK_VOLUME);
        }
        break;
      case audio.InterruptHint.INTERRUPT_HINT_UNDUCK:
        if (p !== null) {
          p.setVolume(1.0);
        }
        break;
      default:
        break;
    }
  }

  // ---- helpers ----

  private pauseInternal(): void {
    const p = this.player;
    if (p !== null && this.isSourceCurrent() && p.state === 'playing') {
      p.pause().catch((e: BusinessError) => this.onCommandFailed('pause', e));
    } else {
      this.emit();
    }
  }

  private isSourceCurrent(): boolean {
    return this.sourceGeneration === this.generation && this.index >= 0;
  }

  private readPosition(): number {
    const p = this.player;
    if (p !== null && this.isSourceCurrent() && this.phase !== 'loading' && this.phase !== 'ended' &&
      canSeek(p.state)) {
      return p.currentTime;
    }
    return this.lastPositionMs;
  }

  private fail(e: BusinessError): void {
    hilog.error(DOMAIN, TAG, 'playback error: %{public}s', describe(e));
    this.lastPositionMs = this.readPosition();
    this.phase = 'error';
    this.playing = false;
    this.errorMessage = describe(e);
    this.stopTicker();
    this.emit();
  }

  private onCommandFailed(command: string, e: BusinessError): void {
    hilog.warn(DOMAIN, TAG, '%{public}s failed: %{public}s', command, describe(e));
  }

  private startTicker(): void {
    if (this.ticker === -1) {
      this.ticker = setInterval(() => this.emit(), TICK_MS);
    }
  }

  private stopTicker(): void {
    if (this.ticker !== -1) {
      clearInterval(this.ticker);
      this.ticker = -1;
    }
  }

  private emit(): void {
    const s = this.snapshot();
    this.listeners.forEach((l: EngineListener) => {
      try {
        l(s);
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'listener threw: %{public}s', describe(e as BusinessError));
      }
    });
  }
}

function canPlay(state: media.AVPlayerState): boolean {
  return state === 'prepared' || state === 'paused' || state === 'completed';
}

function canSeek(state: media.AVPlayerState): boolean {
  return state === 'prepared' || state === 'playing' || state === 'paused' || state === 'completed';
}

export function describe(e: BusinessError): string {
  if (e === undefined || e === null) {
    return 'unknown error';
  }
  return e.code !== undefined ? `${e.code}: ${e.message}` : `${e.message}`;
}
