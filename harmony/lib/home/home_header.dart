import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../settings/settings_screen.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'home_account_switcher.dart';
import 'home_edit.dart';
import 'home_greeting_intro.dart';
import 'home_memory_bubble.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_view_model.dart';
import 'widgets/seam.dart';

/// The greeting's day part, recomputed at the top of each hour (+500 ms).
class HomeGreeting extends Notifier<HomeDayPart> {
  Timer? _timer;

  @override
  HomeDayPart build() {
    ref.onDispose(() => _timer?.cancel());
    final now = ref.watch(homeClockProvider)();
    _schedule(now);
    return homeDayPartAt(now.hour);
  }

  void _schedule(DateTime now) {
    _timer?.cancel();
    _timer = Timer(untilNextHour(now), () {
      final next = ref.read(homeClockProvider)();
      state = homeDayPartAt(next.hour);
      _schedule(next);
    });
  }
}

final homeGreetingProvider = NotifierProvider<HomeGreeting, HomeDayPart>(HomeGreeting.new);

/// Opens Settings (PushPage): the account list, where accounts are
/// switched, removed and added.
void openHomeSettings(BuildContext context) => openSettings(context);

/// Header: greeting over the big "Home" title on the left, the account
/// control on the right. With an account, Memories' ⌄ rests at the page's top
/// centre and its bubble speaks over the title ([HomeMemoryBubble], Android's
/// Recommended entry; the header pill is retired). Rounded cut (ROND 100),
/// Medium weight.
class HomeHeader extends ConsumerWidget {
  const HomeHeader({super.key, required this.accountTitle, required this.compactHeight, this.onEditHome, this.editProgress});

  /// The account card's "Edit Home" entry.
  final VoidCallback? onEditHome;

  /// Edit Home's progress (0 = normal, 1 = editing): the title crossfades to
  /// "Edit Home" and the account control gives way to Undo and Done.
  final Animation<double>? editProgress;

  /// null = no account: a Settings gear instead of the avatar.
  final String? accountTitle;
  final bool compactHeight;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final part = ref.watch(homeGreetingProvider);
    final greetingStyle = gsf(
      TypeTokens.titleMedium.size,
      weight: 500,
      height: TypeTokens.titleMedium.lineHeight / TypeTokens.titleMedium.size,
      round: 100,
    ).copyWith(color: colors.onSurfaceVariant);
    final titleToken = compactHeight ? TypeTokens.headlineSmall : TypeTokens.headlineMedium;
    final titleStyle = gsf(
      titleToken.size,
      weight: 500,
      height: titleToken.lineHeight / titleToken.size,
      round: 100,
    ).copyWith(color: colors.onSurface);
    final title = accountTitle;
    final rowTop = MediaQuery.viewPaddingOf(context).top + HomeFeedMetrics.headerTopPadding;
    final header = Padding(
      padding: EdgeInsets.only(top: rowTop),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: HomeFeedMetrics.headerMinHeight),
        child: Row(
          // The title gives way (clips) before the account control would overflow.
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              child: MemoryBubbleTitle(
                child: Padding(
                  padding: const EdgeInsetsDirectional.only(end: HomeTokens.headerTitleBreathing),
                  // On a cold start the Yoin mark blooms here first (HomeGreetingTitle.kt).
                  child: HomeIntroTitle(
                    greeting: SeamFade(
                      fontSize: greetingStyle.fontSize!,
                      child: Text(strings.greeting(part), style: greetingStyle, maxLines: 1, softWrap: false),
                    ),
                    title: SeamFade(
                      fontSize: titleStyle.fontSize!,
                      child: _EditCrossfade(
                        progress: editProgress,
                        normal: Text(strings.title, style: titleStyle, maxLines: 1, softWrap: false),
                        editing: Text(strings.editTitle, style: titleStyle, maxLines: 1, softWrap: false),
                        alignment: AlignmentDirectional.centerStart,
                      ),
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: HomeHeaderMetrics.avatarGap),
            SeamFade(
              fontSize: HomeHeaderMetrics.avatar,
              child: _EditCrossfade(
                progress: editProgress,
                alignment: AlignmentDirectional.centerEnd,
                normal: title == null
                    ? IconButton(
                        icon: Icon(YoinSymbols.settings, color: colors.onSurfaceVariant),
                        tooltip: strings.settingsCd,
                        onPressed: () => openHomeSettings(context),
                      )
                    : HomeAccountButton(
                        fallbackTitle: title,
                        fallback: AccountMonogram(name: title),
                        onEditHome: onEditHome,
                      ),
                editing: const HomeEditActions(),
              ),
            ),
          ],
        ),
      ),
    );
    if (title == null) return header;
    return HomeMemoryBubble(rowTop: rowTop, trailingReserve: HomeHeaderMetrics.avatarGap + HomeHeaderMetrics.touchBox, child: header);
  }
}

/// [normal] and [editing] on the edit progress: the one leaving fades out
/// and stops taking input past halfway; without a progress, [normal] alone.
class _EditCrossfade extends StatelessWidget {
  const _EditCrossfade({required this.progress, required this.normal, required this.editing, required this.alignment});

  final Animation<double>? progress;
  final Widget normal;
  final Widget editing;
  final AlignmentGeometry alignment;

  @override
  Widget build(BuildContext context) {
    final p = progress;
    if (p == null) return normal;
    return AnimatedBuilder(
      animation: p,
      builder: (context, _) {
        final e = p.value.clamp(0.0, 1.0);
        // One structure throughout, so [normal] keeps its state.
        return Stack(
          alignment: alignment,
          children: [
            IgnorePointer(
              ignoring: e > 0.5,
              child: Opacity(opacity: 1 - e, child: normal),
            ),
            if (e > 0.001)
              IgnorePointer(
                ignoring: e < 0.5,
                child: Opacity(opacity: e, child: editing),
              ),
          ],
        );
      },
    );
  }
}

/// A 32 dp monogram badge with a surfaceContainer ring: the header's stand-in
/// while the account cards (Settings' AccountAvatar) load.
class AccountMonogram extends StatelessWidget {
  const AccountMonogram({super.key, required this.name});
  final String name;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final trimmed = name.trim();
    final letter = trimmed.isEmpty ? '?' : String.fromCharCode(trimmed.runes.first).toUpperCase();
    return Container(
      width: HomeHeaderMetrics.avatar,
      height: HomeHeaderMetrics.avatar,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: colors.primaryContainer,
        border: Border.all(color: colors.surfaceContainer, width: HomeHeaderMetrics.avatarRing),
      ),
      alignment: Alignment.center,
      child: Text(letter, style: gsf(TypeTokens.titleSmall.size, weight: 600, round: 100).copyWith(color: colors.onPrimaryContainer)),
    );
  }
}

/// HomeSectionTitle: Google Sans Flex 18 SemiBold, onSurface. Bare: only the
/// Activities call site adds [HomeSectionMetrics.titleBottomPadding] below.
class HomeSectionTitle extends StatelessWidget {
  const HomeSectionTitle(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) {
    final style = gsf(
      HomeSectionMetrics.titleSize,
      weight: 600,
      height: TypeTokens.titleLarge.lineHeight / HomeSectionMetrics.titleSize,
    ).copyWith(color: Theme.of(context).colorScheme.onSurface);
    return SeamFade(
      fontSize: style.fontSize!,
      child: Text(text, style: style, maxLines: 1, overflow: TextOverflow.ellipsis),
    );
  }
}
