/// What the export / import and Transfer Memories pages share (Android
/// ui/settings/sync/SyncCommon.kt): the page with its pinned footer
/// ([PortabilityPage], SyncPage), the step headline, the progress and outcome
/// heroes, the count tiles, an account's glyph and line.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/model/media_id.dart';
import '../../nowplaying/haptics.dart';
import '../../shell/bar_suppression.dart';
import '../../shell/chrome_back.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart' show MetaLineTokens;
import '../../ui/component/meta_line.dart';
import '../../ui/component/yoin_loading_indicator.dart';
import '../../ui/shapes/shape_path.dart';
import '../account_identity.dart';
import '../settings_colors.dart';
import '../settings_components.dart';
import '../settings_metrics.dart';
import 'portability_metrics.dart';
import 'portability_strings.dart';

/// A flow page (SyncPage): Settings' bar with its back button, the scrolling
/// column and, when given, a [footer] pinned under it for the step's action.
///
/// Back: while [ownsBack] says so, the back button and system back go to
/// [onBack] instead of closing the page (a busy step refuses, a flow steps
/// back) — through [ChromeBackEntry], the one shared way to hold back.
class PortabilityPage extends StatefulWidget {
  const PortabilityPage({
    super.key,
    required this.barTitle,
    required this.children,
    this.footer,
    this.ownsBack,
    this.onBack,
  });

  /// The bar's title, shown at all times (the steps' headlines are small).
  final String barTitle;
  final List<Widget> children;
  final Widget? footer;
  final bool Function()? ownsBack;
  final VoidCallback? onBack;

  @override
  State<PortabilityPage> createState() => _PortabilityPageState();
}

class _PortabilityPageState extends State<PortabilityPage> {
  final _scroll = ScrollController();
  late final ChromeBackEntry? _back = widget.ownsBack == null
      ? null
      : ChromeBackEntry(() => widget.onBack?.call(), owns: () => widget.ownsBack!(), priority: ChromeBackPriority.page);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final back = _back;
    if (back == null) return;
    // Held while this page is on top; a page pushed over it (a setup page) takes its own back.
    final current = ModalRoute.of(context)?.isCurrent ?? false;
    if (current && !back.isAttached) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && (ModalRoute.of(context)?.isCurrent ?? false) && !back.isAttached) {
          back.attach(Navigator.of(context));
        }
      });
    }
  }

  @override
  void dispose() {
    _back?.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final bottom = MediaQuery.paddingOf(context).bottom;
    final footer = widget.footer;
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: widget.barTitle,
          controller: _scroll,
          // No large title hands the bar its title on these pages: it shows from the start.
          titleTop: -1,
          titleHeight: 1,
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: SettingsSeam(
            controller: _scroll,
            child: ListView(
              controller: _scroll,
              clipBehavior: Clip.hardEdge,
              padding: EdgeInsets.fromLTRB(
                SettingsMetrics.pageHorizontal,
                SettingsMetrics.pageTop,
                SettingsMetrics.pageHorizontal,
                SettingsMetrics.pageBottom + (footer == null ? bottom : 0),
              ),
              children: widget.children,
            ),
          ),
        ),
        bottomNavigationBar: footer == null
            ? null
            : Padding(
                padding: EdgeInsets.fromLTRB(
                  PortabilityMetrics.footerHorizontal,
                  PortabilityMetrics.footerTop,
                  PortabilityMetrics.footerHorizontal,
                  PortabilityMetrics.footerBottom + bottom,
                ),
                child: footer,
              ),
      ),
    );
  }
}

/// A flow's pinned buttons (FlowButtons): an optional text button, then the main one filling the row,
/// with a count in a badge.
class FlowButtons extends StatelessWidget {
  const FlowButtons({
    super.key,
    required this.primary,
    required this.onPrimary,
    this.primaryEnabled = true,
    this.badge,
    this.secondary,
    this.onSecondary,
  });

  final String primary;
  final VoidCallback onPrimary;
  final bool primaryEnabled;
  final int? badge;
  final String? secondary;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        if (secondary != null) ...[
          SizedBox(
            height: PortabilityMetrics.buttonHeight,
            child: TextButton(
              onPressed: () {
                YoinHaptics.click();
                onSecondary?.call();
              },
              child: Text(secondary!),
            ),
          ),
          const SizedBox(width: PortabilityMetrics.footerGap),
        ],
        Expanded(
          child: FilledButton(
            onPressed: primaryEnabled
                ? () {
                    YoinHaptics.confirm();
                    onPrimary();
                  }
                : null,
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(PortabilityMetrics.buttonHeight),
              shape: const StadiumBorder(),
              textStyle: theme.textTheme.titleMedium,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(child: Text(primary, maxLines: 1, overflow: TextOverflow.ellipsis)),
                if (badge != null) ...[
                  const SizedBox(width: PortabilityMetrics.badgeGap),
                  Container(
                    constraints: const BoxConstraints(minHeight: PortabilityMetrics.badgeMinHeight),
                    padding: const EdgeInsets.symmetric(
                      horizontal: PortabilityMetrics.badgeHorizontal,
                      vertical: PortabilityMetrics.badgeVertical,
                    ),
                    decoration: ShapeDecoration(
                      color: theme.colorScheme.onPrimary.withValues(alpha: PortabilityMetrics.badgeAlpha),
                      shape: const StadiumBorder(),
                    ),
                    child: Text('$badge', style: theme.textTheme.labelLarge),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// A step's heading (SyncHeadline small): the headline, then a lead line.
class FlowHeadline extends StatelessWidget {
  const FlowHeadline({super.key, required this.headline, this.lead});

  final String headline;
  final String? lead;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(
        top: PortabilityMetrics.headTop,
        bottom: PortabilityMetrics.headBottom,
        left: PortabilityMetrics.headInset,
        right: PortabilityMetrics.headInset,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            header: true,
            child: Text(headline, style: theme.textTheme.headlineMedium!.copyWith(color: theme.colorScheme.onSurface)),
          ),
          if (lead != null) ...[
            const SizedBox(height: PortabilityMetrics.headLeadTop),
            Text(lead!, style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ],
        ],
      ),
    );
  }
}

/// A step that runs (ProgressHero): the loading indicator on a 9-sided cookie — determinate when
/// [progress] is known, with its [count] and a bar under it — and a [caption].
class ProgressHero extends StatefulWidget {
  const ProgressHero({super.key, this.progress, this.count, this.caption});

  /// 0..1, or null while it can't be told.
  final double? progress;
  final String? count;
  final Widget? caption;

  @override
  State<ProgressHero> createState() => _ProgressHeroState();
}

class _ProgressHeroState extends State<ProgressHero> with SingleTickerProviderStateMixin {
  late final SpringValue _progress = SpringValue(
    vsync: this,
    initial: widget.progress ?? 0,
    spring: YoinSprings.defaultSpatial,
  );

  @override
  void didUpdateWidget(ProgressHero old) {
    super.didUpdateWidget(old);
    final target = widget.progress ?? 0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _progress.snapTo(target);
    } else {
      _progress.animateTo(target);
    }
  }

  @override
  void dispose() {
    _progress.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final determinate = widget.progress != null;
    return Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: PortabilityMetrics.heroHorizontal,
        vertical: PortabilityMetrics.heroVertical,
      ),
      child: AnimatedBuilder(
        animation: _progress.animation,
        builder: (context, _) {
          final p = _progress.value.clamp(0.0, 1.0);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: PortabilityMetrics.heroCookie,
                  height: PortabilityMetrics.heroCookie,
                  decoration: ShapeDecoration(
                    color: colors.primaryContainer,
                    shape: PolygonBorder(MaterialShapes.cookie9Sided),
                  ),
                  alignment: Alignment.center,
                  child: determinate
                      ? SizedBox.square(
                          dimension: PortabilityMetrics.heroIndicator,
                          child: CustomPaint(painter: _DeterminatePainter(p, colors.onPrimaryContainer)),
                        )
                      : YoinLoadingIndicator(
                          size: PortabilityMetrics.heroIndicator,
                          contained: false,
                          color: colors.onPrimaryContainer,
                        ),
                ),
              ),
              if (widget.count != null) ...[
                const SizedBox(height: PortabilityMetrics.heroGap),
                Text(
                  widget.count!,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.displaySmall!.copyWith(color: colors.onSurface),
                ),
              ],
              if (determinate) ...[
                const SizedBox(height: PortabilityMetrics.heroGap),
                LinearProgressIndicator(
                  value: p,
                  minHeight: PortabilityMetrics.heroLine,
                  borderRadius: BorderRadius.circular(PortabilityMetrics.heroLine),
                  color: colors.primary,
                  backgroundColor: colors.secondaryContainer,
                ),
              ],
              if (widget.caption != null) ...[
                const SizedBox(height: PortabilityMetrics.heroGap),
                Center(child: widget.caption),
              ],
            ],
          );
        },
      ),
    );
  }
}

/// M3's determinate LoadingIndicator: a circle that turns into a soft burst as [progress] fills, turning with it.
class _DeterminatePainter extends CustomPainter {
  _DeterminatePainter(this.progress, this.color);

  final double progress;
  final Color color;

  static final Morph _morph = Morph(MaterialShapes.circle, MaterialShapes.softBurst);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(progress * math.pi);
    canvas.translate(-size.width / 2, -size.height / 2);
    canvas.scale(size.width, size.height);
    canvas.drawPath(_morph.toPath(progress), Paint()..color = color);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_DeterminatePainter old) => old.progress != progress || old.color != color;
}

/// A finished step (OutcomeHero): a soft burst (a 4-sided cookie when [failed]) that springs in with its
/// icon, the title, a line under it and anything [extra].
class OutcomeHero extends StatefulWidget {
  const OutcomeHero({super.key, required this.icon, required this.title, this.body, this.failed = false, this.extra});

  final IconData icon;
  final String title;
  final String? body;
  final bool failed;
  final Widget? extra;

  @override
  State<OutcomeHero> createState() => _OutcomeHeroState();
}

class _OutcomeHeroState extends State<OutcomeHero> with SingleTickerProviderStateMixin {
  late final SpringValue _appear = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
        _appear.snapTo(1);
      } else {
        _appear.animateTo(1);
      }
    });
  }

  @override
  void dispose() {
    _appear.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final failed = widget.failed;
    return Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: PortabilityMetrics.outcomeHorizontal,
        vertical: PortabilityMetrics.outcomeVertical,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: AnimatedBuilder(
              animation: _appear.animation,
              builder: (context, child) => Transform.rotate(
                angle: (1 - _appear.value) * PortabilityMetrics.outcomeTurnDegrees * math.pi / 180,
                child: Transform.scale(scale: math.max(0.0, _appear.value), child: child),
              ),
              child: Container(
                width: PortabilityMetrics.outcomeMark,
                height: PortabilityMetrics.outcomeMark,
                decoration: ShapeDecoration(
                  color: failed ? colors.errorContainer : colors.primaryContainer,
                  shape: PolygonBorder(failed ? MaterialShapes.cookie4Sided : MaterialShapes.softBurst),
                ),
                child: Icon(
                  widget.icon,
                  size: PortabilityMetrics.outcomeGlyph,
                  color: failed ? colors.onErrorContainer : colors.onPrimaryContainer,
                ),
              ),
            ),
          ),
          const SizedBox(height: PortabilityMetrics.outcomeGap + PortabilityMetrics.outcomeTitleTop),
          Semantics(
            header: true,
            child: Text(
              widget.title,
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface),
            ),
          ),
          if (widget.body != null) ...[
            const SizedBox(height: PortabilityMetrics.outcomeGap),
            Text(
              widget.body!,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
            ),
          ],
          if (widget.extra != null) ...[const SizedBox(height: PortabilityMetrics.outcomeGap), widget.extra!],
        ],
      ),
    );
  }
}

/// A count over its label on a tinted tile (CountTile).
class CountTile extends StatelessWidget {
  const CountTile({
    super.key,
    required this.count,
    required this.label,
    required this.container,
    required this.content,
  });

  final int count;
  final String label;
  final Color container;
  final Color content;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Expanded(
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: container,
          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(PortabilityMetrics.tileRadius)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(PortabilityMetrics.tilePadding),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('$count', style: theme.textTheme.displaySmall!.copyWith(color: content)),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelLarge!.copyWith(
                  color: content.withValues(alpha: PortabilityMetrics.tileLabelAlpha),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A row of count tiles, [CountTile.count] apart.
class CountTiles extends StatelessWidget {
  const CountTiles({super.key, required this.tiles});

  final List<CountTile> tiles;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: PortabilityMetrics.outcomeExtraTop),
    child: Row(
      children: [
        for (final (i, t) in tiles.indexed) ...[if (i > 0) const SizedBox(width: PortabilityMetrics.tileGap), t],
      ],
    ),
  );
}

enum FlowPillTone { neutral, primary, muted }

/// A small status pill (SyncPill with its tones).
class FlowPill extends StatelessWidget {
  const FlowPill({super.key, required this.text, this.tone = FlowPillTone.neutral});

  final String text;
  final FlowPillTone tone;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final (container, content) = switch (tone) {
      FlowPillTone.neutral => (colors.secondaryContainer, colors.onSecondaryContainer),
      FlowPillTone.primary => (colors.primaryContainer, colors.onPrimaryContainer),
      FlowPillTone.muted => (colors.surfaceContainerHighest, colors.onSurfaceVariant),
    };
    return DecoratedBox(
      decoration: ShapeDecoration(color: container, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.syncPillH, vertical: SettingsMetrics.syncPillV),
        child: Text(text, maxLines: 1, style: theme.textTheme.labelMedium!.copyWith(color: content)),
      ),
    );
  }
}

/// A row title with a pill after it, wrapping under it when the name is long (TitleWithPill).
class TitleWithPill extends StatelessWidget {
  const TitleWithPill({super.key, required this.title, this.pill});

  final String title;
  final Widget? pill;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Wrap(
      spacing: PortabilityMetrics.pillGap,
      runSpacing: PortabilityMetrics.pillRunGap,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(title, style: theme.textTheme.titleMedium!.copyWith(color: theme.colorScheme.onSurface)),
        ?pill,
      ],
    );
  }
}

/// The service an account is on, as HarmonyOS draws it: Subsonic and Apple Music have their identity;
/// Spotify (no provider here) and unknown services get the person symbol on a neutral disc.
ServiceIdentity? identityOf(String provider) => switch (provider) {
  MediaId.providerSubsonic => ServiceIdentity.subsonic,
  MediaId.providerAppleMusic => ServiceIdentity.appleMusic,
  _ => null,
};

/// The service's name ("Subsonic", "Apple Music", "Spotify"); the raw token for anything else.
String serviceNameOf(PortabilityStrings s, String? provider) => switch (provider) {
  MediaId.providerSpotify => s.spotify,
  null => '',
  final p => identityOf(p)?.name ?? p,
};

/// The tone-circled service glyph of an account (AccountGlyph); a cookie past the row size.
class AccountGlyph extends StatelessWidget {
  const AccountGlyph({super.key, required this.provider, this.size = SettingsMetrics.rowIconBox});

  final String provider;
  final double size;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final identity = identityOf(provider);
    final tone = identity?.hue.tone(theme.brightness);
    final container = tone?.iconContainer ?? theme.colorScheme.secondaryContainer;
    final content = tone?.iconContent ?? theme.colorScheme.onSecondaryContainer;
    final row = size == SettingsMetrics.rowIconBox;
    final glyph = identity == null
        ? Icon(
            YoinSymbols.person,
            size: row ? SettingsMetrics.rowIcon : size * PortabilityMetrics.pairGlyphIcon,
            color: content,
          )
        : ServiceGlyphIcon(
            identity.glyph,
            size: row ? SettingsMetrics.rowIcon : size * PortabilityMetrics.pairGlyphIcon,
            color: content,
          );
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: container,
        shape: row ? const CircleBorder() : PolygonBorder(MaterialShapes.cookie6Sided),
      ),
      child: SizedBox.square(
        dimension: size,
        child: Center(child: glyph),
      ),
    );
  }
}

/// The line under an account's name: its service (when the name doesn't say it), where it lives, and
/// "From `device`" muted (accountLineGroups).
List<MetaGroup> flowAccountLine(
  PortabilityStrings s,
  String provider,
  String name,
  String? hint, {
  String? fromDevice,
  String Function(String device)? fromLabel,
}) {
  final line = serviceLine(serviceNameOf(s, provider), name, hint);
  return [
    if (line != null) ...[
      if (line.kind != null && line.kind!.isNotEmpty) MetaPlain(line.kind!),
      if (line.detail != null) MetaPlain(line.detail!),
    ],
    if (fromDevice != null && fromDevice.trim().isNotEmpty && fromLabel != null) MetaMuted(fromLabel(fromDevice)),
  ];
}

/// A KB / MB size as value + unit (sizeStat): KB under a megabyte, else MB with one decimal.
MetaStat sizeStat(PortabilityStrings s, int bytes) {
  final kb = bytes / 1024;
  if (kb < 1024) {
    final whole = kb.ceil();
    return MetaStat('${bytes > 0 && whole < 1 ? 1 : whole}', s.unitKb);
  }
  return MetaStat((kb / 1024).toStringAsFixed(1), s.unitMb);
}

/// Meta groups that wrap onto more lines instead of dropping the last ones (MetaFlow): each group as
/// [MetaLine] draws it (a stat's value Medium and tabular, its unit muted 4 apart), 12 apart.
class MetaFlow extends StatelessWidget {
  const MetaFlow({super.key, required this.groups, this.style});

  final List<MetaGroup> groups;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final base = (style ?? theme.textTheme.bodySmall!).copyWith(color: theme.colorScheme.onSurfaceVariant);
    final ink = base.color!;
    final muted = base.copyWith(color: ink.withValues(alpha: ink.a * MetaLineTokens.mutedAlpha));
    // One line box for every item, Google Sans Flex's, whichever font draws the words (a Chinese unit
    // takes the CJK fallback's metrics): the items in a run then share one baseline (Android MetaFlow
    // aligns them by baseline, PR #310), and a stat's value and unit sit on theirs.
    final strut = StrutStyle.fromTextStyle(base, forceStrutHeight: true);
    Widget text(String s, TextStyle style) => Text(s, style: style, strutStyle: strut);
    Widget group(MetaGroup g) => switch (g) {
      MetaPlain(text: final s) => text(s, base),
      MetaMuted(text: final s) => text(s, muted),
      MetaKind(text: final s, :final color) => text(s, base.copyWith(color: color ?? theme.colorScheme.primary)),
      MetaStat(:final value, :final unit) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          text(value, base.copyWith(fontWeight: FontWeight.w500, fontFeatures: const [FontFeature.tabularFigures()])),
          if (unit.isNotEmpty) ...[const SizedBox(width: MetaLineTokens.innerGap), text(unit, muted)],
        ],
      ),
    };
    return Wrap(spacing: MetaLineTokens.groupGap, children: [for (final g in groups) group(g)]);
  }
}
