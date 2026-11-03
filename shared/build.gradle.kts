import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

/*
 * :shared — Kotlin Multiplatform code both apps use: the Material Color
 * Utilities (MCU) port, the Palette port, YoinTokens and the pure layout /
 * colour algorithms. Android consumes it as a library; iOS links the
 * YoinShared XCFramework (iosApp/docs/shared-kmp.md).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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
            xcf.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
