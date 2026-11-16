import 'package:flutter/widgets.dart';

/// What the wide window's right pane shows (SettingsTablet): an account's
/// page, a page it opens (Streaming quality, Volume leveling), or one of the
/// Help and about pages.
sealed class SettingsPane {
  const SettingsPane();

  /// The page this one was opened from inside the pane: back (the pane
  /// page's own back button, and system back) returns there. Null for the
  /// pages the list opens directly.
  SettingsPane? get parent => switch (this) {
    StreamQualityPane(:final profileId) ||
    DownloadsPane(:final profileId) ||
    VolumeLevelingPane(:final profileId) => AccountPane(profileId),
    _ => null,
  };
}

final class AccountPane extends SettingsPane {
  const AccountPane(this.profileId);
  final String profileId;

  @override
  bool operator ==(Object other) => other is AccountPane && other.profileId == profileId;
  @override
  int get hashCode => profileId.hashCode;
}

/// An account's Streaming quality page (StreamQualityPage.kt), opened from
/// that account's page.
final class StreamQualityPane extends SettingsPane {
  const StreamQualityPane(this.profileId);
  final String profileId;

  @override
  bool operator ==(Object other) => other is StreamQualityPane && other.profileId == profileId;
  @override
  int get hashCode => Object.hash(3, profileId);
}

/// The account in use's Downloads page (DownloadsPage.kt), opened from that
/// account's page.
final class DownloadsPane extends SettingsPane {
  const DownloadsPane(this.profileId);
  final String profileId;

  @override
  bool operator ==(Object other) => other is DownloadsPane && other.profileId == profileId;
  @override
  int get hashCode => Object.hash(4, profileId);
}

/// Volume leveling (VolumeLevelingPage.kt), opened from a Subsonic account's
/// page. The choice is the device's, not the account's; [profileId] is the
/// page back returns to.
final class VolumeLevelingPane extends SettingsPane {
  const VolumeLevelingPane(this.profileId);
  final String profileId;

  @override
  bool operator ==(Object other) => other is VolumeLevelingPane && other.profileId == profileId;
  @override
  int get hashCode => Object.hash(5, profileId);
}

/// Settings › Cloud sync (CloudSyncScreen).
final class CloudSyncPane extends SettingsPane {
  const CloudSyncPane();
  @override
  bool operator ==(Object other) => other is CloudSyncPane;
  @override
  int get hashCode => 6;
}

/// Settings › AI features (the Gemini key, tier and answer language).
final class AiPane extends SettingsPane {
  const AiPane();
  @override
  bool operator ==(Object other) => other is AiPane;
  @override
  int get hashCode => 7;
}

final class TipsPane extends SettingsPane {
  const TipsPane();
  @override
  bool operator ==(Object other) => other is TipsPane;
  @override
  int get hashCode => 1;
}

/// Settings › Help and about › Yoin: the version, the website and the privacy policy.
final class AboutPane extends SettingsPane {
  const AboutPane();
  @override
  bool operator ==(Object other) => other is AboutPane;
  @override
  int get hashCode => 8;
}

final class LicensesPane extends SettingsPane {
  const LicensesPane();
  @override
  bool operator ==(Object other) => other is LicensesPane;
  @override
  int get hashCode => 2;
}

/// Opens [pane]'s page: in the right pane on a wide window, else pushed.
typedef OpenSettingsPane = void Function(SettingsPane pane);

/// The list-detail's right pane, for the pages inside it: a page there opens
/// the next one ([open]) in the same pane instead of pushing it over the
/// whole window. Absent (null from [maybeOf]) when the page is pushed.
class SettingsPaneScope extends InheritedWidget {
  const SettingsPaneScope({super.key, required this.open, required super.child});

  final OpenSettingsPane open;

  static OpenSettingsPane? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SettingsPaneScope>()?.open;

  @override
  bool updateShouldNotify(SettingsPaneScope oldWidget) => oldWidget.open != open;
}
