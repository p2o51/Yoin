import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common, Want, wantAgent, WantAgent } from '@kit.AbilityKit';
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
/** The want parameter a notification's tap carries: the link Dart gave it (a restore's backup). */
const LINK_KEY = 'yoinTransferLink';

/**
 * Taps on a notification that carries a link, on their way to Dart (EntryAbility hands over every want). A tap that
 * cold-starts the app arrives before Dart listens, so the latest one waits here until Dart says `ready`.
 */
export class TransferTaps {
  private static channel: MethodChannel | null = null;
  private static ready = false;
  private static pending: string | null = null;

  static attach(channel: MethodChannel): void {
    TransferTaps.channel = channel;
    TransferTaps.ready = false;
  }

  static detach(): void {
    TransferTaps.channel = null;
    TransferTaps.ready = false;
  }

  static markReady(): void {
    TransferTaps.ready = true;
    const pending = TransferTaps.pending;
    TransferTaps.pending = null;
    if (pending !== null) {
      TransferTaps.send(pending);
    }
  }

  /** Reads a notification tap out of [want] (if it is one) and hands its link to Dart. */
  static deliverFrom(want: Want): void {
    const params = want.parameters;
    if (params === undefined) {
      return;
    }
    const link = params[LINK_KEY];
    if (typeof link !== 'string' || (link as string).length === 0) {
      return;
    }
    if (TransferTaps.channel !== null && TransferTaps.ready) {
      TransferTaps.send(link as string);
    } else {
      TransferTaps.pending = link as string;
    }
  }

  private static send(link: string): void {
    const args: Map<string, Any> = new Map();
    args.set('link', link);
    TransferTaps.channel?.invokeMethod('tap', args);
  }
}

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
 * - `hold` and `notify` take a link?: tapping the notification brings Yoin to the front and Dart hears `tap` {link}
 *   (a restore's opens its backup's page, Android's contentIntent); `ready` releases a tap that started the app.
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
  /** A link's want agent, one per link (they must not share one: its parameters would follow the latest). */
  private readonly linkAgents: Map<string, WantAgent> = new Map();
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
    TransferTaps.attach(this.channel);
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
    TransferTaps.detach();
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'hold': {
        const title = stringArg(call, 'title');
        const text = stringArg(call, 'text');
        const percent: Any = call.argument('percent');
        const chip = optionalStringArg(call, 'chip');
        this.hold(title, text, typeof percent === 'number' ? percent as number : -1, chip,
          optionalStringArg(call, 'link')).then((held: boolean) => result.success(held));
        return;
      }
      case 'ready':
        TransferTaps.markReady();
        result.success(null);
        return;
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
          optionalStringArg(call, 'subText'), optionalStringArg(call, 'link')).then(() => result.success(null));
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
  private async hold(title: string, text: string, percent: number, chip: string | null,
    link: string | null): Promise<boolean> {
    if (!ContinuousTasks.multipleSupported()) {
      // Before API 21 an ability holds one task: playback's. Downloads wait while the app is behind, as before.
      return false;
    }
    const task = this.task ?? await this.start();
    if (task === null) {
      return false;
    }
    await this.publish(task, title, text, percent, chip, link);
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
    chip: string | null, link: string | null): Promise<void> {
    let agent: WantAgent | null = null;
    try {
      agent = await this.tapAgent(link);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'tap not set: %{public}s', describe(e as BusinessError));
    }
    try {
      await notificationManager.publish(liveViewRequest(task, title, text, percent, chip, agent));
    } catch (e) {
      if (chip === null) {
        hilog.warn(DOMAIN, TAG, 'progress not shown: %{public}s', describe(e as BusinessError));
        return;
      }
      hilog.warn(DOMAIN, TAG, 'capsule not taken: %{public}s', describe(e as BusinessError));
      try {
        await notificationManager.publish(liveViewRequest(task, title, text, percent, null, agent));
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

  /**
   * A plain notification (a finished restore's result): tapping it brings Yoin to the front (on [link]'s page) and
   * takes it away.
   */
  private async notify(id: number, title: string, text: string, subText: string | null,
    link: string | null): Promise<void> {
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
        wantAgent: await this.tapAgent(link),
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

  /** Tapping a notification with [link] brings Yoin to the front and hands Dart the link; without, just the front. */
  private async tapAgent(link: string | null): Promise<WantAgent> {
    if (link === null) {
      return this.launchAgent();
    }
    const known = this.linkAgents.get(link);
    if (known !== undefined) {
      return known;
    }
    const parameters: Record<string, Object> = {};
    parameters[LINK_KEY] = link;
    const info: wantAgent.WantAgentInfo = {
      wants: [{
        bundleName: this.context.abilityInfo.bundleName,
        abilityName: this.context.abilityInfo.name,
        parameters: parameters
      }],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: linkRequestCode(link),
      actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
    };
    const agent = await wantAgent.getWantAgent(info);
    this.linkAgents.set(link, agent);
    return agent;
  }
}

/** A request code of [link]'s own (2 and up; 1 is the plain launch's), so links never share an agent. */
function linkRequestCode(link: string): number {
  let hash = 0;
  for (let i = 0; i < link.length; i++) {
    hash = (hash * 31 + link.charCodeAt(i)) % 1000000007;
  }
  return 2 + hash % 1000000;
}

/** The task's live view: [percent] below 0, no bar; [chip], the status-bar capsule's text; [agent], what a tap does. */
function liveViewRequest(task: HeldTask, title: string, text: string, percent: number,
  chip: string | null, agent: WantAgent | null): notificationManager.NotificationRequest {
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
  if (agent !== null) {
    request.wantAgent = agent;
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
