import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    id("yoin.musickit-large-id")
    id("yoin.musickit-relro")
}

val releaseKeystorePropertiesFile = (
    findProperty("yoinReleaseKeystoreProperties") as String?
        ?: System.getenv("YOIN_RELEASE_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.yoin/yoin-upload-keystore.properties"
    ).let(::file)

val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

fun releaseKeystoreProperty(name: String): String? =
    releaseKeystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val hasReleaseKeystore = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
).all { releaseKeystoreProperty(it) != null }

val releaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true) &&
        // releaseDebugSigned deliberately signs with the debug key — it must
        // not trip the upload-keystore guard below.
        !taskName.contains("ReleaseDebugSigned", ignoreCase = true)
}

// Sing's LiteRT models come from the Play AI Pack :sing-model-pack (on demand, per device group), so the base
// APK carries none. -PyoinSingBundledModels=true also bundles them in the APK (assets/sing/bundled/<group>/,
// copied from the pack at build time) for builds Play doesn't install: Yoin Nano, the debug-signed release
// APKs, local test builds. Off by default; never on for a Play upload.
val singBundledModels = providers.gradleProperty("yoinSingBundledModels").orNull == "true"

if (releaseTaskRequested && !hasReleaseKeystore) {
    throw GradleException(
        "Release signing requires ${releaseKeystorePropertiesFile.path}. " +
            "See docs/release-0.5-closed-test.md for the local upload-key setup."
    )
}

android {
    namespace = "com.gpo.yoin"
    // Compose foundation 1.13 (BOM 2026.11) needs the 37.1 minor SDK.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.gpo.yoin"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "0.6.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val spotifyClientId = (project.findProperty("spotifyClientId") as String?).orEmpty()
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")

        // Web OAuth client ID from the same Google Cloud project as the Android clients (public, not a
        // secret). -PgoogleWebClientId= (blank) keeps Cloud sync on the AuthorizationClient chooser alone.
        val googleWebClientId = (project.findProperty("googleWebClientId") as String?)
            ?: "747031130557-pnsffrq2rbmrhn5jq6ipamcdgtb5b6p3.apps.googleusercontent.com"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("boolean", "NANO_TEST", "false")
        buildConfigField("boolean", "SING_BUNDLED_MODELS", singBundledModels.toString())
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    // Sing's models: the on-demand AI Pack, its device groups split by SoC (official device targeting from
    // lyra-sing-lite: Tensor G6 and G5 get their AOT build, every other device the fp16 one).
    assetPacks += listOf(":sing-model-pack")
    bundle {
        deviceTargetingConfig = file("device_targeting_configuration.xml")
        deviceGroup {
            enableSplit = true
            defaultGroup = "other"
        }
    }

    androidResources {
        // values/ is English. The matching locale is declared in res/resources.properties.
        generateLocaleConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("releaseUpload") {
                storeFile = file(releaseKeystoreProperty("storeFile")!!)
                storePassword = releaseKeystoreProperty("storePassword")!!
                keyAlias = releaseKeystoreProperty("keyAlias")!!
                keyPassword = releaseKeystoreProperty("keyPassword")!!
            }
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
            // QA: -PyoinAppIdSuffix=.qa installs a second, empty copy beside the real one (a first run without
            // touching the installed app's accounts or database). Unset by default.
            (project.findProperty("yoinAppIdSuffix") as String?)?.let { applicationIdSuffix = it }
            // Gemini Nano test copy (「歌词一句」): -PyoinNanoTest=true with -PyoinAppIdSuffix=.nanotest installs
            // "Yoin Nano" beside the everyday app (own package and data, Spotify setup untouched). Nano runs on a
            // short leash and Home shows what it is doing (LyricHighlightProcessor.testMode).
            if (project.findProperty("yoinNanoTest") == "true") {
                buildConfigField("boolean", "NANO_TEST", "true")
                manifestPlaceholders["appLabel"] = "Yoin Nano"
            }
        }
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The real release build (R8 + resource shrink) signed with the DEBUG
        // key: installable anywhere without the upload keystore, and never
        // confusable with a Play upload. Built automatically per commit by the
        // post-commit hook (scripts/build-debug-signed-release.sh).
        create("releaseDebugSigned") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // R8 copy of "Yoin Nano" (-PyoinNanoTest=true -PyoinAppIdSuffix=.nanotest): installs over the debug Nano
            // test copy (same debug key), beside the everyday app. Gemini Nano broke only under R8 (0.5.9), so
            // Nano changes are checked on this build, not on debug.
            (project.findProperty("yoinAppIdSuffix") as String?)?.let { applicationIdSuffix = it }
            if (project.findProperty("yoinNanoTest") == "true") {
                buildConfigField("boolean", "NANO_TEST", "true")
                manifestPlaceholders["appLabel"] = "Yoin Nano"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Production code (e.g. the detail-cache android.util.LruCache) touches
            // android.* utility classes from JVM unit tests. Return defaults instead
            // of throwing "Method ... not mocked"; tests that need real Android
            // behaviour use Robolectric explicitly.
            isReturnDefaultValues = true
            // Robolectric Compose UI tests (breakpoint fit checks) need the
            // merged manifest's ComponentActivity and the app's resources.
            isIncludeAndroidResources = true
            all {
                // The full Robolectric suite exhausted the CI test worker. Keep
                // one bounded worker and recycle it between groups of classes.
                it.maxHeapSize = "2g"
                it.maxParallelForks = 1
                it.forkEvery = 100
                // Robolectric 4.17 sets up the API 36 ApplicationSharedMemory through
                // jdk.internal.access.SharedSecrets, which java.base doesn't export.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // Sing's full-model parity test runs the real Lyra-Sing-Lite on the desktop ONNX Runtime
                // when given the model (not in the repo): -PyoinSingModel=/path/lyra_sing_lite_fp32.onnx.
                // Optional local run on a real clip made by scripts/sing/gen_sing_fixtures.py --clip:
                // -PyoinSingClipDir=/path/to/clip-out. Both skip when unset.
                (
                    project.findProperty(
                        "yoinSingModel"
                    ) as String?
                    )?.let { path -> it.systemProperty("yoin.sing.model", path) }
                (
                    project.findProperty(
                        "yoinSingClipDir"
                    ) as String?
                    )?.let { path -> it.systemProperty("yoin.sing.clipDir", path) }
                // -PyoinSingPerf=true times a 3-minute song on the desktop JVM (with -PyoinSingModel).
                (
                    project.findProperty(
                        "yoinSingPerf"
                    ) as String?
                    )?.let { flag -> it.systemProperty("yoin.sing.perf", flag) }
            }
        }
    }

    packaging {
        jniLibs {
            // Sing runs LiteRT on 64-bit ARM only (its NPU runtime is arm64-v8a only anyway): a 32-bit or x86
            // device is far too slow for the device gate, and each of those ABIs would add 6–12 MB.
            // (Without the library the router has no backend and Sing fails cleanly; see player/sing/.)
            excludes += listOf("armeabi-v7a", "x86", "x86_64").flatMap { abi ->
                listOf("libLiteRt.so", "liblitert_jni.so", "libLiteRtClGlAccelerator.so").map { "**/$abi/$it" }
            }
            // The Tensor runtime's on-device compiler (1 MB): Sing runs AOT builds only, and Google Tensor has
            // no JIT compilation anyway. The dispatch library it needs stays.
            excludes += "**/libLiteRtCompilerPlugin_google_tensor.so"
        }
    }

    lint {
        // Adopt Android Lint as a CI gate without fixing all pre-existing debt in
        // one go: existing issues are recorded in lint-baseline.xml, so the build
        // only fails on NEW lint errors. Regenerate after intentionally clearing
        // debt with: ./gradlew :app:updateLintBaseline
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }
}

/**
 * The AI Pack's groups copied for a bundled-models APK: model#group_<group>/… → sing/bundled/<group>/… in a
 * generated assets directory (BundledSingModelSource reads them; the metas are the pack's own).
 */
abstract class SingBundledModels : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packAssets: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val groupDir = Regex("^model#group_([^/]+)/")
        fs.sync {
            from(packAssets)
            into(outputDir.dir("sing/bundled"))
            includeEmptyDirs = false
            eachFile { path = path.replace(groupDir, "$1/") }
        }
    }
}

if (singBundledModels) {
    val singBundled = tasks.register<SingBundledModels>("singBundledModels") {
        packAssets.set(layout.projectDirectory.dir("../sing-model-pack/src/main/assets"))
        outputDir.set(layout.buildDirectory.dir("generated/singBundledModels"))
    }
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(singBundled, SingBundledModels::outputDir)
    }
}

// Room writes each schema version to app/schemas/ (commit them): the record later migrations are checked against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Kotlin Multiplatform code shared with the iOS app: MCU, the Palette port, YoinTokens, pure algorithms.
    implementation(project(":shared"))
    implementation(files("libs/musickitauth-release-1.1.2.aar"))
    // libs/ playback AAR with its 64-bit native libraries patched for 16 KB pages (build-logic MusicKitRelroPlugin)
    implementation(files(tasks.named<PatchMusicKitRelro>("patchMusicKitRelro").flatMap { it.patchedAar }))
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose core
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // Material3 — stable from BOM + Expressive alpha explicit
    implementation(libs.material3)
    // Yoin Symbols — the app's icon set (composite build, see settings.gradle.kts)
    implementation(libs.yoin.symbols)
    implementation(libs.material3.expressive)
    // Window size class + fold posture (currentWindowAdaptiveInfo)
    implementation(libs.material3.adaptive)
    implementation(libs.androidx.window)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    // Cast (Phase 14)
    implementation(libs.media3.cast)
    // Sing: Lyra-Sing-Lite on the device (player/sing/) through LiteRT: CompiledModel + GPU accelerator + the
    // Google Tensor NPU dispatch library
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.google.tensor)
    // Sing: fetches the on-demand AI Pack sing_model_pack (AiPackManager)
    implementation(libs.play.ai.delivery)
    implementation(libs.cast.framework)
    implementation(libs.genai.prompt)
    ksp(libs.genai.schema.compiler)
    // Google authorization for optional Drive appDataFolder cloud sync
    implementation(libs.play.services.auth) {
        // Declared by the 22.0.0 POM but referenced by no Play services class; sync has its own Task.await().
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-play-services")
    }
    // Sign in with Google account sheet shown before the Drive consent (needs googleWebClientId)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Local storage
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Shapes. (Palette runs on the :shared port; androidx.palette stays for PaletteParityTest.)
    implementation(libs.graphics.shapes)

    // Home-screen widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Core AndroidX
    implementation(libs.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.palette)
    // The desktop ONNX Runtime, test only: Sing's model parity test runs the exported model on it against Python
    // onnxruntime (LyraSingModelParityTest, skipped unless -PyoinSingModel is given). The app has no ONNX Runtime.
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric's android-all jars (one 90–200 MB jar per Android SDK) come from Gradle, not from Robolectric.
// Left alone, Robolectric downloads them from Maven Central in the middle of the test run, with no retry,
// and a network blip fails the whole test class (MavenArtifactFetcher … IOException). Gradle resolves them
// like any other dependency instead: with its own retries and checksums, into its cache (which CI keeps), and
// offline once cached. The test JVM gets a robolectric-deps.properties that maps each jar to its file.
//
// The jars are listed per SDK, so the list has to cover every SDK a test runs on: each @Config(sdk = …),
// and targetSdk for tests without one. A test on an SDK that isn't listed fails with
// "no artifacts found for org.robolectric:android-all-instrumented:…": add that SDK here. The version
// strings and the -iN suffix are Robolectric's own (DefaultSdkProvider) and change with Robolectric.
val robolectricVersion = "4.17"
val robolectricAndroidAllVersions = mapOf(
    26 to "8.0.0_r4-robolectric-r1",
    27 to "8.1.0-robolectric-4611349",
    34 to "14-robolectric-10818077",
    35 to "15-robolectric-13954326",
    36 to "16-robolectric-13921718",
).mapValues { (_, version) -> "$version-i7" }

check(libs.versions.robolectric.get() == robolectricVersion) {
    "Robolectric is now ${libs.versions.robolectric.get()}: update robolectricVersion and robolectricAndroidAllVersions " +
        "in app/build.gradle.kts from that release's org.robolectric.plugins.DefaultSdkProvider."
}

// One configuration per SDK: in a shared one, Gradle's conflict resolution would keep only the newest version.
val robolectricAndroidAllJars = robolectricAndroidAllVersions.map { (sdk, version) ->
    val configuration = configurations.register("robolectricAndroidAll$sdk") {
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(configuration.name, "org.robolectric:android-all-instrumented:$version")
    configuration
}

/** Writes the robolectric-deps.properties that points Robolectric at the jars Gradle resolved. */
abstract class RobolectricDepsProperties : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val androidAllJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val lines = androidAllJars.files.sortedBy { it.name }.map { jar ->
            val version = jar.name.removePrefix("android-all-instrumented-").removeSuffix(".jar")
            // Keys are group:artifact:version; ':' separates keys from values in .properties, hence the escapes.
            "org.robolectric\\:android-all-instrumented\\:$version=${jar.invariantSeparatorsPath}"
        }
        output.get().asFile.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}

val robolectricDeps = tasks.register<RobolectricDepsProperties>("robolectricDepsProperties") {
    androidAllJars.from(robolectricAndroidAllJars)
    output.set(layout.buildDirectory.file("robolectric/robolectric-deps.properties"))
}

class RobolectricDepsArgument(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val depsFile: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Drobolectric-deps.properties=${depsFile.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(RobolectricDepsArgument(robolectricDeps.flatMap { it.output }))
}
