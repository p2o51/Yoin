import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/applemusic/apple_music_name_region.dart';
import '../nowplaying/haptics.dart';
import '../platform/applemusic/apple_music_auth.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'account_identity.dart';
import 'apple_music_account_view_model.dart';
import 'apple_music_setup_view_model.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// Opens the page of the Apple Music account [profileId] (Manage).
Future<void> openAppleMusicManage(BuildContext context, String profileId) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => AppleMusicAccountScreen(profileId: profileId)));

/// An Apple Music account's page (Android ServiceSetupScreen managing an
/// Apple Music account): the account's own avatar, name and service line;
/// the Account group — the developer-token service it signed in with, its
/// status and Reconnect; then Song names, which storefront its songs and
/// albums are named from (AppleMusicNameRegionGroup). PushPage, opened with
/// YoinPushRoute; on a wide window Settings shows it in its right pane
/// ([inPane]: no bar of its own).
class AppleMusicAccountScreen extends ConsumerStatefulWidget {
  const AppleMusicAccountScreen({super.key, required this.profileId, this.inPane = false});

  final String profileId;
  final bool inPane;

  @override
  ConsumerState<AppleMusicAccountScreen> createState() => _AppleMusicAccountScreenState();
}

class _AppleMusicAccountScreenState extends ConsumerState<AppleMusicAccountScreen> {
  final _endpoint = TextEditingController();
  final _scroll = ScrollController();
  String _lastEndpoint = '';
  bool _seeded = false;

  @override
  void initState() {
    super.initState();
    _endpoint.addListener(_onEndpoint);
  }

  void _onEndpoint() {
    if (_endpoint.text != _lastEndpoint) {
      _lastEndpoint = _endpoint.text;
      ref.read(appleMusicAccountProvider(widget.profileId).notifier).onEndpointChanged();
    }
    setState(() {});
  }

  @override
  void dispose() {
    _endpoint.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _reconnect() => ref.read(appleMusicAccountProvider(widget.profileId).notifier).reconnect(_endpoint.text);

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(appleMusicAccountProvider(widget.profileId));
    if (!_seeded && state.loaded) {
      // The field takes the stored service once, as it arrives.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || _seeded) return;
        _seeded = true;
        final stored = ref.read(appleMusicAccountProvider(widget.profileId)).endpoint;
        _lastEndpoint = stored;
        _endpoint.text = stored;
      });
    }
    final title = state.account?.title ?? strings.introAppleName;
    final list = ListView(
      controller: _scroll,
      clipBehavior: Clip.hardEdge,
      padding: EdgeInsets.fromLTRB(
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageTop,
        SettingsMetrics.pageHorizontal,
        SettingsMetrics.pageBottom + MediaQuery.paddingOf(context).bottom,
      ),
      children: [
        _AccountHero(state: state, title: title),
        const SizedBox(height: SettingsMetrics.sectionGap),
        _AccountGroup(state: state, endpoint: _endpoint, onReconnect: _reconnect),
        const SizedBox(height: SettingsMetrics.sectionGap),
        AppleMusicNameRegionGroup(
          selected: state.nameRegion,
          onSelect: (region) => ref.read(appleMusicAccountProvider(widget.profileId).notifier).setNameRegion(region),
        ),
      ],
    );
    if (widget.inPane) return SafeArea(top: false, bottom: false, child: list);
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: title,
          controller: _scroll,
          titleTop: SettingsMetrics.pageTop + SettingsMetrics.heroMark + SettingsMetrics.heroGap,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.headlineLarge!),
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: SettingsSeam(controller: _scroll, child: list),
        ),
      ),
    );
  }
}

/// The account's own avatar (the same shape, letter and badge as its card),
/// blooming in once; its name a step under the service's own size; the
/// service line.
class _AccountHero extends StatefulWidget {
  const _AccountHero({required this.state, required this.title});

  final AppleMusicAccountState state;
  final String title;

  @override
  State<_AccountHero> createState() => _AccountHeroState();
}

class _AccountHeroState extends State<_AccountHero> with SingleTickerProviderStateMixin {
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
    final theme = Theme.of(context);
    const identity = ServiceIdentity.appleMusic;
    final account = widget.state.account;
    final line = serviceLine(identity.name, widget.title, account?.host);
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
              child: AccountAvatar(
                monogram: monogramOf(widget.title, identity.name),
                shapeIndex: account?.avatarShape ?? 0,
                identity: identity,
                ringColor: settingsPageColor(theme.colorScheme),
                size: SettingsMetrics.heroMark,
              ),
            ),
          ),
          const SizedBox(height: SettingsMetrics.heroGap),
          Semantics(
            header: true,
            child: Text(
              widget.title,
              style: theme.textTheme.headlineLarge!.copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
          if (line != null) ...[
            const SizedBox(height: SettingsMetrics.heroTextGap),
            AccountServiceLine(
              kind: line.kind,
              detail: line.detail,
              color: theme.colorScheme.onSurfaceVariant,
              style: theme.textTheme.bodyLarge,
            ),
          ],
        ],
      ),
    );
  }
}

/// The Account group (Android AppleMusicValidationContent on an existing
/// account): the token service, one status line, Reconnect.
class _AccountGroup extends StatelessWidget {
  const _AccountGroup({required this.state, required this.endpoint, required this.onReconnect});

  final AppleMusicAccountState state;
  final TextEditingController endpoint;
  final VoidCallback onReconnect;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final canSubmit = endpoint.text.trim().startsWith('https://') && !state.isBusy && state.loaded;
    final status = state.status;
    final statusLine = switch (status) {
      AppleSetupAuthorizing() || AppleSetupSaving() => Row(
        key: const ValueKey('busy'),
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
      AppleSetupFailed(:final failure) => Row(
        key: const ValueKey('failed'),
        children: [
          Icon(YoinSymbols.error, size: SettingsMetrics.statusIcon, color: colors.error),
          const SizedBox(width: SettingsMetrics.statusIconGap),
          Expanded(
            child: Text(
              failure == AppleMusicAuthFailure.cancelled ? strings.appleStatusCancelled : strings.appleStatusFailed,
              style: theme.textTheme.bodyMedium!.copyWith(color: colors.error),
            ),
          ),
        ],
      ),
      AppleSetupIdle() => Text(
        state.connected ? strings.appleStatusConnected : strings.appleStatusNeedService,
        key: const ValueKey('idle'),
        style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
      ),
    };
    final error = state.endpointError;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupLabel(title: strings.sectionAccount),
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
                  onSubmitted: canSubmit ? (_) => onReconnect() : null,
                ),
                SpringReveal(
                  visible: error != null,
                  child: error == null
                      ? null
                      : Padding(
                          padding: const EdgeInsetsDirectional.only(
                            start: SettingsMetrics.addressErrorStart,
                            top: SettingsMetrics.addressErrorTop,
                          ),
                          child: Text(
                            error == AppleSetupError.endpointRequired
                                ? strings.appleEndpointRequired
                                : strings.appleEndpointInvalid,
                            style: theme.textTheme.bodyMedium!.copyWith(color: colors.error),
                          ),
                        ),
                ),
                const SizedBox(height: SettingsMetrics.formGap),
                SpringCrossFade(childKey: statusLine.key!, fit: StackFit.loose, child: statusLine),
                const SizedBox(height: SettingsMetrics.formGap),
                FilledButton(
                  onPressed: canSubmit ? onReconnect : null,
                  child: Text(state.connected ? strings.appleReconnect : strings.appleConnect),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// Song names (Android AppleMusicNameRegionGroup): the four regions as radio
/// rows, title over summary, then the foot line.
class AppleMusicNameRegionGroup extends StatelessWidget {
  const AppleMusicNameRegionGroup({super.key, required this.selected, required this.onSelect});

  final AppleMusicNameRegion selected;
  final ValueChanged<AppleMusicNameRegion> onSelect;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    void pick(AppleMusicNameRegion region) {
      if (region == selected) return;
      YoinHaptics.click();
      onSelect(region);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        RadioGroup<AppleMusicNameRegion>(
          groupValue: selected,
          onChanged: (r) {
            if (r != null) pick(r);
          },
          child: SettingsGroup(
            title: strings.appleNamesTitle,
            children: [
              for (final region in AppleMusicNameRegion.values)
                SettingsItem(
                  key: ValueKey(region),
                  title: appleNameRegionTitle(strings, region),
                  summary: appleNameRegionSummary(strings, region),
                  semanticsRadio: true,
                  leading: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.radioInset),
                    child: Radio<AppleMusicNameRegion>(value: region),
                  ),
                  onTap: () => pick(region),
                ),
            ],
          ),
        ),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.rowPaddingHorizontal),
          child: Text(
            strings.appleNamesFoot,
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}

String appleNameRegionTitle(SettingsStrings strings, AppleMusicNameRegion region) => switch (region) {
  AppleMusicNameRegion.off => strings.appleNamesOff,
  AppleMusicNameRegion.original => strings.appleNamesOriginal,
  AppleMusicNameRegion.mainlandChina => strings.appleNamesCn,
  AppleMusicNameRegion.japan => strings.appleNamesJp,
};

String appleNameRegionSummary(SettingsStrings strings, AppleMusicNameRegion region) => switch (region) {
  AppleMusicNameRegion.off => strings.appleNamesOffSummary,
  AppleMusicNameRegion.original => strings.appleNamesOriginalSummary,
  AppleMusicNameRegion.mainlandChina => strings.appleNamesCnSummary,
  AppleMusicNameRegion.japan => strings.appleNamesJpSummary,
};
