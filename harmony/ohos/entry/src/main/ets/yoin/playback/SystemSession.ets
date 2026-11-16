import { avSession } from '@kit.AVSessionKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { image } from '@kit.ImageKit';
import { ArtworkLoader } from './ArtworkLoader';
import { describe, EngineListener, EngineRepeat, EngineSnapshot, PlaybackEngine } from './PlaybackEngine';
import { ContinuousTasks, HeldTask } from '../background/ContinuousTasks';

const DOMAIN = 0x5901;
const TAG = 'YoinSession';
/** Paused this long, the continuous task is given back (the session stays so the lock screen can resume). */
const PAUSED_TASK_TIMEOUT_MS = 10 * 60 * 1000;
/** A position report off the system's own extrapolation by more than this is a seek worth telling it about. */
const POSITION_DRIFT_MS = 1500;
/** The Want parameter that makes EntryAbility open Now Playing. */
export const YOIN_ACTION_KEY = 'yoinAction';
export const YOIN_ACTION_OPEN_NOW_PLAYING = 'openNowPlaying';

/** Session commands only Dart can answer (the favourite write goes through the music source). */
export type SessionCommandListener = (name: string, id: string) => void;

/**
 * Everything the system sees of playback: one AVSession (播控中心, lock screen, headset keys) and the AUDIO_PLAYBACK
 * continuous task that keeps the process alive in the background. Driven only by engine snapshots; the async
 * system calls are coalesced and run one at a time so a burst of state changes cannot interleave create/destroy.
 */
export class SystemSession {
  private context: common.UIAbilityContext;
  private engine: PlaybackEngine;
  private listener: EngineListener;
  private onCommand: SessionCommandListener;
  private session: avSession.AVSession | null = null;
  private metadataId: string | null = null;
  private metadataDurationMs: number | null = null;
  private metadataHasImage: boolean = false;
  private artwork: ArtworkLoader = new ArtworkLoader();
  /** The cover decoded for [artworkForId], waiting to be set. */
  private artworkForId: string | null = null;
  private artworkPixelMap: image.PixelMap | null = null;
  private stateKey: string = '';
  private lastElapsedMs: number = 0;
  private lastUpdateMs: number = 0;
  private lastPlaying: boolean = false;
  private favoriteRegistered: boolean = false;
  private backgroundRunning: boolean = false;
  /** The AUDIO_PLAYBACK task, ended by its own id so the downloads' task (TransferPlugin) carries on. */
  private audioTask: HeldTask | null = null;
  private pausedTimer: number = -1;
  private launchAgent: WantAgent | null = null;
  private pending: EngineSnapshot | null = null;
  private pumping: boolean = false;

  constructor(context: common.UIAbilityContext, engine: PlaybackEngine, onCommand: SessionCommandListener) {
    this.context = context;
    this.engine = engine;
    this.onCommand = onCommand;
    this.listener = (s: EngineSnapshot) => this.offer(s);
    engine.addListener(this.listener);
  }

  /**
   * A new UIAbility took over (the old one was destroyed while the process played on): the session and the
   * continuous task belong to an ability context, so move them to the new one.
   */
  rebind(context: common.UIAbilityContext): void {
    if (context === this.context) {
      return;
    }
    const old = this.context;
    this.context = context;
    this.launchAgent = null;
    this.retire(old);
    this.offer(this.engine.snapshot());
  }

  /** Detaches from the engine and tears down the session and background task. */
  release(): void {
    this.engine.removeListener(this.listener);
    this.offer(new EngineSnapshot());
  }

  private retire(old: common.UIAbilityContext): void {
    const session = this.session;
    const wasRunning = this.backgroundRunning;
    const task = this.audioTask;
    this.session = null;
    this.backgroundRunning = false;
    this.audioTask = null;
    this.resetSessionState();
    if (session !== null) {
      session.destroy().catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'old session destroy failed: %{public}s', describe(e));
      });
    }
    if (wasRunning && task !== null) {
      ContinuousTasks.stop(old, task).catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'old task stop failed: %{public}s', describe(e));
      });
    }
  }

  private offer(snapshot: EngineSnapshot): void {
    this.pending = snapshot;
    if (!this.pumping) {
      this.pump();
    }
  }

  private async pump(): Promise<void> {
    this.pumping = true;
    while (this.pending !== null) {
      const s: EngineSnapshot = this.pending;
      this.pending = null;
      try {
        await this.apply(s);
      } catch (e) {
        hilog.error(DOMAIN, TAG, 'session update failed: %{public}s', describe(e as BusinessError));
      }
    }
    this.pumping = false;
  }

  private async apply(s: EngineSnapshot): Promise<void> {
    if (s.index < 0) {
      this.cancelPausedTimer();
      await this.destroySession();
      await this.stopBackground();
      return;
    }
    const session = await this.ensureSession();
    await this.syncMetadata(session, s);
    await this.syncFavoriteCommand(session, s);
    await this.syncPlaybackState(session, s);
    await this.syncBackground(s);
  }

  private async ensureSession(): Promise<avSession.AVSession> {
    if (this.session !== null) {
      return this.session;
    }
    const session = await avSession.createAVSession(this.context, 'Yoin', 'audio');
    try {
      session.on('play', () => this.engine.play());
      session.on('pause', () => this.engine.pause());
      session.on('stop', () => this.engine.stop());
      session.on('playNext', () => this.engine.next());
      session.on('playPrevious', () => this.engine.previous());
      session.on('seek', (time: number) => this.engine.seekTo(time));
      // setLoopMode hands over the mode the card shows now; the app picks the next one (setTargetLoopMode,
      // API 18, is the one that carries a target).
      session.on('setLoopMode', (mode: avSession.LoopMode) => this.applyLoopMode(nextLoopMode(mode)));
    } catch (e) {
      // A half-wired session would ignore the user's keys: drop it and retry on the next snapshot.
      session.destroy().catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'session destroy failed: %{public}s', describe(err));
      });
      throw e as Error;
    }
    this.session = session;
    try {
      await session.setSupportedLoopModes([
        avSession.LoopMode.LOOP_MODE_SEQUENCE,
        avSession.LoopMode.LOOP_MODE_SINGLE,
        avSession.LoopMode.LOOP_MODE_LIST,
        avSession.LoopMode.LOOP_MODE_SHUFFLE
      ]);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'setSupportedLoopModes failed: %{public}s', describe(e as BusinessError));
    }
    try {
      await session.setLaunchAbility(await this.getLaunchAgent());
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'setLaunchAbility failed: %{public}s', describe(e as BusinessError));
    }
    await session.activate();
    return session;
  }

  /** The system's loop button (one axis) mapped back onto repeat × shuffle; the engine echoes it to Dart. */
  private applyLoopMode(mode: avSession.LoopMode): void {
    switch (mode) {
      case avSession.LoopMode.LOOP_MODE_LIST:
        this.engine.setRepeat('all');
        this.engine.setShuffle(false);
        break;
      case avSession.LoopMode.LOOP_MODE_SHUFFLE:
        this.engine.setRepeat('all');
        this.engine.setShuffle(true);
        break;
      case avSession.LoopMode.LOOP_MODE_SINGLE:
        this.engine.setRepeat('one');
        this.engine.setShuffle(false);
        break;
      case avSession.LoopMode.LOOP_MODE_SEQUENCE:
        this.engine.setRepeat('off');
        this.engine.setShuffle(false);
        break;
      default:
        break;
    }
  }

  private async syncMetadata(session: avSession.AVSession, s: EngineSnapshot): Promise<void> {
    const item = this.engine.currentItem();
    if (item === null) {
      return;
    }
    const newItem = item.id !== this.metadataId;
    const artworkReady = this.artworkForId === item.id && this.artworkPixelMap !== null && !this.metadataHasImage;
    if (!newItem && s.durationMs === this.metadataDurationMs && !artworkReady) {
      return;
    }
    if (newItem) {
      this.artworkForId = null;
      this.artworkPixelMap = null;
      this.metadataHasImage = false;
    }
    const metadata: avSession.AVMetadata = { assetId: item.id, title: item.title };
    if (item.artist !== undefined) {
      metadata.artist = item.artist;
    }
    if (item.album !== undefined) {
      metadata.album = item.album;
    }
    if (s.durationMs !== null) {
      metadata.duration = s.durationMs;
    }
    const pixelMap = this.artworkForId === item.id ? this.artworkPixelMap : null;
    if (pixelMap !== null) {
      metadata.mediaImage = pixelMap;
    }
    // Title and progress go up at once; the cover follows when decoded.
    await session.setAVMetadata(metadata);
    this.metadataId = item.id;
    this.metadataDurationMs = s.durationMs;
    this.metadataHasImage = pixelMap !== null;
    if (newItem && item.artworkUri !== undefined) {
      this.fetchArtwork(item.id, item.artworkUri);
    }
  }

  private fetchArtwork(id: string, url: string): void {
    this.artwork.load(url).then((pixelMap: image.PixelMap | null) => {
      const current = this.engine.currentItem();
      if (current === null || current.id !== id || this.metadataId !== id) {
        return;
      }
      if (pixelMap !== null) {
        this.artworkForId = id;
        this.artworkPixelMap = pixelMap;
        this.offer(this.engine.snapshot());
      } else {
        this.setUrlArtwork(id, url);
      }
    });
  }

  /** The fetch failed: hand the system the URL string as a last resort. */
  private setUrlArtwork(id: string, url: string): void {
    const session = this.session;
    const item = this.engine.currentItem();
    if (session === null || item === null || item.id !== id) {
      return;
    }
    const metadata: avSession.AVMetadata = { assetId: item.id, title: item.title, mediaImage: url };
    if (item.artist !== undefined) {
      metadata.artist = item.artist;
    }
    if (item.album !== undefined) {
      metadata.album = item.album;
    }
    if (this.metadataDurationMs !== null) {
      metadata.duration = this.metadataDurationMs;
    }
    session.setAVMetadata(metadata).catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'url artwork failed: %{public}s', describe(e));
    });
  }

  /** The heart shows only while Dart says the source can favourite this item. */
  private async syncFavoriteCommand(session: avSession.AVSession, s: EngineSnapshot): Promise<void> {
    const wanted = s.favoriteId !== null && s.favoriteId === s.currentId && s.favorite !== null;
    if (wanted === this.favoriteRegistered) {
      return;
    }
    try {
      if (wanted) {
        session.on('toggleFavorite', (assetId: string) => this.onCommand('toggleFavorite', assetId));
      } else {
        session.off('toggleFavorite');
      }
      this.favoriteRegistered = wanted;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'favorite command not updated: %{public}s', describe(e as BusinessError));
    }
  }

  /** Only real changes cross IPC: the system extrapolates the position from {elapsedTime, updateTime}. */
  private async syncPlaybackState(session: avSession.AVSession, s: EngineSnapshot): Promise<void> {
    const state = toPlaybackState(s);
    const key = `${state.state}|${s.index}|${state.loopMode}|${state.isFavorite}|${s.durationMs}|${s.currentId}`;
    const expected = this.lastPlaying ? this.lastElapsedMs + (s.sampledAtMs - this.lastUpdateMs) : this.lastElapsedMs;
    const drifted = Math.abs(s.positionMs - expected) > POSITION_DRIFT_MS;
    if (key === this.stateKey && !drifted) {
      return;
    }
    await session.setAVPlaybackState(state);
    this.stateKey = key;
    this.lastElapsedMs = s.positionMs;
    this.lastUpdateMs = s.sampledAtMs;
    this.lastPlaying = s.isPlaying;
  }

  /**
   * The continuous task starts as soon as the user wants sound (also while the first buffer loads, so locking the
   * phone then keeps the process), ends at the end / on error, and is given back after a long pause.
   */
  private async syncBackground(s: EngineSnapshot): Promise<void> {
    const wantsSound = s.playWhenReady && (s.phase === 'loading' || s.phase === 'ready');
    if (wantsSound || s.isPlaying) {
      this.cancelPausedTimer();
      await this.startBackground();
    } else if (s.phase === 'ended' || s.phase === 'error' || s.phase === 'idle') {
      this.cancelPausedTimer();
      await this.stopBackground();
    } else if (this.backgroundRunning && this.pausedTimer === -1) {
      this.pausedTimer = setTimeout(() => {
        this.pausedTimer = -1;
        const now = this.engine.snapshot();
        if (!now.isPlaying && !now.playWhenReady) {
          this.stopBackground();
        }
      }, PAUSED_TASK_TIMEOUT_MS);
    }
  }

  private cancelPausedTimer(): void {
    if (this.pausedTimer !== -1) {
      clearTimeout(this.pausedTimer);
      this.pausedTimer = -1;
    }
  }

  private resetSessionState(): void {
    this.metadataId = null;
    this.metadataDurationMs = null;
    this.metadataHasImage = false;
    this.artworkForId = null;
    this.artworkPixelMap = null;
    this.stateKey = '';
    this.lastElapsedMs = 0;
    this.lastUpdateMs = 0;
    this.lastPlaying = false;
    this.favoriteRegistered = false;
  }

  private async destroySession(): Promise<void> {
    const session = this.session;
    this.session = null;
    this.resetSessionState();
    this.artwork.clear();
    if (session !== null) {
      await session.deactivate();
      await session.destroy();
    }
  }

  private async startBackground(): Promise<void> {
    if (this.backgroundRunning) {
      return;
    }
    const agent = await this.getLaunchAgent();
    if (ContinuousTasks.multipleSupported()) {
      // API 21+: a task of its own, so the downloads can hold theirs beside it.
      try {
        this.audioTask = await ContinuousTasks.start(this.context,
          backgroundTaskManager.BackgroundTaskMode.MODE_AUDIO_PLAYBACK,
          backgroundTaskManager.BackgroundTaskSubmode.SUBMODE_NORMAL_NOTIFICATION, agent);
        this.backgroundRunning = true;
        return;
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'audio task request failed, using the single-task call: %{public}s',
          describe(e as BusinessError));
      }
    }
    try {
      await backgroundTaskManager.startBackgroundRunning(this.context,
        backgroundTaskManager.BackgroundMode.AUDIO_PLAYBACK, agent);
      this.audioTask = new HeldTask(-1, -1);
      this.backgroundRunning = true;
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'startBackgroundRunning failed: %{public}s', describe(e as BusinessError));
    }
  }

  private async stopBackground(): Promise<void> {
    if (!this.backgroundRunning) {
      return;
    }
    this.backgroundRunning = false;
    const task = this.audioTask ?? new HeldTask(-1, -1);
    this.audioTask = null;
    try {
      await ContinuousTasks.stop(this.context, task);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'stopBackgroundRunning failed: %{public}s', describe(e as BusinessError));
    }
  }

  /** Tapping the session card or the continuous-task notification brings EntryAbility back with Now Playing open. */
  private async getLaunchAgent(): Promise<WantAgent> {
    if (this.launchAgent !== null) {
      return this.launchAgent;
    }
    const parameters: Record<string, Object> = {};
    parameters[YOIN_ACTION_KEY] = YOIN_ACTION_OPEN_NOW_PLAYING;
    const info: wantAgent.WantAgentInfo = {
      wants: [
        {
          bundleName: this.context.abilityInfo.bundleName,
          abilityName: this.context.abilityInfo.name,
          parameters: parameters
        }
      ],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: 0,
      actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
    };
    const agent = await wantAgent.getWantAgent(info);
    this.launchAgent = agent;
    return agent;
  }
}

function toPlaybackState(s: EngineSnapshot): avSession.AVPlaybackState {
  const state: avSession.AVPlaybackState = {
    state: toSessionState(s),
    speed: 1.0,
    position: { elapsedTime: s.positionMs, updateTime: s.sampledAtMs },
    loopMode: toLoopMode(s.repeat, s.shuffle)
  };
  if (s.durationMs !== null) {
    state.duration = s.durationMs;
  }
  if (s.favoriteId !== null && s.favoriteId === s.currentId && s.favorite !== null) {
    state.isFavorite = s.favorite;
  }
  return state;
}

/**
 * The 播控中心 loop button's cycle: the controller sends the mode it shows and the app advances. Follows the
 * round play-mode button as the card can show it (repeat all → shuffle → repeat one → repeat all); repeat off,
 * which only the split pill reaches, goes back to repeat all like a round-button tap does.
 */
function nextLoopMode(current: avSession.LoopMode): avSession.LoopMode {
  switch (current) {
    case avSession.LoopMode.LOOP_MODE_LIST:
      return avSession.LoopMode.LOOP_MODE_SHUFFLE;
    case avSession.LoopMode.LOOP_MODE_SHUFFLE:
      return avSession.LoopMode.LOOP_MODE_SINGLE;
    default:
      return avSession.LoopMode.LOOP_MODE_LIST;
  }
}

/** One system axis for two app axes: shuffle wins over repeat all / off; repeat one shows as SINGLE. */
function toLoopMode(repeat: EngineRepeat, shuffle: boolean): avSession.LoopMode {
  if (repeat === 'one') {
    return avSession.LoopMode.LOOP_MODE_SINGLE;
  }
  if (shuffle) {
    return avSession.LoopMode.LOOP_MODE_SHUFFLE;
  }
  return repeat === 'all' ? avSession.LoopMode.LOOP_MODE_LIST : avSession.LoopMode.LOOP_MODE_SEQUENCE;
}

function toSessionState(s: EngineSnapshot): avSession.PlaybackState {
  switch (s.phase) {
    case 'loading':
      return avSession.PlaybackState.PLAYBACK_STATE_BUFFERING;
    case 'ready':
      return s.isPlaying ? avSession.PlaybackState.PLAYBACK_STATE_PLAY : avSession.PlaybackState.PLAYBACK_STATE_PAUSE;
    case 'ended':
      return avSession.PlaybackState.PLAYBACK_STATE_COMPLETED;
    case 'error':
      return avSession.PlaybackState.PLAYBACK_STATE_ERROR;
    default:
      return avSession.PlaybackState.PLAYBACK_STATE_IDLE;
  }
}
