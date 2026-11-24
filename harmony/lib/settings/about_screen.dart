/// Settings › Help and about › Yoin (Android SettingsScreen.kt AboutYoinItem /
/// AboutYoinPageContent, owner 2026-11-09, plan A): one row with the
/// version, opening a page with the app's version and its links — the website
/// and the privacy policy. The rows that open a page in the browser
/// ([ExternalPageItem]) are also Help and about's Docs and Send feedback.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../landing/landing_host.dart';
import '../landing/landing_strings.dart';
import '../platform/browser/url_opener.dart';
import '../shell/app_messages.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'appearance/appearance_strings.dart';
import 'export_log.dart';
import 'licenses.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_pane.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'tips.dart';
import 'yoin_links.dart';

/// Settings › Help and about › Help and feedback (v4): Tips, Welcome guide, and
/// Docs above; Send feedback and Export log below.
class HelpAndFeedbackScreen extends ConsumerWidget {
  const HelpAndFeedbackScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final read = ref.watch(tipReadProvider);
    final language = Localizations.maybeLocaleOf(context)?.languageCode ?? 'en';
    final openInPane = SettingsPaneScope.maybeOf(context);
    final chevron = openInPane != null
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsSubPage(
      title: strings.helpFeedbackTitle,
      inPane: inPane,
      children: [
        SettingsSegments(
          children: [
            SettingsItem(
              icon: YoinSymbols.sparkle,
              title: strings.aboutTips,
              summary: tipsReadSummary(strings, read),
              trailing: chevron,
              onTap: () {
                if (openInPane != null) {
                  openInPane(const TipsPane());
                } else {
                  Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const TipsScreen()));
                }
              },
            ),
            SettingsItem(
              icon: YoinSymbols.refresh,
              title: LandingStrings.of(context).welcomeGuide,
              summary: AppearanceStrings.of(context).welcomeGuideSummary,
              onTap: () => openWelcomeGuide(context, ref),
            ),
            ExternalPageItem(
              icon: YoinSymbols.note,
              title: strings.aboutDocs,
              url: YoinLinks.docsFor(language),
              openFailed: strings.aboutDocsOpenFailed,
            ),
          ],
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        SettingsSegments(
          children: [
            ExternalPageItem(
              icon: YoinSymbols.send,
              title: strings.aboutFeedback,
              url: YoinLinks.feedbackForm,
              openFailed: strings.aboutFeedbackOpenFailed,
            ),
            const ExportLogItem(),
          ],
        ),
      ],
    );
  }
}

/// Settings › Help and about › About Yoin (v4): single 4-row group — version,
/// website, privacy policy, and open source licenses.
class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final language = Localizations.maybeLocaleOf(context)?.languageCode ?? 'en';
    final openInPane = SettingsPaneScope.maybeOf(context);
    final chevron = openInPane != null
        ? Icon(YoinSymbols.chevronRight, color: Theme.of(context).colorScheme.onSurfaceVariant)
        : null;
    return SettingsSubPage(
      title: strings.aboutYoinTitle,
      inPane: inPane,
      children: [
        SettingsSegments(
          children: [
            SettingsItem(icon: YoinSymbols.info, title: strings.aboutVersion(yoinVersionName)),
            ExternalPageItem(
              icon: YoinSymbols.launch,
              title: strings.aboutWebsite,
              url: YoinLinks.websiteFor(language),
              openFailed: strings.aboutWebsiteOpenFailed,
            ),
            ExternalPageItem(
              icon: YoinSymbols.launch,
              title: strings.aboutPrivacyPolicy,
              url: YoinLinks.privacyPolicy,
              openFailed: strings.aboutPrivacyOpenFailed,
            ),
            SettingsItem(
              icon: YoinSymbols.code,
              title: strings.aboutLicenses,
              trailing: chevron,
              onTap: () {
                if (openInPane != null) {
                  openInPane(const LicensesPane());
                } else {
                  Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const LicensesScreen()));
                }
              },
            ),
          ],
        ),
      ],
    );
  }
}

/// A row that opens one of [YoinLinks] in the browser; [openFailed] on the
/// window's snackbar when nothing can open it.
class ExternalPageItem extends ConsumerWidget {
  const ExternalPageItem({
    super.key,
    required this.icon,
    required this.title,
    required this.url,
    required this.openFailed,
  });

  final IconData icon;
  final String title;
  final String url;
  final String openFailed;

  @override
  Widget build(BuildContext context, WidgetRef ref) => SettingsItem(
    icon: icon,
    title: title,
    onTap: () async {
      final opened = await ref.read(urlOpenerProvider).open(Uri.parse(url));
      if (!opened && context.mounted) showAppMessage(context, openFailed);
    },
  );
}
