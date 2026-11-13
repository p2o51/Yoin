import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'account_identity.dart';
import 'material_shapes.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_strings.dart';
import 'subsonic_setup_view_model.dart';

/// Adding a Subsonic server (Android ServiceSetupScreen, Subsonic, adding):
/// the service's mark and name, what you'll need, then the Connect group —
/// address, username, password, Test and Connect. PushPage; opened with
/// YoinPushRoute.
class SubsonicSetupScreen extends ConsumerStatefulWidget {
  const SubsonicSetupScreen({super.key});

  @override
  ConsumerState<SubsonicSetupScreen> createState() => _SubsonicSetupScreenState();
}

class _SubsonicSetupScreenState extends ConsumerState<SubsonicSetupScreen> {
  final _address = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _scroll = ScrollController();
  String _lastAddress = '';

  @override
  void initState() {
    super.initState();
    _address.addListener(_onAddress);
    _username.addListener(_rebuild);
    _password.addListener(_rebuild);
  }

  void _rebuild() => setState(() {});

  void _onAddress() {
    if (_address.text != _lastAddress) {
      _lastAddress = _address.text;
      ref.read(subsonicSetupViewModelProvider.notifier).onAddressChanged();
    }
    setState(() {});
  }

  @override
  void dispose() {
    _address.dispose();
    _username.dispose();
    _password.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _test() => ref
      .read(subsonicSetupViewModelProvider.notifier)
      .test(address: _address.text, username: _username.text, password: _password.text);

  void _connect() => ref
      .read(subsonicSetupViewModelProvider.notifier)
      .connect(address: _address.text, username: _username.text, password: _password.text);

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    ref.listen(subsonicSetupViewModelProvider.select((s) => s.connected), (_, connected) {
      if (connected) Navigator.of(context).pop();
    });
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: strings.introSubsonicName,
          controller: _scroll,
          // The hero's title sits under its mark.
          titleTop: SettingsMetrics.pageTop + SettingsMetrics.heroMark + SettingsMetrics.heroGap,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
        ),
        body: SafeArea(
          top: false,
          bottom: false,
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
              _RequirementsGroup(requirements: [strings.introSubsonicNeed]),
              const SizedBox(height: SettingsMetrics.sectionGap),
              _ConnectGroup(
                address: _address,
                username: _username,
                password: _password,
                onTest: _test,
                onConnect: _connect,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Hero ──────────────────────────────────────────────────────────────

/// The service's mark in its own colour on a nine-sided cookie, blooming in
/// once on the slow spatial spring; then its name, large, and its tagline.
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
    const identity = ServiceIdentity.subsonic;
    final tone = identity.hue.tone(theme.brightness);
    // On the 24dp line the back button and the group labels share.
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
              strings.introSubsonicName,
              style: theme.textTheme.displaySmall!.copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
          const SizedBox(height: SettingsMetrics.heroTextGap),
          Text(
            strings.introSubsonicTagline,
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

String _errorText(SettingsStrings strings, SetupError error) => switch (error) {
      SetupError.addressRequired => strings.addressRequired,
      SetupError.checkAddress => strings.checkAddress,
      SetupError.usernameRequired => strings.usernameRequired,
      SetupError.passwordRequired => strings.passwordRequired,
      SetupError.serverError => strings.serverError,
      SetupError.unreachable => strings.unreachable,
      SetupError.saveFailed => strings.saveFailed,
    };

class _ConnectGroup extends ConsumerWidget {
  const _ConnectGroup({
    required this.address,
    required this.username,
    required this.password,
    required this.onTest,
    required this.onConnect,
  });

  final TextEditingController address;
  final TextEditingController username;
  final TextEditingController password;
  final VoidCallback onTest;
  final VoidCallback onConnect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(subsonicSetupViewModelProvider);
    final filled = address.text.trim().isNotEmpty && username.text.trim().isNotEmpty && password.text.trim().isNotEmpty;
    final canSubmit = filled && !state.isBusy;
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
            child: AutofillGroup(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // What is wrong with the address sits right under it, and goes once the address changes.
                  ExpressiveTextField(
                    controller: address,
                    label: strings.addressLabel,
                    placeholder: strings.addressPlaceholder,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.next,
                    autofillHints: const [AutofillHints.url],
                  ),
                  SpringReveal(
                    visible: state.addressError != null,
                    child: state.addressError == null
                        ? null
                        : Padding(
                            padding: const EdgeInsetsDirectional.only(
                              start: SettingsMetrics.addressErrorStart,
                              top: SettingsMetrics.addressErrorTop,
                            ),
                            child: _StatusRow(
                              icon: YoinSymbols.error,
                              text: _errorText(strings, state.addressError!),
                              color: colors.error,
                            ),
                          ),
                  ),
                  const SizedBox(height: SettingsMetrics.formGap),
                  ExpressiveTextField(
                    controller: username,
                    label: strings.usernameLabel,
                    placeholder: strings.usernamePlaceholder,
                    textInputAction: TextInputAction.next,
                    autofillHints: const [AutofillHints.username],
                  ),
                  const SizedBox(height: SettingsMetrics.formGap),
                  _SecretTextField(
                    controller: password,
                    label: strings.passwordLabel,
                    placeholder: strings.passwordPlaceholder,
                    onSubmitted: canSubmit ? onConnect : null,
                  ),
                  SpringReveal(
                    visible: state.status is! SetupIdle,
                    child: state.status is SetupIdle
                        ? null
                        : Padding(
                            padding: const EdgeInsets.only(top: SettingsMetrics.formGap),
                            child: _StatusLine(status: state.status),
                          ),
                  ),
                  const SizedBox(height: SettingsMetrics.formGap),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(onPressed: canSubmit ? onTest : null, child: Text(strings.test)),
                      ),
                      const SizedBox(width: SettingsMetrics.buttonGap),
                      Expanded(
                        child: FilledButton(onPressed: canSubmit ? onConnect : null, child: Text(strings.connect)),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// Masked input with a show/hide toggle (SecretTextField). The toggle sits
/// over the field's trailing end, centred on its box.
class _SecretTextField extends StatefulWidget {
  const _SecretTextField({required this.controller, required this.label, required this.placeholder, this.onSubmitted});

  final TextEditingController controller;
  final String label;
  final String placeholder;
  final VoidCallback? onSubmitted;

  @override
  State<_SecretTextField> createState() => _SecretTextFieldState();
}

class _SecretTextFieldState extends State<_SecretTextField> {
  bool _visible = false;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return Stack(
      children: [
        ExpressiveTextField(
          controller: widget.controller,
          label: widget.label,
          placeholder: widget.placeholder,
          obscureText: !_visible,
          textInputAction: TextInputAction.done,
          autofillHints: const [AutofillHints.password],
          onSubmitted: widget.onSubmitted == null ? null : (_) => widget.onSubmitted!(),
        ),
        PositionedDirectional(
          end: 0,
          bottom: 0,
          height: SettingsMetrics.fieldBoxHeight,
          child: Center(
            child: IconButton(
              tooltip: _visible ? strings.cdHide(widget.label) : strings.cdShow(widget.label),
              constraints: const BoxConstraints.tightFor(
                width: SettingsMetrics.secretToggleTouch,
                height: SettingsMetrics.secretToggleTouch,
              ),
              padding: EdgeInsets.zero,
              icon: Icon(
                _visible ? YoinSymbols.visibilityOff : YoinSymbols.visibility,
                size: SettingsMetrics.secretToggleIcon,
                color: colors.onSurfaceVariant,
              ),
              onPressed: () => setState(() => _visible = !_visible),
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

  final SetupStatus status;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final child = switch (status) {
      SetupIdle() => const SizedBox.shrink(),
      SetupTesting() || SetupSaving() => Row(
          children: [
            const YoinLoadingIndicator(size: SettingsMetrics.statusIndicator),
            const SizedBox(width: SettingsMetrics.statusIndicatorGap),
            Expanded(
              child: Text(
                status is SetupTesting ? strings.reaching : strings.connecting,
                style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
          ],
        ),
      SetupReachable() => _StatusRow(icon: YoinSymbols.check, text: strings.reachable, color: colors.primary),
      SetupFailed(:final error, :final serverMessage) => _StatusRow(
          icon: YoinSymbols.error,
          text: serverMessage ?? _errorText(strings, error),
          color: colors.error,
        ),
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
