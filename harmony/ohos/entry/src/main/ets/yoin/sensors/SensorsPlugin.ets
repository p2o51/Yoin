import { Any, FlutterPlugin, FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { sensor } from '@kit.SensorServiceKit';
import { display } from '@kit.ArkUI';
import { systemDateTime } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const CHANNEL = 'yoin/sensors';
const TAG = 'YoinSensors';
const DOMAIN = 0x5906;

/**
 * Serves `yoin/sensors` for Dart's `ChannelMotionSensors` (lib/platform/sensors/motion_sensors.dart): the groove
 * emblem's tilt colour (Android GrooveTilt.kt, TYPE_GAME_ROTATION_VECTOR at SENSOR_DELAY_GAME).
 *  - `start`: registers `sensor.SensorId.ROTATION_VECTOR` at the `game` rate (20ms);
 *  - `stop`: unregisters it;
 *  - pushes `rotation` {x, y, z, w, rotation, t} per sample: the quaternion, the display's rotation (0–3) and the
 *    sample's monotonic time in ms.
 * Dart starts it only while the emblem is shown at rest and the app is in front; leaving the engine stops it.
 * The rotation vector needs no permission.
 */
export default class YoinSensorsPlugin implements FlutterPlugin, MethodCallHandler {
  private channel: MethodChannel | null = null;
  private listening = false;

  getUniqueClassName(): string {
    return 'YoinSensorsPlugin';
  }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
    this.channel.setMethodCallHandler(this);
  }

  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.stop();
    this.channel?.setMethodCallHandler(null);
    this.channel = null;
  }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    if (call.method === 'start') {
      this.start();
      result.success(null);
    } else if (call.method === 'stop') {
      this.stop();
      result.success(null);
    } else {
      result.notImplemented();
    }
  }

  private start(): void {
    if (this.listening) {
      return;
    }
    try {
      sensor.on(sensor.SensorId.ROTATION_VECTOR, (data: sensor.RotationVectorResponse) => this.push(data),
        { interval: 'game' });
      this.listening = true;
    } catch (e) {
      // no rotation vector on this device: the tilt stays at rest
      hilog.warn(DOMAIN, TAG, 'rotation vector unavailable: %{public}s', JSON.stringify(e));
    }
  }

  private stop(): void {
    if (!this.listening) {
      return;
    }
    this.listening = false;
    try {
      sensor.off(sensor.SensorId.ROTATION_VECTOR);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'off failed: %{public}s', JSON.stringify(e));
    }
  }

  private push(data: sensor.RotationVectorResponse): void {
    if (!this.listening || this.channel === null) {
      return;
    }
    const sample: Map<string, Any> = new Map();
    sample.set('x', data.x);
    sample.set('y', data.y);
    sample.set('z', data.z);
    sample.set('w', data.w);
    sample.set('rotation', YoinSensorsPlugin.displayRotation());
    sample.set('t', systemDateTime.getUptime(systemDateTime.TimeType.STARTUP, false));
    this.channel.invokeMethod('rotation', sample);
  }

  /** 0–3 = 0°, 90°, 180°, 270°, as Android Surface.ROTATION_*. */
  private static displayRotation(): number {
    try {
      return display.getDefaultDisplaySync().rotation;
    } catch (e) {
      return 0;
    }
  }
}
