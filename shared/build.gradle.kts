import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

/*
 * :shared — Kotlin Multiplatform code both apps use: the Material Color
 * Utilities (MCU) port, the Palette port, YoinTokens and the pure layout /
 * colour algorithms. Android consumes it as a library; iOS links the
 * YoinShared XCFramework (iosApp/docs/shared-kmp.md).
 *
 * com.gpo.yoin.shared.data (Subsonic client, neutral models, LRC parser) is
 * used by iOS only for now; Android keeps its Retrofit client in app/
 * (iosApp/docs/shared-data.md).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.gpo.yoin.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    val xcf = XCFramework("YoinShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "YoinShared"
            isStatic = true
            // Swift may start suspend calls (SubsonicClient) from any thread /
            // Task, not only the main thread (iosApp/docs/shared-data.md).
            binaryOption("objcExportSuspendFunctionLaunchThreadRestriction", "none")
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
    }
}
