/// Which storefront an Apple Music account's songs and albums are named from
/// (Android data/source/applemusic/AppleMusicNameRegion.kt), and where each
/// account's choice is kept.
library;

import '../../platform/storage/stores.dart';
import '../profile/profile.dart';
import '../profile/profile_repository.dart';

/// An account's song names. [key] is stored: never rename one.
enum AppleMusicNameRegion {
  /// The account's own storefront: names as Apple Music shows them.
  off('off'),

  /// Each song from the storefront of the language it was released in ([OriginalNameTarget.detect]).
  original('original'),

  /// Every song from China mainland's storefront, in Simplified Chinese.
  mainlandChina('cn'),

  /// Every song from Japan's storefront, in Japanese.
  japan('jp');

  const AppleMusicNameRegion(this.key);

  final String key;

  static const defaultRegion = off;

  static AppleMusicNameRegion fromKey(String? key) {
    for (final r in values) {
      if (r.key == key) return r;
    }
    return defaultRegion;
  }
}

/// A storefront to ask for names, and the language to ask it in (the `l` parameter).
final class OriginalNameTarget {
  const OriginalNameTarget(this.storefront, this.language);

  final String storefront;
  final String language;

  static const japan = OriginalNameTarget('jp', 'ja');
  static const korea = OriginalNameTarget('kr', 'ko');
  static const mainlandChina = OriginalNameTarget('cn', 'zh-Hans-CN');
  static const _thailand = OriginalNameTarget('th', 'th');
  static const _russia = OriginalNameTarget('ru', 'ru');
  static const _ukraine = OriginalNameTarget('ua', 'uk');
  static const _saudiArabia = OriginalNameTarget('sa', 'ar');
  static const _israel = OriginalNameTarget('il', 'he');
  static const _india = OriginalNameTarget('in', 'hi');
  static const _greece = OriginalNameTarget('gr', 'el');
  static const _bulgaria = OriginalNameTarget('bg', 'bg');

  @override
  bool operator ==(Object other) =>
      other is OriginalNameTarget && other.storefront == storefront && other.language == language;

  @override
  int get hashCode => Object.hash(storefront, language);

  @override
  String toString() => 'OriginalNameTarget($storefront, $language)';

  /// The storefront [region] asks for an item's names, or null when it keeps
  /// the shown ones. [AppleMusicNameRegion.original] goes by the item's own
  /// language ([detect]); the fixed regions ask their storefront for everything.
  static OriginalNameTarget? forRegion(
    AppleMusicNameRegion region, {
    String? title,
    String? artist,
    String? genre,
    String? isrc,
  }) => switch (region) {
    AppleMusicNameRegion.off => null,
    AppleMusicNameRegion.original => detect(title: title, artist: artist, genre: genre, isrc: isrc),
    AppleMusicNameRegion.mainlandChina => mainlandChina,
    AppleMusicNameRegion.japan => japan,
  };

  /// The language a song was released in, as well as its metadata tells: its
  /// genre first (J-Pop, Mandopop…, in English or in Chinese as the CN
  /// storefront writes them), then the country its ISRC was registered in,
  /// then the script its shown names are written in. Null when nothing points
  /// at a language whose storefront writes names differently from English.
  static OriginalNameTarget? detect({String? title, String? artist, String? genre, String? isrc}) =>
      forGenre(genre) ?? forIsrc(isrc) ?? forScript('${title ?? ''} ${artist ?? ''}');

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
    return switch (code.substring(0, 2)) {
      'JP' => japan,
      'KR' => korea,
      'CN' || 'HK' || 'MO' || 'TW' => mainlandChina,
      _ => null,
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
  /// item's own script, so it takes only an answer that is: one that differs
  /// and carries a letter outside the Latin script, and, for Chinese, a Han
  /// character (not a Japanese or Korean name).
  static bool accepts(AppleMusicNameRegion region, OriginalNameTarget target, String? shown, String? resolved) {
    final answer = resolved?.trim() ?? '';
    if (answer.isEmpty || answer == shown?.trim()) return false;
    if (region != AppleMusicNameRegion.original) return true;
    if (!answer.runes.any(_isNonLatinLetter)) return false;
    return target != mainlandChina || answer.runes.any(_isHan);
  }

  static bool _isKana(int c) => (c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF);

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
