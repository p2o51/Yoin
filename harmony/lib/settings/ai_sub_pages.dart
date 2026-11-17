import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/ai/ai_service.dart';
import '../data/ai/ai_settings.dart';
import '../library/library_cells.dart' show libraryCoverUrl;
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/song_list_item.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'ai_settings_screen.dart' show AiSpringSwap;
import 'ai_settings_view_model.dart';
import 'ai_tier_benchmark.dart';
import 'settings_components.dart' show SettingsSprings;
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_rows.dart' show SettingsSegments;
import 'settings_strings.dart';

/// The tier page (AiTiersPageContent, from the AI page's ⓘ): the tiers of
/// the chosen service side by side, and what they cost. Custom has no tiers:
/// the page then shows Gemini's.
class AiTiersScreen extends ConsumerWidget {
  const AiTiersScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final text = Theme.of(context).textTheme;
    final settings = ref.watch(aiSettingsProvider).value ?? const AiSettings();
    final provider = settings.provider == AiProvider.custom ? AiProvider.gemini : settings.provider;
    final current = settings.copyWith(provider: provider).activeTier;
    return SettingsSubPage(
      title: strings.aiTiersLabel,
      inPane: inPane,
      // Opened from the AI page: back returns there.
      paneBack: inPane,
      children: [
        Text(strings.aiTiersTitle(strings.aiProvider(provider)), style: text.titleLarge),
        const SizedBox(height: SettingsMetrics.aiTiersGap),
        IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final tier in provider.tiers) ...[
                if (tier != provider.tiers.first) const SizedBox(width: SettingsMetrics.aiTierGap),
                Expanded(
                  child: _TierFacts(provider: provider, tier: tier, chosen: tier == current),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: SettingsMetrics.aiTiersGap),
        Text(switch (provider) {
          AiProvider.deepSeek => strings.aiNoteDeepSeek,
          AiProvider.qwen => strings.aiNoteQwen,
          _ => strings.aiNoteGemini,
        }, style: text.bodyMedium),
        const SizedBox(height: SettingsMetrics.aiTiersGap),
        Text(
          strings.aiTiersSource,
          style: text.bodySmall!.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
      ],
    );
  }
}

class _TierFacts extends StatelessWidget {
  const _TierFacts({required this.provider, required this.tier, required this.chosen});

  final AiProvider provider;
  final AiTier tier;
  final bool chosen;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final point = AiTierBenchmark.point(provider, tier);
    final model = AiSettings(provider: provider, tier: tier).modelId;
    final search = switch (provider) {
      AiProvider.gemini => strings.aiSearchGoogle,
      AiProvider.qwen => strings.aiSearchQwen,
      _ => tier == AiTier.best ? strings.aiSearchDeepSeekTwice : strings.aiSearchDeepSeekOnce,
    };
    final content = chosen ? colors.onPrimaryContainer : colors.onSurface;
    // The row labels read in the card's own on-colour: on the chosen card's primaryContainer,
    // onSurfaceVariant (meant for surfaces) all but disappears.
    final labelColor = chosen ? colors.onPrimaryContainer : colors.onSurfaceVariant;
    // An estimate is marked as the estimated cost always was: 「（估）」 after the number.
    final estimated = point?.estimated ?? false;
    final seconds = point == null
        ? '—'
        : estimated
        ? strings.aiSecondsEstimated(trimNumber(point.seconds))
        : strings.aiSeconds(trimNumber(point.seconds));
    final accuracy = point == null
        ? '—'
        : estimated
        ? strings.aiAccuracyEstimated(trimNumber(point.accuracy))
        : '${trimNumber(point.accuracy)}%';
    final cost = point == null
        ? '—'
        : estimated || point.costEstimated
        ? strings.aiCostEstimated(trimNumber(point.costPer1000))
        : strings.aiCost(trimNumber(point.costPer1000));
    return Material(
      key: ValueKey('ai-facts-${tier.name}'),
      color: chosen ? colors.primaryContainer : colors.surfaceBright,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(
          chosen ? SettingsMetrics.aiFactsCornerPicked : SettingsMetrics.aiFactsCorner,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(SettingsMetrics.aiFactsPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              strings.aiTier(tier),
              style: theme.textTheme.headlineSmall!.copyWith(
                color: content,
                fontWeight: FontWeight.w700,
                fontVariations: const [FontVariation.weight(700)],
              ),
            ),
            for (final (label, value) in [
              (strings.aiRowModel, model),
              (strings.aiRowSearch, search),
              (strings.aiRowSpeed, seconds),
              (strings.aiRowAccuracy, accuracy),
              (strings.aiRowCost, cost),
            ]) ...[
              const SizedBox(height: SettingsMetrics.aiFactsGap),
              _Fact(label: label, value: value, color: content, labelColor: labelColor),
            ],
          ],
        ),
      ),
    );
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.label, required this.value, required this.color, required this.labelColor});

  final String label;
  final String value;
  final Color color;
  final Color labelColor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: theme.textTheme.labelMedium!.copyWith(color: labelColor)),
        Text(value, style: theme.textTheme.bodyLarge!.copyWith(color: color)),
      ],
    );
  }
}

/// 「拿一首歌试试」 (AiTryPageContent): writes About for one song with the
/// settings as they are, nothing saved. The song is one of the active
/// account's last [recentTestSongCount] played (owner 2026-11-14): tap one.
class AiTryScreen extends ConsumerWidget {
  const AiTryScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final songs = ref.watch(aiRecentSongsProvider);
    return SettingsSubPage(
      title: strings.aiTry,
      inPane: inPane,
      // Opened from the AI page: back returns there.
      paneBack: inPane,
      children: [
        AiTryBody(
          songs: switch (songs) {
            AsyncData(:final value) => value,
            AsyncError() => const [],
            _ => null,
          },
          run: ref.watch(aiTryProvider),
          onPick: (song) => ref.read(aiTryProvider.notifier).run(song),
        ),
      ],
    );
  }
}

/// The test page over plain state (AiTryPageBody): [songs] null while the
/// history is read, empty with none. While a run goes, its row shows progress
/// and no row starts another.
class AiTryBody extends StatelessWidget {
  const AiTryBody({super.key, required this.songs, required this.run, required this.onPick});

  final List<AiRecentSong>? songs;
  final AiTryRun run;
  final ValueChanged<AiTrySong> onPick;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final songs = this.songs;
    final running = switch (run) {
      AiTryRunning(:final song) => song,
      _ => null,
    };
    final muted = theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (songs != null && songs.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.aiSectionLabelInset),
            child: Text(strings.aiTryNoHistory, style: muted),
          )
        else if (songs != null)
          SettingsSegments(
            children: [
              for (final recent in songs)
                Consumer(
                  key: ValueKey('ai-try-${recent.key}'),
                  builder: (context, ref, _) => SongListItem(
                    title: recent.song.title,
                    artist: recent.song.artist,
                    coverUrl: libraryCoverUrl(ref, recent.cover),
                    decodeWidth: (LibraryTokens.rowThumb * MediaQuery.devicePixelRatioOf(context)).round(),
                    onTap: () {
                      if (running != null) return;
                      YoinHaptics.click();
                      onPick(recent.song);
                    },
                    trailing: _RunningMark(visible: running == recent.song),
                  ),
                ),
            ],
          ),
        const SizedBox(height: SettingsMetrics.aiTryGap),
        // The answer and a failure take each other's place: they cross-fade and the page takes
        // its new height on the spatial spring. Waiting shows on the song's row.
        AiSpringSwap(
          childKey: run.runtimeType,
          child: switch (run) {
            AiTryIdle() => const SizedBox.shrink(),
            AiTryRunning() => Padding(
              padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.aiSectionLabelInset),
              child: Text(strings.aiTryRunning, style: muted),
            ),
            AiTryFailed(:final message) => Padding(
              padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.aiSectionLabelInset),
              child: Text(
                message == null ? strings.aiTryNoKey : strings.aiTryError(message),
                style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.error),
              ),
            ),
            final AiTryDone done => _TryResult(run: done),
          },
        ),
      ],
    );
  }
}

/// The running song's progress mark: fades and scales in on its row
/// (fadeIn + scaleIn from 0.6, Expressive).
class _RunningMark extends StatefulWidget {
  const _RunningMark({required this.visible});

  final bool visible;

  @override
  State<_RunningMark> createState() => _RunningMarkState();
}

class _RunningMarkState extends State<_RunningMark> with TickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: SettingsSprings.effects,
  );
  late final SpringValue _scale = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );

  @override
  void didUpdateWidget(_RunningMark old) {
    super.didUpdateWidget(old);
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _alpha.snapTo(target);
      _scale.snapTo(target);
    } else {
      _alpha.animateTo(target);
      _scale.animateTo(target);
    }
  }

  @override
  void dispose() {
    _alpha.dispose();
    _scale.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_alpha.animation, _scale.animation]),
    builder: (context, _) {
      final a = _alpha.value.clamp(0.0, 1.0);
      if (a <= 0.001 && !widget.visible) return const SizedBox.shrink();
      return Opacity(
        opacity: a,
        child: Transform.scale(
          scale: SettingsMetrics.aiTryMarkScale + (1 - SettingsMetrics.aiTryMarkScale) * _scale.value,
          child: const YoinLoadingIndicator(size: SettingsMetrics.aiTryLoading),
        ),
      );
    },
  );
}

class _TryResult extends StatelessWidget {
  const _TryResult({required this.run});

  final AiTryDone run;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final muted = theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant);
    final search = switch (run.search) {
      AiSearchKind.builtIn => strings.aiTryBuiltin,
      AiSearchKind.none => strings.aiSearchNone,
      AiSearchKind.external => '${strings.aiTryQueries(run.queries.join(' / '))}\n${strings.aiTryPages(run.pages)}',
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(run.model, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleMedium),
        Text(strings.aiSeconds((run.millis / 1000).toStringAsFixed(1)), style: muted),
        const SizedBox(height: SettingsMetrics.aiTryResultGap),
        Text(search, style: theme.textTheme.bodySmall!.copyWith(color: colors.onSurfaceVariant)),
        const SizedBox(height: SettingsMetrics.aiTryResultGap),
        Material(
          color: colors.surfaceBright,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(SettingsMetrics.aiTryCardCorner)),
          child: Padding(
            padding: const EdgeInsets.all(SettingsMetrics.aiTryCardPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final (i, key) in AboutFields.order.indexed) ...[
                  if (i > 0) const SizedBox(height: SettingsMetrics.aiTryResultGap),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: SettingsMetrics.aiTryFieldMaxWidth),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          strings.aboutField(key),
                          style: theme.textTheme.labelMedium!.copyWith(color: colors.onSurfaceVariant),
                        ),
                        Text(
                          run.fields[key] ?? '—',
                          style: theme.textTheme.bodyLarge!.copyWith(
                            color: run.fields[key] == null ? colors.outline : colors.onSurface,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}
