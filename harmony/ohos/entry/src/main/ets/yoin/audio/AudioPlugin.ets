import {
  Any,
  FlutterPlugin,
  FlutterPluginBinding,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult
} from '@ohos/flutter_ohos';
import { audio } from '@kit.AudioKit';
import { common, Want } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { inputConsumer, KeyCode, KeyEvent } from '@kit.InputKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { SpatialChoice } from '../playback/PlaybackEngine';
import { YoinPlaybackHost } from '../playback/PlaybackHost';

const AUDIO_CHANNEL = 'yoin/audio';
const KEYS_CHANNEL = 'yoin/volume_keys';
const TAG = 'YoinAudio';
const DOMAIN = 0x5906;

/** Music, as the player plays it: the routing manager answers for this stream usage. */
const MUSIC: audio.AudioRendererInfo = {
  usage: audio.StreamUsage.STREAM_USAGE_MUSIC,
  rendererFlags: 0
};

/** Wired, USB, HDMI / DisplayPort, line out: lossless reaches the ear (Android WiredTypes). */
const WIRED: audio.DeviceType[] = [
  audio.DeviceType.WIRED_HEADSET,
  audio.DeviceType.WIRED_HEADPHONES,
  audio.DeviceType.USB_HEADSET,
  audio.DeviceType.USB_DEVICE,
  audio.DeviceType.DISPLAY_PORT,
  audio.DeviceType.HDMI,
  audio.DeviceType.LINE_DIGITAL
];

/** Re-encoded on the way (Android BluetoothTypes; NearLink headphones too). */
const WIRELESS: audio.DeviceType[] = [
  audio.DeviceType.BLUETOOTH_A2DP,
  audio.DeviceType.BLUETOOTH_SCO,
  audio.DeviceType.HEARING_AID,
  audio.DeviceType.NEARLINK
];

/**
 * The system's sound settings. HarmonyOS documents no public URI list for Settings' pages; this one is the
 * "声音和振动" entry as community samples name it (not verified on a device): an unknown URI opens Settings' home
 * page, and a refused Want falls back to Settings without one.
 */
const SETTINGS_BUNDLE = 'com.huawei.hmos.settings';
const SETTINGS_ABILITY = 'com.huawei.hmos.settings.MainAbility';
const SOUND_SETTINGS_URI = 'volume_settings';

/** The system's spatial audio as read now (Dart's `SpatialAudioCapability`). */
class SpatialRead {
  supported: boolean = false;
  outputSupported: boolean = false;
  enabled: boolean = false;

  key(): string {
    return `${this.supported}|${this.outputSupported}|${this.enabled}`;
  }

  toMap(): Map<string, Any> {
    const map: Map<string, Any> = new Map();
    map.set('supported', this.supported);
    map.set('outputSupported', this.outputSupported);
    map.set('enabled', this.enabled);
    // No public HarmonyOS read (isHeadTrackingEnabled is a system API).
    map.set('headTracking', false);
    return map;
  }
}

/**
 * Serves `yoin/audio` for Dart's `ChannelAudioHost` (lib/platform/audio/audio_host.dart):
 *  - `output` → 'wired' | 'bluetooth' | 'speaker': what music plays through now (the routing manager's preferred
 *    output for music); `outputChanged(kind)` is pushed on every change. Streaming quality's Auto reads it.
 *  - `spatial` → {supported, outputSupported, enabled, headTracking}: the system's spatial audio
 *    (AudioSpatializationManager); `spatialChanged(map)` is pushed when its switch or the output changes. Public
 *    HarmonyOS has no head-tracking read (isHeadTrackingEnabled is a system API), so headTracking is false.
 *  - `setSpatialDownmix(bool)`: Yoin's switch, applied by the player per song (multichannel songs play with the
 *    audio effect off while it is on) and at once to the song playing.
 *  - `openSoundSettings` → whether Settings opened.
 * And `yoin/volume_keys` for Dart's `ChannelVolumeKeyHost`: `setIntercepting(bool)` takes the volume keys from the
 * system (inputConsumer 'keyPressed', API 16+, foreground focus window only; the system's volume then stays put)
 * or gives them back; each press (repeats too) is pushed as `volumeKey(+1 | -1)`. No permission needed.
 */
export default class YoinAudioPlugin implements FlutterPlugin, MethodCallHandler {
  private audioChannel: MethodChannel | null = null;
  private keysChannel: MethodChannel | null = null;
  private readonly context: common.UIAbilityContext;
  private routing: audio.AudioRoutingManager | null = null;
  private spatialManager: audio.AudioSpatializationManager | null = null;
  private lastOutput: string = '';
  private lastSpatial: string = '';
  private intercepting: boolean = false;
  private readonly onOutput = (_: audio.AudioDeviceDescriptors) => this.pushChanges();
  private readonly onSpatialSwitch = (_: boolean) => this.pushChanges();
  // inputConsumer wants a different callback per key.
  private readonly onVolumeUp = (_: KeyEvent) => this.keysChannel?.invokeMethod('volumeKey', 1);
  private readonly onVolumeDown = (_: KeyEvent) => this.keysChannel?.invokeMethod('volumeKey', -1);

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  getUniqueClassName(): string {
    return 'YoinAudioPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.audioChannel = new MethodChannel(binding.getBinaryMessenger(), AUDIO_CHANNEL);
    this.audioChannel.setMethodCallHandler(this);
    this.keysChannel = new MethodChannel(binding.getBinaryMessenger(), KEYS_CHANNEL);
    this.keysChannel.setMethodCallHandler(this);
    this.watch();
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.setIntercepting(false);
    try {
      this.routing?.off('preferOutputDeviceChangeForRendererInfo', this.onOutput);
    } catch (e) {
      // Never subscribed.
    }
    try {
      this.spatialManager?.off('spatializationEnabledChangeForCurrentDevice', this.onSpatialSwitch);
    } catch (e) {
      // Never subscribed.
    }
    this.audioChannel?.setMethodCallHandler(null);
    this.keysChannel?.setMethodCallHandler(null);
    this.audioChannel = null;
    this.keysChannel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case 'output':
        result.success(this.outputKind());
        return;
      case 'spatial':
        result.success(this.spatialRead().toMap());
        return;
      case 'setSpatialDownmix': {
        const args: Any = call.args;
        SpatialChoice.downmix = args === true;
        YoinPlaybackHost.current()?.engine.applySpatialChoice();
        result.success(null);
        return;
      }
      case 'openSoundSettings':
        this.openSoundSettings().then((opened: boolean) => result.success(opened));
        return;
      case 'setIntercepting': {
        const args: Any = call.args;
        this.setIntercepting(args === true);
        result.success(null);
        return;
      }
      default:
        result.notImplemented();
    }
  }

  // ---- output ----

  private preferredOutputs(): audio.AudioDeviceDescriptors {
    const routing = this.routing ?? audio.getAudioManager().getRoutingManager();
    this.routing = routing;
    return routing.getPreferredOutputDeviceForRendererInfoSync(MUSIC);
  }

  /** The kind of output music plays through now; a wired one wins over Bluetooth when both are listed. */
  private outputKind(): string {
    try {
      const devices = this.preferredOutputs();
      let wireless = false;
      for (let i = 0; i < devices.length; i++) {
        const type = devices[i].deviceType;
        if (WIRED.indexOf(type) >= 0) {
          return 'wired';
        }
        if (WIRELESS.indexOf(type) >= 0) {
          wireless = true;
        }
      }
      return wireless ? 'bluetooth' : 'speaker';
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'output read failed: %{public}s', JSON.stringify(e));
      return 'speaker';
    }
  }

  // ---- spatial audio ----

  private spatialization(): audio.AudioSpatializationManager | null {
    if (this.spatialManager !== null) {
      return this.spatialManager;
    }
    if (!canIUse('SystemCapability.Multimedia.Audio.Spatialization')) {
      return null;
    }
    try {
      this.spatialManager = audio.getAudioManager().getSpatializationManager();
    } catch (e) {
      this.spatialManager = null;
    }
    return this.spatialManager;
  }

  private spatialRead(): SpatialRead {
    const read = new SpatialRead();
    const manager = this.spatialization();
    if (manager === null) {
      return read;
    }
    read.supported = true;
    try {
      read.enabled = manager.isSpatializationEnabledForCurrentDevice();
    } catch (e) {
      read.enabled = false;
    }
    try {
      const devices = this.preferredOutputs();
      for (let i = 0; i < devices.length; i++) {
        if (devices[i].spatializationSupported === true) {
          read.outputSupported = true;
        }
      }
    } catch (e) {
      read.outputSupported = false;
    }
    // A device that doesn't fill spatializationSupported still says so through its switch.
    read.outputSupported = read.outputSupported || read.enabled;
    return read;
  }

  /** Re-reads the output and the spatial state; pushes whichever moved. */
  private pushChanges(): void {
    const output = this.outputKind();
    if (output !== this.lastOutput) {
      this.lastOutput = output;
      this.audioChannel?.invokeMethod('outputChanged', output);
    }
    const spatial: SpatialRead = this.spatialRead();
    if (spatial.key() !== this.lastSpatial) {
      this.lastSpatial = spatial.key();
      this.audioChannel?.invokeMethod('spatialChanged', spatial.toMap());
    }
  }

  private watch(): void {
    try {
      const routing = this.routing ?? audio.getAudioManager().getRoutingManager();
      this.routing = routing;
      routing.on('preferOutputDeviceChangeForRendererInfo', MUSIC, this.onOutput);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'output watch failed: %{public}s', JSON.stringify(e));
    }
    const manager = this.spatialization();
    if (manager !== null) {
      try {
        manager.on('spatializationEnabledChangeForCurrentDevice', this.onSpatialSwitch);
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'spatial watch failed: %{public}s', JSON.stringify(e));
      }
    }
    this.lastOutput = this.outputKind();
    this.lastSpatial = this.spatialRead().key();
  }

  private async openSoundSettings(): Promise<boolean> {
    const page: Want = { bundleName: SETTINGS_BUNDLE, abilityName: SETTINGS_ABILITY, uri: SOUND_SETTINGS_URI };
    try {
      await this.context.startAbility(page);
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'sound settings Want failed: %{public}d', (e as BusinessError).code);
    }
    try {
      await this.context.startAbility({ bundleName: SETTINGS_BUNDLE, abilityName: SETTINGS_ABILITY } as Want);
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'settings Want failed: %{public}d', (e as BusinessError).code);
      return false;
    }
  }

  // ---- volume keys ----

  private setIntercepting(on: boolean): void {
    if (on === this.intercepting) {
      return;
    }
    try {
      if (on) {
        const up: inputConsumer.KeyPressedConfig = { key: KeyCode.KEYCODE_VOLUME_UP, action: 1, isRepeat: true };
        const down: inputConsumer.KeyPressedConfig = { key: KeyCode.KEYCODE_VOLUME_DOWN, action: 1, isRepeat: true };
        inputConsumer.on('keyPressed', up, this.onVolumeUp);
        inputConsumer.on('keyPressed', down, this.onVolumeDown);
      } else {
        inputConsumer.off('keyPressed', this.onVolumeUp);
        inputConsumer.off('keyPressed', this.onVolumeDown);
      }
      this.intercepting = on;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'volume keys %{public}s failed: %{public}s', on ? 'on' : 'off', JSON.stringify(e));
    }
  }
}
