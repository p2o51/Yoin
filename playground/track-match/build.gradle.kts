plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.gpo.yoin.playground.trackmatch.TrackMatchPlaygroundKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
}
