import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/ai/ai_service.dart';
import '../data/ai/ai_settings.dart';
import '../nowplaying/haptics.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'ai_settings_screen.dart' show AiSpringSwap;
import 'ai_settings_view_model.dart';
import 'ai_tier_benchmark.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_strings.dart';

/// The tier page (AiTiersPageContent, from the AI page's ⓘ): both tiers of
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
              for (final tier in AiTier.values) ...[
                if (tier != AiTier.values.first) const SizedBox(width: SettingsMetrics.aiTierGap),
                Expanded(
                  child: _TierFacts(provider: provider, tier: tier, chosen: tier == settings.tier),
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
    String? cost;
    if (point?.costPer1000 case final c?) {
      cost = point!.costEstimated ? strings.aiCostEstimated(trimNumber(c)) : strings.aiCost(trimNumber(c));
    }
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
              (strings.aiRowSpeed, point?.seconds == null ? '—' : strings.aiSeconds(trimNumber(point!.seconds!))),
              (strings.aiRowAccuracy, point?.accuracy == null ? '—' : '${trimNumber(point!.accuracy!)}%'),
              (strings.aiRowCost, cost ?? '—'),
            ]) ...[
              const SizedBox(height: SettingsMetrics.aiFactsGap),
              _Fact(label: label, value: value, color: content),
            ],
          ],
        ),
      ),
    );
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.label, required this.value, required this.color});

  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: theme.textTheme.labelMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        Text(value, style: theme.textTheme.bodyLarge!.copyWith(color: color)),
      ],
    );
  }
}

/// 「拿一首歌试试」 (AiTryPageContent): writes About for one song with the
/// settings as they are, nothing saved. Opens on the song playing now.
class AiTryScreen extends ConsumerStatefulWidget {
  const AiTryScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  ConsumerState<AiTryScreen> createState() => _AiTryScreenState();
}

class _AiTryScreenState extends ConsumerState<AiTryScreen> {
  late final AiTrySong? _playing = ref.read(aiTryPrefillProvider);
  late final TextEditingController _title = TextEditingController(text: _playing?.title ?? '');
  late final TextEditingController _artist = TextEditingController(text: _playing?.artist ?? '');
  late final TextEditingController _album = TextEditingController(text: _playing?.album ?? '');

  @override
  void initState() {
    super.initState();
    for (final c in [_title, _artist, _album]) {
      c.addListener(_changed);
    }
  }

  void _changed() => setState(() {});

  @override
  void dispose() {
    _title.dispose();
    _artist.dispose();
    _album.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final run = ref.watch(aiTryProvider);
    final running = run is AiTryRunning;
    final canRun = !running && _title.text.trim().isNotEmpty && _artist.text.trim().isNotEmpty;
    return SettingsSubPage(
      title: strings.aiTry,
      inPane: widget.inPane,
      // Opened from the AI page: back returns there.
      paneBack: widget.inPane,
      children: [
        ExpressiveTextField(
          key: const ValueKey('ai-try-song'),
          controller: _title,
          label: strings.aiTrySong,
          placeholder: '',
          textInputAction: TextInputAction.next,
        ),
        const SizedBox(height: SettingsMetrics.aiTryFieldGap),
        ExpressiveTextField(
          key: const ValueKey('ai-try-artist'),
          controller: _artist,
          label: strings.aiTryArtist,
          placeholder: '',
          textInputAction: TextInputAction.next,
        ),
        const SizedBox(height: SettingsMetrics.aiTryFieldGap),
        ExpressiveTextField(
          key: const ValueKey('ai-try-album'),
          controller: _album,
          label: strings.aiTryAlbum,
          placeholder: '',
          textInputAction: TextInputAction.done,
        ),
        const SizedBox(height: SettingsMetrics.aiTryFieldGap + SettingsMetrics.aiKeyGap),
        SizedBox(
          height: SettingsMetrics.aiTryHeight,
          child: FilledButton(
            key: const ValueKey('ai-try-run'),
            onPressed: canRun
                ? () {
                    YoinHaptics.click();
                    FocusScope.of(context).unfocus();
                    ref
                        .read(aiTryProvider.notifier)
                        .run(AiTrySong(_title.text.trim(), _artist.text.trim(), _album.text.trim()));
                  }
                : null,
            child: Text(strings.aiTryRun),
          ),
        ),
        const SizedBox(height: SettingsMetrics.aiTryFieldGap * 2),
        // Waiting, the answer and a failure take each other's place: they cross-fade and the
        // page takes its new height on the spatial spring.
        AiSpringSwap(
          childKey: run.runtimeType,
          child: switch (run) {
            AiTryIdle() => const SizedBox.shrink(),
            AiTryRunning() => Row(
              children: [
                const YoinLoadingIndicator(size: SettingsMetrics.aiTryLoading),
                const SizedBox(width: SettingsMetrics.aiTryLoadingGap),
                Flexible(child: Text(strings.aiTryRunning, style: theme.textTheme.bodyLarge)),
              ],
            ),
            AiTryFailed(:final message) => Text(
              message == null ? strings.aiTryNoKey : strings.aiTryError(message),
              style: theme.textTheme.bodyLarge!.copyWith(color: theme.colorScheme.error),
            ),
            final AiTryDone done => _TryResult(run: done),
          },
        ),
      ],
    );
  }
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
