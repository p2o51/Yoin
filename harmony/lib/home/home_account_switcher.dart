/// Home's account switcher (Android HomeAccountSwitcher.kt +
/// HomeAccountQuickSwitch.kt): the avatar of the account in use sits in
/// Home's header. A tap grows the account card out of it — the account in
/// use (avatar, name, service), Manage accounts, the other accounts to switch
/// to, Add account, then Yoin's own entries (Settings, Edit Home). Press and
/// hold instead, and the other accounts drop out of the avatar in a column,
/// each with its name; slide onto one (it swells and ticks) and let go to
/// switch; let go anywhere else and nothing happens.
///
/// The card and the column are overlays above the shell. One spatial spring
/// p (0 = the avatar, 1 = the card) drives the card's bounds and roundness,
/// the scrim and, late, the contents; closing runs the same p back into the
/// avatar. System back closes the card through that p ([ChromeBackEntry]),
/// so Home's own back-to-home is untouched while it is shut.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/profile/profile.dart';
import '../nowplaying/haptics.dart';
import '../settings/account_identity.dart';
import '../settings/account_switch.dart';
import '../settings/settings_screen.dart';
import '../settings/settings_view_model.dart';
import '../shell/bar_suppression.dart';
import '../shell/chrome_back.dart';
import '../theme/motion.dart';
import '../ui/component/desktop_input.dart';
import 'home_metrics.dart';
import 'home_strings.dart';

/// The configured accounts as Settings shows them (same cards, same avatar
/// shapes), oldest first; re-read when the active account or an account changes.
final homeAccountsProvider = FutureProvider<List<AccountCard>>((ref) async {
  ref.watch(activeSourceProvider);
  ref.watch(accountsRevisionProvider);
  final repo = ref.watch(profileRepositoryProvider);
  final profiles = await repo.profiles();
  final active = await repo.activeProfile();
  final shapes = assignAvatarShapes([for (final p in profiles) p.id]);
  return [
    for (final p in profiles)
      accountCardFor(p, await _credentials(repo.credentials(p.id)), isActive: p.id == active?.id, avatarShape: shapes[p.id] ?? 0),
  ];
});

Future<ProfileCredentials?> _credentials(Future<ProfileCredentials?> read) => read.then<ProfileCredentials?>((c) => c, onError: (Object _) => null);

/// The account a switch begun from Home's switcher is going to, from the tap
/// until the switch is over (AccountSwitcherViewModel.pendingTarget): the
/// header shows it from the tap, so it never blinks back to the old account.
class HomeSwitchTarget extends Notifier<String?> {
  @override
  String? build() => null;

  void set(String? profileId) => state = profileId;
}

final homeSwitchTargetProvider = NotifierProvider<HomeSwitchTarget, String?>(HomeSwitchTarget.new);

/// Makes [profileId] the active account (Android AccountSwitcherViewModel.switchTo):
/// through [AccountSwitcher], so the outgoing playback is let go and the
/// account's server asked first; Home, Library, playback and the caches then
/// follow the active source. A switch that doesn't land leaves the old
/// account in use and says so in the shell's snackbar. False when nothing
/// starts (a switch begun here is still under way, or it is the account in
/// use) or it didn't land.
Future<bool> switchHomeAccount(WidgetRef ref, String profileId) =>
    switchHomeAccountIn(ProviderScope.containerOf(ref.context, listen: false), profileId);

Future<bool> switchHomeAccountIn(ProviderContainer container, String profileId) async {
  final pending = container.read(homeSwitchTargetProvider.notifier);
  if (container.read(homeSwitchTargetProvider) != null) return false;
  // Set before anything awaits: the tap's own frame shows the target.
  pending.set(profileId);
  try {
    if (!await switchAccountFromShell(container, profileId)) return false;
    // Landed: hold until the cards have it in use (they re-read with the source).
    try {
      await container.read(homeAccountsProvider.future).timeout(const Duration(seconds: 2));
    } catch (_) {
      // The header follows the cards whenever they come.
    }
    return true;
  } finally {
    try {
      if (container.read(homeSwitchTargetProvider) == profileId) pending.set(null);
    } on StateError {
      // The app (a test's container) went away meanwhile.
    }
  }
}

/// The other accounts' cards (the active one excluded), in Settings' order.
List<AccountCard> otherAccounts(List<AccountCard> all) => [for (final a in all) if (!a.isActive) a];

AccountAvatar _avatarOf(AccountCard card, {required double size, required Color ring}) => AccountAvatar(
      monogram: monogramOf(card.title, card.identity.name),
      shapeIndex: card.avatarShape,
      identity: card.identity,
      ringColor: ring,
      size: size,
    );

/// The account in use as Home's header button: tap = the card, hold = the column.
class HomeAccountButton extends ConsumerStatefulWidget {
  const HomeAccountButton({super.key, required this.fallbackTitle, this.onEditHome, this.fallback});

  /// The active profile's name: the button's label.
  final String fallbackTitle;

  /// The card's "Edit Home" entry; hidden when null.
  final VoidCallback? onEditHome;

  /// Drawn while the account cards load.
  final Widget? fallback;

  @override
  ConsumerState<HomeAccountButton> createState() => _HomeAccountButtonState();
}

class _HomeAccountButtonState extends ConsumerState<HomeAccountButton> {
  final GlobalKey _anchor = GlobalKey();
  OverlayEntry? _card;
  final GlobalKey<_AccountCardOverlayState> _cardKey = GlobalKey();
  OverlayEntry? _fan;
  final ValueNotifier<int> _hover = ValueNotifier(-1);
  final ValueNotifier<bool> _holding = ValueNotifier(false);
  List<AccountCard> _fanAccounts = const [];
  Rect? _fanAnchor;

  Rect? _anchorRect() {
    final box = _anchor.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return null;
    return box.localToGlobal(Offset.zero) & box.size;
  }

  @override
  void dispose() {
    _card?.remove();
    _fan?.remove();
    _hover.dispose();
    _holding.dispose();
    super.dispose();
  }

  void _openCard(List<AccountCard> accounts) {
    final rect = _anchorRect();
    if (rect == null || _card != null) return;
    YoinHaptics.click();
    final navigator = Navigator.of(context);
    final overlay = Overlay.of(context, rootOverlay: true);
    _card = OverlayEntry(
      builder: (_) => _AccountCardOverlay(
        key: _cardKey,
        anchor: rect,
        accounts: accounts,
        navigator: navigator,
        barSuppression: ref.read(barSuppressionProvider.notifier),
        // Not while a switch begun here runs: the layout being edited is the outgoing account's.
        onEditHome: widget.onEditHome == null
            ? null
            : () {
                if (ref.read(homeSwitchTargetProvider) == null) widget.onEditHome!();
              },
        onPick: (card) => switchHomeAccount(ref, card.id),
        onClosed: () {
          _card?.remove();
          _card = null;
        },
      ),
    );
    overlay.insert(_card!);
  }

  // ---- hold-to-fan ----

  void _startFan(List<AccountCard> others) {
    final rect = _anchorRect();
    if (rect == null || others.isEmpty) return;
    YoinHaptics.longPress();
    _fanAccounts = others;
    _fanAnchor = rect;
    _hover.value = -1;
    _holding.value = true;
    _fan ??= OverlayEntry(
      builder: (_) => _AccountFan(anchor: _fanAnchor!, accounts: _fanAccounts, hover: _hover, holding: _holding, onGone: _dropFan),
    );
    Overlay.of(context, rootOverlay: true).insert(_fan!);
  }

  void _dropFan() {
    _fan?.remove();
    _fan = null;
  }

  void _moveFan(Offset global) {
    final anchor = _fanAnchor;
    if (anchor == null) return;
    var hit = -1;
    for (var i = 0; i < _fanAccounts.length; i++) {
      final cy = fanItemCenterY(anchor, i);
      if ((global.dy - cy).abs() < HomeAccountMetrics.fanStep / 2 && (global.dx - anchor.center.dx).abs() < HomeAccountMetrics.fanReach) hit = i;
    }
    if (hit != _hover.value) {
      _hover.value = hit;
      if (hit >= 0) YoinHaptics.segmentTick();
    }
  }

  void _endFan() {
    final chosen = _hover.value >= 0 && _hover.value < _fanAccounts.length ? _fanAccounts[_hover.value] : null;
    _holding.value = false;
    _hover.value = -1;
    if (chosen != null) {
      YoinHaptics.confirm();
      switchHomeAccount(ref, chosen.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final accounts = ref.watch(homeAccountsProvider).value ?? const <AccountCard>[];
    // A switch begun here shows its target from the tap on.
    final target = ref.watch(homeSwitchTargetProvider);
    final active = accounts.where((a) => a.id == target).firstOrNull ?? accounts.where((a) => a.isActive).firstOrNull;
    // The hold offers nothing while a switch begun here runs (Android quickSwitch).
    final others = target != null
        ? const <AccountCard>[]
        : [
            for (final a in accounts)
              if (a.id != active?.id) a,
          ];
    // Android labels the button with the active card's title (the username,
    // else the profile's name); the header's title is only the fallback.
    final title = active?.title ?? widget.fallbackTitle;
    final ring = colors.surfaceContainer;
    return Semantics(
      button: true,
      label: strings.accountCd(title),
      customSemanticsActions: {
        for (final o in others) CustomSemanticsAction(label: strings.accountQuickSwitch(o.title)): () => switchHomeAccount(ref, o.id),
      },
      excludeSemantics: true,
      // Mouse / keyboard: hover layer, click cursor, focus ring, Enter.
      child: YoinHover(
        onTap: () => _openCard(accounts),
        focusStadium: true,
        child: GestureDetector(
          key: _anchor,
          behavior: HitTestBehavior.opaque,
          onTap: () => _openCard(accounts),
          onLongPressStart: others.isEmpty ? null : (_) => _startFan(others),
          onLongPressMoveUpdate: others.isEmpty ? null : (d) => _moveFan(d.globalPosition),
          onLongPressEnd: others.isEmpty ? null : (_) => _endFan(),
          onLongPressCancel: others.isEmpty ? null : () => _holding.value = false,
          child: SizedBox.square(
            dimension: HomeHeaderMetrics.touchBox,
            child: Center(
              child: active == null
                  ? (widget.fallback ?? const SizedBox.shrink())
                  : _avatarOf(active, size: HomeHeaderMetrics.avatar, ring: ring),
            ),
          ),
        ),
      ),
    );
  }
}

/// The column's item [index] centre, in global px, under [anchor] (the header button).
double fanItemCenterY(Rect anchor, int index) =>
    anchor.bottom + HomeAccountMetrics.fanFirstGap + HomeAccountMetrics.fanItem / 2 + index * HomeAccountMetrics.fanStep;

/// The hold's column: items drop out of the avatar's centre one after
/// another (stagger) on the spatial spring, names on their start side.
class _AccountFan extends StatefulWidget {
  const _AccountFan({required this.anchor, required this.accounts, required this.hover, required this.holding, required this.onGone});

  final Rect anchor;
  final List<AccountCard> accounts;
  final ValueNotifier<int> hover;
  final ValueNotifier<bool> holding;
  final VoidCallback onGone;

  @override
  State<_AccountFan> createState() => _AccountFanState();
}

class _AccountFanState extends State<_AccountFan> with TickerProviderStateMixin {
  late final SpringValue _shown = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void initState() {
    super.initState();
    widget.holding.addListener(_sync);
    _shown.animation.addListener(_maybeGone);
    _sync();
  }

  void _sync() => _shown.animateTo(widget.holding.value ? 1 : 0);

  void _maybeGone() {
    if (!widget.holding.value && _shown.value <= 0.001) widget.onGone();
  }

  @override
  void dispose() {
    widget.holding.removeListener(_sync);
    _shown.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final count = widget.accounts.length;
    final anchor = widget.anchor;
    return IgnorePointer(
      child: AnimatedBuilder(
        animation: Listenable.merge([_shown.animation, widget.hover]),
        builder: (context, _) {
          final shown = _shown.value;
          return Stack(
            children: [
              for (var i = 0; i < count; i++)
                () {
                  final local = count <= 1
                      ? shown.clamp(0.0, 1.0)
                      : ((shown - i * HomeAccountMetrics.fanStagger) / (1 - (count - 1) * HomeAccountMetrics.fanStagger)).clamp(0.0, 1.0);
                  final rest = fanItemCenterY(anchor, i);
                  final y = anchor.center.dy + (rest - anchor.center.dy) * local - HomeAccountMetrics.fanItem / 2;
                  return PositionedDirectional(
                    top: y,
                    end: MediaQuery.sizeOf(context).width - anchor.right + (anchor.width - HomeAccountMetrics.fanItem) / 2,
                    child: _FanRow(card: widget.accounts[i], shown: local, hovered: widget.hover.value == i),
                  );
                }(),
            ],
          );
        },
      ),
    );
  }
}

class _FanRow extends StatefulWidget {
  const _FanRow({required this.card, required this.shown, required this.hovered});

  final AccountCard card;
  final double shown;
  final bool hovered;

  @override
  State<_FanRow> createState() => _FanRowState();
}

class _FanRowState extends State<_FanRow> with TickerProviderStateMixin {
  late final SpringValue _lift = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);
  late final SpringValue _fill = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void didUpdateWidget(_FanRow old) {
    super.didUpdateWidget(old);
    _lift.animateTo(widget.hovered ? 1 : 0);
    _fill.animateTo(widget.hovered ? 1 : 0);
  }

  @override
  void dispose() {
    _lift.dispose();
    _fill.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return AnimatedBuilder(
      animation: Listenable.merge([_lift.animation, _fill.animation]),
      builder: (context, _) {
        final lift = _lift.value;
        final shown = widget.shown;
        final out = HomeAccountMetrics.labelOut + lift * HomeAccountMetrics.labelOutLift + (1 - shown) * HomeAccountMetrics.labelOutEnter;
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Opacity(
              opacity: (shown * (0.72 + 0.28 * lift)).clamp(0.0, 1.0),
              child: Transform.translate(
                offset: Offset(rtl ? out : -out, 0),
                child: Material(
                  color: Color.lerp(colors.surfaceContainerHigh, colors.secondaryContainer, _fill.value.clamp(0.0, 1.0)),
                  shape: const StadiumBorder(),
                  elevation: 1 + 3 * lift.clamp(0.0, 1.0),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: HomeAccountMetrics.fanLabelMax),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: HomeAccountMetrics.labelPaddingH, vertical: HomeAccountMetrics.labelPaddingV),
                      child: Text(
                        widget.card.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onSurface),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            Opacity(
              opacity: shown.clamp(0.0, 1.0),
              child: Transform.scale(
                scale: (0.4 + 0.6 * shown) * (1 + 0.22 * lift),
                child: Material(
                  color: colors.surfaceContainerHigh,
                  shape: const CircleBorder(),
                  elevation: 3 + 5 * lift.clamp(0.0, 1.0),
                  child: SizedBox.square(
                    dimension: HomeAccountMetrics.fanItem,
                    child: Center(child: _avatarOf(widget.card, size: HomeAccountMetrics.fanAvatar, ring: colors.surfaceContainerHigh)),
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// The account card over Home, grown from the header avatar on p.
class _AccountCardOverlay extends StatefulWidget {
  const _AccountCardOverlay({
    super.key,
    required this.anchor,
    required this.accounts,
    required this.navigator,
    required this.onPick,
    required this.onClosed,
    this.barSuppression,
    this.onEditHome,
  });

  final Rect anchor;
  final List<AccountCard> accounts;
  final NavigatorState navigator;

  /// The window's one bar goes behind the card's scrim like any modal's
  /// (the sheets' and dialogs' suppression): away while the card is up, back
  /// as it starts to fold into the avatar.
  final BarSuppression? barSuppression;
  final void Function(AccountCard) onPick;
  final VoidCallback onClosed;
  final VoidCallback? onEditHome;

  @override
  State<_AccountCardOverlay> createState() => _AccountCardOverlayState();
}

class _AccountCardOverlayState extends State<_AccountCardOverlay> with TickerProviderStateMixin {
  late final SpringValue _p = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  late final ChromeBackEntry _back = ChromeBackEntry(() => scheduleMicrotask(() {
    if (mounted) close();
  }));
  VoidCallback? _after;
  bool _closing = false;
  final Object _barToken = Object();

  @override
  void initState() {
    super.initState();
    _back.attach(widget.navigator);
    // Not while the tree builds (a provider write then is refused).
    scheduleMicrotask(() {
      if (mounted && !_closing) widget.barSuppression?.add(_barToken);
    });
    _p.animation.addListener(_onTick);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.disableAnimationsOf(context)) {
        _p.snapTo(1);
      } else {
        _p.animateTo(1);
      }
    });
  }

  void _onTick() {
    if (_closing && _p.value <= 0.002) {
      _closing = false;
      _back.detach();
      widget.onClosed();
      final after = _after;
      _after = null;
      after?.call();
    }
  }

  /// Back into the avatar; [then] runs once it is there.
  void close([VoidCallback? then]) {
    if (_closing) return;
    _closing = true;
    widget.barSuppression?.remove(_barToken);
    _after = then;
    if (MediaQuery.disableAnimationsOf(context)) {
      _p.snapTo(0);
      _onTick();
    } else {
      _p.animateTo(0);
    }
  }

  @override
  void dispose() {
    final suppression = widget.barSuppression;
    scheduleMicrotask(() => suppression?.remove(_barToken));
    _back.dispose();
    _p.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final media = MediaQuery.of(context);
    final screen = media.size;
    final width = math.min(screen.width - 2 * HomeAccountMetrics.cardMargin, HomeAccountMetrics.cardMaxWidth);
    final cardTop = media.viewPadding.top + HomeAccountMetrics.cardTop;
    final target = Rect.fromLTWH((screen.width - width) / 2, cardTop, width, 0);
    final strings = HomeStrings.of(context);
    final active = widget.accounts.where((a) => a.isActive).firstOrNull;
    final others = otherAccounts(widget.accounts);
    final content = _CardContent(
      active: active,
      others: others,
      strings: strings,
      onPick: (card) {
        YoinHaptics.click();
        close(() => widget.onPick(card));
      },
      onSettings: () => close(() => openSettings(widget.navigator.context)),
      onEditHome: widget.onEditHome == null ? null : () => close(widget.onEditHome),
    );
    return AnimatedBuilder(
      animation: _p.animation,
      child: content,
      builder: (context, child) {
        final p = _p.value;
        final t = p.clamp(0.0, 1.0);
        final anchor = widget.anchor;
        // Late: the contents come in over the last part of the growth.
        final contentAlpha = ((p - HomeAccountMetrics.contentFrom) / (1 - HomeAccountMetrics.contentFrom)).clamp(0.0, 1.0);
        return Stack(
          children: [
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: close,
                child: ColoredBox(color: colors.scrim.withValues(alpha: HomeAccountMetrics.scrimAlpha * t)),
              ),
            ),
            Positioned(
              left: anchor.left + (target.left - anchor.left) * p,
              top: anchor.top + (target.top - anchor.top) * p,
              width: math.max(0, anchor.width + (target.width - anchor.width) * p),
              child: _GrowingCard(
                p: p,
                anchorHeight: anchor.height,
                radius: anchor.width / 2 + (HomeAccountMetrics.cardCorner - anchor.width / 2) * t,
                color: colors.surfaceContainerHigh,
                child: Opacity(opacity: contentAlpha, child: IgnorePointer(ignoring: _closing || p < 0.9, child: child)),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// The card's surface: its height grows from the avatar's to the content's own on p.
class _GrowingCard extends StatelessWidget {
  const _GrowingCard({required this.p, required this.anchorHeight, required this.radius, required this.color, required this.child});

  final double p;
  final double anchorHeight;
  final double radius;
  final Color color;
  final Widget child;

  @override
  Widget build(BuildContext context) => Material(
        color: color,
        elevation: HomeAccountMetrics.cardElevation * p.clamp(0.0, 1.0),
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(math.max(0, radius))),
        clipBehavior: Clip.antiAlias,
        child: _HeightLerp(p: p, from: anchorHeight, child: child),
      );
}

class _HeightLerp extends SingleChildRenderObjectWidget {
  const _HeightLerp({required this.p, required this.from, super.child});

  final double p;
  final double from;

  @override
  _RenderHeightLerp createRenderObject(BuildContext context) => _RenderHeightLerp(p, from);

  @override
  void updateRenderObject(BuildContext context, _RenderHeightLerp renderObject) => renderObject
    ..p = p
    ..from = from;
}

class _RenderHeightLerp extends RenderProxyBox {
  _RenderHeightLerp(this._p, this._from);

  double _p;
  set p(double v) {
    if (v == _p) return;
    _p = v;
    markNeedsLayout();
  }

  double _from;
  set from(double v) {
    if (v == _from) return;
    _from = v;
    markNeedsLayout();
  }

  @override
  void performLayout() {
    final c = child!;
    c.layout(BoxConstraints(minWidth: constraints.maxWidth, maxWidth: constraints.maxWidth), parentUsesSize: true);
    final h = _from + (c.size.height - _from) * _p;
    size = constraints.constrain(Size(constraints.maxWidth, math.max(0, h)));
  }
}

class _CardContent extends StatelessWidget {
  const _CardContent({
    required this.active,
    required this.others,
    required this.strings,
    required this.onPick,
    required this.onSettings,
    this.onEditHome,
  });

  final AccountCard? active;
  final List<AccountCard> others;
  final HomeStrings strings;
  final void Function(AccountCard) onPick;
  final VoidCallback onSettings;
  final VoidCallback? onEditHome;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final ring = colors.surfaceContainerHigh;
    final a = active;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: HomeAccountMetrics.cardPadding),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (a != null) ...[
            Center(child: _avatarOf(a, size: HomeAccountMetrics.bigAvatar, ring: ring)),
            const SizedBox(height: HomeAccountMetrics.cardGap),
            Text(a.title, textAlign: TextAlign.center, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleLarge!.copyWith(color: colors.onSurface)),
            _ServiceLine(card: a, center: true),
            const SizedBox(height: HomeAccountMetrics.cardGap),
            Center(
              child: OutlinedButton(onPressed: onSettings, child: Text(strings.accountManage)),
            ),
            const SizedBox(height: HomeAccountMetrics.cardGapSmall),
          ],
          for (final o in others) _Row(leading: _avatarOf(o, size: HomeAccountMetrics.rowAvatar, ring: ring), title: o.title, subtitle: _ServiceLine(card: o), onTap: () => onPick(o)),
          _Row(leading: const Icon(YoinSymbols.personAdd), title: strings.accountAdd, onTap: onSettings),
          Divider(height: HomeAccountMetrics.dividerHeight, color: colors.outlineVariant),
          _Row(leading: const Icon(YoinSymbols.settings), title: strings.accountSettings, onTap: onSettings),
          if (onEditHome != null) _Row(leading: const Icon(YoinSymbols.edit), title: strings.editTitle, onTap: onEditHome!),
        ],
      ),
    );
  }
}

class _ServiceLine extends StatelessWidget {
  const _ServiceLine({required this.card, this.center = false});

  final AccountCard card;
  final bool center;

  @override
  Widget build(BuildContext context) {
    final line = serviceLine(card.identity.name, card.title, card.host);
    if (line == null) return const SizedBox.shrink();
    final w = AccountServiceLine(
      kind: line.kind,
      detail: line.detail,
      color: Theme.of(context).colorScheme.onSurfaceVariant,
      style: Theme.of(context).textTheme.bodySmall,
    );
    return center ? Center(child: w) : w;
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.leading, required this.title, required this.onTap, this.subtitle});

  final Widget leading;
  final String title;
  final Widget? subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: HomeAccountMetrics.cardPadding, vertical: HomeAccountMetrics.rowPaddingV),
        child: Row(
          children: [
            SizedBox.square(
              dimension: HomeAccountMetrics.rowAvatar,
              child: Center(child: IconTheme.merge(data: IconThemeData(color: theme.colorScheme.onSurfaceVariant), child: leading)),
            ),
            const SizedBox(width: HomeAccountMetrics.rowGap),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurface)),
                  ?subtitle,
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
