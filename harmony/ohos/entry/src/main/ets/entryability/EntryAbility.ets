import { AbilityConstant, Want } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { FlutterAbility, FlutterEngine } from '@ohos/flutter_ohos';
import { GeneratedPluginRegistrant } from '../plugins/GeneratedPluginRegistrant';
import { YoinPlaybackHost } from '../yoin/playback/PlaybackHost';
import YoinPlaybackPlugin from '../yoin/playback/PlaybackPlugin';
import { YOIN_ACTION_KEY, YOIN_ACTION_OPEN_NOW_PLAYING } from '../yoin/playback/SystemSession';
import YoinStoragePlugin from '../yoin/storage/StoragePlugin';

export default class EntryAbility extends FlutterAbility {
  configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    GeneratedPluginRegistrant.registerWith(flutterEngine)
    // Yoin's own native modules (playback needs the UIAbility context for AVSession and the continuous task).
    YoinPlaybackHost.bind(this.context)
    flutterEngine.getPlugins()?.add(new YoinPlaybackPlugin(this.context))
    flutterEngine.getPlugins()?.add(new YoinStoragePlugin())
  }

  /** Cold start from Yoin's 播控中心 / lock-screen card: open Now Playing once Dart listens. */
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam) {
    super.onCreate(want, launchParam)
    if (wantsNowPlaying(want)) {
      YoinPlaybackHost.bind(this.context).requestOpenNowPlaying()
    }
  }

  /** EntryAbility is a singleton: a card tap while running arrives here. */
  onNewWant(want: Want, launchParams: AbilityConstant.LaunchParam): void {
    super.onNewWant(want, launchParams)
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
