import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { QueueItem } from './QueueItem';

const DOMAIN = 0x5901;
const TAG = 'YoinPlayback';

/** Below this position `previous` goes to the previous item; above it restarts the track (Spotify / ExoPlayer). */
const RESTART_THRESHOLD_MS = 3000;
/** Position samples while playing; Dart extrapolates in between. */
const TICK_MS = 1000;
/** Volume while another app holds shared focus and asks us to duck (Media3's ducked multiplier). */
const DUCK_VOLUME = 0.2;

/** Mirrors Dart's `PlaybackPhase` names exactly (they are sent by name). */
export type EnginePhase = 'idle' | 'loading' | 'ready' | 'ended' | 'error';

/** Mirrors Dart's `EngineRepeat` names. */
export type EngineRepeat = 'off' | 'one' | 'all';

/** What the Dart side's `EngineState.fromMap` reads. */
export class EngineSnapshot {
  phase: EnginePhase = 'idle';
  isPlaying: boolean = false;
  /** Effective intent: false while idle / ended / error or suppressed by a transient focus loss. */
  playWhenReady: boolean = false;
  index: number = -1;
  currentId: string | null = null;
  currentEntryId: string | null = null;
  positionMs: number = 0;
  sampledAtMs: number = 0;
  durationMs: number | null = null;
  errorMessage: string | null = null;
  errorCode: number | null = null;
  repeat: EngineRepeat = 'all';
  shuffle: boolean = false;
  revision: number = 0;
  repeatPlays: number = 0;
  /** Play order as queue indices; null on pure position ticks. */
  order: number[] | null = null;
  /** A pure position sample: nothing but the clock moved. */
  tick: boolean = false;
  /** Session heart: the item it belongs to and its value; null = hidden. */
  favoriteId: string | null = null;
  favorite: boolean | null = null;
}

export type EngineListener = (snapshot: EngineSnapshot) => void;

/**
 * The queue, its play order (shuffle) and repeat, and one AVPlayer. It owns the queue (not Dart) so lock screen,
 * 播控中心, headset keys and auto-advance keep working with the UI gone. Loads are serialised through [opChain] and
 * tagged with a [generation] so a fast skip can never act on a stale source's state events.
 */
export class PlaybackEngine {
  private items: QueueItem[] = [];
  /** Play order: queue indices. Identity unless shuffled. */
  private order: number[] = [];
  private index: number = -1;
  private phase: EnginePhase = 'idle';
  private playing: boolean = false;
  /** The user's intent (ExoPlayer's playWhenReady): survives loads and auto-advance. */
  private playWhenReady: boolean = false;
  private repeat: EngineRepeat = 'all';
  private shuffle: boolean = false;
  /** Dart's queue revision, echoed so Dart can drop events about a queue it already replaced. */
  private revision: number = 0;
  /** Auto-repeats of an entry (repeat one); never reset — Dart keys plays by entry id + this. */
  private repeatPlays: number = 0;
  private pendingStartMs: number = 0;
  private durationMs: number | null = null;
  private lastPositionMs: number = 0;
  private errorMessage: string | null = null;
  private errorCode: number | null = null;
  private favoriteId: string | null = null;
  private favorite: boolean | null = null;
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

  queueItems(): QueueItem[] {
    return this.items;
  }

  /** True while the user wants sound (the host keeps the process for this). */
  isActive(): boolean {
    return this.items.length > 0 && (this.playWhenReady || this.playing);
  }

  snapshot(tick?: boolean): EngineSnapshot {
    const s = new EngineSnapshot();
    s.phase = this.phase;
    s.isPlaying = this.playing;
    s.playWhenReady = this.playWhenReady && (this.phase === 'loading' || this.phase === 'ready');
    s.index = this.index;
    const item = this.currentItem();
    s.currentId = item !== null ? item.id : null;
    s.currentEntryId = item !== null && item.entryId !== undefined ? item.entryId : null;
    s.positionMs = Math.max(0, Math.floor(this.readPosition()));
    s.sampledAtMs = Date.now();
    s.durationMs = this.durationMs !== null ? Math.floor(this.durationMs) : null;
    s.errorMessage = this.errorMessage;
    s.errorCode = this.errorCode;
    s.repeat = this.repeat;
    s.shuffle = this.shuffle;
    s.revision = this.revision;
    s.repeatPlays = this.repeatPlays;
    s.tick = tick === true;
    s.order = s.tick ? null : this.order.slice();
    s.favoriteId = this.favoriteId;
    s.favorite = this.favorite;
    return s;
  }

  // ---- queue ----

  setQueue(items: QueueItem[], startIndex: number, startPositionMs: number, playWhenReady: boolean,
    repeat: EngineRepeat | null, shuffle: boolean | null, revision: number | null): void {
    if (revision !== null) {
      this.revision = revision;
    }
    if (repeat !== null) {
      this.repeat = repeat;
    }
    if (shuffle !== null) {
      this.shuffle = shuffle;
    }
    if (items.length === 0) {
      this.stop();
      return;
    }
    this.items = items;
    this.playWhenReady = playWhenReady;
    this.resumeOnFocusGain = false;
    const index = Math.min(Math.max(0, Math.floor(startIndex)), items.length - 1);
    this.order = this.shuffle ? shuffledOrder(items.length, index) : identity(items.length);
    this.load(index, Math.max(0, startPositionMs));
  }

  /** Inserts [newItems] before queue index [at]. Shuffled: each new entry plays at a random point after the current one. */
  insert(at: number, newItems: QueueItem[], revision: number): void {
    this.revision = revision;
    const n = newItems.length;
    if (n === 0) {
      this.emit();
      return;
    }
    const pos = Math.min(Math.max(0, Math.floor(at)), this.items.length);
    const next: QueueItem[] = [];
    for (let i = 0; i < this.items.length; i++) {
      if (i === pos) {
        newItems.forEach((it: QueueItem) => next.push(it));
      }
      next.push(this.items[i]);
    }
    if (pos === this.items.length) {
      newItems.forEach((it: QueueItem) => next.push(it));
    }
    this.items = next;
    if (this.index >= pos) {
      this.index += n;
    }
    if (this.shuffle) {
      const order = this.order.map((i: number) => i >= pos ? i + n : i);
      for (let k = 0; k < n; k++) {
        const cur = order.indexOf(this.index);
        const from = cur + 1;
        const slot = from + Math.floor(Math.random() * (order.length - from + 1));
        order.splice(slot, 0, pos + k);
      }
      this.order = order;
    } else {
      this.order = identity(this.items.length);
    }
    this.emit();
  }

  /** Removes queue entry [at]; the current entry stays (Dart never asks for it). */
  removeAt(at: number, revision: number): void {
    this.revision = revision;
    const i = Math.floor(at);
    if (i < 0 || i >= this.items.length || i === this.index) {
      this.emit();
      return;
    }
    this.items = this.items.filter((_: QueueItem, k: number) => k !== i);
    if (this.index > i) {
      this.index--;
    }
    this.order = this.order.filter((k: number) => k !== i).map((k: number) => k > i ? k - 1 : k);
    this.emit();
  }

  move(fromIndex: number, toIndex: number, revision: number): void {
    this.revision = revision;
    const from = Math.floor(fromIndex);
    const to = Math.floor(toIndex);
    const n = this.items.length;
    if (from === to || from < 0 || to < 0 || from >= n || to >= n) {
      this.emit();
      return;
    }
    const moved = this.items[from];
    const rest = this.items.filter((_: QueueItem, k: number) => k !== from);
    rest.splice(to, 0, moved);
    this.items = rest;
    this.index = movedIndex(this.index, from, to);
    this.order = this.shuffle ? this.order.map((k: number) => movedIndex(k, from, to)) : identity(n);
    this.emit();
  }

  setRepeat(repeat: EngineRepeat): void {
    this.repeat = repeat;
    this.emit();
  }

  /** On: the current entry first, then a fresh permutation of the rest, so previous goes nowhere new. Off: queue order. */
  setShuffle(shuffle: boolean): void {
    if (shuffle !== this.shuffle) {
      this.shuffle = shuffle;
      this.order = shuffle ? shuffledOrder(this.items.length, this.index) : identity(this.items.length);
    }
    this.emit();
  }

  /** The session heart for item [id]; [favorite] null hides it. */
  setFavorite(id: string, favorite: boolean | null): void {
    this.favoriteId = id;
    this.favorite = favorite;
    this.emit();
  }

  // ---- transport ----

  play(): void {
    this.playWhenReady = true;
    this.resumeOnFocusGain = false;
    if (this.items.length === 0) {
      return;
    }
    if (this.phase === 'ended') {
      // Restart the queue from the top of the play order (Spotify), not the last song.
      this.load(this.order.length > 0 ? this.order[0] : 0, 0);
      return;
    }
    if (this.phase === 'error' || this.phase === 'idle') {
      this.load(Math.max(0, this.index), this.lastPositionMs);
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
    this.seekInternal(positionMs);
  }

  /**
   * Seeks; returns true when it also issued play(). Media3 parity: a seek out of STATE_ENDED while the intent
   * (playWhenReady) is still set resumes playback, so 'previous' or a scrub at the end of the queue is audible
   * and the snapshot never says playWhenReady with nothing playing.
   */
  private seekInternal(positionMs: number): boolean {
    let target = Math.max(0, Math.floor(positionMs));
    if (this.durationMs !== null && this.durationMs > 0) {
      target = Math.min(target, Math.floor(this.durationMs));
    }
    this.lastPositionMs = target;
    const p = this.player;
    let resumed = false;
    if (this.phase === 'loading') {
      this.pendingStartMs = target;
    } else if (p !== null && this.isSourceCurrent() && canSeek(p.state)) {
      const wasEnded = this.phase === 'ended';
      if (wasEnded) {
        this.phase = 'ready';
      }
      p.seek(target, media.SeekMode.SEEK_CLOSEST);
      if (wasEnded && this.playWhenReady && canPlay(p.state)) {
        resumed = true;
        p.play().catch((e: BusinessError) => this.onCommandFailed('play', e));
      }
    }
    this.emit();
    return resumed;
  }

  /** Jumps to queue index [index] from the start (same entry: restarts it without counting a repeat). */
  skipTo(index: number): void {
    if (index >= 0 && index < this.items.length) {
      this.load(Math.floor(index), 0);
    }
  }

  /** Next in play order; repeat all and repeat one wrap (Media3 navigates ONE as ALL); repeat off stops at the end. */
  next(): void {
    if (this.items.length === 0) {
      return;
    }
    const target = this.neighbour(1);
    if (target < 0) {
      return;
    }
    if (target === this.index) {
      // A queue of one: back to its start, no new item.
      const resumed = this.seekInternal(0);
      if (!resumed && this.playWhenReady && !this.playing) {
        this.play();
      }
      return;
    }
    this.load(target, 0);
  }

  previous(): void {
    if (this.items.length === 0) {
      return;
    }
    const target = this.neighbour(-1);
    if (target < 0 || target === this.index || this.readPosition() > RESTART_THRESHOLD_MS) {
      this.seekTo(0);
    } else {
      this.load(target, 0);
    }
  }

  /** Ends playback and drops the queue; listeners see index -1 and tear down the AVSession and background task. */
  stop(): void {
    this.generation++;
    this.sourceGeneration = -1;
    this.items = [];
    this.order = [];
    this.index = -1;
    this.phase = 'idle';
    this.playing = false;
    this.playWhenReady = false;
    this.resumeOnFocusGain = false;
    this.durationMs = null;
    this.lastPositionMs = 0;
    this.pendingStartMs = 0;
    this.errorMessage = null;
    this.errorCode = null;
    this.favoriteId = null;
    this.favorite = null;
    this.stopTicker();
    this.enqueue(async (): Promise<void> => {
      const p = this.player;
      if (p !== null && p.state !== 'idle' && p.state !== 'released') {
        await p.reset();
      }
    });
    this.emit();
  }

  /** Process teardown: stops and frees the AVPlayer. */
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

  // ---- navigation ----

  /** The queue index [step] (+1 / -1) away in play order for a manual skip, or -1. */
  private neighbour(step: number): number {
    const n = this.order.length;
    const pos = this.order.indexOf(this.index);
    if (pos < 0 || n === 0) {
      return -1;
    }
    const k = pos + step;
    if (k >= 0 && k < n) {
      return this.order[k];
    }
    if (this.repeat === 'off') {
      return -1;
    }
    return this.order[(k + n) % n];
  }

  /** Where auto-advance goes after the current item finished: itself (repeat one), the next, or -1 (end). */
  private autoNext(): number {
    if (this.repeat === 'one') {
      return this.index;
    }
    return this.neighbour(1);
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
    this.errorCode = null;
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

  /** Repeat one (or a queue of one under repeat all): the same item again from 0, counted as a repeat. */
  private replayCurrent(p: media.AVPlayer): void {
    this.repeatPlays++;
    this.lastPositionMs = 0;
    const gen = this.generation;
    // From 'completed', play() starts over from the beginning.
    p.play().catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'replay failed, reloading: %{public}s', describe(e));
      if (gen === this.generation && this.index >= 0) {
        this.load(this.index, 0);
      }
    });
    this.emit();
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
    // Headphones unplugged / Bluetooth gone (Android setHandleAudioBecomingNoisy): pause, never auto-resume.
    p.on('audioOutputDeviceChangeWithInfo', (info: audio.AudioStreamDeviceChangeInfo) => {
      if (info.changeReason === audio.AudioStreamDeviceChangeReason.REASON_OLD_DEVICE_UNAVAILABLE) {
        this.onBecomingNoisy();
      }
    });
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
      case 'completed': {
        this.playing = false;
        this.stopTicker();
        // Start the next item at once: the reload gap is the only gap (no gapless in v1).
        const target = this.autoNext();
        if (target >= 0 && target === this.index) {
          this.replayCurrent(p);
        } else if (target >= 0) {
          this.load(target, 0);
        } else {
          this.phase = 'ended';
          if (this.durationMs !== null) {
            this.lastPositionMs = this.durationMs;
          }
          this.emit();
        }
        break;
      }
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

  /** The output we played on went away. If the system already paused us this only drops the intent (no double pause). */
  private onBecomingNoisy(): void {
    if (this.playWhenReady || this.playing || this.resumeOnFocusGain) {
      hilog.info(DOMAIN, TAG, 'output device gone: pausing');
      this.pause();
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
    // Never log the stream URL: it carries the account's token.
    hilog.error(DOMAIN, TAG, 'playback error: %{public}s', describe(e));
    this.lastPositionMs = this.readPosition();
    this.phase = 'error';
    this.playing = false;
    this.errorMessage = describe(e);
    this.errorCode = e !== undefined && e !== null && typeof e.code === 'number' ? e.code : null;
    this.stopTicker();
    this.emit();
  }

  private onCommandFailed(command: string, e: BusinessError): void {
    hilog.warn(DOMAIN, TAG, '%{public}s failed: %{public}s', command, describe(e));
  }

  private startTicker(): void {
    if (this.ticker === -1) {
      this.ticker = setInterval(() => this.emit(true), TICK_MS);
    }
  }

  private stopTicker(): void {
    if (this.ticker !== -1) {
      clearInterval(this.ticker);
      this.ticker = -1;
    }
  }

  private emit(tick?: boolean): void {
    const s = this.snapshot(tick);
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

function identity(n: number): number[] {
  const out: number[] = [];
  for (let i = 0; i < n; i++) {
    out.push(i);
  }
  return out;
}

/** [first] then a Fisher–Yates permutation of every other index. */
function shuffledOrder(n: number, first: number): number[] {
  const rest: number[] = [];
  for (let i = 0; i < n; i++) {
    if (i !== first) {
      rest.push(i);
    }
  }
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    const t = rest[i];
    rest[i] = rest[j];
    rest[j] = t;
  }
  if (first >= 0 && first < n) {
    rest.unshift(first);
  }
  return rest;
}

/** Where queue index [i] lands after the entry at [from] moved to [to]. */
function movedIndex(i: number, from: number, to: number): number {
  if (i === from) {
    return to;
  }
  if (from < to && i > from && i <= to) {
    return i - 1;
  }
  if (to < from && i >= to && i < from) {
    return i + 1;
  }
  return i;
}

/** Code and message for logs; any URL in the text is masked (it would carry the token). */
export function describe(e: BusinessError): string {
  if (e === undefined || e === null) {
    return 'unknown error';
  }
  const text = e.code !== undefined ? `${e.code}: ${e.message}` : `${e.message}`;
  return text.replace(new RegExp('https?://\\S+', 'g'), '<url>');
}
