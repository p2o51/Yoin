import { avSession } from '@kit.AVSessionKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { describe, EngineListener, EngineSnapshot, PlaybackEngine } from './PlaybackEngine';

const DOMAIN = 0x5901;
const TAG = 'YoinSession';

/**
 * Everything the system sees of playback: one AVSession (播控中心, lock screen, headset keys) and the AUDIO_PLAYBACK
 * continuous task that keeps the process alive in the background. Driven only by engine snapshots; the async
 * system calls are coalesced and run one at a time so a burst of state changes cannot interleave create/destroy.
 */
export class SystemSession {
  private context: common.UIAbilityContext;
  private engine: PlaybackEngine;
  private listener: EngineListener;
  private session: avSession.AVSession | null = null;
  private metadataId: string | null = null;
  private metadataDurationMs: number | null = null;
  private backgroundRunning: boolean = false;
  private launchAgent: WantAgent | null = null;
  private pending: EngineSnapshot | null = null;
  private pumping: boolean = false;

  constructor(context: common.UIAbilityContext, engine: PlaybackEngine) {
    this.context = context;
    this.engine = engine;
    this.listener = (s: EngineSnapshot) => this.offer(s);
    engine.addListener(this.listener);
  }

  /** Detaches from the engine and tears down the session and background task. */
  release(): void {
    this.engine.removeListener(this.listener);
    const idle = new EngineSnapshot();
    this.offer(idle);
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
      await this.destroySession();
      await this.stopBackground();
      return;
    }
    const session = await this.ensureSession();
    await this.syncMetadata(session, s);
    await session.setAVPlaybackState(toPlaybackState(s));
    if (s.isPlaying) {
      await this.startBackground();
    } else if (s.phase === 'ended' || s.phase === 'error') {
      await this.stopBackground();
    }
  }

  private async ensureSession(): Promise<avSession.AVSession> {
    if (this.session !== null) {
      return this.session;
    }
    const session = await avSession.createAVSession(this.context, 'Yoin', 'audio');
    this.session = session;
    session.on('play', () => this.engine.play());
    session.on('pause', () => this.engine.pause());
    session.on('stop', () => this.engine.stop());
    session.on('playNext', () => this.engine.next());
    session.on('playPrevious', () => this.engine.previous());
    session.on('seek', (time: number) => this.engine.seekTo(time));
    try {
      await session.setLaunchAbility(await this.getLaunchAgent());
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'setLaunchAbility failed: %{public}s', describe(e as BusinessError));
    }
    await session.activate();
    return session;
  }

  private async syncMetadata(session: avSession.AVSession, s: EngineSnapshot): Promise<void> {
    const item = this.engine.currentItem();
    if (item === null) {
      return;
    }
    if (item.id === this.metadataId && s.durationMs === this.metadataDurationMs) {
      return;
    }
    const metadata: avSession.AVMetadata = { assetId: item.id, title: item.title };
    if (item.artist !== undefined) {
      metadata.artist = item.artist;
    }
    if (item.album !== undefined) {
      metadata.album = item.album;
    }
    if (item.artworkUri !== undefined) {
      metadata.mediaImage = item.artworkUri;
    }
    if (s.durationMs !== null) {
      metadata.duration = s.durationMs;
    }
    await session.setAVMetadata(metadata);
    this.metadataId = item.id;
    this.metadataDurationMs = s.durationMs;
  }

  private async destroySession(): Promise<void> {
    const session = this.session;
    this.session = null;
    this.metadataId = null;
    this.metadataDurationMs = null;
    if (session !== null) {
      await session.deactivate();
      await session.destroy();
    }
  }

  private async startBackground(): Promise<void> {
    if (this.backgroundRunning) {
      return;
    }
    try {
      await backgroundTaskManager.startBackgroundRunning(this.context,
        backgroundTaskManager.BackgroundMode.AUDIO_PLAYBACK, await this.getLaunchAgent());
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
    try {
      await backgroundTaskManager.stopBackgroundRunning(this.context);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'stopBackgroundRunning failed: %{public}s', describe(e as BusinessError));
    }
  }

  /** Tapping the session card or the continuous-task notification brings EntryAbility back. */
  private async getLaunchAgent(): Promise<WantAgent> {
    if (this.launchAgent !== null) {
      return this.launchAgent;
    }
    const info: wantAgent.WantAgentInfo = {
      wants: [
        {
          bundleName: this.context.abilityInfo.bundleName,
          abilityName: this.context.abilityInfo.name
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
    position: { elapsedTime: s.positionMs, updateTime: s.sampledAtMs }
  };
  if (s.durationMs !== null) {
    state.duration = s.durationMs;
  }
  return state;
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
