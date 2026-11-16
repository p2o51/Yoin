import { AbilityConstant, Want } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { FlutterAbility, FlutterEngine } from '@ohos/flutter_ohos';
import { GeneratedPluginRegistrant } from '../plugins/GeneratedPluginRegistrant';
import { setQaSilent } from '../yoin/playback/PlaybackEngine';
import { YoinPlaybackHost } from '../yoin/playback/PlaybackHost';
import YoinPlaybackPlugin from '../yoin/playback/PlaybackPlugin';
import { YOIN_ACTION_KEY, YOIN_ACTION_OPEN_NOW_PLAYING } from '../yoin/playback/SystemSession';
import YoinStoragePlugin from '../yoin/storage/StoragePlugin';
import YoinI18nPlugin from '../yoin/i18n/I18nPlugin';
import YoinAppleMusicAuthPlugin from '../yoin/applemusic/AppleMusicAuthPlugin';
import YoinCardsPlugin, { CardsHost } from '../yoin/cards/CardsPlugin';
import YoinWindowPlugin, { YoinWindowHost } from '../yoin/window/WindowPlugin';
import YoinNetworkPlugin from '../yoin/network/NetworkPlugin';
import YoinSharePlugin from '../yoin/share/SharePlugin';
import YoinCloudSyncPlugin from '../yoin/sync/CloudSyncPlugin';
import YoinHapticsPlugin from '../yoin/haptics/HapticsPlugin';
import YoinSensorsPlugin from '../yoin/sensors/SensorsPlugin';
import YoinTransferPlugin from '../yoin/background/TransferPlugin';
import { window } from '@kit.ArkUI';

export default class EntryAbility extends FlutterAbility {
  configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    GeneratedPluginRegistrant.registerWith(flutterEngine)
    // Yoin's own native modules (playback needs the UIAbility context for AVSession and the continuous task).
    YoinPlaybackHost.bind(this.context)
    flutterEngine.getPlugins()?.add(new YoinPlaybackPlugin(this.context))
    flutterEngine.getPlugins()?.add(new YoinStoragePlugin())
    flutterEngine.getPlugins()?.add(new YoinI18nPlugin())
    flutterEngine.getPlugins()?.add(new YoinAppleMusicAuthPlugin())
    flutterEngine.getPlugins()?.add(new YoinCardsPlugin(this.context))
    flutterEngine.getPlugins()?.add(new YoinWindowPlugin())
    flutterEngine.getPlugins()?.add(new YoinNetworkPlugin())
    flutterEngine.getPlugins()?.add(new YoinSharePlugin(this.context))
    flutterEngine.getPlugins()?.add(new YoinCloudSyncPlugin(this.context))
    flutterEngine.getPlugins()?.add(new YoinHapticsPlugin())
    flutterEngine.getPlugins()?.add(new YoinSensorsPlugin())
    // Downloads keep running behind other apps (a data-transfer continuous task with their progress).
    flutterEngine.getPlugins()?.add(new YoinTransferPlugin(this.context))
  }

  /**
   * 2in1 / free windows: once the content is up, the system title bar gives way to the app's own ground (only the
   * three buttons stay); Dart keeps clear of them and drags the window from that strip (yoin/window).
   */
  onWindowStageCreate(windowStage: window.WindowStage) {
    super.onWindowStageCreate(windowStage)
    let attached = false
    windowStage.on('windowStageEvent', (event: window.WindowStageEventType) => {
      if (attached || event !== window.WindowStageEventType.SHOWN) {
        return
      }
      attached = true
      YoinWindowHost.attach(windowStage.getMainWindowSync())
    })
  }

  /** Cold start from Yoin's 播控中心 / lock-screen card: open Now Playing once Dart listens. */
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam) {
    super.onCreate(want, launchParam)
    applyQaFlags(want)
    CardsHost.deliverFrom(want)
    if (wantsNowPlaying(want)) {
      YoinPlaybackHost.bind(this.context).requestOpenNowPlaying()
    }
  }

  /** EntryAbility is a singleton: a card tap while running arrives here. */
  onNewWant(want: Want, launchParams: AbilityConstant.LaunchParam): void {
    super.onNewWant(want, launchParams)
    applyQaFlags(want)
    CardsHost.deliverFrom(want)
    if (wantsNowPlaying(want)) {
      YoinPlaybackHost.bind(this.context).requestOpenNowPlaying()
    }
  }

  onDestroy() {
    super.onDestroy()
    // Playback is process-level: it carries on while the user wants sound, else it is torn down here.
    YoinPlaybackHost.onAbilityDestroy(this.context)
  }

  /**
   * Back at the root (Home / Library): go to the background like Android's task-to-back, never terminate the
   * ability, so the music keeps playing.
   */
  popSystemNavigator(): boolean {
    this.context.moveAbilityToBackground().catch((e: BusinessError) => {
      hilog.warn(0x5901, 'YoinAbility', 'moveAbilityToBackground failed: %{public}d', e.code)
    })
    return true
  }
}

function wantsNowPlaying(want: Want): boolean {
  const params = want.parameters
  return params !== undefined && params[YOIN_ACTION_KEY] === YOIN_ACTION_OPEN_NOW_PLAYING
}

/** QA launches (scripts/device.sh) pass yoinQaSilent=true: play muted. Absent = normal sound. */
function applyQaFlags(want: Want): void {
  const params = want.parameters
  if (params !== undefined && params['yoinQaSilent'] === true) {
    setQaSilent(true)
  } else if (isHomeScreenLaunch(want)) {
    // Opened from its icon: a person is listening, whatever an earlier QA launch in this process set.
    setQaSilent(false)
  }
}

/** The launcher starts the main ability with the home action / entity (module.json5 skills). */
function isHomeScreenLaunch(want: Want): boolean {
  const entities = want.entities ?? []
  const home = want.action === 'action.system.home' || entities.indexOf('entity.system.home') >= 0
  hilog.info(0x5901, 'YoinAbility', 'launch want: action=%{public}s home=%{public}s', want.action ?? '-', `${home}`)
  return home
}
