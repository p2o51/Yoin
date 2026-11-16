import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../platform/applemusic/apple_music_auth.dart';
import '../settings/apple_music_setup_view_model.dart';
import '../settings/settings_components.dart';
import '../settings/settings_strings.dart';
import '../settings/subsonic_setup_screen.dart' show SecretTextField;
import '../settings/subsonic_setup_view_model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/expressive/expressive_shapes.dart';
import '../ui/component/expressive/expressive_surface_button.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'landing_models.dart';
import 'landing_motion.dart';
import 'landing_scenes.dart';
import 'landing_strings.dart';

// The connect steps reuse the Settings setup view models unchanged (one per
// service). Only the faces differ: the floating window's tighter layout, and
// the mascot reacting (LandingConnect.kt).

/// What the mascot does about a connect step. [connected] comes from the
/// landing itself, which follows the setup view models for as long as it shows.
abstract interface class LandingReactions {
  void refused();
  void connected(LandingService service);
}

/// The connect forms' fields, kept by the landing so stepping back and forth
/// never loses what was typed (Android keeps them in the step's saved state).
class LandingForms {
  final subsonicAddress = TextEditingController();
  final subsonicUsername = TextEditingController();
  final subsonicPassword = TextEditingController();
  final appleOwnUrl = TextEditingController();

  /// Apple Music: the user's own token service instead of Yoin's.
  final appleOwnService = ValueNotifier<bool>(false);

  void dispose() {
    subsonicAddress.dispose();
    subsonicUsername.dispose();
    subsonicPassword.dispose();
    appleOwnUrl.dispose();
    appleOwnService.dispose();
  }
}

/// A short horizontal shake for a refused form, on the shake spring.
class _Shake {
  _Shake(TickerProvider vsync) : anim = LandingAnim(vsync, 0);

  final LandingAnim anim;

  void play() {
    anim.snapTo(0);
    anim.animateTo(0, LandingSprings.mascotShake, velocity: LandingTokens.shakeVelocity);
  }

  Widget wrap(Widget child) => AnimatedBuilder(
    animation: anim.animation,
    builder: (context, child) => Transform.translate(offset: Offset(anim.value, 0), child: child),
    child: child,
  );

  void dispose() => anim.dispose();
}

/// The service's face at the top of its step: mark, name (and badge), tagline.
class _ServiceHeader extends StatelessWidget {
  const _ServiceHeader({required this.service});

  final LandingService service;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final settings = SettingsStrings.of(context);
    final strings = LandingStrings.of(context);
    final (name, tagline, badge) = switch (service) {
      LandingService.subsonic => (settings.introSubsonicName, settings.introSubsonicTagline, null),
      LandingService.appleMusic => (settings.introAppleName, settings.introAppleTagline, strings.appleBadge),
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        children: [
          LandingServiceMark(service: service, size: LandingTokens.headerMark, glyph: LandingTokens.headerGlyph),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        name,
                        style: landingWeight(theme.textTheme.titleLarge!, 600).copyWith(color: colors.onSurface),
                      ),
                    ),
                    if (badge != null) ...[
                      const SizedBox(width: 8),
                      DecoratedBox(
                        decoration: ShapeDecoration(
                          color: colors.secondaryContainer,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(9)),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 1),
                          child: Text(
                            badge.toUpperCase(),
                            style: theme.textTheme.labelSmall!.copyWith(color: colors.onSecondaryContainer),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                Text(tagline, style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The step's main button in the service's colour; spins while busy, settles
/// into "Connected" (its colours spring from accent to container).
class _ServiceCta extends StatefulWidget {
  const _ServiceCta({
    required this.service,
    required this.label,
    required this.busyLabel,
    required this.done,
    required this.onTap,
  });

  final LandingService service;
  final String label;
  final String? busyLabel;
  final bool done;
  final VoidCallback onTap;

  @override
  State<_ServiceCta> createState() => _ServiceCtaState();
}

class _ServiceCtaState extends State<_ServiceCta> with TickerProviderStateMixin {
  late final SpringValue _done = SpringValue(
    vsync: this,
    initial: widget.done ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_ServiceCta old) {
    super.didUpdateWidget(old);
    final target = widget.done ? 1.0 : 0.0;
    MediaQuery.disableAnimationsOf(context) ? _done.snapTo(target) : _done.animateTo(target);
  }

  @override
  void dispose() {
    _done.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tone = landingTone(context, widget.service);
    final strings = LandingStrings.of(context);
    final busy = widget.busyLabel;
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: SizedBox(
        height: LandingTokens.ctaHeight,
        child: AnimatedBuilder(
          animation: _done.animation,
          builder: (context, _) {
            final d = _done.value.clamp(0.0, 1.0);
            final background = Color.lerp(tone.accent, tone.container, d)!;
            final content = Color.lerp(tone.onAccent, tone.onContainer, d)!;
            final style = theme.textTheme.titleSmall!.copyWith(color: content);
            final Widget face;
            if (widget.done) {
              face = Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(YoinSymbols.check, size: 20, color: content),
                  const SizedBox(width: 10),
                  Text(strings.connected, style: style),
                ],
              );
            } else if (busy != null) {
              face = Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  YoinLoadingIndicator(size: 24, contained: false, color: content),
                  const SizedBox(width: 10),
                  Flexible(
                    child: Text(busy, style: style, maxLines: 1, overflow: TextOverflow.ellipsis),
                  ),
                ],
              );
            } else {
              face = Text(widget.label, style: style);
            }
            return ExpressiveSurfaceButton(
              shapes: m3eIconButtonShapes(height: LandingTokens.ctaHeight),
              height: LandingTokens.ctaHeight,
              color: background,
              contentColor: content,
              semanticLabel: widget.done ? strings.connected : (busy ?? widget.label),
              onTap: () {
                if (widget.done || busy != null) return;
                // The keyboard goes down so the answer (the mascot, an error line) is in view.
                FocusManager.instance.primaryFocus?.unfocus();
                widget.onTap();
              },
              child: ExcludeSemantics(child: Center(child: face)),
            );
          },
        ),
      ),
    );
  }
}

/// The step's error, unfolding under the form.
class _ErrorLine extends StatelessWidget {
  const _ErrorLine({required this.text});

  final String? text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final text = this.text;
    return SpringReveal(
      visible: text != null,
      child: text == null
          ? null
          : Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 8, 0),
              child: Text(text, style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.error)),
            ),
    );
  }
}

String _setupErrorText(SettingsStrings strings, SetupError error) => switch (error) {
  SetupError.addressRequired => strings.addressRequired,
  SetupError.checkAddress => strings.checkAddress,
  SetupError.usernameRequired => strings.usernameRequired,
  SetupError.passwordRequired => strings.passwordRequired,
  SetupError.serverError => strings.serverError,
  SetupError.unreachable => strings.unreachable,
  SetupError.saveFailed => strings.saveFailed,
};

// ── Subsonic ────────────────────────────────────────────────────────────

/// Connect = reach the server, then save: an unreachable server never becomes an account.
class SubsonicConnectScene extends ConsumerStatefulWidget {
  const SubsonicConnectScene({super.key, required this.forms, required this.connected, required this.reactions});

  final LandingForms forms;
  final bool connected;
  final LandingReactions reactions;

  @override
  ConsumerState<SubsonicConnectScene> createState() => _SubsonicConnectSceneState();
}

class _SubsonicConnectSceneState extends ConsumerState<SubsonicConnectScene> with TickerProviderStateMixin {
  late final _Shake _shake = _Shake(this);

  @override
  void dispose() {
    _shake.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    final vm = ref.read(subsonicSetupViewModelProvider.notifier);
    final forms = widget.forms;
    await vm.connect(
      address: forms.subsonicAddress.text,
      username: forms.subsonicUsername.text,
      password: forms.subsonicPassword.text,
    );
    if (!mounted) return;
    final state = ref.read(subsonicSetupViewModelProvider);
    // A connection is the landing's to celebrate (it hears it even after a step away).
    if (!state.connected && (state.status is SetupFailed || state.addressError != null)) {
      widget.reactions.refused();
      _shake.play();
    }
  }

  @override
  Widget build(BuildContext context) {
    final settings = SettingsStrings.of(context);
    final state = ref.watch(subsonicSetupViewModelProvider);
    final forms = widget.forms;
    final status = state.status;
    final error = switch ((state.addressError, status)) {
      (final SetupError e, _) => _setupErrorText(settings, e),
      (_, SetupFailed(:final error, :final serverMessage)) => serverMessage ?? _setupErrorText(settings, error),
      _ => null,
    };
    return LandingSceneColumn(
      children: [
        const _ServiceHeader(service: LandingService.subsonic),
        _shake.wrap(
          Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // No autofill hints: HarmonyOS would offer to save the server password to the system vault.
              ExpressiveTextField(
                controller: forms.subsonicAddress,
                label: settings.addressLabel,
                placeholder: settings.addressPlaceholder,
                keyboardType: TextInputType.url,
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 8),
              ExpressiveTextField(
                controller: forms.subsonicUsername,
                label: settings.usernameLabel,
                placeholder: settings.usernamePlaceholder,
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 8),
              SecretTextField(
                controller: forms.subsonicPassword,
                label: settings.passwordLabel,
                placeholder: settings.passwordPlaceholder,
              ),
            ],
          ),
        ),
        _ErrorLine(text: widget.connected ? null : error),
        _ServiceCta(
          service: LandingService.subsonic,
          label: settings.connect,
          busyLabel: switch (status) {
            SetupTesting() => settings.reaching,
            SetupSaving() => settings.connecting,
            _ => null,
          },
          done: widget.connected,
          onTap: _connect,
        ),
      ],
    );
  }
}

// ── Apple Music ─────────────────────────────────────────────────────────

/// Apple Music needs a developer token service. Yoin's own is the default
/// and is never shown or copied; a user with an Apple Developer Program
/// membership can point at their own. The sign-in itself is the ArkWeb page
/// the Settings flow uses (Android's picture-in-picture guide has no
/// HarmonyOS counterpart: the sign-in stays inside Yoin).
class AppleConnectScene extends ConsumerStatefulWidget {
  const AppleConnectScene({super.key, required this.forms, required this.connected, required this.reactions});

  final LandingForms forms;
  final bool connected;
  final LandingReactions reactions;

  @override
  ConsumerState<AppleConnectScene> createState() => _AppleConnectSceneState();
}

class _AppleConnectSceneState extends ConsumerState<AppleConnectScene> with TickerProviderStateMixin {
  late final _Shake _shake = _Shake(this);
  bool _attempted = false;

  @override
  void dispose() {
    _shake.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    final forms = widget.forms;
    final endpoint = forms.appleOwnService.value ? forms.appleOwnUrl.text.trim() : yoinAppleMusicTokenServiceUrl;
    if (!endpoint.startsWith('https://')) {
      // As Android: the mascot says no; the form itself stays still.
      setState(() => _attempted = false);
      widget.reactions.refused();
      return;
    }
    setState(() => _attempted = true);
    await ref.read(appleMusicSetupViewModelProvider.notifier).connect(endpoint);
    if (!mounted) return;
    final state = ref.read(appleMusicSetupViewModelProvider);
    if (state.connected) {
      setState(() => _attempted = false);
    } else if (state.status is AppleSetupFailed || state.endpointError != null) {
      widget.reactions.refused();
      _shake.play();
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final settings = SettingsStrings.of(context);
    final strings = LandingStrings.of(context);
    final state = ref.watch(appleMusicSetupViewModelProvider);
    final forms = widget.forms;
    final failed = _attempted && !state.isBusy && !state.connected;
    final error = !failed
        ? null
        : switch ((state.endpointError, state.status)) {
            (AppleSetupError.endpointRequired, _) => settings.appleEndpointRequired,
            (AppleSetupError.endpointInvalid, _) => settings.appleEndpointInvalid,
            (_, AppleSetupFailed(failure: AppleMusicAuthFailure.cancelled)) => settings.appleStatusCancelled,
            (_, AppleSetupFailed()) => settings.appleStatusFailed,
            _ => null,
          };
    return LandingSceneColumn(
      children: [
        const _ServiceHeader(service: LandingService.appleMusic),
        LandingLabel(strings.appleToken),
        ValueListenableBuilder<bool>(
          valueListenable: forms.appleOwnService,
          builder: (context, own, _) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              LandingTokenOption(
                title: strings.appleYoinTitle,
                body: strings.appleYoinBody,
                selected: !own,
                first: true,
                last: false,
                onTap: () => forms.appleOwnService.value = false,
              ),
              const SizedBox(height: LandingTokens.rowGap),
              LandingTokenOption(
                title: strings.appleOwnTitle,
                body: strings.appleOwnBody,
                selected: own,
                first: false,
                last: !own,
                onTap: () => forms.appleOwnService.value = true,
              ),
              SpringReveal(
                visible: own,
                child: Padding(
                  padding: const EdgeInsets.only(top: LandingTokens.rowGap),
                  child: DecoratedBox(
                    decoration: ShapeDecoration(
                      color: colors.surfaceBright,
                      shape: const RoundedRectangleBorder(
                        borderRadius: BorderRadius.vertical(
                          top: Radius.circular(LandingTokens.rowInner),
                          bottom: Radius.circular(LandingTokens.rowOuter),
                        ),
                      ),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(12, 4, 12, 12),
                      child: _shake.wrap(
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            ExpressiveTextField(
                              controller: forms.appleOwnUrl,
                              label: settings.appleEndpointLabel,
                              placeholder: settings.appleEndpointPlaceholder,
                              keyboardType: TextInputType.url,
                              textInputAction: TextInputAction.done,
                            ),
                            // Nothing on HarmonyOS opens a browser yet: the address is said, not linked.
                            Padding(
                              padding: const EdgeInsetsDirectional.only(start: 6, top: 8),
                              child: Text(
                                strings.appleProgramLink,
                                style: theme.textTheme.labelLarge!.copyWith(color: colors.primary),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsetsDirectional.only(start: 6, top: 12),
          child: Row(
            children: [
              Icon(YoinSymbols.info, size: 18, color: colors.onSurfaceVariant),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  strings.appleNeed,
                  style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                ),
              ),
            ],
          ),
        ),
        _ErrorLine(text: widget.connected ? null : error),
        _ServiceCta(
          service: LandingService.appleMusic,
          label: settings.appleConnect,
          busyLabel: state.isBusy && _attempted ? settings.appleWorking : null,
          done: widget.connected,
          onTap: _connect,
        ),
      ],
    );
  }
}

/// One of the two token rows: a radio, its title and line (TokenOption).
class LandingTokenOption extends StatelessWidget {
  const LandingTokenOption({
    super.key,
    required this.title,
    required this.body,
    required this.selected,
    required this.first,
    required this.last,
    required this.onTap,
  });

  final String title;
  final String body;
  final bool selected;
  final bool first;
  final bool last;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    const outer = Radius.circular(LandingTokens.rowOuter);
    const inner = Radius.circular(LandingTokens.rowInner);
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: first ? outer : inner, bottom: last ? outer : inner),
    );
    return Semantics(
      inMutuallyExclusiveGroup: true,
      checked: selected,
      child: Material(
        color: colors.surfaceBright,
        shape: shape,
        child: InkWell(
          customBorder: shape,
          onTap: () {
            if (!selected) YoinHaptics.contextClick();
            onTap();
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: [
                ExcludeSemantics(
                  child: RadioGroup<bool>(
                    groupValue: selected,
                    onChanged: (_) => onTap(),
                    child: const Radio<bool>(value: true),
                  ),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                      Text(body, style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
