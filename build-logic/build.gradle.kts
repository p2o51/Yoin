plugins {
    `kotlin-dsl`
}

dependencies {
    // Both come with the Android Gradle plugin, which :app applies in the same plugins block.
    compileOnly(libs.android.gradleApi)
    compileOnly(libs.asm)
}

gradlePlugin {
    plugins {
        register("musicKitLargeId") {
            id = "yoin.musickit-large-id"
            implementationClass = "MusicKitLargeIdPlugin"
        }
    }
}
