
import common from '@ohos.app.ability.common';
import { curves } from '@kit.ArkUI';
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
  @StorageLink('yoinReducedMotion') reducedMotion: boolean = false;

  private authOverlayTransition(): TransitionEffect {
    if (this.reducedMotion) {
      return TransitionEffect.OPACITY.animation({ duration: 150, curve: Curve.EaseOut })
    }
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: 40 }))
        .combine(TransitionEffect.scale({ x: 0.97, y: 0.97 }))
        .animation({ curve: curves.springMotion(0.38, 0.88) }),
      TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: 28 }))
        .combine(TransitionEffect.scale({ x: 0.98, y: 0.98 }))
        .animation({ curve: curves.springMotion(0.32, 0.92) }),
    )
  }

  build() {
    Stack() {
      Column() {
        FlutterPage({ viewId: this.viewId })
      }
      if (this.amAuthActive) {
        AppleMusicAuthView()
          .transition(this.authOverlayTransition())
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
