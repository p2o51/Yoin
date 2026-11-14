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
import { BusinessError } from '@kit.BasicServicesKit';
import { CardStore, PlayPublishItem } from './CardStore';

const CHANNEL = 'yoin/cards';
const DOMAIN = 0x5901;
const TAG = 'YoinCards';

/** The want parameter a card tap carries (PlayCard.ets postCardAction). */
export const YOIN_CARD_KEY = 'yoinCard';

/**
 * Card taps on their way to Dart. A tap that cold-starts the app arrives
 * before Dart listens, so the latest one waits here until Dart says `ready`.
 */
export class CardsHost {
  private static channel: MethodChannel | null = null;
  private static ready = false;
  private static pending: Map<string, Any> | null = null;

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
    const map: Map<string, Any> = new Map();
    map.set('kind', kind as string);
    const entity = params['entity'];
    const id = params['id'];
    if (typeof entity === 'string') {
      map.set('entity', entity as string);
    }
    if (typeof id === 'string') {
      map.set('id', id as string);
    }
    hilog.info(DOMAIN, TAG, 'card tap: %{public}s', kind as string);
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
 * `ready` releases a tap that arrived before Dart listened.
 */
export default class YoinCardsPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private readonly context: common.Context;

  constructor(context: common.Context) {
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
      default:
        result.notImplemented();
    }
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
