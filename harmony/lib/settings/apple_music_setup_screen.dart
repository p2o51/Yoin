import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../platform/applemusic/apple_music_auth.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'account_identity.dart';
import 'apple_music_setup_view_model.dart';
import 'material_shapes.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_strings.dart';

/// Adding an Apple Music account (Android ServiceSetupScreen, Apple Music):
/// the service's mark and name, what you'll need, then the Connect group —
/// the developer-token service and one Connect button that starts the ArkWeb
/// sign-in. The Apple ID step happens in the sign-in page, not here. PushPage;
/// opened with YoinPushRoute.
class AppleMusicSetupScreen extends ConsumerStatefulWidget {
  const AppleMusicSetupScreen({super.key});

  @override
  ConsumerState<AppleMusicSetupScreen> createState() => _AppleMusicSetupScreenState();
}

class _AppleMusicSetupScreenState extends ConsumerState<AppleMusicSetupScreen> {
  final _endpoint = TextEditingController(text: yoinAppleMusicTokenServiceUrl);
  final _scroll = ScrollController();
  String _lastEndpoint = yoinAppleMusicTokenServiceUrl;

  @override
  void initState() {
    super.initState();
    _endpoint.addListener(_onEndpoint);
  }

  void _onEndpoint() {
    if (_endpoint.text != _lastEndpoint) {
      _lastEndpoint = _endpoint.text;
      ref.read(appleMusicSetupViewModelProvider.notifier).onEndpointChanged();
    }
    setState(() {});
  }

  @override
  void dispose() {
    _endpoint.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _connect() => ref.read(appleMusicSetupViewModelProvider.notifier).connect(_endpoint.text);

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    ref.listen(appleMusicSetupViewModelProvider.select((s) => s.connected), (_, connected) {
      if (connected) Navigator.of(context).pop();
    });
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: strings.introAppleName,
          controller: _scroll,
          titleTop: SettingsMetrics.pageTop + SettingsMetrics.heroMark + SettingsMetrics.heroGap,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: SettingsSeam(
            controller: _scroll,
            child: ListView(
              controller: _scroll,
              padding: EdgeInsets.fromLTRB(
                SettingsMetrics.pageHorizontal,
                SettingsMetrics.pageTop,
                SettingsMetrics.pageHorizontal,
                SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
              ),
              children: [
                const _ServiceHero(),
                const SizedBox(height: SettingsMetrics.sectionGap),
                _RequirementsGroup(requirements: [strings.introAppleNeedSub, strings.introAppleNeedToken]),
                const SizedBox(height: SettingsMetrics.sectionGap),
                _ConnectGroup(endpoint: _endpoint, onConnect: _connect),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Hero ──────────────────────────────────────────────────────────────

/// The service's mark in its own colour on a nine-sided cookie, blooming in
/// once; then its name, large, and its tagline (shared with the Subsonic page).
class _ServiceHero extends StatefulWidget {
  const _ServiceHero();

  @override
  State<_ServiceHero> createState() => _ServiceHeroState();
}

class _ServiceHeroState extends State<_ServiceHero> with SingleTickerProviderStateMixin {
  late final SpringValue _bloom = SpringValue(
    vsync: this,
    initial: SettingsMetrics.heroBloomFrom,
    spring: SettingsSprings.slowSpatial,
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _bloom.snapTo(1);
    } else {
      _bloom.animateTo(1);
    }
  }

  @override
  void dispose() {
    _bloom.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    const identity = ServiceIdentity.appleMusic;
    final tone = identity.hue.tone(theme.brightness);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          AnimatedBuilder(
            animation: _bloom.animation,
            builder: (context, child) {
              final b = _bloom.value;
              return Transform.rotate(
                angle: (1 - b) * SettingsMetrics.heroBloomTurnDegrees * math.pi / 180,
                child: Transform.scale(scale: b, child: child),
              );
            },
            child: ExcludeSemantics(
              child: DecoratedBox(
                decoration: ShapeDecoration(color: tone.iconContainer, shape: PolygonBorder(cookie9Sided)),
                child: SizedBox.square(
                  dimension: SettingsMetrics.heroMark,
                  child: Icon(identity.glyph, size: SettingsMetrics.heroGlyph, color: tone.iconContent),
                ),
              ),
            ),
          ),
          const SizedBox(height: SettingsMetrics.heroGap),
          Semantics(
            header: true,
            child: Text(
              strings.introAppleName,
              style: theme.textTheme.displaySmall!.copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
          const SizedBox(height: SettingsMetrics.heroTextGap),
          Text(
            strings.introAppleTagline,
            style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

// ── What you need ────────────────────────────────────────────────────

class _RequirementsGroup extends StatelessWidget {
  const _RequirementsGroup({required this.requirements});

  final List<String> requirements;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsGroupLabel(title: strings.sectionNeed),
        const SizedBox(height: SettingsMetrics.needGap),
        Wrap(
          spacing: SettingsMetrics.chipSpacing,
          runSpacing: SettingsMetrics.chipSpacing,
          children: [
            for (final requirement in requirements)
              DecoratedBox(
                decoration: ShapeDecoration(color: settingsRowColor(colors), shape: const StadiumBorder()),
                child: Padding(
                  padding: const EdgeInsetsDirectional.fromSTEB(
                    SettingsMetrics.chipPaddingStart,
                    SettingsMetrics.chipPaddingVertical,
                    SettingsMetrics.chipPaddingEnd,
                    SettingsMetrics.chipPaddingVertical,
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(YoinSymbols.check, size: SettingsMetrics.chipIcon, color: colors.primary),
                      const SizedBox(width: SettingsMetrics.chipIconGap),
                      Flexible(
                        child: Text(requirement, style: theme.textTheme.labelLarge!.copyWith(color: colors.onSurface)),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

// ── Connect ──────────────────────────────────────────────────────────

String _endpointErrorText(SettingsStrings strings, AppleSetupError error) => switch (error) {
      AppleSetupError.endpointRequired => strings.appleEndpointRequired,
      AppleSetupError.endpointInvalid => strings.appleEndpointInvalid,
    };

String _failureText(SettingsStrings strings, AppleMusicAuthFailure failure) => switch (failure) {
      AppleMusicAuthFailure.cancelled => strings.appleStatusCancelled,
      _ => strings.appleStatusFailed,
    };

class _ConnectGroup extends ConsumerWidget {
  const _ConnectGroup({required this.endpoint, required this.onConnect});

  final TextEditingController endpoint;
  final VoidCallback onConnect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(appleMusicSetupViewModelProvider);
    final canSubmit = endpoint.text.trim().isNotEmpty && !state.isBusy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupLabel(title: strings.sectionConnect),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        Material(
          color: settingsRowColor(colors),
          shape: settingsSegmentShape(0, 1),
          child: Padding(
            padding: const EdgeInsets.all(SettingsMetrics.formPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ExpressiveTextField(
                  controller: endpoint,
                  label: strings.appleEndpointLabel,
                  placeholder: strings.appleEndpointPlaceholder,
                  keyboardType: TextInputType.url,
                  textInputAction: TextInputAction.done,
                  onSubmitted: canSubmit ? (_) => onConnect() : null,
                ),
                SpringReveal(
                  visible: state.endpointError != null,
                  child: state.endpointError == null
                      ? null
                      : Padding(
                          padding: const EdgeInsetsDirectional.only(
                            start: SettingsMetrics.addressErrorStart,
                            top: SettingsMetrics.addressErrorTop,
                          ),
                          child: _StatusRow(
                            icon: YoinSymbols.error,
                            text: _endpointErrorText(strings, state.endpointError!),
                            color: colors.error,
                          ),
                        ),
                ),
                SpringReveal(
                  visible: state.status is! AppleSetupIdle,
                  child: state.status is AppleSetupIdle
                      ? null
                      : Padding(
                          padding: const EdgeInsets.only(top: SettingsMetrics.formGap),
                          child: _StatusLine(status: state.status),
                        ),
                ),
                const SizedBox(height: SettingsMetrics.formGap),
                FilledButton(onPressed: canSubmit ? onConnect : null, child: Text(strings.appleConnect)),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// Only speaks when there's something to say; cross-fades between states.
class _StatusLine extends StatelessWidget {
  const _StatusLine({required this.status});

  final AppleSetupStatus status;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final child = switch (status) {
      AppleSetupIdle() => const SizedBox.shrink(),
      AppleSetupAuthorizing() || AppleSetupSaving() => Row(
          children: [
            const YoinLoadingIndicator(size: SettingsMetrics.statusIndicator),
            const SizedBox(width: SettingsMetrics.statusIndicatorGap),
            Expanded(
              child: Text(
                status is AppleSetupAuthorizing ? strings.appleStatusAuthorize : strings.appleWorking,
                style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
          ],
        ),
      AppleSetupFailed(:final failure) =>
        _StatusRow(icon: YoinSymbols.error, text: _failureText(strings, failure), color: colors.error),
    };
    return SpringCrossFade(childKey: status.runtimeType, fit: StackFit.loose, child: child);
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.icon, required this.text, required this.color});

  final IconData icon;
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Icon(icon, size: SettingsMetrics.statusIcon, color: color),
          const SizedBox(width: SettingsMetrics.statusIconGap),
          Expanded(child: Text(text, style: Theme.of(context).textTheme.bodyMedium!.copyWith(color: color))),
        ],
      );
}
