package com.gpo.yoin.shared.home

import kotlin.test.Test

/**
 * Writes and checks the drag / span / pinch golden vectors (shared/src/commonTest/resources/golden/home-drag.json)
 * and their Kotlin mirror (commonTest HomeDragGoldenData.kt). Regenerate after an intentional change:
 * `YOIN_WRITE_HOME_DRAG_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*HomeDragGoldenTest*'`.
 */
class HomeDragGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        HomeGoldenFiles.check(
            json = HomeDragGolden.render(),
            file = HomeDragGolden.FILE,
            mirror = "shared/src/commonTest/kotlin/com/gpo/yoin/shared/home/HomeDragGoldenData.kt",
            constant = "HOME_DRAG_GOLDEN",
            env = "YOIN_WRITE_HOME_DRAG_GOLDEN",
            writer = "HomeDragGoldenTest",
        )
    }
}
