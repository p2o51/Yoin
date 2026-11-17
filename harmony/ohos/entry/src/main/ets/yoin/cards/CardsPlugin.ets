import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { common, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { rpc } from '@kit.IPCKit';
import { CardStore, MEMORY_FORM, MemoryPublishItem, PlayPublishItem } from './CardStore';

const CHANNEL = 'yoin/cards';
const DOMAIN = 0x5901;
const TAG = 'YoinCards';

/** The want parameter a card tap carries (PlayCard.ets postCardAction). */
export const YOIN_CARD_KEY = 'yoinCard';

/**
 * The callee method the Play card's play key calls (postCardAction `call`): the system starts EntryAbility in the
 * background if it isn't running (onCreate, no window; FlutterAbility runs Dart there) and hands it the card's params.
 */
export const CARD_PLAY_METHOD = 'yoinCardPlay';

/** Marks the want that brings Yoin forward for a play's fallback page (one delivery, whichever lifecycle path wins). */
const OPEN_TOKEN_KEY = 'yoinCardToken';

/** How long a page waits to be shown once Yoin comes to the front, in case bringing it there failed. */
const AWAIT_FRONT_MS = 15000;

/** UIAbilityContext.startSelf (bring this ability to the front from the background; 2in1 only, 801 elsewhere). */
const START_SELF_API = 26;

/** The callee's answer: the call carries no reply, only an (empty) parcel the system requires. */
class CardCallReply implements rpc.Parcelable {
  marshalling(dataOut: rpc.MessageSequence): boolean {
    return true;
  }

  unmarshalling(dataIn: rpc.MessageSequence): boolean {
    return true;
  }
}

/** A page the play's fallback is bringing Yoin forward for. */
interface AwaitedOpen {
  token: string;
  action: Map<string, Any>;
  sinceMs: number;
}

/**
 * Card taps on their way to Dart. A tap that cold-starts the app arrives
 * before Dart listens, so the latest one waits here until Dart says `ready`.
 */
export class CardsHost {
  private static channel: MethodChannel | null = null;
  private static ready = false;
  private static pending: Map<string, Any> | null = null;
  private static foreground = false;
  private static awaited: AwaitedOpen | null = null;
  private static deliveredToken = '';

  static attach(channel: MethodChannel): void {
    CardsHost.channel = channel;
    CardsHost.ready = false;
  }

  static detach(): void {
    CardsHost.channel = null;
    CardsHost.ready = false;
  }

  static markReady(): void {
    CardsHost.ready = true;
    const pending: Map<string, Any> | null = CardsHost.pending;
    CardsHost.pending = null;
    if (pending !== null) {
      CardsHost.send(pending);
    }
  }

  /** Reads a card tap out of [want] (if it is one) and hands it to Dart. */
  static deliverFrom(want: Want): void {
    const params = want.parameters;
    if (params === undefined) {
      return;
    }
    const kind = params[YOIN_CARD_KEY];
    if (typeof kind !== 'string') {
      return;
    }
    const rawToken = params[OPEN_TOKEN_KEY];
    if (typeof rawToken === 'string') {
      // The fallback page's want: already shown on coming to the front, or shown now.
      const token = rawToken as string;
      if (token === CardsHost.deliveredToken) {
        return;
      }
      CardsHost.deliveredToken = token;
      if (CardsHost.awaited !== null && CardsHost.awaited.token === token) {
        CardsHost.awaited = null;
      }
    }
    CardsHost.deliver(CardsHost.actionOf(kind as string, params['entity'], params['id']));
  }

  /**
   * The Play card's play key (callee [CARD_PLAY_METHOD]): the params come as the JSON string of the card's
   * postCardAction params. Yoin stays where it is (behind other apps, or in front): only the play goes to Dart.
   */
  static deliverCall(data: rpc.MessageSequence): rpc.Parcelable {
    try {
      const params = JSON.parse(data.readString()) as Record<string, Object>;
      CardsHost.deliver(CardsHost.actionOf('play', params['entity'], params['id']));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'card call unreadable: %{public}s', JSON.stringify(e));
    }
    return new CardCallReply();
  }

  /** Whether EntryAbility is in front (its window shown); a play's fallback page then opens in place. */
  static setForeground(foreground: boolean): void {
    CardsHost.foreground = foreground;
    if (!foreground) {
      return;
    }
    // Brought forward without the want (startSelf), or before it arrived: show the awaited page now.
    const awaited: AwaitedOpen | null = CardsHost.awaited;
    CardsHost.awaited = null;
    if (awaited !== null && Date.now() - awaited.sinceMs <= AWAIT_FRONT_MS) {
      CardsHost.deliveredToken = awaited.token;
      CardsHost.deliver(awaited.action);
    }
  }

  /**
   * A card's play could not start (Dart `openDetail`, Android WidgetPlayActivity's fallback): the page, with
   * Yoin in front. In front already: the page opens at once. Behind other apps: Yoin is brought forward with the
   * page's want (as the card's `router` tap does); if the system refuses a start from the background, startSelf
   * (API 26, 2in1) brings this ability forward and the page opens on coming to the front.
   */
  static openDetail(context: common.UIAbilityContext, entity: string, id: string): void {
    const action: Map<string, Any> = CardsHost.actionOf('open', entity, id);
    if (CardsHost.foreground) {
      CardsHost.deliver(action);
      return;
    }
    const token = `${Date.now()}`;
    CardsHost.awaited = { token: token, action: action, sinceMs: Date.now() };
    const want: Want = {
      bundleName: context.abilityInfo.bundleName,
      abilityName: context.abilityInfo.name,
      parameters: { 'yoinCard': 'open', 'entity': entity, 'id': id, 'yoinCardToken': token }
    };
    context.startAbility(want).catch((e: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'bringing Yoin forward failed: %{public}d', e.code);
      CardsHost.startSelf(context);
    });
  }

  private static startSelf(context: common.UIAbilityContext): void {
    if (deviceInfo.sdkApiVersion < START_SELF_API) {
      return;
    }
    try {
      context.startSelf().catch((e: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'startSelf failed: %{public}d', e.code);
      });
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'startSelf failed: %{public}s', JSON.stringify(e));
    }
  }

  private static actionOf(kind: string, entity: Object | undefined, id: Object | undefined): Map<string, Any> {
    const map: Map<string, Any> = new Map();
    map.set('kind', kind);
    if (typeof entity === 'string') {
      map.set('entity', entity as string);
    }
    if (typeof id === 'string') {
      map.set('id', id as string);
    }
    return map;
  }

  private static deliver(map: Map<string, Any>): void {
    hilog.info(DOMAIN, TAG, 'card tap: %{public}s', `${map.get('kind')}`);
    if (CardsHost.channel !== null && CardsHost.ready) {
      CardsHost.send(map);
    } else {
      CardsHost.pending = map;
    }
  }

  private static send(map: Map<string, Any>): void {
    CardsHost.channel?.invokeMethod('action', map);
  }
}

/**
 * Serves `yoin/cards` for Dart's `ChannelCardsHost`: `publishPlay` stores the
 * Play card's items and covers (CardStore) and refreshes every placed card;
 * `ready` releases a tap that arrived before Dart listened; `openDetail` shows
 * a page a card's play could not start, Yoin brought to the front.
 */
export default class YoinCardsPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinCardsPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
    CardsHost.attach(this.channel);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
    CardsHost.detach();
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'ready':
        CardsHost.markReady();
        result.success(null);
        return;
      case 'publishPlay':
        this.publishPlay(call, result);
        return;
      case 'publishMemory':
        this.publishMemory(call, result);
        return;
      case 'openDetail':
        CardsHost.openDetail(this.context, `${call.argument('entity') ?? ''}`, `${call.argument('id') ?? ''}`);
        result.success(null);
        return;
      default:
        result.notImplemented();
    }
  }

  private publishMemory(call: MethodCall, result: MethodResult): void {
    const rawItems: Any = call.argument('items');
    if (!Array.isArray(rawItems)) {
      result.error('bad_args', 'publishMemory needs items', null);
      return;
    }
    const items: MemoryPublishItem[] = [];
    (rawItems as Array<Any>).forEach((raw: Any) => {
      const m: Map<string, Any> = raw as Map<string, Any>;
      const cover: Any = m.get('cover');
      items.push({
        session: `${m.get('session') ?? ''}`,
        album: `${m.get('album') ?? ''}`,
        artist: `${m.get('artist') ?? ''}`,
        score: `${m.get('score') ?? ''}`,
        lastHeard: `${m.get('lastHeard') ?? ''}`,
        cover: cover instanceof Uint8Array ? cover as Uint8Array : null,
      });
    });
    try {
      CardStore.saveMemory(this.context, items);
    } catch (e) {
      const err = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'saving the memory card failed: %{public}d', err.code ?? -1);
      result.error('io', 'Could not save the card data', null);
      return;
    }
    CardStore.refreshAll(this.context, MEMORY_FORM)
      .then(() => result.success(null))
      .catch(() => result.success(null));
  }

  private publishPlay(call: MethodCall, result: MethodResult): void {
    const rawItems: Any = call.argument('items');
    const glyphLight: Any = call.argument('glyphLight');
    const glyphDark: Any = call.argument('glyphDark');
    if (!Array.isArray(rawItems) || !(glyphLight instanceof Uint8Array) || !(glyphDark instanceof Uint8Array)) {
      result.error('bad_args', 'publishPlay needs items and both glyphs', null);
      return;
    }
    const items: PlayPublishItem[] = [];
    (rawItems as Array<Any>).forEach((raw: Any) => {
      const m: Map<string, Any> = raw as Map<string, Any>;
      const cover: Any = m.get('cover');
      items.push({
        entity: `${m.get('entity') ?? ''}`,
        id: `${m.get('id') ?? ''}`,
        title: `${m.get('title') ?? ''}`,
        subtitle: `${m.get('subtitle') ?? ''}`,
        cover: cover instanceof Uint8Array ? cover as Uint8Array : null,
      });
    });
    try {
      CardStore.savePlay(this.context, items, glyphLight as Uint8Array, glyphDark as Uint8Array);
    } catch (e) {
      const err = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'saving the play card failed: %{public}d', err.code ?? -1);
      result.error('io', 'Could not save the card data', null);
      return;
    }
    CardStore.refreshAll(this.context)
      .then(() => result.success(null))
      .catch(() => result.success(null));
  }
}
