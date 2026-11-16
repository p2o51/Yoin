import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_pane.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// Settings › Features › AI features (Android GeminiItem): the service and
/// tier in use, or what it's for until a key is set; opens [AiSettingsScreen]
/// (in the right pane on a wide window).
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
    return SettingsItem(
      icon: YoinSymbols.sparkle,
      title: strings.aiTitle,
      summary: ai.isReady ? null : strings.aiSummaryEmpty,
      summaryContent: ai.isReady
          ? MetaLine(
              groups: [MetaPlain(strings.aiProviderGemini), MetaMuted(strings.aiTier(ai.tier))],
              style: Theme.of(context).textTheme.bodyMedium,
              color: colors.onSurfaceVariant,
            )
          : null,
      trailing: trailing,
      onTap: onOpen ?? () => Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const AiSettingsScreen())),
    );
  }
}

/// Settings › AI features (Android AiSettingsPage, Gemini only on
/// HarmonyOS): the two tiers, the key, and the language answers and
/// translations come in. Every change is kept at once.
class AiSettingsScreen extends ConsumerStatefulWidget {
  const AiSettingsScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  ConsumerState<AiSettingsScreen> createState() => _AiSettingsScreenState();
}

class _AiSettingsScreenState extends ConsumerState<AiSettingsScreen> {
  final _key = TextEditingController();
  bool _seeded = false;

  @override
  void initState() {
    super.initState();
    _key.addListener(_onKey);
  }

  @override
  void dispose() {
    _key.dispose();
    super.dispose();
  }

  void _onKey() {
    if (!_seeded) return;
    ref.read(aiSettingsProvider.notifier).setGeminiKey(_key.text);
  }

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final ai = ref.watch(aiSettingsProvider).value;
    if (ai != null && !_seeded) {
      _key.text = ai.geminiKey;
      _seeded = true;
    }
    final settings = ai ?? const AiSettings();
    final vm = ref.read(aiSettingsProvider.notifier);
    return SettingsSubPage(
      title: strings.aiTitlePage,
      inPane: widget.inPane,
      children: [
        SettingsGroupLabel(title: strings.aiTiersLabel),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        Row(
          children: [
            for (final tier in AiTier.values) ...[
              if (tier != AiTier.values.first) const SizedBox(width: SettingsMetrics.aiTierGap),
              Expanded(
                child: AiTierCard(
                  key: ValueKey('ai-tier-${tier.name}'),
                  label: strings.aiTier(tier),
                  selected: settings.tier == tier,
                  onTap: () {
                    YoinHaptics.segmentTick();
                    vm.setTier(tier);
                  },
                ),
              ),
            ],
          ],
        ),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        ExpressiveTextField(
          key: const ValueKey('ai-gemini-key'),
          controller: _key,
          label: strings.aiKeyLabel,
          placeholder: 'AIza…',
          obscureText: true,
          textInputAction: TextInputAction.done,
        ),
        const SizedBox(height: SettingsMetrics.aiBlockGap),
        AiLanguageButton(language: settings.targetLanguage, onPick: vm.setTargetLanguage),
      ],
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
