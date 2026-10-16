plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

// build-logic's own tests (the MusicKit native-library patch) run with everyone else's: `./gradlew test`.
tasks.register("test") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
