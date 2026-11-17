import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../settings/account_identity.dart';
import '../settings/settings_colors.dart';
import '../settings/settings_view_model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/shapes/shape_path.dart';
import 'landing_models.dart';
import 'landing_strings.dart';

/// A step's content inside the floating window (LandingSceneColumn): scrolls
/// when it outgrows the window, centred when the window is taller. The
/// vertical padding sits outside the scroll, so scrolled content never runs
/// into the window's round corners (there is no rounded clip).
class LandingSceneColumn extends StatelessWidget {
  const LandingSceneColumn({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final minInner = math.max(0.0, constraints.minHeight - 2 * LandingTokens.scenePaddingV);
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: LandingTokens.scenePaddingV),
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: LandingTokens.scenePaddingH),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: minInner),
            child: Align(
              alignment: Alignment.center,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: children,
              ),
            ),
          ),
        ),
      );
    },
  );
}

/// [style] at a real weight [weight]: Google Sans Flex is variable, so the
/// wght axis moves with the weight (a bare fontWeight would keep the static cut).
TextStyle landingWeight(TextStyle style, int weight) => style.copyWith(
  fontWeight: FontWeight.values.firstWhere((w) => w.value == weight, orElse: () => FontWeight.w600),
  fontVariations: [
    for (final v in style.fontVariations ?? const <FontVariation>[])
      if (v.axis != 'wght') v,
    FontVariation.weight(weight.toDouble()),
  ],
);

/// The section label inside the window (Settings' group label, a step smaller).
class LandingLabel extends StatelessWidget {
  const LandingLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(6, 4, 6, 8),
      child: Text(text, style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.primary)),
    );
  }
}

/// A rounded group of rows on the brighter surface.
class LandingGroup extends StatelessWidget {
  const LandingGroup({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: DecoratedBox(
      decoration: ShapeDecoration(
        color: Theme.of(context).colorScheme.surfaceBright,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(LandingTokens.groupCorner)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
      ),
    ),
  );
}

/// A service's face (colour family and mark) for [service].
ServiceIdentity landingIdentity(LandingService service) => ServiceIdentity.forProvider(service.provider);

/// The pick tile's avatar: MaterialShapes.Circle → Cookie9Sided when picked.
final Morph _tileMorph = Morph(MaterialShapes.circle, MaterialShapes.cookie9Sided);

/// Paints [shape] filling the box, in [color].
class _ShapePainter extends CustomPainter {
  _ShapePainter(this.shape, this.color);

  final ShapeBorder shape;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawPath(shape.getOuterPath(Offset.zero & size), Paint()..color = color);
  }

  @override
  bool shouldRepaint(_ShapePainter old) => old.shape != shape || old.color != color;
}

/// The service's mark on a filled nine-lobed cookie.
class LandingServiceMark extends StatelessWidget {
  const LandingServiceMark({super.key, required this.service, required this.size, required this.glyph});

  final LandingService service;
  final double size;
  final double glyph;

  @override
  Widget build(BuildContext context) {
    final identity = landingIdentity(service);
    final tone = identity.hue.tone(Theme.of(context).brightness);
    return CustomPaint(
      painter: _ShapePainter(PolygonBorder(MaterialShapes.cookie9Sided), tone.accent),
      child: SizedBox.square(
        dimension: size,
        child: Center(
          child: ServiceGlyphIcon(identity.glyph, size: glyph, color: tone.onAccent),
        ),
      ),
    );
  }
}

// ── Pick ────────────────────────────────────────────────────────────────

class PickScene extends StatelessWidget {
  const PickScene({super.key, required this.picked, required this.onToggle});

  final Set<LandingService> picked;
  final ValueChanged<LandingService> onToggle;

  @override
  Widget build(BuildContext context) => LandingSceneColumn(
    children: [
      for (final service in LandingService.values) ...[
        if (service != LandingService.values.first) const SizedBox(height: LandingTokens.tileGap),
        ServiceTile(service: service, selected: picked.contains(service), onTap: () => onToggle(service)),
      ],
    ],
  );
}

String _pickLine(LandingStrings strings, LandingService service) => switch (service) {
  LandingService.subsonic => strings.pickSubsonic,
  LandingService.appleMusic => strings.pickApple,
};

/// One service to pick: its colours, corner and avatar spring with the pick;
/// the avatar morphs circle → nine-lobed cookie with an eighth of a turn.
class ServiceTile extends StatefulWidget {
  const ServiceTile({super.key, required this.service, required this.selected, required this.onTap});

  final LandingService service;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<ServiceTile> createState() => _ServiceTileState();
}

class _ServiceTileState extends State<ServiceTile> with TickerProviderStateMixin {
  late final SpringValue _color = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _corner = SpringValue(
    vsync: this,
    initial: widget.selected ? LandingTokens.tileCornerPicked : LandingTokens.tileCorner,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _morph = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.fastSpatial,
  );

  @override
  void didUpdateWidget(ServiceTile old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    final on = widget.selected ? 1.0 : 0.0;
    final corner = widget.selected ? LandingTokens.tileCornerPicked : LandingTokens.tileCorner;
    if (MediaQuery.disableAnimationsOf(context)) {
      _color.snapTo(on);
      _corner.snapTo(corner);
      _morph.snapTo(on);
    } else {
      _color.animateTo(on);
      _corner.animateTo(corner);
      _morph.animateTo(on);
    }
  }

  @override
  void dispose() {
    _color.dispose();
    _corner.dispose();
    _morph.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = LandingStrings.of(context);
    final identity = landingIdentity(widget.service);
    final tone = identity.hue.tone(theme.brightness);
    return Semantics(
      checked: widget.selected,
      button: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_color.animation, _corner.animation, _morph.animation]),
        builder: (context, _) {
          final c = _color.value.clamp(0.0, 1.0);
          final m = _morph.value;
          final background = Color.lerp(colors.surfaceBright, tone.container, c)!;
          final content = Color.lerp(colors.onSurface, tone.onContainer, c)!;
          final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(math.max(0.0, _corner.value)));
          final checkScale = m.clamp(0.0, 1.2);
          return Material(
            color: background,
            shape: shape,
            child: InkWell(
              customBorder: shape,
              onTap: () {
                YoinHaptics.toggle(on: !widget.selected);
                widget.onTap();
              },
              child: Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(14, 14, 16, 14),
                child: Row(
                  children: [
                    Transform.rotate(
                      angle: m * LandingTokens.tileMorphTurn * math.pi / 180,
                      child: CustomPaint(
                        painter: _ShapePainter(
                          MorphBorder(_tileMorph, m),
                          Color.lerp(tone.iconContainer, tone.accent, c)!,
                        ),
                        child: SizedBox.square(
                          dimension: LandingTokens.tileAvatar,
                          child: Transform.rotate(
                            angle: -m * LandingTokens.tileMorphTurn * math.pi / 180,
                            child: ServiceGlyphIcon(
                              identity.glyph,
                              size: LandingTokens.tileGlyph,
                              color: Color.lerp(tone.iconContent, tone.onAccent, c),
                            ),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            identity.name,
                            style: landingWeight(theme.textTheme.titleMedium!, 600).copyWith(color: content),
                          ),
                          Text(
                            _pickLine(strings, widget.service),
                            style: theme.textTheme.bodyMedium!.copyWith(color: content.withValues(alpha: 0.78)),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 14),
                    Container(
                      width: LandingTokens.tileCheck,
                      height: LandingTokens.tileCheck,
                      decoration: ShapeDecoration(
                        color: Color.lerp(colors.surfaceBright.withValues(alpha: 0), tone.accent, c),
                        shape: CircleBorder(
                          side: BorderSide(
                            color: Color.lerp(colors.outline, tone.accent, c)!,
                            width: LandingTokens.tileCheckStroke,
                          ),
                        ),
                      ),
                      child: Transform.scale(
                        scale: checkScale,
                        child: Icon(YoinSymbols.check, size: LandingTokens.tileCheckGlyph, color: tone.onAccent),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

// ── About ───────────────────────────────────────────────────────────────

class AboutScene extends StatelessWidget {
  const AboutScene({super.key, required this.picked});

  final Set<LandingService> picked;

  @override
  Widget build(BuildContext context) {
    final strings = LandingStrings.of(context);
    final services = [
      for (final s in LandingService.values)
        if (picked.contains(s)) s,
    ];
    return LandingSceneColumn(
      children: [
        LandingLabel(strings.aboutDoes),
        LandingGroup(
          children: [
            for (final line in [strings.doesPlay, strings.doesNotes, strings.doesAccounts, strings.doesPasswords])
              _MarkRow(text: line, yes: true),
          ],
        ),
        LandingLabel(strings.aboutDoesnt),
        LandingGroup(
          children: [
            for (final line in [strings.dontSell, strings.dontHost]) _MarkRow(text: line, yes: false),
          ],
        ),
        LandingLabel(strings.aboutNeed),
        LandingGroup(
          children: [
            if (services.isEmpty)
              _MarkRow(text: strings.needNone, yes: false)
            else
              for (final service in services)
                _ServiceRow(
                  service: service,
                  text: switch (service) {
                    LandingService.subsonic => strings.needSubsonic,
                    LandingService.appleMusic => strings.needApple,
                  },
                ),
          ],
        ),
      ],
    );
  }
}

class _MarkRow extends StatelessWidget {
  const _MarkRow({required this.text, required this.yes});

  final String text;
  final bool yes;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Container(
            width: LandingTokens.markSize,
            height: LandingTokens.markSize,
            decoration: ShapeDecoration(
              color: yes ? colors.primary : colors.surfaceContainerHighest,
              shape: const CircleBorder(),
            ),
            child: Icon(
              yes ? YoinSymbols.check : YoinSymbols.close,
              size: LandingTokens.markGlyph,
              color: yes ? colors.onPrimary : colors.onSurfaceVariant,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurface)),
          ),
        ],
      ),
    );
  }
}

class _ServiceRow extends StatelessWidget {
  const _ServiceRow({required this.service, required this.text});

  final LandingService service;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          LandingServiceMark(service: service, size: LandingTokens.markSize, glyph: LandingTokens.serviceMarkGlyph),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurface)),
          ),
        ],
      ),
    );
  }
}

// ── Ready ───────────────────────────────────────────────────────────────

/// The accounts the Ready step lists: the one in use first, then by age.
List<AccountCard> landingAccounts(List<AccountCard> accounts) => [
  ...accounts.where((a) => a.isActive),
  ...accounts.where((a) => !a.isActive),
];

class ReadyScene extends StatelessWidget {
  const ReadyScene({super.key, required this.accounts});

  final List<AccountCard> accounts;

  @override
  Widget build(BuildContext context) {
    final strings = LandingStrings.of(context);
    final theme = Theme.of(context);
    final ordered = landingAccounts(accounts);
    return LandingSceneColumn(
      children: [
        LandingLabel(strings.readyAccounts),
        if (ordered.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 16),
            child: Text(
              strings.readyEmpty,
              style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          )
        else
          for (var i = 0; i < ordered.length; i++) ...[
            if (i > 0) const SizedBox(height: LandingTokens.rowGap),
            _AccountRow(account: ordered[i], first: i == 0, last: i == ordered.length - 1),
          ],
      ],
    );
  }
}

class _AccountRow extends StatelessWidget {
  const _AccountRow({required this.account, required this.first, required this.last});

  final AccountCard account;
  final bool first;
  final bool last;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = LandingStrings.of(context);
    final identity = account.identity;
    const outer = Radius.circular(LandingTokens.rowOuter);
    const inner = Radius.circular(LandingTokens.rowInner);
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceBright,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: first ? outer : inner, bottom: last ? outer : inner),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            AccountAvatar(
              monogram: monogramOf(account.displayName, identity.name),
              shapeIndex: account.avatarShape,
              identity: identity,
              ringColor: colors.surfaceBright,
              size: LandingTokens.readyAvatar,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    account.displayName,
                    style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(identity.name, style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
                ],
              ),
            ),
            if (account.isActive) ...[
              const SizedBox(width: 14),
              DecoratedBox(
                decoration: ShapeDecoration(
                  color: colors.primary,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                  child: Text(
                    strings.readyInUse,
                    style: theme.textTheme.labelMedium!.copyWith(color: colors.onPrimary),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The colour family of a service, for the connect steps.
SettingsTone landingTone(BuildContext context, LandingService service) =>
    landingIdentity(service).hue.tone(Theme.of(context).brightness);
