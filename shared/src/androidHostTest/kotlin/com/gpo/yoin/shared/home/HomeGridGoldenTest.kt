package com.gpo.yoin.shared.home

import kotlin.test.Test

/**
 * Writes and checks the two-column grid golden vectors (shared/src/commonTest/resources/golden/home-grid.json)
 * and their Kotlin mirror (commonTest HomeGridGoldenData.kt). Regenerate after an intentional change:
 * `YOIN_WRITE_HOME_GRID_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*HomeGridGoldenTest*'`.
 */
class HomeGridGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        HomeGoldenFiles.check(
            json = HomeGridGolden.render(),
            file = HomeGridGolden.FILE,
            mirror = "shared/src/commonTest/kotlin/com/gpo/yoin/shared/home/HomeGridGoldenData.kt",
            constant = "HOME_GRID_GOLDEN",
            env = "YOIN_WRITE_HOME_GRID_GOLDEN",
            writer = "HomeGridGoldenTest",
        )
    }
}
