import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { common, WantAgent } from '@kit.AbilityKit';
import { deviceInfo } from '@kit.BasicServicesKit';

/** API 21 lets one UIAbility hold several continuous tasks, each stopped by its own id. */
const MULTIPLE_TASKS_API = 21;

/**
 * A continuous task this app holds. [id] is -1 for a task started with the pre-API-21 call, which only
 * `stopBackgroundRunning(context)` (every task of the ability) can end.
 */
export class HeldTask {
  readonly id: number;
  readonly notificationId: number;

  constructor(id: number, notificationId: number) {
    this.id = id;
    this.notificationId = notificationId;
  }
}

/**
 * The app's continuous tasks: playback's AUDIO_PLAYBACK task (SystemSession) and the downloads' DATA_TRANSFER task
 * (TransferPlugin) live side by side, each with its own id, so ending one never ends the other.
 */
export class ContinuousTasks {
  /** Whether this system holds several tasks per ability (API 21+). */
  static multipleSupported(): boolean {
    return deviceInfo.sdkApiVersion >= MULTIPLE_TASKS_API;
  }

  /** Starts a task of [mode] / [submode] (see BackgroundTaskSubmode's mapping) whose notification opens [agent]. */
  static async start(context: common.UIAbilityContext, mode: backgroundTaskManager.BackgroundTaskMode,
    submode: backgroundTaskManager.BackgroundTaskSubmode, agent: WantAgent): Promise<HeldTask> {
    const request = new backgroundTaskManager.ContinuousTaskRequest();
    request.backgroundTaskModes = [mode];
    request.backgroundTaskSubmodes = [submode];
    request.wantAgent = agent;
    request.combinedTaskNotification = false;
    const res: backgroundTaskManager.ContinuousTaskNotification =
      await backgroundTaskManager.startBackgroundRunning(context, request);
    return new HeldTask(res.continuousTaskId ?? -1, res.notificationId);
  }

  /** Ends [task] only (or, for a pre-API-21 task, the ability's tasks). */
  static async stop(context: common.UIAbilityContext, task: HeldTask): Promise<void> {
    if (task.id >= 0 && ContinuousTasks.multipleSupported()) {
      await backgroundTaskManager.stopBackgroundRunning(context, task.id);
    } else {
      await backgroundTaskManager.stopBackgroundRunning(context);
    }
  }
}
