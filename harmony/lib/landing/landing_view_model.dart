import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../home/home_section.dart';
import '../settings/settings_view_model.dart';
import '../ui/component/seam/seam_style.dart';
import 'landing_models.dart';
import 'landing_store.dart';

/// One landing on screen: its mode and a serial, so a re-run starts from a
/// fresh state machine (Android keys the landing's ViewModel store by session).
@immutable
final class LandingSession {
  const LandingSession(this.mode, this.serial);

  final LandingMode mode;
  final int serial;

  @override
  bool operator ==(Object other) => other is LandingSession && other.mode == mode && other.serial == serial;

  @override
  int get hashCode => Object.hash(mode, serial);
}

/// Where the landing reads and writes a profile's Home layout: the same rows
/// Home's edit mode writes ([HomeLayoutNotifier.keyFor]).
abstract interface class LandingLayoutGateway {
  Future<HomeLayout?> read(String provider, String profileId);
  Future<void> write(String provider, String profileId, HomeLayout layout);
  Future<void> clear(String provider, String profileId);
}

final class KeyValueLandingLayoutGateway implements LandingLayoutGateway {
  const KeyValueLandingLayoutGateway(this._ref);

  final Ref _ref;

  @override
  Future<HomeLayout?> read(String provider, String profileId) async {
    final raw = await _ref.read(keyValueStoreProvider).read(HomeLayoutNotifier.keyFor(provider, profileId));
    if (raw == null) return null;
    return HomeLayout.fromJson((jsonDecode(raw) as Map).cast<String, Object?>());
  }

  @override
  Future<void> write(String provider, String profileId, HomeLayout layout) => _ref
      .read(keyValueStoreProvider)
      .write(HomeLayoutNotifier.keyFor(provider, profileId), jsonEncode(layout.toJson()));

  @override
  Future<void> clear(String provider, String profileId) =>
      _ref.read(keyValueStoreProvider).write(HomeLayoutNotifier.keyFor(provider, profileId), null);
}

final landingLayoutGatewayProvider = Provider<LandingLayoutGateway>((ref) => KeyValueLandingLayoutGateway(ref));

/// Whether [layout] is the catalogue's default (nothing to write).
bool isDefaultHomeLayout(HomeLayout layout) => listEquals(layout.sections, HomeLayout.defaultLayout.sections);

/// The sections the Home step lists: the known ones this build can draw, in
/// layout order (Android leaves out 「歌词一句」 without Gemini Nano the same
/// way: hidden from the list, kept in the layout).
List<HomeSectionPref> landingShownSections(HomeLayout layout) => [
  for (final pref in layout.sections)
    if (pref.section?.availableInV1 ?? false) pref,
];

/// The landing's state machine (Android LandingViewModel): which step, what
/// was picked and connected, and the Home layout. Accounts are added by the
/// existing setup view models; this only learns about them from the account
/// list (any id that was not there when the landing opened is "ours").
class LandingViewModel extends Notifier<LandingUiState> {
  LandingViewModel(this.session);

  final LandingSession session;

  Set<String>? _profilesAtStart;
  final _created = <String>{};
  final _providers = <String, String>{};
  String? _activeId;
  bool _finishStarted = false;

  /// The Home step was edited: a late read of the stored layout must not undo it.
  bool _layoutEdited = false;

  @override
  LandingUiState build() {
    ref.listen(settingsViewModelProvider, (_, next) => _onAccounts(next));
    var initial = LandingUiState.initial(session.mode);
    final current = ref.read(settingsViewModelProvider);
    if (current is SettingsContent) {
      _absorb(current);
      initial = initial.rebuilt(hasAccount: current.accounts.isNotEmpty);
    }
    if (session.mode == LandingMode.rerun) _loadActiveLayout();
    return initial;
  }

  /// Accounts there before this landing opened: any other one was added here. Null until the list first loads.
  Set<String>? get profilesAtStart => _profilesAtStart;

  /// Accounts added during this landing, oldest first.
  Set<String> get createdProfileIds => Set.unmodifiable(_created);

  void _absorb(SettingsContent content) {
    final ids = [for (final a in content.accounts) a.id];
    final start = _profilesAtStart ??= ids.toSet();
    _created.addAll(ids.where((id) => !start.contains(id)));
    for (final a in content.accounts) {
      _providers[a.id] = a.provider;
    }
    _activeId = content.activeId;
  }

  void _onAccounts(SettingsUiState next) {
    if (next is! SettingsContent) return;
    _absorb(next);
    state = state.rebuilt(hasAccount: next.accounts.isNotEmpty);
  }

  Future<void> _loadActiveLayout() async {
    final profile = await ref.read(profileRepositoryProvider).activeProfile();
    if (!ref.mounted || profile == null) return;
    HomeLayout? stored;
    try {
      stored = await ref.read(landingLayoutGatewayProvider).read(profile.provider, profile.id);
    } catch (_) {
      stored = null;
    }
    if (!ref.mounted || stored == null || _layoutEdited) return;
    state = state.copyWith(layout: HomeLayout.reconcile(stored.sections));
  }

  void next() {
    if (state.index < state.steps.length - 1) state = state.copyWith(index: state.index + 1);
  }

  void back() {
    if (state.index > 0) state = state.copyWith(index: state.index - 1);
  }

  /// Jumps straight to [step] if it is in the current list.
  void goTo(LandingStep step) {
    final i = state.steps.indexOf(step);
    if (i >= 0) state = state.copyWith(index: i);
  }

  void toggleService(LandingService service) {
    final picked = {...state.picked};
    if (!picked.remove(service)) picked.add(service);
    state = state.copyWith(picked: picked).rebuilt(hasAccount: state.hasAccount);
  }

  void onConnected(LandingService service) {
    if (state.connected.contains(service)) return;
    state = state.copyWith(connected: {...state.connected, service});
  }

  /// The edge step's pick: applied and stored at once, like Settings › Scroll edge.
  void setEdge(SeamTopStyle style) => ref.read(seamTopStyleProvider.notifier).select(style);

  void setSectionEnabled(HomeSection section, bool enabled) {
    _layoutEdited = true;
    state = state.copyWith(layout: withSectionEnabled(state.layout, section, enabled));
  }

  /// Reorders the full list, hidden sections included (the landing shows the ones this build draws).
  void moveSection(int from, int to) {
    final list = [...state.layout.sections];
    if (from < 0 || to < 0 || from >= list.length || to >= list.length || from == to) return;
    list.insert(to, list.removeAt(from));
    _layoutEdited = true;
    state = state.copyWith(layout: HomeLayout(list));
  }

  /// Moves a row of the Home step: [from] and [to] index [landingShownSections].
  void moveShownSection(int from, int to) {
    final shown = landingShownSections(state.layout);
    if (from < 0 || to < 0 || from >= shown.length || to >= shown.length) return;
    final sections = state.layout.sections;
    moveSection(sections.indexOf(shown[from]), sections.indexOf(shown[to]));
  }

  /// "Open Yoin": the layout goes to every account added here (and, on a
  /// re-run, to the active one), the landing is marked seen. The returned
  /// future completes when the hand-off may start — Home already shows the
  /// chosen layout. A second call while one runs does nothing (returns false).
  Future<bool> finish() async {
    if (_finishStarted || state.finishing) return false;
    _finishStarted = true;
    state = state.copyWith(finishing: true);
    final layout = state.layout;
    final targets = <String>{..._created, if (session.mode == LandingMode.rerun && _activeId != null) _activeId!};
    final gateway = ref.read(landingLayoutGatewayProvider);
    final isDefault = isDefaultHomeLayout(layout);
    for (final id in targets) {
      final provider = _providers[id];
      if (provider == null) continue;
      try {
        isDefault ? await gateway.clear(provider, id) : await gateway.write(provider, id, layout);
      } catch (_) {
        // Home keeps its default; the landing still closes.
      }
    }
    try {
      await ref.read(landingStoreProvider).markDone();
    } catch (_) {
      // Not saved: the landing may show again next launch, nothing else breaks.
    }
    if (!ref.mounted) return true;
    // The account in use gets its layout now, so Home's first frame behind the hand-off is the chosen one.
    if (targets.contains(_activeId)) {
      if (isDefault) {
        ref.invalidate(homeLayoutProvider);
      } else {
        ref.read(homeLayoutProvider.notifier).set(layout);
      }
    }
    return true;
  }
}

final landingViewModelProvider = NotifierProvider.autoDispose.family<LandingViewModel, LandingUiState, LandingSession>(
  LandingViewModel.new,
);
