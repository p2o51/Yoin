/// Opening a web page in the system browser (Android's ACTION_VIEW /
/// LocalUriHandler): Settings › Help and about's links and "Open in Apple
/// Music". It rides the seam cloud sync already uses for Google's sign-in
/// page ([CloudSyncHost.openBrowser]: HarmonyOS a view-data Want, Windows the
/// shell), so no second native path exists.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../sync/cloud_sync_host.dart';

abstract interface class UrlOpener {
  /// Opens [url] in the system browser (or the app that owns its links).
  /// False when nothing could open it.
  Future<bool> open(Uri url);
}

/// [UrlOpener] over the cloud-sync host's browser hand-off.
final class BrowserUrlOpener implements UrlOpener {
  const BrowserUrlOpener(this._host);

  final CloudSyncHost _host;

  @override
  Future<bool> open(Uri url) async {
    if (!url.hasScheme || !(url.isScheme('https') || url.isScheme('http'))) return false;
    try {
      return await _host.openBrowser(url);
    } on Object {
      return false;
    }
  }
}

/// The browser hand-off; main.dart's platform bundle overrides it, tests use a fake.
final urlOpenerProvider = Provider<UrlOpener>((ref) => const BrowserUrlOpener(InertCloudSyncHost()));
