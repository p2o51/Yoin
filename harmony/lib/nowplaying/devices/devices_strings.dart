import 'package:flutter/widgets.dart';

/// The Devices pill and sheet, English and Simplified Chinese, copied from
/// Android res/values{,-zh-rCN}/strings_{nowplaying,component,shell}.xml
/// (key in the comment).
class DevicesStrings {
  const DevicesStrings._(this._zh);

  final bool _zh;

  static DevicesStrings of(BuildContext context) =>
      DevicesStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  static const en = DevicesStrings._(false);

  String get pillDevices => _zh ? '设备' : 'Devices'; // np_pill_devices

  // The sheet (strings_component.xml).
  String get title => _zh ? '设备' : 'Devices'; // cmp_devices_title

  /// cmp_devices_subtitle_subsonic without "Chromecast" (HarmonyOS has none;
  /// the system cast row stands in for it). Flagged for the owner's copy model.
  String get subtitleSubsonic => _zh ? '此设备与 DLNA · 同一 Wi-Fi' : 'This device and DLNA · Same Wi-Fi';
  String get subtitleOther => _zh ? '播放设备' : 'Playback devices'; // cmp_devices_subtitle_other
  String get cdRefresh => _zh ? '刷新设备' : 'Refresh devices'; // cmp_devices_cd_refresh
  String get thisDevice => _zh ? '此设备' : 'This device'; // cmp_devices_this_device
  String get emptySubsonic =>
      _zh ? '当前仅可在本机播放。' : 'Only local playback is available right now.'; // cmp_devices_empty_subsonic
  String get emptyOther => _zh ? '未找到设备。' : 'No devices found.'; // cmp_devices_empty_other
  String get cdCurrent => _zh ? '当前设备' : 'Current device'; // cmp_devices_cd_current

  // Row status (strings_nowplaying.xml).
  String get switchBack => _zh ? '切回此设备' : 'Switch back to this device'; // np_device_switch_back_cast
  String get casting => _zh ? '正在投播' : 'Casting'; // np_device_connected_cast
  String get notCasting => _zh ? '未投播' : 'Not casting'; // np_device_use_cast_pill

  /// The system cast row's name while nothing is cast (player_cast_device_session).
  String get castDevice => _zh ? '投播设备' : 'Cast Device';

  /// A DLNA row's status line: Android shows this literal in every language.
  String get dlnaUpnp => 'DLNA · UPnP';

  // Failures.
  String get errorDiscovery => _zh
      ? '无法发现音响。请连接同一 Wi-Fi 网络后重试。'
      : 'Unable to find speakers. Connect to the same Wi-Fi network and try again.'; // np_device_dlna_error_discovery
  String get errorGroup => _zh
      ? '无法连接 Sonos 分组。请检查音响连接后重试。'
      : 'Unable to connect to the Sonos group. Check the speaker connection and try again.'; // np_device_dlna_error_group
  String get errorStop => _zh
      ? '音响未确认停止。请重试后再切换播放设备。'
      : 'The speaker did not confirm stopping. Retry before switching playback.'; // np_device_dlna_error_stop
  String get errorPlayback => _zh
      ? '无法控制音响。请检查音响连接，并确认音响可访问 Subsonic 服务器。'
      : 'Unable to control the speaker. Check its connection and access to the Subsonic server.'; // np_device_dlna_error_playback
  String get subsonicOnly =>
      _zh ? 'DLNA 投播需要 Subsonic 音频流。' : 'DLNA playback requires a Subsonic stream.'; // player_dlna_subsonic_only
  String get playFirst => _zh
      ? '请先播放一首 Subsonic 歌曲，再选择 DLNA 音响。'
      : 'Play a Subsonic track before choosing a DLNA speaker.'; // player_dlna_play_first
  String get couldntSwitch => _zh ? '无法切换设备。' : "Couldn't switch devices."; // np_msg_couldnt_switch_devices
}
