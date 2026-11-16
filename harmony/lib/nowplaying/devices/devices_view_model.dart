/// The Devices sheet's state (Android NowPlayingViewModel devicesState /
/// refreshDevices / selectDevice, DevicesSheet): this device, the system's
/// cast panel and the DLNA speakers on the LAN, which one plays, and what
/// went wrong. Reads and drives the [PlaybackRouter].
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/model/model.dart';
import '../../playback/output/dlna_manager.dart';
import '../../playback/output/dlna_protocol.dart';
import '../../playback/output/output_providers.dart';
import '../../playback/output/playback_router.dart';

enum DeviceKind { local, systemCast, dlna }

/// A row's second line (Android stores keys on YoinDevice.statusText; the sheet words them).
enum DeviceStatus { none, switchBack, casting, notCasting, dlna }

@immutable
final class YoinDevice {
  const YoinDevice({
    required this.id,
    required this.kind,
    required this.name,
    required this.isActive,
    this.isSelectable = true,
    this.status = DeviceStatus.none,
  });

  static const localId = 'local';
  static const systemCastId = 'system-cast';

  final String id;
  final DeviceKind kind;

  /// The device's own name; empty for this device and the idle cast row (the sheet words those).
  final String name;
  final bool isActive;
  final bool isSelectable;
  final DeviceStatus status;

  @override
  bool operator ==(Object other) =>
      other is YoinDevice &&
      other.id == id &&
      other.kind == kind &&
      other.name == name &&
      other.isActive == isActive &&
      other.isSelectable == isSelectable &&
      other.status == status;

  @override
  int get hashCode => Object.hash(id, kind, name, isActive, isSelectable, status);
}

/// What the sheet says went wrong.
enum DevicesError { discovery, group, stopUnconfirmed, playback, subsonicOnly, playFirst, couldntSwitch }

@immutable
final class DevicesSheetState {
  const DevicesSheetState({
    this.providerId,
    this.devices = const [],
    this.loading = false,
    this.busyDeviceId,
    this.error,
  });

  final String? providerId;
  final List<YoinDevice> devices;
  final bool loading;
  final String? busyDeviceId;
  final DevicesError? error;

  bool get isSubsonic => providerId == MediaId.providerSubsonic;

  DevicesSheetState copyWith({
    String? providerId,
    List<YoinDevice>? devices,
    bool? loading,
    String? busyDeviceId,
    bool clearBusy = false,
    DevicesError? error,
    bool clearError = false,
  }) => DevicesSheetState(
    providerId: providerId ?? this.providerId,
    devices: devices ?? this.devices,
    loading: loading ?? this.loading,
    busyDeviceId: clearBusy ? null : (busyDeviceId ?? this.busyDeviceId),
    error: clearError ? null : (error ?? this.error),
  );

  @override
  bool operator ==(Object other) =>
      other is DevicesSheetState &&
      other.providerId == providerId &&
      listEquals(other.devices, devices) &&
      other.loading == loading &&
      other.busyDeviceId == busyDeviceId &&
      other.error == error;

  @override
  int get hashCode => Object.hash(providerId, Object.hashAll(devices), loading, busyDeviceId, error);
}

DevicesError devicesErrorOf(DlnaError error) => switch (error) {
  DlnaError.discovery => DevicesError.discovery,
  DlnaError.group => DevicesError.group,
  DlnaError.stopUnconfirmed => DevicesError.stopUnconfirmed,
  DlnaError.playback => DevicesError.playback,
};

/// The rows for [providerId] (Android buildDevices + fallbackDevices): this
/// device; the system cast row where the platform has one; for Subsonic the
/// DLNA speakers found and the one in use.
List<YoinDevice> buildDevices({
  required String? providerId,
  required OutputRoute route,
  required bool hasSystemPicker,
  String? systemCastName,
  List<DlnaDevice> dlnaDevices = const [],
}) {
  final onDlna = route is DlnaOutput ? route.device : null;
  final onSystemCast = route is SystemCastOutput;
  final castName = systemCastName ?? (route is SystemCastOutput ? route.name : null);
  final rows = <YoinDevice>[
    YoinDevice(
      id: YoinDevice.localId,
      kind: DeviceKind.local,
      name: '',
      isActive: route is LocalOutput,
      // From a speaker the phone takes over again; the cast device lets go in the system panel.
      isSelectable: onDlna != null,
      status: route is LocalOutput ? DeviceStatus.none : DeviceStatus.switchBack,
    ),
    if (hasSystemPicker)
      YoinDevice(
        id: YoinDevice.systemCastId,
        kind: DeviceKind.systemCast,
        name: castName ?? '',
        isActive: onSystemCast,
        isSelectable: onDlna == null,
        status: onSystemCast ? DeviceStatus.casting : DeviceStatus.notCasting,
      ),
  ];
  if (providerId == MediaId.providerSubsonic) {
    final seen = <String>{};
    for (final renderer in [...dlnaDevices, ?onDlna]) {
      if (!seen.add(renderer.id)) continue;
      final active = renderer.id == onDlna?.id;
      rows.add(
        YoinDevice(
          id: renderer.id,
          kind: DeviceKind.dlna,
          name: renderer.name,
          isActive: active,
          isSelectable: !onSystemCast,
          status: active ? DeviceStatus.casting : DeviceStatus.dlna,
        ),
      );
    }
  }
  return rows;
}

/// Keeps rows where they were across a refresh (Android mergeDevicesForStableRows):
/// known rows keep their place with the new data, new rows go at the end, gone rows leave.
List<YoinDevice> mergeDevicesForStableRows(List<YoinDevice> previous, List<YoinDevice> next) {
  if (previous.isEmpty) return next;
  if (next.isEmpty) return previous;
  final nextById = {for (final row in next) row.id: row};
  final previousIds = {for (final row in previous) row.id};
  return [
    for (final row in previous) ?nextById[row.id],
    for (final row in next)
      if (!previousIds.contains(row.id)) row,
  ];
}

class DevicesViewModel extends Notifier<DevicesSheetState> {
  PlaybackRouter? get _router => ref.read(playbackRouterProvider);
  late List<Listenable> _watched;

  @override
  DevicesSheetState build() {
    final router = ref.watch(playbackRouterProvider);
    // Read, not watched: [refresh] reads the account afresh (a rebuild would drop a search in flight).
    final source = ref.read(activeSourceProvider).value;
    _watched = router == null
        ? const []
        : [
            router.route,
            router.systemOutput,
            router.castFailure,
            router.dlna.devices,
            router.dlna.connectedDevice,
            router.dlna.error,
            router.dlna.isDiscovering,
          ];
    for (final l in _watched) {
      l.addListener(_onRouterChanged);
    }
    ref.onDispose(() {
      for (final l in _watched) {
        l.removeListener(_onRouterChanged);
      }
    });
    return _derive(DevicesSheetState(providerId: source?.provider));
  }

  /// The rows and the renderer's failure as the router has them now.
  DevicesSheetState _derive(DevicesSheetState base) {
    final router = _router;
    final route = router?.route.value ?? const LocalOutput();
    final output = router?.systemOutput.value;
    final next = buildDevices(
      providerId: base.providerId,
      route: route,
      hasSystemPicker: router?.castHost.hasPicker ?? false,
      systemCastName: output != null && output.isRemote && output.name.isNotEmpty ? output.name : null,
      dlnaDevices: router?.dlna.devices.value ?? const [],
    );
    final dlnaError = base.isSubsonic ? router?.dlna.error.value : null;
    final castFailed = router?.castFailure.value != null && route is SystemCastOutput;
    final rendererError = dlnaError != null
        ? devicesErrorOf(dlnaError)
        : castFailed
        ? DevicesError.playback
        : null;
    return base.copyWith(
      devices: mergeDevicesForStableRows(base.devices, next),
      loading: base.loading || (router?.dlna.isDiscovering.value ?? false),
      error: rendererError ?? base.error,
    );
  }

  void _onRouterChanged() {
    if (!ref.mounted) return;
    final router = _router;
    final discovering = router?.dlna.isDiscovering.value ?? false;
    // A renderer's own failure replaces (or clears) what the sheet shows.
    final cleared =
        state.error == DevicesError.discovery ||
        state.error == DevicesError.group ||
        state.error == DevicesError.stopUnconfirmed ||
        state.error == DevicesError.playback;
    state = _derive(state.copyWith(loading: discovering, clearError: cleared));
  }

  /// Opening the sheet, the refresh button and a pull: search the LAN again (Subsonic).
  Future<void> refresh({bool showLoading = true}) async {
    final router = _router;
    final providerId = (await ref.read(activeSourceProvider.future))?.provider;
    if (!ref.mounted) return;
    state = _derive(
      DevicesSheetState(
        providerId: providerId,
        devices: state.providerId == providerId ? state.devices : const [],
        loading: showLoading && providerId == MediaId.providerSubsonic && router != null,
        busyDeviceId: state.busyDeviceId,
      ),
    );
    if (showLoading && providerId == MediaId.providerSubsonic && router != null) {
      await router.dlna.discover();
      if (!ref.mounted) return;
      state = _derive(state.copyWith(loading: false));
    }
  }

  /// Moves playback to [device] (Android selectDevice). The cast row opens
  /// the system's own panel; the switch itself happens there.
  Future<void> select(YoinDevice device) async {
    final router = _router;
    if (router == null || !device.isSelectable || state.busyDeviceId != null) return;
    if (device.kind == DeviceKind.systemCast) {
      final opened = await router.openSystemPicker();
      if (!opened && ref.mounted) state = state.copyWith(error: DevicesError.couldntSwitch);
      return;
    }
    state = state.copyWith(busyDeviceId: device.id, clearError: true);
    DevicesError? failure;
    try {
      switch (device.kind) {
        case DeviceKind.local:
          await router.transferToLocal();
        case DeviceKind.dlna:
          if (state.providerId != MediaId.providerSubsonic) {
            throw const OutputTransferException(OutputTransferFailure.notRemotePlayable);
          }
          final renderer =
              router.dlna.devices.value.where((d) => d.id == device.id).firstOrNull ??
              switch (router.route.value) {
                DlnaOutput(device: final current) when current.id == device.id => current,
                _ => null,
              };
          if (renderer == null) throw const OutputTransferException(OutputTransferFailure.renderer);
          await router.transferToDlna(renderer);
        case DeviceKind.systemCast:
          break;
      }
    } on OutputTransferException catch (e) {
      final dlnaError = router.dlna.error.value;
      failure = switch (e.reason) {
        OutputTransferFailure.playFirst => DevicesError.playFirst,
        OutputTransferFailure.notRemotePlayable => DevicesError.subsonicOnly,
        OutputTransferFailure.systemCastActive => DevicesError.couldntSwitch,
        OutputTransferFailure.renderer => dlnaError == null ? DevicesError.couldntSwitch : devicesErrorOf(dlnaError),
      };
    } catch (_) {
      failure = DevicesError.couldntSwitch;
    }
    if (!ref.mounted) return;
    state = _derive(state.copyWith(clearBusy: true, error: failure, clearError: failure == null));
  }
}

final devicesViewModelProvider = NotifierProvider.autoDispose<DevicesViewModel, DevicesSheetState>(
  DevicesViewModel.new,
);
