import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'account_identity.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_strings.dart';
import 'settings_view_model.dart';
import 'subsonic_setup_screen.dart';

/// Opens Settings (PushPage). The Home avatar, Home's and Library's error
/// "Settings" and Library's settings button all come here.
Future<void> openSettings(BuildContext context) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const SettingsScreen()));

/// Opens the Subsonic setup page to add an account. Android opens a service
/// picker first; v1 has one service, so Add goes straight to its page.
Future<void> openAddAccount(BuildContext context) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const SubsonicSetupScreen()));

/// Settings, v1: Android SettingsScreen's page chrome and its Accounts
/// section (the account switcher). Tap an account to use it, its menu uses or
/// removes it, Add opens the service's setup page. Its own page with no bar.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(settingsViewModelProvider);
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: settingsPageColor(colors),
        appBar: SettingsBar(
          title: strings.title,
          controller: _scroll,
          titleTop: SettingsBar.headlineOffset,
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
              SettingsHeadline(title: strings.title),
              SpringCrossFade(
                childKey: state is SettingsContent,
                fit: StackFit.loose,
                child: switch (state) {
                  SettingsLoading() => const Center(child: YoinLoadingIndicator()),
                  SettingsContent() => _AccountsSection(state: state),
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AccountsSection extends StatelessWidget {
  const _AccountsSection({required this.state});

  final SettingsContent state;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupLabel(title: strings.sectionAccounts),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        if (state.accounts.isEmpty) const _EmptyAccountsCard() else _AccountCardRow(accounts: state.accounts),
      ],
    );
  }
}

/// No account yet: a primary-container panel with the one thing to do.
class _EmptyAccountsCard extends StatelessWidget {
  const _EmptyAccountsCard();

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Material(
      color: colors.primaryContainer,
      shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
      child: Padding(
        padding: const EdgeInsets.all(SettingsMetrics.emptyPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(strings.accountsEmptyTitle, style: theme.textTheme.titleLarge!.copyWith(color: colors.onPrimaryContainer)),
            const SizedBox(height: SettingsMetrics.emptyGap),
            FilledButton.icon(
              onPressed: () => openAddAccount(context),
              icon: const Icon(YoinSymbols.add, size: SettingsMetrics.emptyButtonIcon),
              label: Text(strings.accountsAdd),
              style: FilledButton.styleFrom(iconSize: SettingsMetrics.emptyButtonIcon),
            ),
          ],
        ),
      ),
    );
  }
}

/// The account switcher. It runs edge to edge of the screen, past the page
/// padding, so cards are only ever cut by the screen edge; resting, the
/// first card sits on the page margin.
class _AccountCardRow extends StatelessWidget {
  const _AccountCardRow({required this.accounts});

  final List<AccountCard> accounts;

  /// Card height from its content, so text never clips at large font scales.
  static double _height(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scaler = MediaQuery.textScalerOf(context);
    double line(TextStyle s) => scaler.scale(s.fontSize! * (s.height ?? 1));
    return SettingsMetrics.cardFixedHeight +
        line(type.titleMedium!) * 2 +
        line(type.bodyMedium!) +
        line(type.labelMedium!);
  }

  @override
  Widget build(BuildContext context) {
    const bleed = SettingsMetrics.pageHorizontal;
    return LayoutBuilder(
      builder: (context, constraints) => SizedBox(
        height: _height(context),
        child: OverflowBox(
          minWidth: constraints.maxWidth + bleed * 2,
          maxWidth: constraints.maxWidth + bleed * 2,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: bleed, vertical: SettingsMetrics.cardRowVertical),
            children: [
              for (final card in accounts) ...[
                _AccountCardTile(key: ValueKey(card.id), card: card),
                const SizedBox(width: SettingsMetrics.cardGap),
              ],
              const _AddAccountTile(),
            ],
          ),
        ),
      ),
    );
  }
}

enum _AccountAction { use, remove }

/// One account in the switcher (ProfileCardTile). The service is the colour
/// (the account in use fills with it) plus the mark on the avatar and its
/// name on the line under the account; the avatar's shape and letter tell
/// two accounts on one service apart. The others rest a little smaller.
class _AccountCardTile extends ConsumerStatefulWidget {
  const _AccountCardTile({super.key, required this.card});

  final AccountCard card;

  @override
  ConsumerState<_AccountCardTile> createState() => _AccountCardTileState();
}

class _AccountCardTileState extends ConsumerState<_AccountCardTile> with TickerProviderStateMixin {
  late final SpringValue _active = SpringValue(
    vsync: this,
    initial: widget.card.isActive ? 1 : 0,
    spring: SettingsSprings.effects,
  );
  late final SpringValue _scale = SpringValue(
    vsync: this,
    initial: widget.card.isActive ? 1 : SettingsMetrics.cardInactiveScale,
    spring: SettingsSprings.spatial,
  );

  @override
  void didUpdateWidget(_AccountCardTile old) {
    super.didUpdateWidget(old);
    final active = widget.card.isActive;
    final scale = active ? 1.0 : SettingsMetrics.cardInactiveScale;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _active.snapTo(active ? 1 : 0);
      _scale.snapTo(scale);
    } else {
      _active.animateTo(active ? 1 : 0);
      _scale.animateTo(scale);
    }
  }

  @override
  void dispose() {
    _active.dispose();
    _scale.dispose();
    super.dispose();
  }

  Future<void> _switch() async {
    final messenger = ScaffoldMessenger.maybeOf(context);
    final failed = SettingsStrings.of(context).switchFailed;
    final ok = await ref.read(settingsViewModelProvider.notifier).switchTo(widget.card.id);
    if (!ok) messenger?.showSnackBar(SnackBar(content: Text(failed)));
  }

  Future<void> _options(BuildContext anchorContext) async {
    final strings = SettingsStrings.of(context);
    final card = widget.card;
    final choice = await showYoinDropdownMenu<_AccountAction>(
      context: anchorContext,
      anchor: globalRectOf(anchorContext),
      semanticLabel: strings.cdAccountOptionsCard(card.title),
      items: [
        if (!card.isActive) YoinMenuItem(value: _AccountAction.use, label: strings.accountsUse),
        YoinMenuItem(value: _AccountAction.remove, label: strings.accountsRemoveMenu),
      ],
    );
    if (!mounted) return;
    switch (choice) {
      case _AccountAction.use:
        await _switch();
      case _AccountAction.remove:
        final confirmed = await _confirmRemove();
        if (confirmed == true) await ref.read(settingsViewModelProvider.notifier).remove(card.id);
      case null:
        break;
    }
  }

  Future<bool?> _confirmRemove() {
    final strings = SettingsStrings.of(context);
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(strings.removeTitle),
        content: Text(strings.removeBody(widget.card.displayName)),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(strings.removeCancel)),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: Text(strings.removeConfirm)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final card = widget.card;
    const identity = ServiceIdentity.subsonic; // v1 ships Subsonic only.
    final tone = identity.hue.tone(theme.brightness);
    final line = serviceLine(identity.name, card.title, card.host);
    return AnimatedBuilder(
      animation: Listenable.merge([_active.animation, _scale.animation]),
      builder: (context, _) {
        final a = _active.value.clamp(0.0, 1.0);
        final fill = Color.lerp(settingsRowColor(colors), tone.container, a)!;
        final ink = Color.lerp(colors.onSurface, tone.onContainer, a)!;
        // Secondary text at full strength on the tinted card; the usual variant on a plain one.
        final secondary = Color.lerp(colors.onSurfaceVariant, tone.onContainer, a)!;
        return Transform.scale(
          scale: _scale.value,
          child: SizedBox(
            width: SettingsMetrics.cardWidth,
            child: Semantics(
              selected: card.isActive,
              button: !card.isActive,
              onTapHint: card.isActive ? null : strings.cdSwitchAccount,
              child: Material(
                color: fill,
                shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: card.isActive ? null : _switch,
                  child: Padding(
                    padding: const EdgeInsetsDirectional.fromSTEB(
                      SettingsMetrics.cardPaddingStart,
                      SettingsMetrics.cardPaddingTop,
                      SettingsMetrics.cardPaddingEnd,
                      SettingsMetrics.cardPaddingBottom,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            AccountAvatar(
                              monogram: monogramOf(card.title, identity.name),
                              shapeIndex: card.avatarShape,
                              identity: identity,
                              ringColor: fill,
                            ),
                            const Spacer(),
                            Transform.translate(
                              offset: const Offset(0, -SettingsMetrics.cardMenuLift),
                              child: Builder(
                                builder: (anchorContext) => IconButton(
                                  tooltip: strings.cdAccountOptionsCard(card.title),
                                  icon: Icon(YoinSymbols.moreVertical, size: SettingsMetrics.cardMenuIcon, color: secondary),
                                  onPressed: () => _options(anchorContext),
                                ),
                              ),
                            ),
                          ],
                        ),
                        const Spacer(),
                        // Name and service read as one cluster.
                        Padding(
                          padding: const EdgeInsetsDirectional.only(end: SettingsMetrics.cardTextEnd),
                          child: Text(
                            card.title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleMedium!.copyWith(color: ink),
                          ),
                        ),
                        if (line != null)
                          Padding(
                            padding: const EdgeInsetsDirectional.only(end: SettingsMetrics.cardTextEnd),
                            child: AccountServiceLine(kind: line.kind, detail: line.detail, color: secondary),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The switcher's last tile: Add, in primary on a plain row surface.
class _AddAccountTile extends StatelessWidget {
  const _AddAccountTile();

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return SizedBox(
      width: SettingsMetrics.addTileWidth,
      child: Material(
        color: settingsRowColor(colors),
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => openAddAccount(context),
          child: Semantics(
            button: true,
            label: strings.accountsAdd,
            excludeSemantics: true,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(YoinSymbols.add, size: SettingsMetrics.addTileIcon, color: colors.primary),
                const SizedBox(height: SettingsMetrics.addTileGap),
                Text(strings.accountsAddShort, style: theme.textTheme.labelLarge!.copyWith(color: colors.primary)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
