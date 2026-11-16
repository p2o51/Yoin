/// The Devices sheet (Android ui/component/DevicesSheet.kt): where the music
/// plays. This device, the system's own output and cast panel (HarmonyOS
/// AVCastPicker), and the DLNA speakers on the same Wi-Fi; the one in use
/// carries a check. Opening it searches the LAN again; so do the refresh
/// button and a pull.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/model/model.dart';
import '../../playback/playback_controller.dart';
import '../../settings/settings_components.dart' show SpringReveal;
import '../../theme/tokens.dart';
import '../../ui/component/spring_cross_fade.dart';
import '../../ui/component/yoin_pull_to_refresh.dart';
import '../haptics.dart';
import 'devices_strings.dart';
import 'devices_view_model.dart';

/// The words for [error].
String devicesErrorText(DevicesStrings strings, DevicesError error) => switch (error) {
  DevicesError.discovery => strings.errorDiscovery,
  DevicesError.group => strings.errorGroup,
  DevicesError.stopUnconfirmed => strings.errorStop,
  DevicesError.playback => strings.errorPlayback,
  DevicesError.subsonicOnly => strings.subsonicOnly,
  DevicesError.playFirst => strings.playFirst,
  DevicesError.couldntSwitch => strings.couldntSwitch,
};

/// A row's icon (DeviceIcons.kt iconForDevice).
IconData deviceIcon(YoinDevice device) => switch (device.kind) {
  DeviceKind.local => YoinSymbols.headphones,
  DeviceKind.systemCast => device.isActive ? YoinSymbols.castConnected : YoinSymbols.cast,
  DeviceKind.dlna => YoinSymbols.speaker,
};

String deviceName(DevicesStrings strings, YoinDevice device) => switch (device.kind) {
  DeviceKind.local => strings.thisDevice,
  DeviceKind.systemCast => device.name.isEmpty ? strings.castDevice : device.name,
  DeviceKind.dlna => device.name,
};

String? deviceStatus(DevicesStrings strings, YoinDevice device) => switch (device.status) {
  DeviceStatus.none => null,
  DeviceStatus.switchBack => strings.switchBack,
  DeviceStatus.casting => strings.casting,
  DeviceStatus.notCasting => strings.notCasting,
  DeviceStatus.dlna => strings.dlnaUpnp,
};

class DevicesSheet extends ConsumerStatefulWidget {
  const DevicesSheet({super.key});

  @override
  ConsumerState<DevicesSheet> createState() => _DevicesSheetState();
}

class _DevicesSheetState extends ConsumerState<DevicesSheet> {
  MediaId? _openedOn;

  @override
  void initState() {
    super.initState();
    _openedOn = ref.read(playbackControllerProvider).current?.id;
    // Fetch on open: the sheet never shows a stale list until someone finds the refresh button.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) ref.read(devicesViewModelProvider.notifier).refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    // A new song closes the sheet (Android keys it on the song: remember(state.songId)).
    ref.listen(playbackControllerProvider.select((s) => s.current?.id), (_, id) {
      if (id != _openedOn && mounted) Navigator.of(context).maybePop();
    });
    final state = ref.watch(devicesViewModelProvider);
    final vm = ref.read(devicesViewModelProvider.notifier);
    final strings = DevicesStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final navBottom = MediaQuery.paddingOf(context).bottom;
    final error = state.error;
    final emptyText = state.isSubsonic ? strings.emptySubsonic : strings.emptyOther;

    void refresh() {
      YoinHaptics.tick();
      vm.refresh();
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(
            SheetTokens.devicesHeaderStart,
            SheetTokens.devicesHeaderTop,
            SheetTokens.devicesHeaderEnd,
            SheetTokens.devicesHeaderBottom,
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(strings.title, style: text.titleMedium!.copyWith(color: colors.onSurface)),
                    Text(
                      state.isSubsonic ? strings.subtitleSubsonic : strings.subtitleOther,
                      style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
              IconButton(
                key: const ValueKey('devices-refresh'),
                tooltip: strings.cdRefresh,
                onPressed: state.loading ? null : refresh,
                icon: SizedBox.square(
                  dimension: 24,
                  child: SpringCrossFade(
                    childKey: state.loading,
                    child: Center(
                      child: state.loading
                          ? const SizedBox.square(
                              dimension: SheetTokens.deviceSpinner,
                              child: CircularProgressIndicator(strokeWidth: SheetTokens.deviceSpinnerStroke),
                            )
                          : Icon(YoinSymbols.refresh, semanticLabel: strings.cdRefresh),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        Flexible(
          child: YoinPullToRefresh(
            refreshing: state.loading,
            onRefresh: refresh,
            child: ListView(
              shrinkWrap: true,
              physics: const AlwaysScrollableScrollPhysics(parent: ClampingScrollPhysics()),
              padding: EdgeInsets.fromLTRB(
                SheetTokens.devicesListPaddingH,
                SheetTokens.devicesListTop,
                SheetTokens.devicesListPaddingH,
                SheetTokens.devicesListBottom + navBottom,
              ),
              children: [
                SpringReveal(
                  visible: error != null,
                  child: error == null
                      ? null
                      : Padding(
                          padding: const EdgeInsets.fromLTRB(
                            SheetTokens.devicesMessagePaddingH,
                            SheetTokens.devicesMessagePaddingV,
                            SheetTokens.devicesMessagePaddingH,
                            SheetTokens.devicesMessagePaddingV + SheetTokens.devicesRowGap,
                          ),
                          child: Text(
                            devicesErrorText(strings, error),
                            key: const ValueKey('devices-error'),
                            style: text.bodySmall!.copyWith(color: colors.error),
                          ),
                        ),
                ),
                if (!state.loading && state.devices.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: SheetTokens.devicesMessagePaddingH,
                      vertical: SheetTokens.devicesEmptyPaddingV,
                    ),
                    child: Text(emptyText, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
                  ),
                for (var i = 0; i < state.devices.length; i++) ...[
                  if (i > 0) const SizedBox(height: SheetTokens.devicesRowGap),
                  DeviceRow(
                    key: ValueKey('device-${state.devices[i].id}'),
                    device: state.devices[i],
                    busy: state.busyDeviceId == state.devices[i].id,
                    onTap: () {
                      YoinHaptics.contextClick();
                      vm.select(state.devices[i]);
                    },
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// One device (DevicesSheet.kt DeviceRow): its icon (primary when in use),
/// name and status line, then a spinner while switching or the check when
/// in use. A row that can't be picked dims unless it is the one in use.
class DeviceRow extends StatelessWidget {
  const DeviceRow({super.key, required this.device, required this.busy, required this.onTap});

  final YoinDevice device;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final strings = DevicesStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final enabled = device.isSelectable && !busy;
    final status = deviceStatus(strings, device);
    return Opacity(
      opacity: enabled || device.isActive ? 1 : SheetTokens.deviceDisabledAlpha,
      child: Material(
        color: colors.surfaceContainerHigh,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: enabled ? onTap : null,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: SheetTokens.deviceRowPaddingH,
              vertical: SheetTokens.deviceRowPaddingV,
            ),
            child: Row(
              children: [
                Icon(
                  deviceIcon(device),
                  size: SheetTokens.deviceIcon,
                  color: device.isActive ? colors.primary : colors.onSurfaceVariant,
                ),
                const SizedBox(width: SheetTokens.deviceIconGap),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(deviceName(strings, device), style: text.bodyLarge!.copyWith(color: colors.onSurface)),
                      if (status != null) Text(status, style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant)),
                    ],
                  ),
                ),
                const SizedBox(width: SheetTokens.deviceIconGap),
                SizedBox.square(
                  dimension: SheetTokens.deviceTrailing,
                  child: SpringCrossFade(
                    childKey: busy ? 'busy' : (device.isActive ? 'active' : 'none'),
                    child: Center(
                      child: busy
                          ? const SizedBox.square(
                              dimension: SheetTokens.deviceSpinner,
                              child: CircularProgressIndicator(strokeWidth: SheetTokens.deviceSpinnerStroke),
                            )
                          : device.isActive
                          ? Icon(
                              YoinSymbols.check,
                              size: SheetTokens.deviceTrailing,
                              color: colors.primary,
                              semanticLabel: strings.cdCurrent,
                            )
                          : const SizedBox.shrink(),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Opens the Devices sheet over Now Playing (Android ModalBottomSheet).
Future<void> showDevicesSheet(BuildContext navigatorContext) => showModalBottomSheet<void>(
  context: navigatorContext,
  // Clear of the status bar / the 2in1 window caption, like Android's sheet with its top insets.
  useSafeArea: true,
  isScrollControlled: true,
  showDragHandle: true,
  backgroundColor: Theme.of(navigatorContext).colorScheme.surfaceContainer,
  builder: (_) => const DevicesSheet(),
);
