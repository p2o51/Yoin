import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../shell/bar_suppression.dart';
import '../theme/tokens.dart';
import 'account_identity.dart';
import 'settings_metrics.dart';
import 'settings_strings.dart';

/// The services an account can be added for (Android SetupService, as far as
/// HarmonyOS goes: Spotify has no setup here).
enum AddAccountService { subsonic, appleMusic }

/// Add an account (Android SettingsScreen AddAccountSheet): just the choice,
/// each service's name and one short line. What a service can do is told on
/// its own page, after the user shows interest by tapping it. Files on this
/// device are listed as coming later. Null when dismissed.
Future<AddAccountService?> showAddAccountSheet(BuildContext context) {
  YoinHaptics.click();
  return showModalBottomSheet<AddAccountService>(
    context: context,
    useSafeArea: false,
    isScrollControlled: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    builder: (_) => const ChromeBarSuppression(child: AddAccountSheet()),
  );
}

class AddAccountSheet extends StatelessWidget {
  const AddAccountSheet({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    void pick(AddAccountService service) => Navigator.of(context).pop(service);
    return SingleChildScrollView(
      padding: EdgeInsets.only(bottom: SettingsMetrics.addSheetBottom + MediaQuery.paddingOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              SettingsMetrics.addSheetTitleSide,
              0,
              SettingsMetrics.addSheetTitleSide,
              SettingsMetrics.addSheetTitleBottom,
            ),
            child: Text(
              strings.addSheetTitle,
              style: theme.textTheme.headlineSmall!.copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
          ServiceChoiceRow(
            identity: ServiceIdentity.subsonic,
            name: strings.serviceNameSubsonic,
            summary: strings.providerSubsonicSummary,
            onTap: () => pick(AddAccountService.subsonic),
          ),
          ServiceChoiceRow(
            identity: ServiceIdentity.appleMusic,
            name: strings.serviceNameApple,
            summary: strings.providerAppleSummary,
            badge: strings.providerBadgePreview,
            onTap: () => pick(AddAccountService.appleMusic),
          ),
          ServiceChoiceRow(
            identity: ServiceIdentity.local,
            name: strings.serviceNameLocal,
            summary: strings.providerLocalSummary,
            onTap: null,
          ),
        ],
      ),
    );
  }
}

/// One service: its mark on its tone, its name (and a badge), one line, and a
/// chevron while it can be picked. [onTap] null: listed, not offered yet.
class ServiceChoiceRow extends StatelessWidget {
  const ServiceChoiceRow({
    super.key,
    required this.identity,
    required this.name,
    required this.summary,
    required this.onTap,
    this.badge,
  });

  final ServiceIdentity identity;
  final String name;
  final String summary;
  final VoidCallback? onTap;
  final String? badge;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final tone = identity.hue.tone(theme.brightness);
    final enabled = onTap != null;
    final row = Padding(
      padding: const EdgeInsets.all(SettingsMetrics.choiceRowPadding),
      child: Row(
        spacing: SettingsMetrics.choiceRowGap,
        children: [
          DecoratedBox(
            decoration: BoxDecoration(color: tone.iconContainer, shape: BoxShape.circle),
            child: SizedBox.square(
              dimension: SettingsMetrics.choiceIconBox,
              child: ServiceGlyphIcon(identity.glyph, size: SettingsMetrics.choiceIcon, color: tone.iconContent),
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              spacing: SettingsMetrics.rowTextGap,
              children: [
                Row(
                  spacing: SettingsMetrics.choiceBadgeGap,
                  children: [
                    Flexible(
                      child: Text(name, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
                    ),
                    if (badge != null)
                      DecoratedBox(
                        decoration: ShapeDecoration(color: colors.tertiaryContainer, shape: const StadiumBorder()),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: SettingsMetrics.choiceBadgeH,
                            vertical: SettingsMetrics.choiceBadgeV,
                          ),
                          child: Text(
                            badge!,
                            style: theme.textTheme.labelSmall!.copyWith(color: colors.onTertiaryContainer),
                          ),
                        ),
                      ),
                  ],
                ),
                Text(summary, style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
              ],
            ),
          ),
          if (enabled) Icon(YoinSymbols.chevronRight, color: colors.onSurfaceVariant),
        ],
      ),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.choiceRowInset),
      child: Opacity(
        opacity: enabled ? 1 : SettingsMetrics.choiceDisabledAlpha,
        child: Material(
          type: MaterialType.transparency,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap == null
                ? null
                : () {
                    YoinHaptics.click();
                    onTap!();
                  },
            child: Semantics(button: enabled, enabled: enabled, child: row),
          ),
        ),
      ),
    );
  }
}
