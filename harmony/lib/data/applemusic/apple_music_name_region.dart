/// Which storefront an Apple Music account's songs and albums are named from
/// (Android data/source/applemusic/AppleMusicNameRegion.kt), and where each
/// account's choice is kept.
library;

import '../../platform/storage/stores.dart';
import '../profile/profile.dart';
import '../profile/profile_repository.dart';

/// One curated Apple Music storefront option with its official primary language tag (`l` parameter).
/// Matches Android and iOS in exact code order.
final class AppleMusicStorefrontOption {
  const AppleMusicStorefrontOption(this.code, this.language, this.enName, this.zhName);

  final String code;
  final String language;
  final String enName;
  final String zhName;

  String displayName({required bool zh}) => zh ? zhName : enName;

  static const us = AppleMusicStorefrontOption('us', 'en-US', 'United States', '美国');
  static const gb = AppleMusicStorefrontOption('gb', 'en-GB', 'United Kingdom', '英国');
  static const jp = AppleMusicStorefrontOption('jp', 'ja', 'Japan', '日本');
  static const cn = AppleMusicStorefrontOption('cn', 'zh-Hans-CN', 'China mainland', '中国大陆');
  static const tw = AppleMusicStorefrontOption('tw', 'zh-Hant-TW', 'Taiwan', '台湾');
  static const hk = AppleMusicStorefrontOption('hk', 'zh-Hant-HK', 'Hong Kong', '香港');
  static const kr = AppleMusicStorefrontOption('kr', 'ko', 'South Korea', '韩国');
  static const ca = AppleMusicStorefrontOption('ca', 'en-CA', 'Canada', '加拿大');
  static const au = AppleMusicStorefrontOption('au', 'en-AU', 'Australia', '澳大利亚');
  static const de = AppleMusicStorefrontOption('de', 'de-DE', 'Germany', '德国');
  static const fr = AppleMusicStorefrontOption('fr', 'fr-FR', 'France', '法国');
  static const es = AppleMusicStorefrontOption('es', 'es-ES', 'Spain', '西班牙');
  static const it = AppleMusicStorefrontOption('it', 'it-IT', 'Italy', '意大利');
  static const br = AppleMusicStorefrontOption('br', 'pt-BR', 'Brazil', '巴西');
  static const mx = AppleMusicStorefrontOption('mx', 'es-MX', 'Mexico', '墨西哥');
  static const inStorefront = AppleMusicStorefrontOption('in', 'hi', 'India', '印度');
  static const th = AppleMusicStorefrontOption('th', 'th', 'Thailand', '泰国');
  static const ru = AppleMusicStorefrontOption('ru', 'ru', 'Russia', '俄罗斯');

  static const List<AppleMusicStorefrontOption> curated = [
    us,
    gb,
    jp,
    cn,
    tw,
    hk,
    kr,
    ca,
    au,
    de,
    fr,
    es,
    it,
    br,
    mx,
    inStorefront,
    th,
    ru,
  ];

  static AppleMusicStorefrontOption? forCode(String code) {
    final normalized = code.trim().toLowerCase();
    for (final option in curated) {
      if (option.code == normalized) return option;
    }
    return null;
  }

  @override
  bool operator ==(Object other) =>
      other is AppleMusicStorefrontOption && other.code == code && other.language == language;

  @override
  int get hashCode => Object.hash(code, language);

  @override
  String toString() => 'AppleMusicStorefrontOption($code, $language)';
}

/// An account's song names. [key] is stored: never rename `"off"`, `"original"`, or storefront codes.
sealed class AppleMusicNameRegion {
  const AppleMusicNameRegion();

  String get key;

  /// The account's own storefront: names as Apple Music shows them.
  static const AppleMusicNameRegion off = _OffNameRegion();

  /// Each song from the storefront of the language it was released in ([OriginalNameTarget.detect]).
  static const AppleMusicNameRegion original = _OriginalNameRegion();

  /// Every song from China mainland's storefront, in Simplified Chinese.
  static const AppleMusicNameRegion mainlandChina = AppleMusicStorefrontRegion(AppleMusicStorefrontOption.cn);

  /// Every song from Japan's storefront, in Japanese.
  static const AppleMusicNameRegion japan = AppleMusicStorefrontRegion(AppleMusicStorefrontOption.jp);

  static const AppleMusicNameRegion defaultRegion = off;

  factory AppleMusicNameRegion.storefront(AppleMusicStorefrontOption option) = AppleMusicStorefrontRegion;

  static AppleMusicNameRegion fromKey(String? key) {
    final normalized = key?.trim().toLowerCase();
    if (normalized == null || normalized.isEmpty) return defaultRegion;
    if (normalized == 'off') return off;
    if (normalized == 'original') return original;
    final option = AppleMusicStorefrontOption.forCode(normalized);
    return option != null ? AppleMusicStorefrontRegion(option) : defaultRegion;
  }
}

final class _OffNameRegion extends AppleMusicNameRegion {
  const _OffNameRegion();

  @override
  String get key => 'off';

  @override
  bool operator ==(Object other) => other is _OffNameRegion;

  @override
  int get hashCode => 'off'.hashCode;
}

final class _OriginalNameRegion extends AppleMusicNameRegion {
  const _OriginalNameRegion();

  @override
  String get key => 'original';

  @override
  bool operator ==(Object other) => other is _OriginalNameRegion;

  @override
  int get hashCode => 'original'.hashCode;
}

/// Every song from one chosen Apple Music storefront, in that storefront's primary language.
final class AppleMusicStorefrontRegion extends AppleMusicNameRegion {
  const AppleMusicStorefrontRegion(this.option);

  final AppleMusicStorefrontOption option;

  @override
  String get key => option.code;

  @override
  bool operator ==(Object other) => other is AppleMusicStorefrontRegion && other.option == option;

  @override
  int get hashCode => option.hashCode;

  @override
  String toString() => 'AppleMusicStorefrontRegion(${option.code})';
}

/// A storefront to ask for names, and the language to ask it in (the `l` parameter).
final class OriginalNameTarget {
  const OriginalNameTarget(this.storefront, this.language);

  final String storefront;
  final String language;

  static const japan = OriginalNameTarget('jp', 'ja');
  static const korea = OriginalNameTarget('kr', 'ko');
  static const mainlandChina = OriginalNameTarget('cn', 'zh-Hans-CN');
  static const unitedStates = OriginalNameTarget('us', 'en-US');
  static const _thailand = OriginalNameTarget('th', 'th');
  static const _russia = OriginalNameTarget('ru', 'ru');
  static const _ukraine = OriginalNameTarget('ua', 'uk');
  static const _saudiArabia = OriginalNameTarget('sa', 'ar');
  static const _israel = OriginalNameTarget('il', 'he');
  static const _india = OriginalNameTarget('in', 'hi');
  static const _greece = OriginalNameTarget('gr', 'el');
  static const _bulgaria = OriginalNameTarget('bg', 'bg');

  static const _latinIsrcCountries = {
    'US', 'QM', 'QZ', 'GB', 'UK', 'CA', 'AU', 'NZ', 'IE',
    'SE', 'NO', 'DK', 'FI', 'IS', 'NL', 'BE', 'DE', 'AT', 'CH',
    'FR', 'IT', 'ES', 'PT', 'BR', 'MX', 'AR', 'CO', 'CL', 'PL', 'CZ', 'ZA',
  };

  @override
  bool operator ==(Object other) =>
      other is OriginalNameTarget && other.storefront == storefront && other.language == language;

  @override
  int get hashCode => Object.hash(storefront, language);

  @override
  String toString() => 'OriginalNameTarget($storefront, $language)';

  /// The storefront [region] asks for an item's names, or null when it keeps
  /// the shown ones. [AppleMusicNameRegion.original] goes by the item's own
  /// language ([detect]); a fixed [AppleMusicStorefrontRegion] asks its storefront for everything.
  static OriginalNameTarget? forRegion(
    AppleMusicNameRegion region, {
    String? title,
    String? artist,
    String? genre,
    String? isrc,
  }) => switch (region) {
    _OffNameRegion() => null,
    _OriginalNameRegion() => detect(title: title, artist: artist, genre: genre, isrc: isrc),
    AppleMusicStorefrontRegion(:final option) => OriginalNameTarget(option.code, option.language),
  };

  /// The language a song was released in, as well as its metadata tells: its
  /// genre first (J-Pop, Mandopop…, in English or in Chinese as the CN
  /// storefront writes them), then the country its ISRC was registered in
  /// (including Western/Latin countries when the shown text was transliterated
  /// into Katakana/Hangul/Han), then the script its shown names are written in.
  static OriginalNameTarget? detect({String? title, String? artist, String? genre, String? isrc}) {
    final shown = '${title ?? ''} ${artist ?? ''}';
    final byGenre = forGenre(genre);
    if (byGenre != null) return byGenre;
    final byIsrc = forIsrc(isrc);
    if (byIsrc != null) {
      if (byIsrc != unitedStates || shown.runes.any(_isNonLatinLetter)) return byIsrc;
      return null;
    }
    if (_looksLikeKatakanaTransliteration(shown)) return unitedStates;
    return forScript(shown);
  }

  static OriginalNameTarget? forGenre(String? genre) {
    final g = genre?.trim().toLowerCase() ?? '';
    if (g.isEmpty) return null;
    bool has(List<String> markers) => markers.any(g.contains);
    if (has(const ['j-pop', 'japanese', 'anime', '日本流行', '日语流行', '日語流行', '动漫', '動漫'])) return japan;
    if (has(const ['k-pop', 'korean', '韩国流行', '韓國流行', '韩语流行', '韓語流行'])) return korea;
    if (has(const [
      'mandopop',
      'cantopop',
      'chinese',
      'hong kong',
      'c-pop',
      '国语流行',
      '國語流行',
      '华语流行',
      '華語流行',
      '中文流行',
      '粤语流行',
      '粵語流行',
    ])) {
      return mainlandChina;
    }
    if (has(const ['thai'])) return _thailand;
    if (has(const ['russian'])) return _russia;
    if (has(const ['ukrain'])) return _ukraine;
    if (has(const ['arab', 'khaleeji'])) return _saudiArabia;
    if (has(const ['israel', 'hebrew'])) return _israel;
    if (has(const ['bollywood', 'indian'])) return _india;
    if (has(const ['greek'])) return _greece;
    if (has(const ['bulgar'])) return _bulgaria;
    return null;
  }

  /// The ISRC's first two letters name the country of its registrant, which mostly is the artist's.
  static OriginalNameTarget? forIsrc(String? isrc) {
    final code = isrc?.trim().toUpperCase() ?? '';
    if (code.length < 2) return null;
    final prefix = code.substring(0, 2);
    return switch (prefix) {
      'JP' => japan,
      'KR' => korea,
      'CN' || 'HK' || 'MO' || 'TW' => mainlandChina,
      _ => _latinIsrcCountries.contains(prefix) ? unitedStates : null,
    };
  }

  /// Kana is Japanese and Hangul Korean; Han alone is Chinese. Latin script says nothing.
  static OriginalNameTarget? forScript(String text) {
    if (text.runes.any(_isKana)) return japan;
    if (text.runes.any(_isHangul)) return korea;
    if (text.runes.any(_isHan)) return mainlandChina;
    return null;
  }

  /// Whether names a storefront answered for [target] are worth showing
  /// instead of the shown ones. A fixed region shows whatever its storefront
  /// calls the item. [AppleMusicNameRegion.original] asks for names in the
  /// item's own script: for [unitedStates], accept a Latin name that drops
  /// Katakana/Hangul/Han transliteration; for non-Latin targets, require a
  /// non-Latin letter (and Han for Chinese).
  static bool accepts(AppleMusicNameRegion region, OriginalNameTarget target, String? shown, String? resolved) {
    final answer = resolved?.trim() ?? '';
    if (answer.isEmpty || answer == shown?.trim()) return false;
    if (region != AppleMusicNameRegion.original) return true;
    if (target == unitedStates) {
      final shownText = shown?.trim() ?? '';
      final answerNonLatin = answer.runes.where(_isNonLatinLetter).length;
      final shownNonLatin = shownText.runes.where(_isNonLatinLetter).length;
      return answer.runes.any(_isLatinLetter) && answerNonLatin < shownNonLatin;
    }
    if (!answer.runes.any(_isNonLatinLetter)) return false;
    return target != mainlandChina || answer.runes.any(_isHan);
  }

  static bool _isHiragana(int c) => c >= 0x3040 && c <= 0x309F;

  static bool _isKatakana(int c) => (c >= 0x30A1 && c <= 0x30FA) || (c >= 0x31F0 && c <= 0x31FF);

  static bool _isKana(int c) => (c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF);

  static bool _looksLikeKatakanaTransliteration(String text) {
    if (!text.contains('・')) return false;
    if (text.runes.any(_isHiragana) || text.runes.any(_isHan)) return false;
    return text.runes.any(_isKatakana);
  }

  static bool _isLatinLetter(int c) {
    final ch = String.fromCharCode(c);
    return _letter.hasMatch(ch) && _latin.hasMatch(ch);
  }

  static bool _isHangul(int c) =>
      (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F);

  /// CJK Unified Ideographs and their extensions, compatibility ideographs.
  static bool _isHan(int c) =>
      (c >= 0x4E00 && c <= 0x9FFF) ||
      (c >= 0x3400 && c <= 0x4DBF) ||
      (c >= 0x20000 && c <= 0x2EBEF) ||
      (c >= 0xF900 && c <= 0xFAFF) ||
      (c >= 0x2F800 && c <= 0x2FA1F) ||
      c == 0x3007;

  static final _letter = RegExp(r'\p{L}', unicode: true);
  static final _latin = RegExp(r'\p{Script=Latin}', unicode: true);

  static bool _isNonLatinLetter(int c) {
    final ch = String.fromCharCode(c);
    return _letter.hasMatch(ch) && !_latin.hasMatch(ch);
  }
}

/// Each Apple Music account's [AppleMusicNameRegion] (Android
/// AppleMusicNameRegionStore), by profile id. Never synced as a secret.
final class AppleMusicNameRegionStore {
  const AppleMusicNameRegionStore(this._values);

  final KeyValueStore _values;

  static String keyFor(String profileId) => 'apple_music.name_region.$profileId';

  Future<AppleMusicNameRegion> read(String profileId) async {
    try {
      return AppleMusicNameRegion.fromKey(await _values.read(keyFor(profileId)));
    } catch (_) {
      return AppleMusicNameRegion.defaultRegion;
    }
  }

  Future<void> write(String profileId, AppleMusicNameRegion region) =>
      _values.write(keyFor(profileId), region == AppleMusicNameRegion.defaultRegion ? null : region.key);
}

/// Reads the name region of the account [credentials] belong to, for its
/// source (a source is built from credentials alone): the account is found
/// once by its Music User Token among the stored accounts, then its region is
/// read from [values] each time, so a change applies to the next list. The
/// default while no stored account holds them.
Future<AppleMusicNameRegion> Function() appleMusicNameRegionReader(
  ProfileRepository Function() profiles,
  KeyValueStore Function() values,
  AppleMusicCredentials credentials,
) {
  String? profileId;
  return () async {
    try {
      profileId ??= await _profileHolding(profiles(), credentials);
      final id = profileId;
      if (id == null) return AppleMusicNameRegion.defaultRegion;
      return AppleMusicNameRegionStore(values()).read(id);
    } catch (_) {
      return AppleMusicNameRegion.defaultRegion; // names as shown
    }
  };
}

Future<String?> _profileHolding(ProfileRepository profiles, AppleMusicCredentials credentials) async {
  for (final p in await profiles.profiles()) {
    if (p.provider != credentials.provider) continue;
    final stored = await profiles.credentials(p.id);
    if (stored is AppleMusicCredentials && stored.musicUserToken == credentials.musicUserToken) return p.id;
  }
  return null;
}
