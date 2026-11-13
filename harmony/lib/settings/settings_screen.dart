import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/profile/profile.dart';
import '../shell/bar_suppression.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'settings_strings.dart';
import 'settings_view_model.dart';
import 'subsonic_setup_screen.dart';

/// Opens Settings (PushPage). The Home avatar, Home's and Library's error
/// "Settings" and Library's settings button all come here.
Future<void> openSettings(BuildContext context) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const SettingsScreen()));

/// Opens the Subsonic setup page to add an account.
Future<void> openAddAccount(BuildContext context) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const SubsonicSetupScreen()));

/// Settings, v1: the account list (Android SettingsScreen's Accounts
/// section). Tap an account to use it, its menu removes it, Add account
/// opens the service's setup page. Its own page with no bar (Android: an
/// Activity of its own).
class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final state = ref.watch(settingsViewModelProvider);
    return ChromeBarSuppression(
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(YoinSymbols.back),
            tooltip: MaterialLocalizations.of(context).backButtonTooltip,
            onPressed: () => Navigator.of(context).pop(),
          ),
          title: Text(strings.title),
        ),
        body: SafeArea(
          top: false,
          child: SpringCrossFade(
            childKey: state is SettingsContent,
            child: switch (state) {
              SettingsLoading() => const SizedBox.expand(),
              SettingsContent() => _AccountList(state: state),
            },
          ),
        ),
      ),
    );
  }
}

class _AccountList extends ConsumerWidget {
  const _AccountList({required this.state});

  final SettingsContent state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final text = Theme.of(context).textTheme;
    final colors = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(
        SettingsTokens.pagePadding,
        SettingsTokens.sectionTop,
        SettingsTokens.pagePadding,
        SettingsTokens.pagePadding,
      ),
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: SettingsTokens.sectionLabelInset,
            vertical: SettingsTokens.sectionLabelGap,
          ),
          child: Text(strings.sectionAccounts, style: text.titleSmall!.copyWith(color: colors.primary)),
        ),
        if (state.profiles.isEmpty)
          Padding(
            padding: const EdgeInsets.all(SettingsTokens.sectionLabelInset),
            child: Text(strings.accountsEmptyTitle, style: text.bodyLarge!.copyWith(color: colors.onSurfaceVariant)),
          ),
        for (final profile in state.profiles)
          Padding(
            padding: const EdgeInsets.only(bottom: SettingsTokens.rowGap),
            child: _AccountRow(profile: profile, active: profile.id == state.activeId),
          ),
        const SizedBox(height: SettingsTokens.sectionLabelGap),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: FilledButton.tonalIcon(
            style: FilledButton.styleFrom(shape: const StadiumBorder()),
            icon: const Icon(YoinSymbols.add),
            label: Text(strings.accountsAdd),
            onPressed: () => openAddAccount(context),
          ),
        ),
      ],
    );
  }
}

class _AccountRow extends ConsumerWidget {
  const _AccountRow({required this.profile, required this.active});

  final Profile profile;
  final bool active;

  Future<void> _switch(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.maybeOf(context);
    final failed = SettingsStrings.of(context).switchFailed;
    final ok = await ref.read(settingsViewModelProvider.notifier).switchTo(profile.id);
    if (!ok) messenger?.showSnackBar(SnackBar(content: Text(failed)));
  }

  Future<void> _options(BuildContext context, WidgetRef ref) async {
    final strings = SettingsStrings.of(context);
    final choice = await showYoinDropdownMenu<_AccountAction>(
      context: context,
      anchor: globalRectOf(context),
      semanticLabel: strings.cdAccountOptionsRow(profile.displayName),
      items: [
        if (!active) YoinMenuItem(value: _AccountAction.use, label: strings.accountsUse, icon: YoinSymbols.personCheck),
        YoinMenuItem(value: _AccountAction.remove, label: strings.accountsRemoveMenu, icon: YoinSymbols.delete),
      ],
    );
    if (!context.mounted) return;
    switch (choice) {
      case _AccountAction.use:
        await _switch(context, ref);
      case _AccountAction.remove:
        final confirmed = await _confirmRemove(context);
        if (confirmed == true) await ref.read(settingsViewModelProvider.notifier).remove(profile.id);
      case null:
        break;
    }
  }

  Future<bool?> _confirmRemove(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(strings.removeTitle),
        content: Text(strings.removeBody(profile.displayName)),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(strings.removeCancel)),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: Text(strings.removeConfirm)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final text = Theme.of(context).textTheme;
    final colors = Theme.of(context).colorScheme;
    final service = strings.serviceNameSubsonic; // v1 ships Subsonic only.
    return Material(
      color: active ? colors.secondaryContainer : colors.surfaceContainer,
      shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
      clipBehavior: Clip.antiAlias,
      child: Semantics(
        button: !active,
        selected: active,
        hint: active ? null : strings.cdSwitchAccount,
        child: InkWell(
          onTap: active ? null : () => _switch(context, ref),
          child: Padding(
            padding: const EdgeInsetsDirectional.only(
              start: SettingsTokens.rowPadding,
              top: SettingsTokens.rowPadding,
              bottom: SettingsTokens.rowPadding,
            ),
            child: Row(
              children: [
                Icon(
                  active ? YoinSymbols.personCheck : YoinSymbols.person,
                  color: active ? colors.onSecondaryContainer : colors.onSurfaceVariant,
                ),
                const SizedBox(width: SettingsTokens.rowPadding),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        profile.displayName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.titleMedium!.copyWith(
                          color: active ? colors.onSecondaryContainer : colors.onSurface,
                        ),
                      ),
                      Text(
                        service,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.bodyMedium!.copyWith(
                          color: active ? colors.onSecondaryContainer : colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                Builder(
                  builder: (anchorContext) => IconButton(
                    tooltip: strings.cdAccountOptionsRow(profile.displayName),
                    icon: const Icon(YoinSymbols.moreVertical),
                    onPressed: () => _options(anchorContext, ref),
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

enum _AccountAction { use, remove }
