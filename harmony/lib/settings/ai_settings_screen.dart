import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../home/widgets/spring_height.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'ai_settings_view_model.dart';
import 'ai_sub_pages.dart';
import 'ai_tradeoff_chart.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_pane.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'subsonic_setup_screen.dart' show SecretTextField;

/// Settings › Features › AI features (Android GeminiItem): the service and
/// tier (Custom: its model) in use, or what it's for until a key is set;
/// opens [AiSettingsScreen] (in the right pane on a wide window).
class AiSettingsRow extends ConsumerWidget {
  const AiSettingsRow({super.key, this.trailing, this.onOpen});

  final Widget? trailing;

  /// The list-detail's opener; null pushes the page.
  final VoidCallback? onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final ai = ref.watch(aiSettingsProvider).value ?? const AiSettings();
    final colors = Theme.of(context).colorScheme;
    final tier = ai.provider == AiProvider.custom ? ai.modelId : strings.aiTier(ai.tier);
    return SettingsItem(
      icon: YoinSymbols.sparkle,
      title: strings.aiTitle,
      summary: ai.isReady ? null : strings.aiSummaryEmpty,
      summaryContent: ai.isReady
          ? MetaLine(
              groups: [MetaPlain(strings.aiProvider(ai.provider)), MetaMuted(tier)],
              style: Theme.of(context).textTheme.bodyMedium,
              color: colors.onSurfaceVariant,
            )
          : null,
      trailing: trailing,
      onTap: onOpen ?? () => Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const AiSettingsScreen())),
    );
  }
}

/// Settings › AI features (Android AiSettingsPage, AI settings v2): a
/// service row, the tradeoff chart with 「换个角度」, two tier cards and the
/// key. Custom swaps the tiers for its endpoint, model, thinking and search.
/// Details live on the tier page (ⓘ), the debug run on 「拿一首歌试试」. Every
/// change is kept at once.
class AiSettingsScreen extends ConsumerWidget {
  const AiSettingsScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final settings = ref.watch(aiSettingsProvider).value ?? const AiSettings();
    final models = ref.watch(aiModelListProvider);
    final openLink = ref.read(aiLinkOpenerProvider);
    return SettingsSubPage(
      title: strings.aiTitlePage,
      inPane: inPane,
      children: [
        AiSettingsBody(
          settings: settings,
          models: models,
          actions: StoredAiSettingsActions(ref),
          onOpenTiers: () => openAiSubPage(context, const AiTiersPane()),
          onOpenTry: () => openAiSubPage(context, const AiTryPane()),
          // No app to open a link with must not take the page down.
          onOpenLink: (url) async {
            try {
              await openLink(Uri.parse(url));
            } catch (_) {}
          },
        ),
      ],
    );
  }
}

/// Wraps one part of [AiSettingsBody] for the Tips demo (its finger's target).
typedef AiPartWrapper<T> = Widget Function(T part, Widget child);

/// The AI page's controls over plain state (AiSettingsPageBody): what
/// [AiSettingsScreen] shows, and what the Tips demo shows with state of its
/// own. The text fields keep what is typed; a value changed from elsewhere
/// (a model chip, the store loading) is put into them.
class AiSettingsBody extends StatefulWidget {
  const AiSettingsBody({
    super.key,
    required this.settings,
    required this.models,
    required this.actions,
    required this.onOpenTiers,
    required this.onOpenTry,
    required this.onOpenLink,
    this.providerWrapper,
    this.tierWrapper,
    this.tryWrapper,
  });

  final AiSettings settings;
  final AiModelList models;
  final AiSettingsActions actions;
  final VoidCallback onOpenTiers;
  final VoidCallback onOpenTry;
  final ValueChanged<String> onOpenLink;
  final AiPartWrapper<AiProvider>? providerWrapper;
  final AiPartWrapper<AiTier>? tierWrapper;
  final Widget Function(Widget child)? tryWrapper;

  @override
  State<AiSettingsBody> createState() => _AiSettingsBodyState();
}

class _AiSettingsBodyState extends State<AiSettingsBody> {
  final List<TextEditingController> _all = [];
  bool _syncing = false;

  late final Map<AiProvider, TextEditingController> _keys = {
    for (final p in AiProvider.values) p: _field(widget.settings.keyFor(p), (text) => widget.actions.setKey(p, text)),
  };
  late final Map<AiSearchService, TextEditingController> _searchKeys = {
    for (final s in AiSearchService.values)
      if (s != AiSearchService.none)
        s: _field(widget.settings.searchKeyFor(s), (text) => widget.actions.setSearchKey(s, text)),
  };

  // Drafts so a half-typed address or model isn't trimmed under the cursor.
  late final TextEditingController _url = _field(
    widget.settings.customBaseUrl,
    (text) => widget.actions.setCustomBaseUrl(text),
  );
  late final TextEditingController _model = _field(
    widget.settings.customModel,
    (text) => widget.actions.setCustomModel(text),
  );

  /// The search service whose key field shows: the last one picked, kept on
  /// screen while the field folds away for 「不搜索」.
  late AiSearchService _keyService = widget.settings.searchService == AiSearchService.none
      ? AiSearchService.deepSeek
      : widget.settings.searchService;

  TextEditingController _field(String initial, ValueChanged<String> onChanged) {
    final controller = TextEditingController(text: initial);
    var last = initial;
    controller.addListener(() {
      if (controller.text == last) return;
      last = controller.text;
      if (!_syncing) onChanged(controller.text);
    });
    _all.add(controller);
    return controller;
  }

  @override
  void initState() {
    super.initState();
    _keys;
    _searchKeys;
    _url;
    _model;
  }

  @override
  void didUpdateWidget(AiSettingsBody old) {
    super.didUpdateWidget(old);
    final s = widget.settings;
    if (s.searchService != AiSearchService.none) _keyService = s.searchService;
    _syncing = true;
    void sync(TextEditingController c, String value) {
      if (c.text.trim() != value.trim()) c.text = value;
    }

    for (final MapEntry(:key, :value) in _keys.entries) {
      sync(value, s.keyFor(key));
    }
    for (final MapEntry(:key, :value) in _searchKeys.entries) {
      sync(value, s.searchKeyFor(key));
    }
    sync(_url, s.customBaseUrl);
    sync(_model, s.customModel);
    _syncing = false;
  }

  @override
  void dispose() {
    for (final c in _all) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final s = widget.settings;
    final tryButton = SizedBox(
      height: SettingsMetrics.aiTryHeight,
      child: FilledButton(
        key: const ValueKey('ai-try'),
        onPressed: () {
          YoinHaptics.click();
          widget.onOpenTry();
        },
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(YoinSymbols.playCircle, size: SettingsMetrics.aiTryIcon),
            const SizedBox(width: SettingsMetrics.aiTryIconGap),
            Flexible(child: Text(strings.aiTry, maxLines: 1, overflow: TextOverflow.ellipsis)),
          ],
        ),
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _ProviderRow(
          selected: s.provider,
          onSelect: (p) {
            YoinHaptics.tick();
            widget.actions.setProvider(p);
          },
          wrapper: widget.providerWrapper,
        ),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        AiTradeoffChart(provider: s.provider, tier: s.tier),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        // Tiers ⇄ Custom is the page's one body swap: the two cross-fade and the page takes its new
        // height on the spatial spring. The tiered services share a body, so picking among them
        // swaps nothing.
        AiSpringSwap(
          childKey: s.provider == AiProvider.custom,
          child: s.provider == AiProvider.custom ? _custom(context) : _tiered(context, s.provider),
        ),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        AiLanguageButton(language: s.targetLanguage, onPick: widget.actions.setTargetLanguage),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        widget.tryWrapper?.call(tryButton) ?? tryButton,
      ],
    );
  }

  Widget _tiered(BuildContext context, AiProvider provider) {
    final strings = SettingsStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final (linkText, url) = switch (provider) {
      AiProvider.gemini => (strings.aiGetKeyGemini, 'https://aistudio.google.com/apikey'),
      AiProvider.deepSeek => (strings.aiGetKeyDeepSeek, 'https://platform.deepseek.com/api_keys'),
      AiProvider.qwen => (strings.aiGetKeyQwen, 'https://bailian.console.aliyun.com/?tab=model#/api-key'),
      AiProvider.custom => ('', ''),
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(child: _SectionLabel(strings.aiTiersLabel)),
            IconButton(
              key: const ValueKey('ai-tier-info'),
              tooltip: strings.aiCdTierDetails,
              icon: Icon(YoinSymbols.info, color: colors.onSurfaceVariant),
              onPressed: () {
                YoinHaptics.click();
                widget.onOpenTiers();
              },
            ),
          ],
        ),
        const SizedBox(height: SettingsMetrics.aiGroupGap),
        Row(
          children: [
            for (final tier in AiTier.values) ...[
              if (tier != AiTier.values.first) const SizedBox(width: SettingsMetrics.aiTierGap),
              Expanded(
                child: _wrap(
                  widget.tierWrapper,
                  tier,
                  AiTierCard(
                    key: ValueKey('ai-tier-${tier.name}'),
                    label: strings.aiTier(tier),
                    selected: widget.settings.tier == tier,
                    onTap: () {
                      YoinHaptics.tick();
                      widget.actions.setTier(tier);
                    },
                  ),
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        // One field per service: its own key, and a key shown in the clear for one is masked for the next.
        SecretTextField(
          key: ValueKey('ai-key-${provider.name}'),
          controller: _keys[provider]!,
          label: strings.aiKeyLabel,
          placeholder: provider == AiProvider.gemini ? 'AIza…' : 'sk-…',
        ),
        const SizedBox(height: SettingsMetrics.aiKeyGap),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: _LinkButton(key: const ValueKey('ai-key-link'), text: linkText, onTap: () => widget.onOpenLink(url)),
        ),
      ],
    );
  }

  Widget _custom(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final s = widget.settings;
    final models = widget.models;
    final chips = modelChips(models.ids, _model.text);
    final searchOn = s.searchService != AiSearchService.none;
    final keyName = strings.aiSearchService(_keyService);
    final searchUrl = switch (_keyService) {
      AiSearchService.deepSeek => 'https://platform.deepseek.com/api_keys',
      AiSearchService.exa => 'https://dashboard.exa.ai/api-keys',
      AiSearchService.tavily => 'https://app.tavily.com/home',
      AiSearchService.none => '',
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SectionLabel(strings.aiCustomFormat),
        const SizedBox(height: SettingsMetrics.aiGroupGap),
        Row(
          children: [
            for (final format in AiApiFormat.values) ...[
              if (format != AiApiFormat.values.first) const SizedBox(width: SettingsMetrics.aiChipGap),
              Expanded(
                child: AiChoiceChip(
                  key: ValueKey('ai-format-${format.name}'),
                  text: format == AiApiFormat.openAi ? strings.aiFormatOpenAi : strings.aiFormatAnthropic,
                  selected: s.customFormat == format,
                  onTap: () {
                    YoinHaptics.tick();
                    widget.actions.setCustomFormat(format);
                  },
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: SettingsMetrics.aiGroupGap + SettingsMetrics.aiKeyGap),
        ExpressiveTextField(
          key: const ValueKey('ai-custom-url'),
          controller: _url,
          label: strings.aiCustomUrl,
          placeholder: s.customFormat == AiApiFormat.openAi ? 'https://api.openai.com/v1' : 'https://api.anthropic.com',
          keyboardType: TextInputType.url,
          textInputAction: TextInputAction.next,
        ),
        const SizedBox(height: SettingsMetrics.aiGroupGap),
        SecretTextField(
          key: const ValueKey('ai-key-custom'),
          controller: _keys[AiProvider.custom]!,
          label: strings.aiKeyLabel,
          placeholder: 'sk-…',
        ),
        const SizedBox(height: SettingsMetrics.aiGroupGap),
        // The model field and the rows that come and go under it. They bring their own gaps, so a
        // row opening or closing moves what is below it the whole way, with no jump at either end.
        Stack(
          children: [
            ExpressiveTextField(
              key: const ValueKey('ai-custom-model'),
              controller: _model,
              label: strings.aiCustomModel,
              placeholder: '',
              textInputAction: TextInputAction.done,
            ),
            PositionedDirectional(
              end: 0,
              bottom: 0,
              height: SettingsMetrics.fieldBoxHeight,
              child: Center(
                child: TextButton(
                  key: const ValueKey('ai-custom-pull'),
                  onPressed: !models.loading && s.customBaseUrl.trim().isNotEmpty && s.customKey.trim().isNotEmpty
                      ? () {
                          YoinHaptics.click();
                          widget.actions.pullModels();
                        }
                      : null,
                  child: Text(strings.aiCustomPull),
                ),
              ),
            ),
          ],
        ),
        ClipRect(
          child: SpringHeight(
            heightKey: (models.error, chips.join('\n')),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (models.error case final error?)
                  Padding(
                    padding: const EdgeInsetsDirectional.only(
                      start: SettingsMetrics.aiSectionLabelInset,
                      end: SettingsMetrics.aiSectionLabelInset,
                      top: SettingsMetrics.aiGroupGap,
                    ),
                    child: Text(
                      strings.aiModelsError(error),
                      style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.error),
                    ),
                  ),
                if (chips.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: SettingsMetrics.aiGroupGap),
                    child: Wrap(
                      spacing: SettingsMetrics.aiChipGap,
                      runSpacing: SettingsMetrics.aiChipGap,
                      children: [
                        for (final id in chips)
                          AiChoiceChip(
                            key: ValueKey('ai-model-$id'),
                            text: id,
                            selected: s.customModel == id,
                            height: SettingsMetrics.aiModelChipHeight,
                            rest: SettingsMetrics.aiModelChipCorner,
                            onTap: () {
                              YoinHaptics.tick();
                              widget.actions.setCustomModel(id);
                            },
                          ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
        // Thinking once there is a model to think with (owner 2026-11-12).
        SpringReveal(
          visible: s.customModel.trim().isNotEmpty,
          child: Padding(
            padding: const EdgeInsets.only(top: SettingsMetrics.aiThinkingTop),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SectionLabel(strings.aiCustomThinking),
                const SizedBox(height: SettingsMetrics.aiGroupGap),
                Row(
                  children: [
                    for (final level in AiThinking.values) ...[
                      if (level != AiThinking.values.first) const SizedBox(width: SettingsMetrics.aiChipGap),
                      Expanded(
                        child: AiChoiceChip(
                          key: ValueKey('ai-thinking-${level.name}'),
                          text: strings.aiThinking(level),
                          selected: s.customThinking == level,
                          onTap: () {
                            YoinHaptics.tick();
                            widget.actions.setCustomThinking(level);
                          },
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: SettingsMetrics.aiGroupGap + SettingsMetrics.aiKeyGap),
        _SectionLabel(strings.aiSearchLabel),
        const SizedBox(height: SettingsMetrics.aiGroupGap),
        Wrap(
          spacing: SettingsMetrics.aiChipGap,
          runSpacing: SettingsMetrics.aiChipGap,
          children: [
            for (final service in AiSearchService.values)
              AiChoiceChip(
                key: ValueKey('ai-search-${service.name}'),
                text: strings.aiSearchService(service),
                selected: s.searchService == service,
                onTap: () {
                  YoinHaptics.tick();
                  widget.actions.setSearchService(service);
                },
              ),
          ],
        ),
        SpringReveal(
          visible: searchOn,
          child: Padding(
            padding: const EdgeInsets.only(top: SettingsMetrics.aiGroupGap),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // One field per service: its own key, and a key shown in the clear for one
                // service is masked again for the next.
                SecretTextField(
                  key: ValueKey('ai-search-key-${_keyService.name}'),
                  controller: _searchKeys[_keyService]!,
                  label: strings.aiSearchKey(keyName),
                  placeholder: '',
                ),
                const SizedBox(height: SettingsMetrics.aiGroupGap),
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: _LinkButton(
                    key: const ValueKey('ai-search-link'),
                    text: strings.aiGetSearchKey(keyName),
                    onTap: () => widget.onOpenLink(searchUrl),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

Widget _wrap<T>(AiPartWrapper<T>? wrapper, T part, Widget child) => wrapper?.call(part, child) ?? child;

/// The fetched model ids to offer as chips (modelChips). An endpoint with a
/// short list shows all of it; an aggregator's hundreds are narrowed by what
/// is [typed] in the model field, so any of them can be reached, and never
/// more than [SettingsMetrics.aiMaxModelChips] are drawn.
List<String> modelChips(List<String> ids, String typed) {
  if (ids.length <= SettingsMetrics.aiMaxModelChips) return ids;
  final query = typed.trim().toLowerCase();
  return ids.where((id) => id.toLowerCase().contains(query)).take(SettingsMetrics.aiMaxModelChips).toList();
}

/// A label over a group (SectionLabel): labelLarge in onSurfaceVariant, 4 in.
class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: SettingsMetrics.aiSectionLabelInset),
      child: Semantics(
        header: true,
        child: Text(text, style: theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      ),
    );
  }
}

/// "Get a key …" with the launch mark (the TextButton under a key field).
class _LinkButton extends StatelessWidget {
  const _LinkButton({super.key, required this.text, required this.onTap});

  final String text;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => TextButton(
    onPressed: onTap,
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Flexible(child: Text(text)),
        const SizedBox(width: SettingsMetrics.aiLinkIconGap),
        const Icon(YoinSymbols.launch, size: SettingsMetrics.aiLinkIcon),
      ],
    ),
  );
}

/// The service row (ProviderRow): four weighted chips.
class _ProviderRow extends StatelessWidget {
  const _ProviderRow({required this.selected, required this.onSelect, this.wrapper});

  final AiProvider selected;
  final ValueChanged<AiProvider> onSelect;
  final AiPartWrapper<AiProvider>? wrapper;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return Row(
      children: [
        for (final provider in AiProvider.values) ...[
          if (provider != AiProvider.values.first) const SizedBox(width: SettingsMetrics.aiChipGap),
          Expanded(
            child: _wrap(
              wrapper,
              provider,
              AiChoiceChip(
                key: ValueKey('ai-provider-${provider.name}'),
                text: strings.aiProvider(provider),
                selected: selected == provider,
                onTap: () => onSelect(provider),
                // A chip of a set width keeps only a thin edge: its word is centred, and a
                // 360 phone leaves 「DeepSeek」 no room for more.
                horizontalPadding: SettingsMetrics.aiProviderChipPadding,
              ),
            ),
          ),
        ],
      ],
    );
  }
}

/// A choice that rounds into a pill and fills when picked (ChoiceChip: the
/// services, formats, thinking, search, model ids). Corner on the fast
/// spatial spring, colour on the fast effects spring.
class AiChoiceChip extends StatefulWidget {
  const AiChoiceChip({
    super.key,
    required this.text,
    required this.selected,
    required this.onTap,
    this.rest = SettingsMetrics.aiChipCorner,
    this.height = SettingsMetrics.aiChipHeight,
    this.horizontalPadding = SettingsMetrics.aiChipPadding,
  });

  final String text;
  final bool selected;
  final VoidCallback onTap;
  final double rest;
  final double height;
  final double horizontalPadding;

  @override
  State<AiChoiceChip> createState() => _AiChoiceChipState();
}

class _AiChoiceChipState extends State<AiChoiceChip> with TickerProviderStateMixin {
  late final SpringValue _corner = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.fastSpatial,
  );
  late final SpringValue _colour = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.fastEffects,
  );

  @override
  void didUpdateWidget(AiChoiceChip old) {
    super.didUpdateWidget(old);
    if (old.selected == widget.selected) return;
    final target = widget.selected ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _corner.snapTo(target);
      _colour.snapTo(target);
    } else {
      _corner.animateTo(target);
      _colour.animateTo(target);
    }
  }

  @override
  void dispose() {
    _corner.dispose();
    _colour.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Semantics(
      selected: widget.selected,
      inMutuallyExclusiveGroup: true,
      button: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_corner.animation, _colour.animation]),
        builder: (context, _) {
          final c = _colour.value.clamp(0.0, 1.0);
          final radius = (widget.rest + (widget.height / 2 - widget.rest) * _corner.value).clamp(0.0, widget.height);
          final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(radius));
          return Material(
            color: Color.lerp(colors.surfaceBright, colors.primary, c),
            shape: shape,
            child: InkWell(
              customBorder: shape,
              onTap: widget.onTap,
              child: SizedBox(
                height: widget.height,
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: widget.horizontalPadding),
                  child: Center(
                    widthFactor: 1,
                    child: Text(
                      widget.text,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: text.labelLarge!.copyWith(color: Color.lerp(colors.onSurface, colors.onPrimary, c)),
                    ),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// Cross-fades a swapped body while the page takes its new height on the
/// spatial spring (Compose AnimatedContent + SizeTransform): the incoming
/// child sets the height, the outgoing one fades on top of it, and the box
/// clips to the height shown.
class AiSpringSwap extends StatefulWidget {
  const AiSpringSwap({super.key, required this.childKey, required this.child});

  final Object childKey;
  final Widget child;

  @override
  State<AiSpringSwap> createState() => _AiSpringSwapState();
}

class _SwapEntry {
  _SwapEntry(this.key, this.child, this.alpha);
  final Object key;
  Widget child;
  final SpringValue alpha;
}

class _AiSpringSwapState extends State<AiSpringSwap> with TickerProviderStateMixin {
  final _entries = <_SwapEntry>[];

  @override
  void initState() {
    super.initState();
    _entries.add(
      _SwapEntry(widget.childKey, widget.child, SpringValue(vsync: this, initial: 1, spring: SettingsSprings.effects)),
    );
  }

  @override
  void didUpdateWidget(AiSpringSwap old) {
    super.didUpdateWidget(old);
    final current = _entries.last;
    if (current.key == widget.childKey) {
      current.child = widget.child;
      return;
    }
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    for (final e in _entries) {
      e.alpha.animateTo(0);
    }
    // Coming back to a key still fading out revives that child (its state with it).
    final entry =
        _entries.where((e) => e.key == widget.childKey).firstOrNull ??
        _SwapEntry(
          widget.childKey,
          widget.child,
          SpringValue(vsync: this, initial: reduce ? 1 : 0, spring: SettingsSprings.effects),
        );
    _entries
      ..remove(entry)
      ..add(entry);
    entry.child = widget.child;
    reduce ? entry.alpha.snapTo(1) : entry.alpha.animateTo(1);
    for (final e in _entries.take(_entries.length - 1)) {
      e.alpha.animation.addStatusListener((_) => _prune());
    }
    if (reduce) _prune(force: true);
  }

  void _prune({bool force = false}) {
    if (!mounted) return;
    final dead = [
      for (final e in _entries.take(_entries.length - 1))
        if (force || e.alpha.value <= 0.001) e,
    ];
    if (dead.isEmpty) return;
    setState(() {
      for (final e in dead) {
        _entries.remove(e);
        e.alpha.dispose();
      }
    });
  }

  @override
  void dispose() {
    for (final e in _entries) {
      e.alpha.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    Widget faded(_SwapEntry e) =>
        FadeTransition(opacity: e.alpha.animation.drive(Tween(begin: 0.0, end: 1.0)), child: e.child);
    final current = _entries.last;
    return ClipRect(
      child: SpringHeight(
        heightKey: current.key,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            for (final e in _entries.take(_entries.length - 1))
              Positioned(
                key: ObjectKey(e),
                top: 0,
                left: 0,
                right: 0,
                child: IgnorePointer(child: faded(e)),
              ),
            KeyedSubtree(key: ObjectKey(current), child: faded(current)),
          ],
        ),
      ),
    );
  }
}

/// One tier (TierCard): 84 high, its corner and colour springing between rest and chosen.
class AiTierCard extends StatefulWidget {
  const AiTierCard({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<AiTierCard> createState() => _AiTierCardState();
}

class _AiTierCardState extends State<AiTierCard> with TickerProviderStateMixin {
  late final SpringValue _corner = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _colour = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.fastEffects,
  );

  @override
  void didUpdateWidget(AiTierCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selected == widget.selected) return;
    final target = widget.selected ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _corner.snapTo(target);
      _colour.snapTo(target);
    } else {
      _corner.animateTo(target);
      _colour.animateTo(target);
    }
  }

  @override
  void dispose() {
    _corner.dispose();
    _colour.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Semantics(
      selected: widget.selected,
      inMutuallyExclusiveGroup: true,
      button: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_corner.animation, _colour.animation]),
        builder: (context, _) {
          final c = _colour.value.clamp(0.0, 1.0);
          final radius =
              SettingsMetrics.aiTierCorner +
              (SettingsMetrics.aiTierCornerSelected - SettingsMetrics.aiTierCorner) * _corner.value;
          return Material(
            color: Color.lerp(colors.surfaceBright, colors.primary, c),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radius)),
            child: InkWell(
              customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radius)),
              onTap: widget.onTap,
              child: SizedBox(
                height: SettingsMetrics.aiTierHeight,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.aiTierPadding),
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Text(
                      widget.label,
                      style: text.headlineMedium!.copyWith(
                        color: Color.lerp(colors.onSurface, colors.onPrimary, c),
                        fontWeight: FontWeight.w700,
                        fontVariations: const [FontVariation.weight(700)],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// "Answer in" and the language (LanguageButton): an outlined button that opens the language menu.
class AiLanguageButton extends StatelessWidget {
  const AiLanguageButton({super.key, required this.language, required this.onPick});

  final String language;
  final ValueChanged<String> onPick;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return Builder(
      builder: (context) => OutlinedButton(
        key: const ValueKey('ai-answer-in'),
        onPressed: () async {
          YoinHaptics.tick();
          final box = context.findRenderObject() as RenderBox?;
          if (box == null || !box.hasSize) return;
          final picked = await showYoinDropdownMenu<String>(
            context: context,
            anchor: box.localToGlobal(Offset.zero) & box.size,
            items: [
              for (final option in AiLanguages.supported)
                YoinMenuItem(value: option, label: option, checked: option == language),
            ],
          );
          if (picked != null) {
            YoinHaptics.contextClick();
            onPick(picked);
          }
        },
        child: Align(alignment: AlignmentDirectional.centerStart, child: Text(strings.aiAnswerIn(language))),
      ),
    );
  }
}

/// Opens the AI page: in the wide window's right pane, else pushed.
void openAiSettings(BuildContext context) {
  final openInPane = SettingsPaneScope.maybeOf(context);
  if (openInPane != null) {
    openInPane(const AiPane());
  } else {
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const AiSettingsScreen()));
  }
}

/// Opens the tier page or 「拿一首歌试试」 from the AI page: in the same pane
/// on a wide window (back returns to the AI page), else pushed.
void openAiSubPage(BuildContext context, SettingsPane pane) {
  final openInPane = SettingsPaneScope.maybeOf(context);
  if (openInPane != null) {
    openInPane(pane);
    return;
  }
  final page = switch (pane) {
    AiTryPane() => const AiTryScreen(),
    _ => const AiTiersScreen(),
  };
  Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => page));
}
