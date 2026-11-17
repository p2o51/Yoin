/// Settings › Help and about › Yoin (Android SettingsScreen.kt AboutYoinItem /
/// AboutYoinPageContent, owner 2026-11-09, plan A): one row with the
/// version, opening a page with the app's version and its links — the website
/// and the privacy policy. The rows that open a page in the browser
/// ([ExternalPageItem]) are also Help and about's Docs and Send feedback.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../platform/browser/url_opener.dart';
import '../shell/app_messages.dart';
import 'licenses.dart' show yoinVersionName;
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'yoin_links.dart';

/// The Yoin page: the app and its version, then its links.
class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final language = Localizations.maybeLocaleOf(context)?.languageCode ?? 'en';
    return SettingsSubPage(
      title: strings.aboutName,
      inPane: inPane,
      children: [
        SettingsSegments(
          children: [
            // The headline already says Yoin: the row is just the version.
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
