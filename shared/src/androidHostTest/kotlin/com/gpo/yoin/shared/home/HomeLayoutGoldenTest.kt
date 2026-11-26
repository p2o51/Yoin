package com.gpo.yoin.shared.home

import kotlin.test.Test

/**
 * Writes and checks the Home layout golden vectors (shared/src/commonTest/resources/golden/home-layout.json)
 * that iOS (Swift through YoinShared) and HarmonyOS (the Dart port) are tested against, and the same file as
 * a Kotlin string (commonTest HomeLayoutGoldenData.kt) for [HomeLayoutGoldenReplayTest] on the iOS simulator.
 * Every expected value is what this Kotlin computes through [YoinHomeLayout]; Android's app pins the same file
 * (app HomeLayoutPinTest). Regenerate after an intentional change:
 * `YOIN_WRITE_HOME_LAYOUT_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*HomeLayoutGoldenTest*'`.
 */
class HomeLayoutGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        HomeGoldenFiles.check(
            json = HomeLayoutGolden.render(),
            file = HomeLayoutGolden.FILE,
            mirror = "shared/src/commonTest/kotlin/com/gpo/yoin/shared/home/HomeLayoutGoldenData.kt",
            constant = "HOME_LAYOUT_GOLDEN",
            env = "YOIN_WRITE_HOME_LAYOUT_GOLDEN",
            writer = "HomeLayoutGoldenTest",
        )
    }
}
