/// Settings › Help and about: the app's version and the Open source licenses
/// page (Android OpenSourceLicensesPage.kt), built on Flutter's
/// [LicenseRegistry] (what LicensePage shows) in Settings' own rows.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../ui/component/yoin_loading_indicator.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'license_ofl.dart';
import 'license_packages.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// The app's version name. Keep equal to pubspec.yaml's `version` (the build
/// writes that into the HAP; a test checks the two agree).
const yoinVersionName = '0.1.0';

/// One acknowledged component: its name, a line to know it by (its
/// copyright, when the text starts with one) and its license text.
@immutable
final class OssComponent {
  const OssComponent({required this.name, required this.summary, required this.text});

  final String name;
  final String? summary;
  final String text;
}

/// The font first (Android lists it first too), then every package the
/// registry knows that ships in the app, by name — the build's dev-only
/// tooling ([notShipped], default [notShippedDartPackages]) is left out.
/// Packages with several license texts keep them together, one after the other.
Future<List<OssComponent>> loadOssComponents({
  Stream<LicenseEntry>? entries,
  Set<String> notShipped = notShippedDartPackages,
}) async {
  final texts = <String, List<String>>{};
  await for (final entry in entries ?? LicenseRegistry.licenses) {
    final packages = entry.packages.where((p) => !notShipped.contains(p)).toList();
    if (packages.isEmpty) continue;
    final text = entry.paragraphs.map((p) => '${'  ' * p.indent.clamp(0, 8)}${p.text}').join('\n\n');
    for (final package in packages) {
      texts.putIfAbsent(package, () => []).add(text);
    }
  }
  final names = texts.keys.toList()..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
  return [
    OssComponent(name: 'Google Sans Flex', summary: _firstLine(googleSansFlexLicense), text: googleSansFlexLicense.trim()),
    for (final name in names)
      OssComponent(name: name, summary: _firstLine(texts[name]!.first), text: texts[name]!.join('\n\n\n')),
  ];
}

String? _firstLine(String text) {
  final line = text.trim().split('\n').first.trim();
  return line.isEmpty ? null : line;
}

final ossComponentsProvider = FutureProvider.autoDispose<List<OssComponent>>((ref) => loadOssComponents());

/// The licenses page: a word of thanks, then each component; a tap shows its
/// license in full on a page of its own.
class LicensesScreen extends ConsumerWidget {
  const LicensesScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final components = ref.watch(ossComponentsProvider);
    return SettingsSubPage(
      title: strings.aboutLicenses,
      inPane: inPane,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            SettingsMetrics.labelInset,
            0,
            SettingsMetrics.labelInset,
            SettingsMetrics.introBottom,
          ),
          child: Text(
            strings.licensesIntro,
            style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
        switch (components) {
          AsyncData(:final value) => SettingsSegments(
              children: [
                for (final component in value)
                  SettingsItem(
                    key: ValueKey(component.name),
                    icon: YoinSymbols.code,
                    title: component.name,
                    summary: component.summary,
                    onTap: () => Navigator.of(context)
                        .push(YoinPushRoute<void>(builder: (_) => LicenseTextScreen(component: component))),
                  ),
              ],
            ),
          AsyncError() => const SizedBox.shrink(),
          _ => const Center(child: YoinLoadingIndicator()),
        },
      ],
    );
  }
}

/// One license in full: the name as the page title, the text under it.
class LicenseTextScreen extends StatelessWidget {
  const LicenseTextScreen({super.key, required this.component});

  final OssComponent component;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SettingsSubPage(
      title: component.name,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            SettingsMetrics.labelInset,
            SettingsMetrics.licenseTextTop,
            SettingsMetrics.labelInset,
            0,
          ),
          child: SelectableText(
            component.text,
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}
