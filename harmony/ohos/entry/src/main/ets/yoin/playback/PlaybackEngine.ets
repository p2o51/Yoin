import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { clampGain, QueueItem } from './QueueItem';
import { AvEnginePlayer, DecodedFormat, EnginePlayer, EnginePlayerEvents, EnginePlayers } from './EnginePlayer';

const DOMAIN = 0x5901;
const TAG = 'YoinPlayback';

/** Below this position `previous` goes to the previous item; above it restarts the track (Spotify / ExoPlayer). */
const RESTART_THRESHOLD_MS = 3000;
/** Position samples while playing; Dart extrapolates in between. */
const TICK_MS = 1000;
/**
 * Gapless (near): this close to the end of the current item, the next one in play order is prepared on a second
 * AVPlayer, and 'completed' (or a manual next) swaps to it and plays at once — no source, buffer or prepare in
 * between. Off = the v1 behaviour (load after the end).
 */
const PRELOAD_NEXT = true;
const PRELOAD_LEAD_MS = 20000;
/** Volume while another app holds shared focus and asks us to duck (Media3's ducked multiplier). */
const DUCK_VOLUME = 0.2;

/**
 * QA only: when the app is launched with want parameter `yoinQaSilent: true`
 * (scripts/device.sh does this), every player outputs at volume 0 so device
 * tests never make sound (night rule; iOS uses -silentAudio the same way).
 */
let qaSilent = false;
/** Engines re-apply their output volume when the QA flag flips (a later launch from the icon clears it). */
const qaSilentListeners: Array<() => void> = [];

export function setQaSilent(silent: boolean): void {
  if (silent === qaSilent) {
    return;
  }
  qaSilent = silent;
  qaSilentListeners.forEach((l: () => void) => l());
}

function outVolume(volume: number): number {
  return qaSilent ? 0 : volume;
}

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

/** What the player decodes for the entry it just opened, and from where (Dart's `PlayingFormatEvent`). */
export class PlayingFormat {
  id: string = '';
  entryId: string | null = null;
  /** The downloaded file opened, not the stream. */
  fromFile: boolean = false;
  decoded: DecodedFormat = new DecodedFormat();
}

export type FormatListener = (format: PlayingFormat) => void;

/**
 * Yoin's spatial-audio switch (Settings › Streaming quality, Dart `SpatialAudioMonitor` through `yoin/audio`).
 * Process-wide like the engine: it outlives the Flutter engine, and the next one sends it again at start.
 */
export class SpatialChoice {
  /** Multichannel songs play folded down to stereo, without the system's spatial rendering. */
  static downmix: boolean = false;
}

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
  /** The player of the current item. */
  private player: EnginePlayer | null = null;
  /** One player per kind ('' = AVPlayer, else a registered URI scheme), made on first use. */
  private players: Map<string, Promise<EnginePlayer>> = new Map();
  private ticker: number = -1;
  /** Set when the system paused us for a transient interruption; a resume hint then restarts playback. */
  private resumeOnFocusGain: boolean = false;
  /** An idle AVPlayer that is never the current one: the next item is prepared on it (gapless). */
  private spare: EnginePlayer | null = null;
  /** The queue entry set on [spare] (identity: a queue edit that replaces it invalidates the preload). */
  private preloadItem: QueueItem | null = null;
  /** [spare] reported 'prepared' for [preloadItem]. */
  private preloadReady: boolean = false;
  /** Bumped by every preload start / discard; a stale preload op does nothing. */
  private preloadToken: number = 0;
  private listeners: EngineListener[] = [];
  private formatListeners: FormatListener[] = [];
  /** The current source's channel count as its decoder reported it; 0 until it does (the spatial switch reads it). */
  private decodedChannels: number = 0;
  private released: boolean = false;
  /** Another app asked us to duck (shared focus); every volume set meanwhile is ducked too. */
  private ducked: boolean = false;

  constructor() {
    qaSilentListeners.push(() => this.reapplyVolume());
  }

  /**
   * The volume [item] plays at: its volume-leveling gain (ReplayGain, from Dart), ducked while another app asks,
   * 0 in QA silent mode. Each AVPlayer holds one item, so setting this before it plays (load, gapless swap) changes
   * the level on the song's first sample (Android switches it in the audio sink at the period boundary).
   */
  private volumeFor(item: QueueItem | null): number {
    const gain = item !== null ? clampGain(item.gain) : 1;
    return outVolume(gain * (this.ducked ? DUCK_VOLUME : 1.0));
  }

  /** The QA flag, the duck or the item's gain changed: the current source plays at the volume that now applies. */
  private reapplyVolume(): void {
    const p = this.player;
    if (p !== null && this.isSourceCurrent() && this.phase === 'ready') {
      p.setVolume(this.volumeFor(this.currentItem()));
    }
  }

  addListener(listener: EngineListener): void {
    this.listeners.push(listener);
  }

  removeListener(listener: EngineListener): void {
    this.listeners = this.listeners.filter((l: EngineListener) => l !== listener);
  }

  addFormatListener(listener: FormatListener): void {
    this.formatListeners.push(listener);
  }

  /**
   * [SpatialChoice] changed: the song playing takes it at once (Android re-sets the player's audio attributes).
   * A multichannel source plays with the audio effect off while the switch is off; everything else with the
   * default effects, so stereo songs never change.
   */
  applySpatialChoice(): void {
    const p = this.player;
    if (p !== null && p instanceof AvEnginePlayer && this.isSourceCurrent()) {
      (p as AvEnginePlayer).setAudioEffectNone(SpatialChoice.downmix && this.decodedChannels > 2);
    }
  }

  /**
   * Reads what [p] decodes for [item] once it is prepared (or swapped in), applies the spatial switch to it and
   * tells the format listeners. Only AVPlayer describes its tracks; other players report nothing.
   */
  private reportFormat(p: EnginePlayer, item: QueueItem | null): void {
    this.decodedChannels = 0;
    if (item === null || !(p instanceof AvEnginePlayer)) {
      return;
    }
    const av = p as AvEnginePlayer;
    const opened: QueueItem = item;
    const gen = this.generation;
    const fromFile = av.openedFile();
    av.decodedFormat().then((decoded: DecodedFormat | null) => {
      if (gen !== this.generation || this.player !== p) {
        return;
      }
      if (decoded === null) {
        // No audio track described: the default effects, not the last song's (an AVPlayer is reused).
        this.applySpatialChoice();
        return;
      }
      this.decodedChannels = decoded.channels;
      this.applySpatialChoice();
      const format = new PlayingFormat();
      format.id = opened.id;
      format.entryId = opened.entryId !== undefined ? opened.entryId : null;
      format.fromFile = fromFile;
      format.decoded = decoded;
      this.formatListeners.forEach((l: FormatListener) => {
        try {
          l(format);
        } catch (e) {
          hilog.error(DOMAIN, TAG, 'format listener threw: %{public}s', describe(e as BusinessError));
        }
      });
    }).catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'track description failed: %{public}s', describe(e));
      if (gen === this.generation && this.player === p) {
        // Channels unknown: the default effects, not the last song's (an AVPlayer is reused).
        this.applySpatialChoice();
      }
    });
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

  /**
   * Volume leveling changed: new gains by entry id. The entry playing takes its new volume at once; the others
   * (a prepared spare included: the swap sets its volume) when they start.
   */
  setGains(gains: Map<string, number>): void {
    let changed = 0;
    this.items.forEach((item: QueueItem) => {
      const id = item.entryId;
      const value = id !== undefined ? gains.get(id) : undefined;
      if (value !== undefined) {
        const gain = clampGain(value);
        if (gain !== item.gain) {
          item.gain = gain;
          changed++;
        }
      }
    });
    if (changed > 0) {
      hilog.info(DOMAIN, TAG, 'volume leveling: %{public}d gain(s) changed', changed);
      this.reapplyVolume();
    }
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
    if (this.phase === 'ready' && p !== null && this.isSourceCurrent() && canPlay(p.state())) {
      p.play().catch((e: BusinessError) => this.onCommandFailed('play', e));
    } else {
      // Still loading: the 'prepared' handler reads playWhenReady.
      this.emit();
    }
  }

  pause(): void {
    this.playWhenReady = false;
    this.resumeOnFocusGain = false;
    // A long pause would leave the prepared stream's connection to time out; prepare again once playing.
    this.discardPreload();
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
    } else if (p !== null && this.isSourceCurrent() && canSeek(p.state())) {
      const wasEnded = this.phase === 'ended';
      if (wasEnded) {
        this.phase = 'ready';
      }
      p.seek(target);
      if (wasEnded && this.playWhenReady && canPlay(p.state())) {
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
    this.ducked = false;
    this.stopTicker();
    this.discardPreload();
    this.enqueue(async (): Promise<void> => {
      const p = this.player;
      if (p !== null && p.state() !== 'idle' && p.state() !== 'released') {
        await p.reset();
      }
    });
    this.emit();
  }

  /** Process teardown: stops and frees every player. */
  release(): void {
    this.stop();
    this.released = true;
    this.enqueue(async (): Promise<void> => {
      const all = Array.from(this.players.values());
      const spare = this.spare;
      this.player = null;
      this.spare = null;
      this.players.clear();
      for (const pending of all) {
        const p = await pending;
        await p.release();
      }
      if (spare !== null) {
        await spare.release();
      }
    });
    this.listeners = [];
    this.formatListeners = [];
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
    if (startMs === 0 && this.swapToPreloaded(index)) {
      return;
    }
    this.discardPreload();
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
      const p = await this.ensurePlayer(item);
      if (gen !== this.generation) {
        return;
      }
      // Switching kind (AVPlayer <-> a registered player): silence the other one first.
      const previous = this.player;
      if (previous !== null && previous !== p && previous.state() !== 'idle' && previous.state() !== 'released') {
        await previous.reset();
      }
      this.player = p;
      if (p.state() !== 'idle') {
        await p.reset();
      }
      if (gen !== this.generation) {
        return;
      }
      this.sourceGeneration = gen;
      await p.setSource(item);
    }, gen);
  }

  // ---- gapless ----

  /**
   * Near the end of a playing item, prepares the next one in play order on the spare AVPlayer. A preload the queue
   * no longer leads to (edit, shuffle, repeat change) is dropped. Items of a registered player (Apple Music) are not
   * preloaded: that player fetches ahead on its own.
   */
  private maybePreload(): void {
    if (!PRELOAD_NEXT || this.released || this.phase !== 'ready' || !this.playing) {
      return;
    }
    const target = this.autoNext();
    let want: QueueItem | null = target >= 0 && target !== this.index ? this.items[target] : null;
    if (want !== null && EnginePlayers.kindOf(want.uri) !== '') {
      want = null;
    }
    if (this.preloadItem !== null && this.preloadItem !== want) {
      this.discardPreload();
    }
    if (want === null || this.preloadItem === want) {
      return;
    }
    const duration = this.durationMs;
    if (duration === null || duration <= 0 || duration - this.readPosition() > PRELOAD_LEAD_MS) {
      return;
    }
    const item: QueueItem = want;
    this.preloadItem = item;
    this.preloadReady = false;
    const token = ++this.preloadToken;
    this.enqueue(async (): Promise<void> => {
      if (token !== this.preloadToken || this.released) {
        return;
      }
      try {
        const p = await this.ensureSpare();
        if (token !== this.preloadToken) {
          return;
        }
        if (p.state() !== 'idle') {
          await p.reset();
        }
        if (token !== this.preloadToken) {
          return;
        }
        await p.setSource(item);
        hilog.info(DOMAIN, TAG, 'preloading the next item');
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'preload failed: %{public}s', describe(e as BusinessError));
        if (token === this.preloadToken) {
          this.discardPreload();
        }
      }
    });
  }

  /**
   * Plays queue index [index] from the prepared spare, if that is what it holds: the spare becomes the AVPlayer
   * of the queue and the old one the next spare. False = load it the usual way.
   */
  private swapToPreloaded(index: number): boolean {
    const spare = this.spare;
    const item = index >= 0 && index < this.items.length ? this.items[index] : null;
    if (item === null || spare === null || !this.preloadReady || this.preloadItem !== item ||
      spare.state() !== 'prepared') {
      return false;
    }
    const gen = ++this.generation;
    const previous = this.player;
    const previousAv = this.players.get('');
    this.spare = null;
    this.preloadItem = null;
    this.preloadReady = false;
    this.preloadToken++;
    this.index = index;
    this.phase = 'ready';
    this.playing = false;
    this.durationMs = spare.duration() > 0 ? spare.duration() : (item.durationMs !== undefined ? item.durationMs : null);
    this.lastPositionMs = 0;
    this.pendingStartMs = 0;
    this.errorMessage = null;
    this.errorCode = null;
    this.stopTicker();
    this.player = spare;
    this.players.set('', Promise.resolve(spare));
    this.sourceGeneration = gen;
    spare.setVolume(this.volumeFor(item));
    logGain(item);
    if (this.playWhenReady) {
      spare.play().catch((e: BusinessError) => this.onCommandFailed('play', e));
    }
    hilog.info(DOMAIN, TAG, 'gapless: swapped to the preloaded item');
    this.emit();
    this.reportFormat(spare, item);
    // The finished item's player is silenced; the old AVPlayer waits as the next spare.
    this.enqueue(async (): Promise<void> => {
      if (previous !== null && previous !== spare && previous.state() !== 'idle' && previous.state() !== 'released') {
        await previous.reset();
      }
      if (previousAv !== undefined) {
        const av = await previousAv;
        if (av !== spare && av !== this.player && this.spare === null) {
          if (av.state() !== 'idle' && av.state() !== 'released') {
            await av.reset();
          }
          this.spare = av;
        }
      }
    });
    return true;
  }

  /** Forgets the preload; the spare is reset in turn (a newer preload resets it anyway). */
  private discardPreload(): void {
    if (this.preloadItem === null) {
      return;
    }
    this.preloadItem = null;
    this.preloadReady = false;
    const token = ++this.preloadToken;
    this.enqueue(async (): Promise<void> => {
      const p = this.spare;
      if (p !== null && token === this.preloadToken && p.state() !== 'idle' && p.state() !== 'released') {
        await p.reset();
      }
    });
  }

  /** The spare AVPlayer, made on first use (only from an op on [opChain], so never two). */
  private async ensureSpare(): Promise<EnginePlayer> {
    const existing = this.spare;
    if (existing !== null) {
      return existing;
    }
    const holder = new PlayerHolder();
    const p = await AvEnginePlayer.create(this.eventsFor(holder));
    holder.player = p;
    this.spare = p;
    return p;
  }

  /** The spare's own events while it prepares the next item (never the current player's). */
  private onSpareState(p: EnginePlayer, state: string): void {
    if (p !== this.spare || this.preloadItem === null) {
      return;
    }
    const token = this.preloadToken;
    switch (state) {
      case 'initialized':
        p.prepareForMusic().catch((e: BusinessError) => {
          hilog.warn(DOMAIN, TAG, 'preload prepare failed: %{public}s', describe(e));
          if (token === this.preloadToken) {
            this.discardPreload();
          }
        });
        break;
      case 'prepared':
        this.preloadReady = true;
        break;
      default:
        break;
    }
  }

  private onSpareError(p: EnginePlayer, e: BusinessError): void {
    if (p === this.spare && this.preloadItem !== null) {
      hilog.warn(DOMAIN, TAG, 'preload error: %{public}s', describe(e));
      if (this.preloadItem.viaCache()) {
        // Loaded again when it starts, from the server.
        this.preloadItem.cacheBypassed = true;
      }
      this.discardPreload();
    }
  }

  /** Repeat one (or a queue of one under repeat all): the same item again from 0, counted as a repeat. */
  private replayCurrent(p: EnginePlayer): void {
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

  private ensurePlayer(item: QueueItem): Promise<EnginePlayer> {
    const kind = EnginePlayers.kindOf(item.uri);
    let pending = this.players.get(kind);
    if (pending === undefined) {
      const holder = new PlayerHolder();
      const events = this.eventsFor(holder);
      const factory = EnginePlayers.factory(kind);
      pending = (factory !== undefined ? factory(events) : AvEnginePlayer.create(events))
        .then((p: EnginePlayer) => {
          holder.player = p;
          return p;
        });
      this.players.set(kind, pending);
    }
    return pending;
  }

  /** A player's events, acted on only while it is the current item's player. */
  private eventsFor(holder: PlayerHolder): EnginePlayerEvents {
    const current = (): EnginePlayer | null =>
      holder.player !== null && holder.player === this.player ? holder.player : null;
    const events: EnginePlayerEvents = {
      onState: (state: string) => {
        const p = current();
        if (p !== null) {
          this.onPlayerState(p, state);
        } else if (holder.player !== null) {
          this.onSpareState(holder.player, state);
        }
      },
      onError: (e: BusinessError) => {
        if (current() !== null && this.isSourceCurrent()) {
          this.fail(e);
        } else if (current() === null && holder.player !== null) {
          this.onSpareError(holder.player, e);
        }
      },
      onTime: (time: number) => {
        if (current() !== null && this.isSourceCurrent() && this.phase !== 'loading') {
          this.lastPositionMs = time;
        }
      },
      onSeekDone: (time: number) => {
        if (current() !== null && this.isSourceCurrent() && this.phase !== 'loading') {
          this.lastPositionMs = time;
          this.emit();
        }
      },
      onDuration: (duration: number) => {
        if (current() !== null && this.isSourceCurrent() && duration > 0 && duration !== this.durationMs) {
          this.durationMs = duration;
          this.emit();
        }
      },
      onInterrupt: (event: audio.InterruptEvent) => {
        if (current() !== null) {
          this.onInterrupt(event);
        }
      },
      onDeviceGone: () => {
        if (current() !== null) {
          this.onBecomingNoisy();
        }
      },
    };
    return events;
  }

  private onPlayerState(p: EnginePlayer, state: string): void {
    if (!this.isSourceCurrent()) {
      return;
    }
    switch (state) {
      case 'initialized': {
        const gen = this.generation;
        p.prepareForMusic().catch((e: BusinessError) => {
          if (gen === this.generation) {
            this.fail(e);
          }
        });
        break;
      }
      case 'prepared': {
        this.phase = 'ready';
        const item = this.currentItem();
        p.setVolume(this.volumeFor(item));
        logGain(item);
        if (p.duration() > 0) {
          this.durationMs = p.duration();
        }
        if (this.pendingStartMs > 0) {
          p.seek(this.pendingStartMs);
        }
        this.pendingStartMs = 0;
        if (this.playWhenReady) {
          p.play().catch((e: BusinessError) => this.onCommandFailed('play', e));
        }
        this.emit();
        this.reportFormat(p, item);
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
        // Start the next item at once: from the preloaded spare when it holds it (load() swaps), else reloaded.
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
        this.ducked = true;
        if (p !== null) {
          p.setVolume(this.volumeFor(this.currentItem()));
        }
        break;
      case audio.InterruptHint.INTERRUPT_HINT_UNDUCK:
        this.ducked = false;
        if (p !== null) {
          p.setVolume(this.volumeFor(this.currentItem()));
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
    if (p !== null && this.isSourceCurrent() && p.state() === 'playing') {
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
      canSeek(p.state())) {
      return p.currentTime();
    }
    return this.lastPositionMs;
  }

  private fail(e: BusinessError): void {
    const item = this.currentItem();
    if (item !== null && item.viaCache()) {
      // The stream cache failed (its server gone with the Flutter engine, or it could not reach the server):
      // the same item again from the server, where it was (Android FLAG_IGNORE_CACHE_ON_ERROR).
      hilog.warn(DOMAIN, TAG, 'stream cache failed, streaming directly: %{public}s', describe(e));
      item.cacheBypassed = true;
      this.load(this.index, this.readPosition());
      return;
    }
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
      this.ticker = setInterval(() => {
        this.emit(true);
        this.maybePreload();
      }, TICK_MS);
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

/** The volume-leveling gain an item starts at (debug log, like Android's YoinReplayGain). */
function logGain(item: QueueItem | null): void {
  if (item !== null && item.gain !== 1) {
    hilog.info(DOMAIN, TAG, 'volume leveling: x%{public}s', item.gain.toFixed(4));
  }
}

function canPlay(state: string): boolean {
  return state === 'prepared' || state === 'paused' || state === 'completed';
}

function canSeek(state: string): boolean {
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

/** Fills in a player once its factory resolves (events can arrive before that). */
class PlayerHolder {
  player: EnginePlayer | null = null;
}

/** Code and message for logs; any URL in the text is masked (it would carry the token). */
export function describe(e: BusinessError): string {
  if (e === undefined || e === null) {
    return 'unknown error';
  }
  const text = e.code !== undefined ? `${e.code}: ${e.message}` : `${e.message}`;
  return text.replace(new RegExp('https?://\\S+', 'g'), '<url>');
}
