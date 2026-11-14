import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../memories/home_memory_pill.dart';
import '../settings/settings_screen.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
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
/// control on the right; the Memories pill sits before the account control.
/// Rounded cut (ROND 100), Medium weight.
class HomeHeader extends ConsumerWidget {
  const HomeHeader({super.key, required this.accountTitle, required this.compactHeight});

  /// null = no account: a Settings gear instead of the avatar.
  final String? accountTitle;
  final bool compactHeight;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final part = ref.watch(homeGreetingProvider);
    final greetingStyle = gsf(TypeTokens.titleMedium.size, weight: 500, height: TypeTokens.titleMedium.lineHeight / TypeTokens.titleMedium.size, round: 100)
        .copyWith(color: colors.onSurfaceVariant);
    final titleToken = compactHeight ? TypeTokens.headlineSmall : TypeTokens.headlineMedium;
    final titleStyle = gsf(titleToken.size, weight: 500, height: titleToken.lineHeight / titleToken.size, round: 100)
        .copyWith(color: colors.onSurface);
    final title = accountTitle;
    return Padding(
      padding: EdgeInsets.only(top: MediaQuery.viewPaddingOf(context).top + HomeFeedMetrics.headerTopPadding),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: HomeFeedMetrics.headerMinHeight),
        child: Row(
          children: [
            Padding(
              padding: const EdgeInsetsDirectional.only(end: HomeTokens.headerTitleBreathing),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  SeamFade(
                    fontSize: greetingStyle.fontSize!,
                    child: Text(strings.greeting(part), style: greetingStyle, maxLines: 1, softWrap: false),
                  ),
                  SeamFade(
                    fontSize: titleStyle.fontSize!,
                    child: Text(strings.title, style: titleStyle, maxLines: 1, softWrap: false),
                  ),
                ],
              ),
            ),
            // The Memories pill (HeaderPill): the latest memory, its score and the notes kept.
            Expanded(
              child: title == null
                  ? const SizedBox.shrink()
                  : const Align(alignment: AlignmentDirectional.centerEnd, child: HomeMemoryPill()),
            ),
            const SizedBox(width: HomeHeaderMetrics.avatarGap),
            SeamFade(
              fontSize: HomeHeaderMetrics.avatar,
              child: title == null
                  ? IconButton(
                      icon: Icon(YoinSymbols.settings, color: colors.onSurfaceVariant),
                      tooltip: strings.settingsCd,
                      onPressed: () => openHomeSettings(context),
                    )
                  : Semantics(
                      button: true,
                      label: strings.accountCd(title),
                      excludeSemantics: true,
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: () => openHomeSettings(context),
                        child: SizedBox.square(
                          dimension: HomeHeaderMetrics.touchBox,
                          child: Center(child: AccountMonogram(name: title)),
                        ),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A 32 dp monogram badge with a surfaceContainer ring.
///
/// TODO(harmony): the Settings area owns AccountAvatar (shaped monogram);
/// swap this stand-in for it once it exists.
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
      child: Text(
        letter,
        style: gsf(TypeTokens.titleSmall.size, weight: 600, round: 100).copyWith(color: colors.onPrimaryContainer),
      ),
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
    final style = gsf(HomeSectionMetrics.titleSize, weight: 600, height: TypeTokens.titleLarge.lineHeight / HomeSectionMetrics.titleSize)
        .copyWith(color: Theme.of(context).colorScheme.onSurface);
    return SeamFade(fontSize: style.fontSize!, child: Text(text, style: style, maxLines: 1, overflow: TextOverflow.ellipsis));
  }
}
