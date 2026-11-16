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
/** The label of the plain notifications `notify` posts (a finished restore), apart from the task's own. */
const NOTICE_LABEL = 'yoin_transfer_notice';

/**
 * Serves `yoin/transfer` for Dart's `ChannelBackgroundTransferHost` (lib/platform/background/): while songs download
 * (Dart's OfflineDownloads, plain sockets in this process) or a playlist restore matches and writes, the ability holds
 * a DATA_TRANSFER continuous task, so the app isn't suspended behind other apps (Android YoinDownloadService's
 * foreground service, RestoreLiveUpdate). Dart shares the one task between them (SharedBackgroundTransfer). Its
 * live-view notification shows the work's progress; the system ends a data-transfer task whose progress isn't updated
 * for 10 minutes, which Dart's updates (every finished song, at most once a second otherwise) keep well inside.
 * - `hold` {title, text, percent?, chip?}: starts the task (API 21+, beside playback's own task) or updates its
 *   notification: no percent, no bar; chip, the status-bar capsule's text ("12 / 45"). Answers false when the task
 *   can't be held (the app is behind, an older system).
 * - `release`: ends the task and its notification.
 * - Dart hears `ended` {byUser} when the task ended otherwise (the user removed the notification, or the system
 *   stopped it: a slow transfer, no progress update).
 * - `notificationsAllowed` {ask}: whether notifications are on; with ask, the system's dialog first when it can.
 * - `notify` {id, title, text, subText?}: a plain notification that outlives the task (a finished restore);
 *   `cancelNotice` {id} takes it away.
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
        const chip = optionalStringArg(call, 'chip');
        this.hold(title, text, typeof percent === 'number' ? percent as number : -1, chip)
          .then((held: boolean) => result.success(held));
        return;
      }
      case 'release':
        this.release().then(() => result.success(null));
        return;
      case 'notificationsAllowed': {
        const ask: Any = call.argument('ask');
        this.notificationsAllowed(ask === true).then((allowed: boolean) => result.success(allowed));
        return;
      }
      case 'notify': {
        const id: Any = call.argument('id');
        this.notify(typeof id === 'number' ? id as number : 0, stringArg(call, 'title'), stringArg(call, 'text'),
          optionalStringArg(call, 'subText')).then(() => result.success(null));
        return;
      }
      case 'cancelNotice': {
        const id: Any = call.argument('id');
        this.cancelNotice(typeof id === 'number' ? id as number : 0).then(() => result.success(null));
        return;
      }
      default:
        result.notImplemented();
    }
  }

  /** [percent] below 0: no bar (work whose length isn't known). */
  private async hold(title: string, text: string, percent: number, chip: string | null): Promise<boolean> {
    if (!ContinuousTasks.multipleSupported()) {
      // Before API 21 an ability holds one task: playback's. Downloads wait while the app is behind, as before.
      return false;
    }
    const task = this.task ?? await this.start();
    if (task === null) {
      return false;
    }
    await this.publish(task, title, text, percent, chip);
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

  /**
   * The task's own notification, as a progress live view (the system's download template); with [chip], the
   * status-bar capsule shows it. A capsule the system won't take is dropped rather than losing the progress.
   */
  private async publish(task: HeldTask, title: string, text: string, percent: number,
    chip: string | null): Promise<void> {
    try {
      await notificationManager.publish(liveViewRequest(task, title, text, percent, chip));
    } catch (e) {
      if (chip === null) {
        hilog.warn(DOMAIN, TAG, 'progress not shown: %{public}s', describe(e as BusinessError));
        return;
      }
      hilog.warn(DOMAIN, TAG, 'capsule not taken: %{public}s', describe(e as BusinessError));
      try {
        await notificationManager.publish(liveViewRequest(task, title, text, percent, null));
      } catch (again) {
        hilog.warn(DOMAIN, TAG, 'progress not shown: %{public}s', describe(again as BusinessError));
      }
    }
  }

  private async notificationsAllowed(ask: boolean): Promise<boolean> {
    try {
      let enabled = await notificationManager.isNotificationEnabled();
      if (!enabled && ask) {
        try {
          // The system's dialog, once: a no (or a dialog already shown) rejects.
          await notificationManager.requestEnableNotification(this.context);
        } catch (e) {
          hilog.info(DOMAIN, TAG, 'notifications not enabled: %{public}s', describe(e as BusinessError));
        }
        enabled = await notificationManager.isNotificationEnabled();
      }
      return enabled;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'notification state unknown: %{public}s', describe(e as BusinessError));
      return false;
    }
  }

  /** A plain notification (a finished restore's result): tapping it brings Yoin to the front and takes it away. */
  private async notify(id: number, title: string, text: string, subText: string | null): Promise<void> {
    try {
      const normal: notificationManager.NotificationBasicContent = { title: title, text: text };
      if (subText !== null) {
        normal.additionalText = subText;
      }
      const request: notificationManager.NotificationRequest = {
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: normal
        },
        id: id,
        label: NOTICE_LABEL,
        notificationSlotType: notificationManager.SlotType.SERVICE_INFORMATION,
        wantAgent: await this.launchAgent(),
        tapDismissed: true,
        isAlertOnce: true
      };
      await notificationManager.publish(request);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'notice not posted: %{public}s', describe(e as BusinessError));
    }
  }

  private async cancelNotice(id: number): Promise<void> {
    try {
      await notificationManager.cancel(id, NOTICE_LABEL);
    } catch (e) {
      // None posted (or already swiped away).
      hilog.info(DOMAIN, TAG, 'notice not removed: %{public}s', describe(e as BusinessError));
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

/** The task's live view: [percent] below 0, no bar; [chip], the status-bar capsule's text. */
function liveViewRequest(task: HeldTask, title: string, text: string, percent: number,
  chip: string | null): notificationManager.NotificationRequest {
  const live: notificationManager.NotificationSystemLiveViewContent = {
    typeCode: LIVE_VIEW_TYPE_TRANSFER,
    title: title,
    text: text
  };
  const request: notificationManager.NotificationRequest = {
    content: {
      notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW,
      systemLiveView: live
    },
    id: task.notificationId,
    notificationSlotType: notificationManager.SlotType.LIVE_VIEW
  };
  if (percent >= 0) {
    const progress = Math.max(0, Math.min(99, Math.floor(percent)));
    live.progress = { maxValue: 100, currentValue: progress, isPercentage: true };
    request.template = { name: 'downloadTemplate', data: { title: title, fileName: text, progressValue: progress } };
  }
  if (chip !== null) {
    live.capsule = { title: chip };
  }
  return request;
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

function optionalStringArg(call: MethodCall, key: string): string | null {
  const v: Any = call.argument(key);
  return typeof v === 'string' ? v as string : null;
}

function describe(e: BusinessError): string {
  return e !== undefined && e !== null ? `${e.code} ${e.message}` : 'unknown';
}
