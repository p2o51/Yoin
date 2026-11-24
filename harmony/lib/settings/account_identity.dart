import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/media_id.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/shapes/shape_path.dart';
import 'service_marks.dart';
import 'settings_colors.dart';
import 'settings_metrics.dart';

// Who an account is, at a glance (AccountIdentity.kt). The service is its
// colour (your Subsonic server blue), its mark on the avatar and its name on
// the line under the account; the avatar's shape and letter say which
// account, so two logins on one service never look alike.

/// What a service's face shows: a Yoin Symbol, or its redrawn brand mark
/// (ServiceMarks.kt — artwork, so it lives in the app).
@immutable
sealed class ServiceGlyph {
  const ServiceGlyph();
}

/// A Yoin Symbol.
final class SymbolGlyph extends ServiceGlyph {
  const SymbolGlyph(this.icon);

  final IconData icon;

  @override
  bool operator ==(Object other) => other is SymbolGlyph && other.icon == icon;

  @override
  int get hashCode => icon.hashCode;
}

/// A redrawn service mark ([ServiceMark]).
final class MarkGlyph extends ServiceGlyph {
  const MarkGlyph(this.mark);

  final ServiceMark mark;

  @override
  bool operator ==(Object other) => other is MarkGlyph && other.mark == mark;

  @override
  int get hashCode => mark.hashCode;
}

/// A [ServiceGlyph] drawn the way an [Icon] is: [size] square, in [color]
/// (the ambient [IconTheme]'s when not given).
class ServiceGlyphIcon extends StatelessWidget {
  const ServiceGlyphIcon(this.glyph, {super.key, this.size, this.color});

  final ServiceGlyph glyph;
  final double? size;
  final Color? color;

  @override
  Widget build(BuildContext context) => switch (glyph) {
    SymbolGlyph(:final icon) => Icon(icon, size: size, color: color),
    MarkGlyph(:final mark) => ServiceMarkIcon(mark, size: size, color: color),
  };
}

/// A service's face in Settings: its name, its mark and its hue.
@immutable
final class ServiceIdentity {
  const ServiceIdentity({required this.name, required this.glyph, required this.hue});

  final String name;
  final ServiceGlyph glyph;
  final SettingsHue hue;

  /// Subsonic: Android draws it with the Cloud symbol (Spotify and Apple Music
  /// have redrawn marks in ServiceMarks.kt; Subsonic has none there).
  static const subsonic = ServiceIdentity(
    name: 'Subsonic',
    glyph: SymbolGlyph(YoinSymbols.cloud),
    hue: SettingsHue.blue,
  );

  /// Apple Music: Android's redrawn mark (two eighth notes under a heavy
  /// slanted beam, [ServiceMark.appleMusic]) in the rose hue.
  static const appleMusic = ServiceIdentity(
    name: 'Apple Music',
    glyph: MarkGlyph(ServiceMark.appleMusic),
    hue: SettingsHue.rose,
  );

  /// Files on this device (Android ProviderKind.LOCAL): not built yet; the
  /// add-account sheet lists it as coming later.
  static const local = ServiceIdentity(name: 'Local', glyph: SymbolGlyph(YoinSymbols.folder), hue: SettingsHue.amber);

  /// The identity for a [MediaId] provider token; Subsonic for anything else.
  static ServiceIdentity forProvider(String provider) =>
      provider == MediaId.providerAppleMusic ? appleMusic : subsonic;
}

/// How many avatar shapes there are.
const int avatarShapeCount = 8;

/// Eight clearly different silhouettes, no two scalloped circles (Android
/// avatarPolygon), by slot.
RoundedPolygon avatarPolygon(int index) => switch (index % avatarShapeCount) {
  0 => MaterialShapes.cookie6Sided,
  1 => MaterialShapes.clover4Leaf,
  2 => MaterialShapes.gem,
  3 => MaterialShapes.arch,
  4 => MaterialShapes.slanted,
  5 => MaterialShapes.ghostish,
  6 => MaterialShapes.sunny,
  _ => MaterialShapes.oval,
};

/// Gives every account its own avatar shape. Walks [ids] oldest first; each
/// starts at a slot hashed from its id (Java's String.hashCode, so a shape
/// matches the Android app's for the same id) and takes the first one nobody
/// before it has.
Map<String, int> assignAvatarShapes(List<String> ids) {
  final taken = List.filled(avatarShapeCount, false);
  final result = <String, int>{};
  for (final id in ids) {
    if (result.containsKey(id)) continue;
    final start = _javaHash(id) & (avatarShapeCount - 1); // floorMod by a power of two
    var slot = start;
    for (var k = 0; k < avatarShapeCount; k++) {
      final candidate = (start + k) % avatarShapeCount;
      if (!taken[candidate]) {
        slot = candidate;
        break;
      }
    }
    taken[slot] = true;
    result[id] = slot;
  }
  return result;
}

int _javaHash(String s) {
  var h = 0;
  for (final unit in s.codeUnits) {
    h = (31 * h + unit) & 0xFFFFFFFF;
  }
  return h;
}

final _letterOrDigit = RegExp(r'[\p{L}\p{N}]', unicode: true);

/// The avatar letter: the first letter or digit of [title], upper-cased. A
/// leading service name is skipped, so unnamed accounts don't all read the
/// service's initial. Empty when there is none (the avatar then shows only
/// the service mark).
String monogramOf(String title, [String? serviceName]) {
  var name = title;
  if (serviceName != null &&
      title.length > serviceName.length &&
      title.toLowerCase().startsWith(serviceName.toLowerCase())) {
    final rest = title.substring(serviceName.length);
    if (_letterOrDigit.hasMatch(rest)) name = rest;
  }
  for (final rune in name.runes) {
    final char = String.fromCharCode(rune);
    if (_letterOrDigit.hasMatch(char)) return char.toUpperCase();
  }
  return '';
}

/// The line under an account's name: service kind, then host. The service
/// name drops out when the title already says it; null when nothing is left.
({String? kind, String? detail})? serviceLine(String serviceName, String title, String? detail) {
  final lower = title.toLowerCase();
  final kind = lower.contains(serviceName.toLowerCase()) ? null : serviceName;
  final where = (detail == null || detail.trim().isEmpty || lower.contains(detail.toLowerCase())) ? null : detail;
  if (kind == null && where == null) return null;
  return (kind: kind, detail: where);
}

/// The line under an account's name on Settings' account cards (v4):
/// shows the server host when present plus what the account's page configures
/// ([scopeHint]), without repeating the service name the avatar already shows.
({String? host, String? scope})? accountCardLine(
  String serviceName,
  String title,
  String? detail,
  String? scopeHint,
) {
  final hint = scopeHint?.trim();
  if (hint == null || hint.isEmpty) {
    final legacy = serviceLine(serviceName, title, detail);
    return legacy == null ? null : (host: legacy.detail, scope: legacy.kind);
  }
  final lower = title.toLowerCase();
  final where = (detail == null || detail.trim().isEmpty || lower.contains(detail.toLowerCase())) ? null : detail;
  return (host: where, scope: hint);
}

/// Host (optional) + configurable scope hint under an account's name (v4):
/// on narrow cards ([stacked] = true), [host] and [scope] stack on two lines so
/// neither collapses to an ellipsis in English; on wide rows [host] yields with
/// an ellipsis first so [scope] stays visible on one line, separated by
/// whitespace only (never `·`).
class AccountCardScopeLine extends StatelessWidget {
  const AccountCardScopeLine({
    super.key,
    required this.host,
    required this.scope,
    required this.color,
    this.style,
    this.stacked = false,
  });

  final String? host;
  final String? scope;
  final Color color;
  final TextStyle? style;
  final bool stacked;

  static const double _gap = 10;

  @override
  Widget build(BuildContext context) {
    final base = (style ?? Theme.of(context).textTheme.bodyMedium!).copyWith(color: color);
    if (host != null && scope != null) {
      final scopeStyle = base.copyWith(color: color.withValues(alpha: color.a * 0.75));
      if (stacked) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(host!, style: base, maxLines: 1, overflow: TextOverflow.ellipsis),
            Text(scope!, style: scopeStyle, maxLines: 1, overflow: TextOverflow.ellipsis),
          ],
        );
      }
      return LayoutBuilder(
        builder: (context, constraints) {
          final scopePainter = TextPainter(
            text: TextSpan(text: scope, style: scopeStyle),
            textDirection: Directionality.of(context),
            textScaler: MediaQuery.textScalerOf(context),
            maxLines: 1,
          )..layout();
          // When scope fits with room for at least an ellipsis on host, let host yield first.
          if (scopePainter.width + _gap + 24 <= constraints.maxWidth) {
            return Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    host!,
                    style: base,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: _gap),
                Text(
                  scope!,
                  style: scopeStyle,
                  maxLines: 1,
                  softWrap: false,
                ),
              ],
            );
          }
          // Extreme font scale or wide monospace test font: single-line rich text, never overflowing.
          return Text.rich(
            TextSpan(
              children: [
                TextSpan(text: host, style: base),
                const WidgetSpan(child: SizedBox(width: _gap)),
                TextSpan(text: scope, style: scopeStyle),
              ],
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          );
        },
      );
    }
    return Text(
      scope ?? host ?? '',
      style: base,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );
  }
}

/// Service kind and host (ServiceLine / MetaLine): the kind in Medium
/// weight, the host after a 12dp gap — never a dot.
class AccountServiceLine extends StatelessWidget {
  const AccountServiceLine({super.key, required this.kind, required this.detail, required this.color, this.style});

  final String? kind;
  final String? detail;

  /// The line's ink. The kind reads at 75% of it (Android MetaLine's Kind on a non-theme ink).
  final Color color;
  final TextStyle? style;

  static const double _groupGap = 12;

  @override
  Widget build(BuildContext context) {
    final base = (style ?? Theme.of(context).textTheme.bodyMedium!).copyWith(color: color);
    return Text.rich(
      TextSpan(children: [
        if (kind != null)
          TextSpan(
            text: kind,
            style: base.copyWith(fontWeight: FontWeight.w500, color: color.withValues(alpha: color.a * 0.75)),
          ),
        if (kind != null && detail != null) const WidgetSpan(child: SizedBox(width: _groupGap)),
        if (detail != null) TextSpan(text: detail),
      ]),
      style: base,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );
  }
}

/// An account's avatar, in two layers (AccountAvatar). Behind and large: the
/// SERVICE — the account's own shape filled with the service's accent, the
/// service mark on it. In front and small, bottom-end: the PERSON — the first
/// letter of their name — ring-cut from the shape by [ringColor]. Decorative
/// for accessibility: the name and service are text beside it.
class AccountAvatar extends StatefulWidget {
  const AccountAvatar({
    super.key,
    required this.monogram,
    required this.shapeIndex,
    required this.identity,
    required this.ringColor,
    this.size = SettingsMetrics.avatarSize,
  });

  final String monogram;
  final int shapeIndex;
  final ServiceIdentity identity;
  final Color ringColor;
  final double size;

  @override
  State<AccountAvatar> createState() => _AccountAvatarState();
}

class _AccountAvatarState extends State<AccountAvatar> with SingleTickerProviderStateMixin {
  late RoundedPolygon _from = avatarPolygon(widget.shapeIndex);
  late RoundedPolygon _to = _from;
  late Morph _shapeMorph = Morph(_from, _to);
  late final SpringValue _morph = SpringValue(
    vsync: this,
    initial: 1,
    spring: YoinSprings.of(MotionTokens.standardDefaultSpatial),
  );

  @override
  void didUpdateWidget(AccountAvatar old) {
    super.didUpdateWidget(old);
    final target = avatarPolygon(widget.shapeIndex);
    if (identical(target, _to)) return;
    // If an account's shape changes (an older one it collided with was
    // removed) it morphs to the new one instead of snapping: from the shape
    // it was heading to, as Android's rememberAvatarShape does.
    _from = _to;
    _to = target;
    _shapeMorph = Morph(_from, _to);
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _morph.snapTo(1);
    } else {
      _morph
        ..snapTo(0)
        ..animateTo(1);
    }
  }

  @override
  void dispose() {
    _morph.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    final tone = widget.identity.hue.tone(Theme.of(context).brightness);
    final showBadge = widget.monogram.isNotEmpty;
    final badge = math.max(size * SettingsMetrics.avatarBadgeFraction, SettingsMetrics.avatarBadgeMin);
    final ring = (size / SettingsMetrics.avatarRingDivisor).clamp(
      SettingsMetrics.avatarRingMin,
      SettingsMetrics.avatarRingMax,
    );
    final lean = showBadge ? -size * SettingsMetrics.avatarGlyphLeanFraction : 0.0;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return ExcludeSemantics(
      child: SizedBox.square(
        dimension: size,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            AnimatedBuilder(
              animation: _morph.animation,
              builder: (context, _) => DecoratedBox(
                decoration: ShapeDecoration(
                  color: tone.accent,
                  shape: MorphBorder(_shapeMorph, _morph.value),
                ),
                child: SizedBox.square(
                  dimension: size,
                  child: Center(
                    // With a badge, the mark leans toward top-start so the badge never bites it.
                    child: Transform.translate(
                      offset: Offset(rtl ? -lean : lean, lean),
                      child: ServiceGlyphIcon(
                        widget.identity.glyph,
                        size: size * SettingsMetrics.avatarGlyphFraction,
                        color: tone.onAccent,
                      ),
                    ),
                  ),
                ),
              ),
            ),
            if (showBadge)
              PositionedDirectional(
                end: -ring,
                bottom: -ring,
                child: Container(
                  width: badge + ring * 2,
                  height: badge + ring * 2,
                  padding: EdgeInsets.all(ring),
                  decoration: BoxDecoration(color: widget.ringColor, shape: BoxShape.circle),
                  child: DecoratedBox(
                    decoration: BoxDecoration(color: tone.iconContainer, shape: BoxShape.circle),
                    child: Center(
                      // The letter scales with the badge, not the font scale: it's a mark.
                      child: Text(
                        widget.monogram,
                        maxLines: 1,
                        textScaler: TextScaler.noScaling,
                        style: Theme.of(context).textTheme.labelLarge!.copyWith(
                              fontSize: badge * SettingsMetrics.avatarLetterFraction,
                              height: 1,
                              color: tone.iconContent,
                            ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// An account's small mark (Android AccountMark): its avatar shape in its
/// service's hue with the service's glyph, no letter. Beside an account's name
/// where the avatar would be too much (Home's other-accounts shelf, a
/// memory's "From …" line). Decorative: the name next to it is what's read.
class AccountMark extends StatelessWidget {
  const AccountMark({super.key, required this.shapeIndex, required this.identity, this.size = 16});

  final int shapeIndex;
  final ServiceIdentity identity;
  final double size;

  @override
  Widget build(BuildContext context) {
    final tone = identity.hue.tone(Theme.of(context).brightness);
    return ExcludeSemantics(
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: tone.accent,
          shape: PolygonBorder(avatarPolygon(shapeIndex)),
        ),
        child: SizedBox.square(
          dimension: size,
          child: Center(
            child: ServiceGlyphIcon(identity.glyph, size: size * SettingsMetrics.markGlyphFraction, color: tone.onAccent),
          ),
        ),
      ),
    );
  }
}
