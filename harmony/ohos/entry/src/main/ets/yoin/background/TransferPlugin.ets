import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { notificationManager } from '@kit.NotificationKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { ContinuousTasks, HeldTask } from './ContinuousTasks';

const CHANNEL = 'yoin/transfer';
const TAG = 'YoinTransfer';
const DOMAIN = 0x5909;
/** The live view's type for uploads and downloads (the only one a data-transfer task updates). */
const LIVE_VIEW_TYPE_TRANSFER = 8;

/**
 * Serves `yoin/transfer` for Dart's `ChannelBackgroundTransferHost` (lib/platform/background/): while songs download
 * (Dart's OfflineDownloads, plain sockets in this process) the ability holds a DATA_TRANSFER continuous task, so the
 * app isn't suspended behind other apps (Android YoinDownloadService's foreground service). Its live-view
 * notification shows the batch's progress; the system ends a data-transfer task whose progress isn't updated for 10
 * minutes, which Dart's updates (every finished song, at most once a second otherwise) keep well inside.
 * - `hold` {title, text, percent}: starts the task (API 21+, beside playback's own task) or updates its notification.
 *   Answers false when the task can't be held (the app is behind, an older system).
 * - `release`: ends the task and its notification.
 * - Dart hears `ended` {byUser} when the task ended otherwise (the user removed the notification, or the system
 *   stopped it: a slow transfer, no progress update).
 *
 * Why not `request.agent` (the system's own download service): a hosted transfer suspends the app anyway, the task
 * would keep the signed stream address (the account's token) in the system's records, a Subsonic error (a 200 with
 * an XML body) would be stored as the song, and Windows shares Dart's downloader.
 */
export default class YoinTransferPlugin implements FlutterPlugin, MethodCallHandler {
  private readonly context: common.UIAbilityContext;
  private channel: MethodChannel | null = null;
  private task: HeldTask | null = null;
  private starting: Promise<HeldTask | null> | null = null;
  private agent: WantAgent | null = null;
  private listening: boolean = false;
  private readonly onCancel = (info: backgroundTaskManager.ContinuousTaskCancelInfo): void => {
    const task = this.task;
    if (task !== null && info.id === task.id) {
      hilog.info(DOMAIN, TAG, 'transfer task ended by the system or the user: %{public}d', info.reason);
      this.task = null;
      // The user ending it (removing the notification) is a no for this batch; the system ending it (a slow
      // transfer, no progress update) is not, and Dart asks again once it can.
      const args: Map<string, Any> = new Map();
      args.set('byUser', isUserCancel(info));
      this.channel?.invokeMethod('ended', args);
    }
  };

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinTransferPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    // The downloads run in Dart: they stop with this engine, so does the task.
    this.release();
    if (this.listening) {
      this.listening = false;
      try {
        backgroundTaskManager.off('continuousTaskCancel', this.onCancel);
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'cancel listener not removed: %{public}s', describe(e as BusinessError));
      }
    }
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'hold': {
        const title = stringArg(call, 'title');
        const text = stringArg(call, 'text');
        const percent: Any = call.argument('percent');
        this.hold(title, text, typeof percent === 'number' ? percent as number : 0)
          .then((held: boolean) => result.success(held));
        return;
      }
      case 'release':
        this.release().then(() => result.success(null));
        return;
      default:
        result.notImplemented();
    }
  }

  private async hold(title: string, text: string, percent: number): Promise<boolean> {
    if (!ContinuousTasks.multipleSupported()) {
      // Before API 21 an ability holds one task: playback's. Downloads wait while the app is behind, as before.
      return false;
    }
    const task = this.task ?? await this.start();
    if (task === null) {
      return false;
    }
    await this.publish(task, title, text, percent);
    return true;
  }

  private start(): Promise<HeldTask | null> {
    if (this.starting === null) {
      this.starting = this.request().finally(() => {
        this.starting = null;
      });
    }
    return this.starting;
  }

  private async request(): Promise<HeldTask | null> {
    try {
      this.listen();
      const task = await ContinuousTasks.start(this.context, backgroundTaskManager.BackgroundTaskMode.MODE_DATA_TRANSFER,
        backgroundTaskManager.BackgroundTaskSubmode.SUBMODE_LIVE_VIEW_NOTIFICATION, await this.launchAgent());
      this.task = task;
      hilog.info(DOMAIN, TAG, 'transfer task held');
      return task;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'transfer task refused: %{public}s', describe(e as BusinessError));
      return null;
    }
  }

  /** The task's own notification, as a progress live view (the system's download template). */
  private async publish(task: HeldTask, title: string, text: string, percent: number): Promise<void> {
    const progress = Math.max(0, Math.min(99, Math.floor(percent)));
    const template: notificationManager.NotificationTemplate = {
      name: 'downloadTemplate',
      data: { title: title, fileName: text, progressValue: progress }
    };
    const request: notificationManager.NotificationRequest = {
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW,
        systemLiveView: {
          typeCode: LIVE_VIEW_TYPE_TRANSFER,
          title: title,
          text: text,
          progress: { maxValue: 100, currentValue: progress, isPercentage: true }
        }
      },
      id: task.notificationId,
      notificationSlotType: notificationManager.SlotType.LIVE_VIEW,
      template: template
    };
    try {
      await notificationManager.publish(request);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'progress not shown: %{public}s', describe(e as BusinessError));
    }
  }

  private async release(): Promise<void> {
    const pending = this.starting;
    if (pending !== null) {
      await pending;
    }
    const task = this.task;
    this.task = null;
    if (task === null) {
      return;
    }
    try {
      await ContinuousTasks.stop(this.context, task);
      hilog.info(DOMAIN, TAG, 'transfer task released');
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'transfer task not released: %{public}s', describe(e as BusinessError));
    }
  }

  private listen(): void {
    if (this.listening) {
      return;
    }
    try {
      backgroundTaskManager.on('continuousTaskCancel', this.onCancel);
      this.listening = true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'cancel listener not set: %{public}s', describe(e as BusinessError));
    }
  }

  /** Tapping the notification brings Yoin to the front. */
  private async launchAgent(): Promise<WantAgent> {
    if (this.agent !== null) {
      return this.agent;
    }
    const info: wantAgent.WantAgentInfo = {
      wants: [{ bundleName: this.context.abilityInfo.bundleName, abilityName: this.context.abilityInfo.name }],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: 1,
      actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
    };
    const agent = await wantAgent.getWantAgent(info);
    this.agent = agent;
    return agent;
  }
}

/** The user ended the task (USER_CANCEL; USER_CANCEL_REMOVE_NOTIFICATION is reserved for the same). */
function isUserCancel(info: backgroundTaskManager.ContinuousTaskCancelInfo): boolean {
  return info.reason === backgroundTaskManager.ContinuousTaskCancelReason.USER_CANCEL ||
    info.reason === backgroundTaskManager.ContinuousTaskCancelReason.USER_CANCEL_REMOVE_NOTIFICATION;
}

function stringArg(call: MethodCall, key: string): string {
  const v: Any = call.argument(key);
  return typeof v === 'string' ? v as string : '';
}

function describe(e: BusinessError): string {
  return e !== undefined && e !== null ? `${e.code} ${e.message}` : 'unknown';
}
