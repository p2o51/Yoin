import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../offline/downloads_screen.dart';
import '../offline/offline_strings.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/error_strings.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'account_identity.dart';
import 'material_shapes.dart';
import 'service_intro.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'settings_view_model.dart';
import 'stream_quality_screen.dart';
import 'subsonic_setup_view_model.dart';
import 'volume_leveling_screen.dart';

/// Opens the page of the Subsonic account [profileId] (Manage).
Future<void> openSubsonicManage(BuildContext context, String profileId) => Navigator.of(context)
    .push(YoinPushRoute<void>(builder: (_) => SubsonicSetupScreen(profileId: profileId)));

/// A Subsonic server's page (Android ServiceSetupScreen, Subsonic).
///
/// Adding ([profileId] null): the service's mark and name, what you'll need,
/// then the Connect group — address, username, password, Test and Connect.
/// Managing: the account's own avatar and name, the Server group filled with
/// what is stored (Save instead of Connect), then Playback. PushPage, opened
/// with YoinPushRoute; on a wide window Settings shows the managing page in
/// its right pane instead ([inPane]: no bar of its own, [onDone] after a save).
class SubsonicSetupScreen extends ConsumerStatefulWidget {
  const SubsonicSetupScreen({super.key, this.profileId, this.inPane = false, this.onDone});

  final String? profileId;
  final bool inPane;

  /// In the pane: called once the account is saved (the page doesn't pop).
  final VoidCallback? onDone;

  @override
  ConsumerState<SubsonicSetupScreen> createState() => _SubsonicSetupScreenState();
}

class _SubsonicSetupScreenState extends ConsumerState<SubsonicSetupScreen> {
  final _address = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _scroll = ScrollController();

  /// Two columns: the hero side's own scroll (the bar's title reads it).
  final _leadScroll = ScrollController();
  String _lastAddress = '';
  bool _seeded = false;

  NotifierProvider<SubsonicSetupViewModel, SubsonicSetupState> get _provider => subsonicSetupProvider(widget.profileId);

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
      ref.read(_provider.notifier).onAddressChanged();
    }
    setState(() {});
  }

  @override
  void dispose() {
    _address.dispose();
    _username.dispose();
    _password.dispose();
    _scroll.dispose();
    _leadScroll.dispose();
    super.dispose();
  }

  void _test() =>
      ref.read(_provider.notifier).test(address: _address.text, username: _username.text, password: _password.text);

  void _connect() =>
      ref.read(_provider.notifier).connect(address: _address.text, username: _username.text, password: _password.text);

  /// Managing: the fields take the stored values once, as they arrive.
  void _seed(SubsonicSetupState state) {
    if (_seeded || !state.loaded) return;
    _seeded = true;
    final stored = state.stored;
    if (stored == null) return;
    _lastAddress = stored.baseUrl;
    _address.text = stored.baseUrl;
    _username.text = stored.username;
    _password.text = stored.password;
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(_provider);
    ref.listen(_provider.select((s) => s.connected), (_, connected) {
      if (!connected) return;
      if (widget.inPane) {
        widget.onDone?.call();
      } else {
        Navigator.of(context).pop();
      }
    });
    if (!_seeded && state.loaded) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() => _seed(ref.read(_provider)));
      });
    }
    final managing = widget.profileId != null;
    final account = state.account;
    final hero = _ServiceHero(account: account);
    final requirements = _RequirementsGroup(requirements: [strings.introSubsonicNeed]);
    final connect = _ConnectGroup(
      provider: _provider,
      address: _address,
      username: _username,
      password: _password,
      onTest: _test,
      onConnect: _connect,
    );
    // Managing: the account's own settings under its connection.
    final accountSettings = <Widget>[
      if (managing) ...[
        SettingsGroup(
          title: strings.sectionPlayback,
          children: [
            StreamQualityRow(profileId: widget.profileId!),
            // Subsonic reports ReplayGain (Capability.replayGain).
            VolumeLevelingRow(profileId: widget.profileId!),
          ],
        ),
        // Downloads lists and removes the account in use's songs, so its
        // row shows only on that account's page (ProfileServiceSettings).
        if (account?.isActive ?? false)
          SettingsGroup(
            title: OfflineStrings.of(context).sectionStorage,
            children: [DownloadsRow(profileId: widget.profileId!)],
          ),
      ],
    ];
    final columns = setupUsesColumns(YoinWindowInfo.of(context));
    final Widget body;
    if (columns) {
      // Landscape / wide: the service stays put on the left; the form first,
      // then "What you get" (adding) or its settings (managing), on the right.
      body = SetupColumns(
        leadController: _leadScroll,
        sideController: _scroll,
        lead: [hero, if (!managing) requirements],
        side: [connect, if (managing) ...accountSettings else const ServiceHighlightsGroup(service: SetupService.subsonic)],
      );
    } else {
      // Adding: the pitch first, then connect. Managing: straight to the controls.
      final blocks = <Widget>[
        hero,
        if (!managing) ...[const ServiceHighlightsGroup(service: SetupService.subsonic), requirements],
        connect,
        ...accountSettings,
      ];
      body = ListView(
        controller: _scroll,
        // The scroll clips itself; the pane's corners are painted over it.
        clipBehavior: Clip.hardEdge,
        padding: EdgeInsets.fromLTRB(
          SettingsMetrics.pageHorizontal,
          SettingsMetrics.pageTop,
          SettingsMetrics.pageHorizontal,
          SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
        ),
        children: [
          for (var i = 0; i < blocks.length; i++) ...[
            if (i > 0) const SizedBox(height: SettingsMetrics.sectionGap),
            blocks[i],
          ],
        ],
      );
    }
    if (widget.inPane) {
      return SafeArea(top: false, bottom: false, child: body);
    }
    final title = account?.title ?? strings.introSubsonicName;
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: title,
          // Two columns: the hero doesn't scroll with the form.
          controller: columns ? _leadScroll : _scroll,
          // The hero's title sits under its mark.
          titleTop: SettingsMetrics.pageTop + SettingsMetrics.heroMark + SettingsMetrics.heroGap,
          titleHeight: SettingsBar.lineHeight(
            context,
            managing ? Theme.of(context).textTheme.headlineLarge! : Theme.of(context).textTheme.displaySmall!,
          ),
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: columns ? body : SettingsSeam(controller: _scroll, child: body),
        ),
      ),
    );
  }
}

// ── Hero ──────────────────────────────────────────────────────────────

/// Adding: the service's mark in its own colour on a nine-sided cookie,
/// blooming in once on the slow spatial spring; then its name, large, and its
/// tagline. Managing ([account]): the account's own avatar — same shape,
/// letter and badge as its card — its name a step smaller, and the service line.
class _ServiceHero extends StatefulWidget {
  const _ServiceHero({this.account});

  final AccountCard? account;

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
    final account = widget.account;
    final line = account == null ? null : serviceLine(identity.name, account.title, account.host);
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
              child: account != null
                  ? AccountAvatar(
                      monogram: monogramOf(account.title, identity.name),
                      shapeIndex: account.avatarShape,
                      identity: identity,
                      ringColor: settingsPageColor(theme.colorScheme),
                      size: SettingsMetrics.heroMark,
                    )
                  : DecoratedBox(
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
              account?.title ?? strings.introSubsonicName,
              style: (account != null ? theme.textTheme.headlineLarge! : theme.textTheme.displaySmall!)
                  .copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
          const SizedBox(height: SettingsMetrics.heroTextGap),
          if (line != null)
            AccountServiceLine(
              kind: line.kind,
              detail: line.detail,
              color: theme.colorScheme.onSurfaceVariant,
              style: theme.textTheme.bodyLarge,
            )
          else if (account == null)
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

/// What the form says for [error] (strings_settings.xml).
String setupErrorText(BuildContext context, SetupError error) {
  final strings = SettingsStrings.of(context);
  return switch (error) {
    SetupError.addressRequired => strings.addressRequired,
    SetupError.checkAddress => strings.checkAddress,
    SetupError.usernameRequired => strings.usernameRequired,
    SetupError.passwordRequired => strings.passwordRequired,
    SetupError.serverError => strings.serverError,
    SetupError.unreachable => strings.unreachable,
    SetupError.saveFailed => strings.saveFailed,
    SetupError.accountLimit => ServiceIntroStrings.of(context).accountLimit,
  };
}

/// A failed test or connect: the server's own words, else which way it
/// didn't answer (UserMessage.kt), else [setupErrorText].
String setupFailureText(BuildContext context, SetupFailed failed) =>
    failed.serverMessage ?? failed.line?.text(ErrorStrings.of(context)) ?? setupErrorText(context, failed.error);

class _ConnectGroup extends ConsumerWidget {
  const _ConnectGroup({
    required this.provider,
    required this.address,
    required this.username,
    required this.password,
    required this.onTest,
    required this.onConnect,
  });

  final NotifierProvider<SubsonicSetupViewModel, SubsonicSetupState> provider;
  final TextEditingController address;
  final TextEditingController username;
  final TextEditingController password;
  final VoidCallback onTest;
  final VoidCallback onConnect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(provider);
    final filled = address.text.trim().isNotEmpty && username.text.trim().isNotEmpty && password.text.trim().isNotEmpty;
    final canTest = filled && !state.isBusy;
    final canSubmit = canTest && state.loaded && state.canAddProfile;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupLabel(title: state.isManaging ? strings.sectionServer : strings.sectionConnect),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        Material(
          color: settingsRowColor(colors),
          shape: settingsSegmentShape(0, 1),
          child: Padding(
            padding: const EdgeInsets.all(SettingsMetrics.formPadding),
            // No autofill anywhere on this form: HarmonyOS would offer to save
            // the server password to the system vault (Flutter only reports a
            // field to the platform's autofill when it has hints).
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (state.credentialsMissing) ...[
                  SettingsNotice(text: strings.passwordMissing),
                  const SizedBox(height: SettingsMetrics.formGap),
                ],
                // What is wrong with the address sits right under it, and goes once the address changes.
                ExpressiveTextField(
                  controller: address,
                  label: strings.addressLabel,
                  placeholder: strings.addressPlaceholder,
                  keyboardType: TextInputType.url,
                  textInputAction: TextInputAction.next,
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
                            text: setupErrorText(context, state.addressError!),
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
                ),
                const SizedBox(height: SettingsMetrics.formGap),
                SecretTextField(
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
                // Five accounts already: Connect waits, and says why (settings_setup_limit_subsonic).
                SpringReveal(
                  visible: !state.canAddProfile,
                  child: Padding(
                    padding: const EdgeInsets.only(top: SettingsMetrics.formGap),
                    child: SettingsNotice(text: ServiceIntroStrings.of(context).limitSubsonic),
                  ),
                ),
                const SizedBox(height: SettingsMetrics.formGap),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(onPressed: canTest ? onTest : null, child: Text(strings.test)),
                    ),
                    const SizedBox(width: SettingsMetrics.buttonGap),
                    Expanded(
                      child: FilledButton(
                        onPressed: canSubmit ? onConnect : null,
                        child: Text(state.isManaging ? strings.save : strings.connect),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// Masked input with a show/hide toggle (SecretTextField). The toggle sits
/// over the field's trailing end, centred on its box.
class SecretTextField extends StatefulWidget {
  const SecretTextField({
    super.key,
    required this.controller,
    required this.label,
    required this.placeholder,
    this.onSubmitted,
  });

  final TextEditingController controller;
  final String label;
  final String placeholder;
  final VoidCallback? onSubmitted;

  @override
  State<SecretTextField> createState() => _SecretTextFieldState();
}

class _SecretTextFieldState extends State<SecretTextField> {
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
      final SetupFailed failed => _StatusRow(icon: YoinSymbols.error, text: setupFailureText(context, failed), color: colors.error),
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
