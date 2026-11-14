
import common from '@ohos.app.ability.common';
import { FlutterPage } from '@ohos/flutter_ohos'
import { AppleMusicAuthView } from '../yoin/applemusic/AppleMusicAuthView'
import { AppleMusicAuthHost } from '../yoin/applemusic/AppleMusicAuthHost'

let storage = LocalStorage.getShared()
const EVENT_BACK_PRESS = 'EVENT_BACK_PRESS'

@Entry(storage)
@Component
struct Index {
  private context = getContext(this) as common.UIAbilityContext
  @LocalStorageLink('viewId') viewId: string = "";
  // The ArkWeb Apple Music sign-in overlay (AppleMusicAuthHost toggles the
  // AppStorage key 'yoinAmAuthActive', AM_AUTH_ACTIVE).
  @StorageLink('yoinAmAuthActive') amAuthActive: boolean = false;

  build() {
    Stack() {
      Column() {
        FlutterPage({ viewId: this.viewId })
      }
      if (this.amAuthActive) {
        AppleMusicAuthView()
      }
    }
    .width('100%')
    .height('100%')
  }

  onBackPress(): boolean {
    // Back during sign-in cancels it instead of leaving the Flutter screen.
    if (this.amAuthActive) {
      AppleMusicAuthHost.cancel()
      return true
    }
    this.context.eventHub.emit(EVENT_BACK_PRESS)
    return true
  }
}
