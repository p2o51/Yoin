import { FlutterAbility, FlutterEngine } from '@ohos/flutter_ohos';
import { GeneratedPluginRegistrant } from '../plugins/GeneratedPluginRegistrant';
import YoinPlaybackPlugin from '../yoin/playback/PlaybackPlugin';
import YoinStoragePlugin from '../yoin/storage/StoragePlugin';

export default class EntryAbility extends FlutterAbility {
  configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    GeneratedPluginRegistrant.registerWith(flutterEngine)
    // Yoin's own native modules (playback needs the UIAbility context for AVSession and the continuous task).
    flutterEngine.getPlugins()?.add(new YoinPlaybackPlugin(this.context))
    flutterEngine.getPlugins()?.add(new YoinStoragePlugin())
  }
}
