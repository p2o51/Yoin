package com.gpo.yoin.shared.home

import kotlin.test.Test

/**
 * Writes and checks the gesture machine golden vectors (shared/src/commonTest/resources/golden/home-edit-gestures.json)
 * and their Kotlin mirror (commonTest HomeEditGesturesGoldenData.kt). Regenerate after an intentional change:
 * `YOIN_WRITE_HOME_EDIT_GESTURES_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*HomeEditGesturesGoldenTest*'`.
 */
class HomeEditGesturesGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        HomeGoldenFiles.check(
            json = HomeEditGesturesGolden.render(),
            file = HomeEditGesturesGolden.FILE,
            mirror = "shared/src/commonTest/kotlin/com/gpo/yoin/shared/home/HomeEditGesturesGoldenData.kt",
            constant = "HOME_EDIT_GESTURES_GOLDEN",
            env = "YOIN_WRITE_HOME_EDIT_GESTURES_GOLDEN",
            writer = "HomeEditGesturesGoldenTest",
        )
    }
}
