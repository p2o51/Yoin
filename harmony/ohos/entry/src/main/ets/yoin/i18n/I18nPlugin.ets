import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { i18n, intl } from '@kit.LocalizationKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/i18n';
const TAG = 'YoinI18n';
const DOMAIN = 0x5903;

/** Han → Latin transforms, best first: Android uses `Han-Latin/Names` (surname readings). */
const TRANSLITERATOR_IDS: string[] = ['Han-Latin/Names', 'Hani-Latin/Names', 'Han-Latin', 'Hani-Latin', 'Any-Latin', 'Any-Latn'];

/**
 * Pinyin boundaries for the zh collator fallback: the first syllable of each initial letter (A B C D E F G H J K L
 * M N O P Q R S T W X Y Z; no I, U, V). A Han character files under the last boundary that collates at or before it.
 */
const PINYIN_BOUNDARIES: string[] = ['阿', '八', '嚓', '哒', '妸', '发', '旮', '哈', '讥', '咔', '垃', '痳', '拏', '噢', '妑',
  '七', '呥', '扨', '它', '穵', '夕', '丫', '帀'];
const PINYIN_LETTERS: string = 'abcdefghjklmnopqrstwxyz';

/** Past this many cached names the cache starts over. */
const CACHE_LIMIT = 20000;

/**
 * Serves `yoin/i18n` for Dart's `ChannelNameTransliterator` (lib/platform/i18n/): `latinKeys({texts})` returns one
 * Latin sort form per text, same order. Han runs become pinyin with tone marks via LocalizationKit's
 * `i18n.Transliterator` ("林间" → "lín jiān", "午後のソーダ" → "wǔ hòu のソーダ"); kana, Hangul and everything else
 * pass through, so the Library keeps their own buckets (Android LibraryIndex). Where no Han transliterator exists,
 * the zh pinyin `intl.Collator` supplies the initial letter instead ("周杰伦" → "z 周杰伦"). Results are cached.
 */
export default class YoinI18nPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private transliterator: i18n.Transliterator | null = null;
  private transliteratorResolved = false;
  private collator: intl.Collator | null = null;
  private cache: Map<string, string> = new Map<string, string>();

  getUniqueClassName(): string {
    return 'YoinI18nPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method !== 'latinKeys') {
      result.notImplemented();
      return;
    }
    const raw: Any = call.argument('texts');
    if (!Array.isArray(raw)) {
      result.error('bad_args', 'latinKeys needs a list of texts', null);
      return;
    }
    const texts: Array<Any> = raw as Array<Any>;
    try {
      if (this.cache.size + texts.length > CACHE_LIMIT) {
        this.cache.clear();
      }
      const keys: string[] = [];
      for (let i = 0; i < texts.length; i++) {
        const text: Any = texts[i];
        keys.push(typeof text === 'string' ? this.latinKey(text as string) : '');
      }
      result.success(keys);
    } catch (e) {
      result.error('i18n', `latinKeys failed: ${JSON.stringify(e)}`, null);
    }
  }

  private latinKey(text: string): string {
    const cached = this.cache.get(text);
    if (cached !== undefined) {
      return cached;
    }
    const key = this.transform(text);
    this.cache.set(text, key);
    return key;
  }

  /** [text] with each Han run replaced by its Latin reading, runs set apart by single spaces. */
  private transform(text: string): string {
    const translit = this.getTransliterator();
    if (translit === null) {
      return this.collatorKey(text);
    }
    let out = '';
    let run = '';
    const flush = (): void => {
      if (run.length === 0) {
        return;
      }
      const latin = translit.transform(run).trim();
      run = '';
      if (out.length > 0 && !out.endsWith(' ')) {
        out += ' ';
      }
      out += latin;
      out += ' ';
    };
    for (const ch of text) {
      if (isHan(ch.codePointAt(0) ?? 0)) {
        run += ch;
      } else {
        flush();
        if (!(ch === ' ' && out.endsWith(' '))) {
          out += ch;
        }
      }
    }
    flush();
    return out.trim();
  }

  /** The first transliterator that turns Han into Latin, or null. */
  private getTransliterator(): i18n.Transliterator | null {
    if (this.transliteratorResolved) {
      return this.transliterator;
    }
    this.transliteratorResolved = true;
    let available: string[] = [];
    try {
      available = i18n.Transliterator.getAvailableIDs();
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'getAvailableIDs failed: %{public}s', JSON.stringify(e));
    }
    for (const id of TRANSLITERATOR_IDS) {
      if (available.length > 0 && available.indexOf(id) < 0 && id.indexOf('/') >= 0) {
        continue;
      }
      try {
        const candidate = i18n.Transliterator.getInstance(id);
        const probe = candidate.transform('林');
        if (probe.length > 0 && !containsHan(probe)) {
          hilog.info(DOMAIN, TAG, 'transliterator %{public}s', id);
          this.transliterator = candidate;
          return candidate;
        }
      } catch (e) {
        // Not on this device: try the next one.
      }
    }
    hilog.warn(DOMAIN, TAG, 'no Han transliterator; falling back to the zh collator');
    return null;
  }

  /** Fallback: a leading Han character's pinyin initial ("z 周杰伦"); anything else unchanged. */
  private collatorKey(text: string): string {
    const trimmed = text.trim();
    const first = trimmed.codePointAt(0) ?? 0;
    if (!isHan(first)) {
      return text;
    }
    if (this.collator === null) {
      this.collator = new intl.Collator('zh-Hans-CN', { collation: 'pinyin', sensitivity: 'base' } as intl.CollatorOptions);
    }
    const ch = String.fromCodePoint(first);
    let lo = 0;
    let hi = PINYIN_BOUNDARIES.length - 1;
    let found = 0;
    while (lo <= hi) {
      const mid = Math.floor((lo + hi) / 2);
      if (this.collator.compare(PINYIN_BOUNDARIES[mid], ch) <= 0) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return `${PINYIN_LETTERS.charAt(found)} ${trimmed}`;
  }
}

function containsHan(text: string): boolean {
  for (const ch of text) {
    if (isHan(ch.codePointAt(0) ?? 0)) {
      return true;
    }
  }
  return false;
}

/** Han script: CJK Unified + extensions, compatibility ideographs, 々 and 〇 (same set as Dart's isHan). */
function isHan(c: number): boolean {
  return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF) ||
    (c >= 0x20000 && c <= 0x323AF) || c === 0x3005 || c === 0x3007;
}
