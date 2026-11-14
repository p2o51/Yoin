import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'account_identity.dart';
import 'apple_music_setup_screen.dart';
import 'settings_colors.dart';
import 'settings_components.dart';
import 'licenses.dart';
import 'material_shapes.dart';
import 'settings_metrics.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'settings_view_model.dart';
import 'subsonic_setup_screen.dart';
import 'tips.dart';

/// Opens Settings (PushPage). The Home avatar, Home's and Library's error
/// "Settings" and Library's settings button all come here.
Future<void> openSettings(BuildContext context) =>
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const SettingsScreen()));

enum _Service { subsonic, appleMusic }

/// Add an account: Android opens a service picker first. v1 shipped one
/// service; with Apple Music there are two, so Add shows a short menu anchored
/// to the tapped control, then opens that service's setup page. [anchorContext]
/// is the control's own context (for the menu anchor).
Future<void> openAddAccount(BuildContext anchorContext) async {
  final strings = SettingsStrings.of(anchorContext);
  final navigator = Navigator.of(anchorContext);
  final choice = await showYoinDropdownMenu<_Service>(
    context: anchorContext,
    anchor: globalRectOf(anchorContext),
    semanticLabel: strings.addServiceTitle,
    items: [
      YoinMenuItem(value: _Service.subsonic, label: strings.serviceNameSubsonic, icon: ServiceIdentity.subsonic.glyph),
      YoinMenuItem(value: _Service.appleMusic, label: strings.serviceNameApple, icon: ServiceIdentity.appleMusic.glyph),
    ],
  );
  switch (choice) {
    case _Service.subsonic:
      await navigator.push(YoinPushRoute<void>(builder: (_) => const SubsonicSetupScreen()));
    case _Service.appleMusic:
      await navigator.push(YoinPushRoute<void>(builder: (_) => const AppleMusicSetupScreen()));
    case null:
      break;
  }
}

/// What the wide window's right pane shows (SettingsTablet): an account's
/// page, or one of the Help and about pages.
sealed class SettingsPane {
  const SettingsPane();
}

final class AccountPane extends SettingsPane {
  const AccountPane(this.profileId);
  final String profileId;

  @override
  bool operator ==(Object other) => other is AccountPane && other.profileId == profileId;
  @override
  int get hashCode => profileId.hashCode;
}

final class TipsPane extends SettingsPane {
  const TipsPane();
  @override
  bool operator ==(Object other) => other is TipsPane;
  @override
  int get hashCode => 1;
}

final class LicensesPane extends SettingsPane {
  const LicensesPane();
  @override
  bool operator ==(Object other) => other is LicensesPane;
  @override
  int get hashCode => 2;
}

/// Opens [pane]'s page: in the right pane on a wide window, else pushed.
typedef OpenSettingsPane = void Function(SettingsPane pane);

/// Settings (Android SettingsScreen): the account switcher, then Help and
/// about. Tap an account to use it (the one in use, or one that needs
/// attention, opens its page); its menu manages, uses or removes it; Add
/// opens the service's setup page. Its own page with no bar.
///
/// On a wide window (≥ 840) it is a list-detail like Android's SettingsTablet:
/// the list on the left (a third), the page it opens on the right, and an
/// empty seal until something is picked.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  final _scroll = ScrollController();
  SettingsPane? _pane;

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  bool get _wide => YoinWindowInfo.of(context).layoutMode == LayoutMode.wide;

  void _open(SettingsPane pane) {
    if (_wide) {
      setState(() => _pane = pane);
      return;
    }
    final navigator = Navigator.of(context);
    switch (pane) {
      case AccountPane(:final profileId):
        openSubsonicManage(context, profileId);
      case TipsPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const TipsScreen()));
      case LicensesPane():
        navigator.push(YoinPushRoute<void>(builder: (_) => const LicensesScreen()));
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(settingsViewModelProvider);
    final wide = _wide;
    // An account that went away takes its page with it.
    var pane = wide ? _pane : null;
    if (pane is AccountPane && state is SettingsContent && !state.accounts.any((a) => a.id == (pane as AccountPane).profileId)) {
      pane = null;
    }
    final list = ListView(
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
            SettingsContent() => Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _AccountsSection(state: state, onOpen: _open),
                  const SizedBox(height: SettingsMetrics.sectionGap),
                  _HelpAboutGroup(open: pane, listDetail: wide, onOpen: _open),
                ],
              ),
          },
        ),
      ],
    );
    return ChromeBarSuppression(
      child: Scaffold(
        backgroundColor: wide ? settingsListPaneColor(colors) : settingsPageColor(colors),
        appBar: SettingsBar(
          title: strings.title,
          controller: _scroll,
          titleTop: SettingsBar.headlineOffset,
          titleHeight: SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!),
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: wide ? _ListDetail(list: list, pane: pane, onDone: () => setState(() => _pane = null)) : list,
        ),
      ),
    );
  }
}

/// The two panes: the list a third of the window (never under 360), the page
/// on the right on its own panel in the page colour, cross-fading as the
/// pick changes.
class _ListDetail extends StatelessWidget {
  const _ListDetail({required this.list, required this.pane, required this.onDone});

  final Widget list;
  final SettingsPane? pane;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final window = YoinWindowInfo.of(context);
    final listWidth = math.max(SettingsMetrics.splitListMinWidth, window.width * SettingsMetrics.splitListFraction);
    final pane = this.pane;
    final page = switch (pane) {
      null => const _PanePlaceholder(),
      AccountPane(:final profileId) =>
        SubsonicSetupScreen(key: ValueKey(profileId), profileId: profileId, inPane: true, onDone: onDone),
      TipsPane() => const TipsScreen(inPane: true),
      LicensesPane() => const LicensesScreen(inPane: true),
    };
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(width: listWidth, child: list),
        Expanded(
          child: Padding(
            padding: EdgeInsets.only(
              right: SettingsMetrics.pageHorizontal,
              bottom: SettingsMetrics.pageHorizontal + MediaQuery.paddingOf(context).bottom,
            ),
            child: Material(
              color: settingsPageColor(colors),
              shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
              clipBehavior: Clip.antiAlias,
              child: YoinWindowScope(
                info: window.forPaneWidth(window.width - listWidth),
                child: SpringCrossFade(childKey: pane ?? 'placeholder', child: page),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// The right pane before anything is picked (SettingsPlaceholderPane): an
/// outlined twelve-sided cookie and one quiet line.
class _PanePlaceholder extends StatelessWidget {
  const _PanePlaceholder();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DecoratedBox(
            decoration: ShapeDecoration(
              shape: PolygonBorder(
                cookie12Sided,
                side: BorderSide(color: colors.outlineVariant, width: SettingsMetrics.placeholderStroke),
              ),
            ),
            child: const SizedBox.square(dimension: SettingsMetrics.placeholderMark),
          ),
          const SizedBox(height: SettingsMetrics.placeholderGap),
          Text(
            SettingsStrings.of(context).placeholder,
            style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

/// Help and about: Tips (how many are read), Yoin and its version, and the
/// open source licenses. On a wide window the rows that open a page show a
/// chevron and the open one is marked.
class _HelpAboutGroup extends ConsumerWidget {
  const _HelpAboutGroup({required this.open, required this.listDetail, required this.onOpen});

  final SettingsPane? open;
  final bool listDetail;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final read = ref.watch(tipReadProvider);
    final chevron = listDetail
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsGroup(
      title: strings.sectionHelpAbout,
      selected: {
        if (open is TipsPane) 0,
        if (open is LicensesPane) 2,
      },
      children: [
        SettingsItem(
          icon: YoinSymbols.sparkle,
          title: strings.aboutTips,
          summary: tipsReadSummary(strings, read),
          trailing: chevron,
          onTap: () => onOpen(const TipsPane()),
        ),
        // No website or privacy links on Harmony yet (nothing opens a browser): just the version.
        SettingsItem(icon: YoinSymbols.info, title: strings.aboutName, summary: strings.aboutVersion(yoinVersionName)),
        SettingsItem(
          icon: YoinSymbols.code,
          title: strings.aboutLicenses,
          trailing: chevron,
          onTap: () => onOpen(const LicensesPane()),
        ),
      ],
    );
  }
}

class _AccountsSection extends StatelessWidget {
  const _AccountsSection({required this.state, required this.onOpen});

  final SettingsContent state;
  final OpenSettingsPane onOpen;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroupLabel(title: strings.sectionAccounts),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        if (state.accounts.isEmpty)
          const _EmptyAccountsCard()
        else
          _AccountCardRow(accounts: state.accounts, onOpen: onOpen),
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
            Builder(
              builder: (anchorContext) => FilledButton.icon(
                onPressed: () => openAddAccount(anchorContext),
                icon: const Icon(YoinSymbols.add, size: SettingsMetrics.emptyButtonIcon),
                label: Text(strings.accountsAdd),
                style: FilledButton.styleFrom(iconSize: SettingsMetrics.emptyButtonIcon),
              ),
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
  const _AccountCardRow({required this.accounts, required this.onOpen});

  final List<AccountCard> accounts;
  final OpenSettingsPane onOpen;

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
                _AccountCardTile(key: ValueKey(card.id), card: card, onOpen: onOpen),
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

enum _AccountAction { manage, use, remove }

/// One account in the switcher (ProfileCardTile). The service is the colour
/// (the account in use fills with it) plus the mark on the avatar and its
/// name on the line under the account; the avatar's shape and letter tell
/// two accounts on one service apart. The others rest a little smaller.
class _AccountCardTile extends ConsumerStatefulWidget {
  const _AccountCardTile({super.key, required this.card, required this.onOpen});

  final AccountCard card;
  final OpenSettingsPane onOpen;

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
        if (card.canManage) YoinMenuItem(value: _AccountAction.manage, label: strings.accountsManage),
        if (!card.isActive) YoinMenuItem(value: _AccountAction.use, label: strings.accountsUse),
        YoinMenuItem(value: _AccountAction.remove, label: strings.accountsRemoveMenu),
      ],
    );
    if (!mounted) return;
    switch (choice) {
      case _AccountAction.manage:
        widget.onOpen(AccountPane(card.id));
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
    final identity = card.identity;
    final tone = identity.hue.tone(theme.brightness);
    final line = serviceLine(identity.name, card.title, card.host);
    final opensPage = card.tapOpensPage;
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
              button: opensPage || !card.isActive,
              onTapHint: opensPage ? strings.cdManageAccount : (card.isActive ? null : strings.cdSwitchAccount),
              child: Material(
                color: fill,
                shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  // The one in use, or one that needs attention, opens its page; any other switches.
                  onTap: opensPage ? () => widget.onOpen(AccountPane(card.id)) : (card.isActive ? null : _switch),
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
                        SpringReveal(
                          visible: card.credentialsMissing,
                          child: card.credentialsMissing
                              ? Padding(
                                  padding: const EdgeInsets.only(top: SettingsMetrics.pillTop),
                                  child: _StatusPill(label: strings.accountCredentialsMissing),
                                )
                              : null,
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
        child: Builder(
          builder: (anchorContext) => InkWell(
          onTap: () => openAddAccount(anchorContext),
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
      ),
    );
  }
}

/// An account's one problem, in error colours (AccountStatusPill).
class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(color: colors.errorContainer, shape: const StadiumBorder()),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SettingsMetrics.pillHorizontal,
          vertical: SettingsMetrics.pillVertical,
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.labelMedium!.copyWith(color: colors.onErrorContainer),
        ),
      ),
    );
  }
}
