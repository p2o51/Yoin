package com.gpo.yoin.player.sing

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.aipacks.AiPackManager
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.google.android.play.core.aipacks.AiPackState
import com.google.android.play.core.aipacks.AiPackStateUpdateListener
import com.google.android.play.core.aipacks.model.AiPackErrorCode
import com.google.android.play.core.aipacks.model.AiPackStatus
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException

/** What [AiPackSingModelSource] needs of Play AI Delivery (the Play implementation: [PlayAiPackClient]). */
interface AiPackClient {
    /** The pack's `assets` directory if the pack is on the device and up to date ([AiPackManager.getPackLocation]), else null. */
    fun assetsPath(pack: String): String?

    /** Downloads [pack] ([AiPackManager.fetch]); returns once it is COMPLETED. Throws IOException when Play can't deliver it. */
    suspend fun fetch(pack: String, onProgress: (Float) -> Unit)
}

/**
 * The Play AI Pack `sing_model_pack` (on-demand, split by device group — see the :sing-model-pack module):
 * Play delivers the group's `model/` directory, its build(s) and the [LiteRtCatalog] meta naming them
 * (`model/models.json`). On the device already → used as it is; else fetched (progress reported), then
 * every build SHA-256-checked against the meta and [SingLiteRtPlan.TRUSTED]. The builds stay where Play
 * put them (the app's internal storage); nothing is copied.
 */
class AiPackSingModelSource(
    private val client: AiPackClient,
    private val pack: String = PACK,
) : SingModelSource {
    override val name: String = "Play AI Pack $pack"

    override fun local(): SingModelSet? {
        val assets = client.assetsPath(pack) ?: return null
        return read(File(assets)).takeIf { it.complete() }
    }

    override suspend fun obtain(onProgress: (Float) -> Unit): SingModelSet {
        val assets = client.assetsPath(pack) ?: run {
            client.fetch(pack, onProgress)
            client.assetsPath(pack) ?: throw IOException("$pack: fetched, but Play gives no location for it")
        }
        currentCoroutineContext().ensureActive()
        val set = read(File(assets))
        SingModelFiles.verifyAll(set)
        onProgress(1f)
        return set
    }

    /** The meta Play delivered with the builds: which group, which builds. */
    private fun read(assets: File): SingModelSet {
        val dir = File(assets, MODEL_DIR)
        val text = try {
            File(dir, SingLiteRtPlan.META_FILE).readText()
        } catch (e: FileNotFoundException) {
            throw IOException("$pack: no $MODEL_DIR/${SingLiteRtPlan.META_FILE}", e)
        }
        val catalog = try {
            SingLiteRtPlan.parse(text)
        } catch (e: SerializationException) {
            throw IOException("$pack: unreadable $MODEL_DIR/${SingLiteRtPlan.META_FILE}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("$pack: unreadable $MODEL_DIR/${SingLiteRtPlan.META_FILE}", e)
        }
        return SingModelSet("$name, group ${catalog.group ?: "?"}", catalog, dir)
    }

    companion object {
        /** The AI Pack's name (`aiPack { packName }` in :sing-model-pack). */
        const val PACK = "sing_model_pack"

        /** The pack's directory of builds: `model#group_<group>/` in the module, `model/` once delivered. */
        const val MODEL_DIR = "model"
    }
}

/** Assets as [BundledSingModelSource] reads them (the APK's AssetManager; a directory in tests). */
interface SingAssetReader {
    /** The names in the asset directory [path]; empty when there is none. */
    fun list(path: String): List<String>

    fun open(path: String): InputStream

    companion object {
        fun of(context: Context): SingAssetReader = object : SingAssetReader {
            override fun list(path: String): List<String> = context.assets.list(path)?.toList().orEmpty()

            override fun open(path: String): InputStream = context.assets.open(path)
        }
    }
}

/**
 * The builds bundled in a `-PyoinSingBundledModels=true` APK (Yoin Nano and other test builds, which
 * Play doesn't install): the AI Pack's groups copied into assets/sing/bundled/<group>/ at build time,
 * each with its meta. Like Play, only what is for this device ([keep]: the build's SoC matches) is used;
 * on first use copied into [dir] (no-backup storage) and SHA-256-checked.
 */
class BundledSingModelSource(
    private val assets: SingAssetReader,
    private val dir: File,
    private val keep: (LiteRtModelMeta) -> Boolean,
) : SingModelSource {
    override val name: String = "bundled"

    /** The bundled metas merged, each build's file relative to [dir] (`<group>/<file>`); null when nothing is bundled. */
    private val catalog: LiteRtCatalog? by lazy {
        val groups = assets.list(ASSET_DIR).sorted()
        val models = groups.flatMap { group ->
            val meta = try {
                assets.open("$ASSET_DIR/$group/${SingLiteRtPlan.META_FILE}").use { SingLiteRtPlan.parse(it.readBytes().decodeToString()) }
            } catch (e: FileNotFoundException) {
                return@flatMap emptyList()
            } catch (e: SerializationException) {
                throw IOException("bundled $group/${SingLiteRtPlan.META_FILE} unreadable", e)
            }
            meta.models.filter(keep).map { it.copy(file = "$group/${it.file}") }
        }.distinctBy { it.sha256.lowercase() } // fp16 is in every group: copied once
        if (groups.isEmpty()) null else LiteRtCatalog(group = groups.joinToString("+"), models = models)
    }

    private fun set(catalog: LiteRtCatalog) = SingModelSet("bundled (${catalog.group})", catalog, dir)

    override fun local(): SingModelSet? = catalog?.let(::set)?.takeIf { it.complete() }

    override suspend fun obtain(onProgress: (Float) -> Unit): SingModelSet {
        val catalog = catalog ?: throw IOException("no models bundled in this APK")
        val set = set(catalog)
        if (catalog.models.isEmpty()) throw IOException("no bundled build for this device")
        val total = catalog.models.sumOf { it.bytes }.toFloat()
        var done = 0L
        for (meta in catalog.models) {
            currentCoroutineContext().ensureActive()
            val target = set.fileFor(meta)
            val ok = runCatching { SingModelFiles.verify(target, meta) }.isSuccess
            if (!ok) copy(meta, target)
            done += meta.bytes
            onProgress(done / total)
        }
        return set
    }

    private fun copy(meta: LiteRtModelMeta, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        assets.open("$ASSET_DIR/${meta.file}").use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
        try {
            SingModelFiles.verify(part, meta)
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("${meta.file}: could not move into place")
        }
    }

    companion object {
        /** Where the build puts the AI Pack's groups in a bundled-models APK (app/build.gradle.kts). */
        const val ASSET_DIR = "sing/bundled"
    }
}

/**
 * [AiPackClient] on Play AI Delivery (`com.google.android.play:ai-delivery`). Statuses that need the user
 * (confirmation for an app Play didn't install, waiting for Wi-Fi) or end the request (failed, canceled)
 * end [fetch] with an IOException: Sing then reports the model unavailable, and asking again fetches again.
 */
class PlayAiPackClient(context: Context) : AiPackClient {
    private val appContext = context.applicationContext
    private val manager: AiPackManager by lazy { AiPackManagerFactory.getInstance(appContext) }

    override fun assetsPath(pack: String): String? = manager.getPackLocation(pack)?.assetsPath()

    override suspend fun fetch(pack: String, onProgress: (Float) -> Unit) {
        val done = CompletableDeferred<Unit>()
        var lastStatus = -1
        fun onState(state: AiPackState) {
            if (state.name() != pack) return
            val status = state.status()
            if (status != lastStatus) {
                lastStatus = status
                Log.i(TAG, "AI pack $pack: ${statusName(status)} (${state.bytesDownloaded()}/${state.totalBytesToDownload()} B, error ${errorName(state.errorCode())})")
            }
            when (status) {
                AiPackStatus.DOWNLOADING -> {
                    val total = state.totalBytesToDownload()
                    if (total > 0) onProgress(0.95f * state.bytesDownloaded().coerceIn(0L, total) / total)
                }
                // Clamped: Play's local-testing service reported 10 000 % here.
                AiPackStatus.TRANSFERRING -> onProgress(0.95f + 0.05f * state.transferProgressPercentage().coerceIn(0, 100) / 100f)
                AiPackStatus.COMPLETED -> done.complete(Unit)
                AiPackStatus.FAILED -> done.completeExceptionally(IOException("$pack: download failed (${errorName(state.errorCode())})"))
                AiPackStatus.CANCELED -> done.completeExceptionally(IOException("$pack: download canceled"))
                AiPackStatus.REQUIRES_USER_CONFIRMATION ->
                    done.completeExceptionally(IOException("$pack: Play needs the user's confirmation (the app wasn't installed by Play)"))
                AiPackStatus.WAITING_FOR_WIFI -> done.completeExceptionally(IOException("$pack: waiting for Wi-Fi"))
                else -> Unit
            }
        }
        val listener = object : AiPackStateUpdateListener {
            override fun onStateUpdate(state: AiPackState) = onState(state)
        }
        manager.registerListener(listener)
        try {
            Log.i(TAG, "AI pack $pack: fetch")
            val states = try {
                manager.fetch(listOf(pack)).await()
            } catch (e: Exception) {
                throw IOException("$pack: fetch failed (${e.message ?: e.javaClass.simpleName})", e)
            }
            states.packStates()[pack]?.let(::onState)
            done.await()
        } finally {
            manager.unregisterListener(listener)
        }
    }

    companion object {
        private const val TAG = "YoinSing"

        fun statusName(status: Int): String = when (status) {
            AiPackStatus.NOT_INSTALLED -> "NOT_INSTALLED"
            AiPackStatus.PENDING -> "PENDING"
            AiPackStatus.WAITING_FOR_WIFI -> "WAITING_FOR_WIFI"
            AiPackStatus.REQUIRES_USER_CONFIRMATION -> "REQUIRES_USER_CONFIRMATION"
            AiPackStatus.DOWNLOADING -> "DOWNLOADING"
            AiPackStatus.TRANSFERRING -> "TRANSFERRING"
            AiPackStatus.COMPLETED -> "COMPLETED"
            AiPackStatus.FAILED -> "FAILED"
            AiPackStatus.CANCELED -> "CANCELED"
            else -> "UNKNOWN($status)"
        }

        fun errorName(code: Int): String = when (code) {
            AiPackErrorCode.NO_ERROR -> "NO_ERROR"
            AiPackErrorCode.APP_UNAVAILABLE -> "APP_UNAVAILABLE"
            AiPackErrorCode.PACK_UNAVAILABLE -> "PACK_UNAVAILABLE"
            AiPackErrorCode.INVALID_REQUEST -> "INVALID_REQUEST"
            AiPackErrorCode.DOWNLOAD_NOT_FOUND -> "DOWNLOAD_NOT_FOUND"
            AiPackErrorCode.API_NOT_AVAILABLE -> "API_NOT_AVAILABLE"
            AiPackErrorCode.NETWORK_ERROR -> "NETWORK_ERROR"
            AiPackErrorCode.ACCESS_DENIED -> "ACCESS_DENIED"
            AiPackErrorCode.INSUFFICIENT_STORAGE -> "INSUFFICIENT_STORAGE"
            AiPackErrorCode.APP_NOT_OWNED -> "APP_NOT_OWNED"
            AiPackErrorCode.UNRECOGNIZED_INSTALLATION -> "UNRECOGNIZED_INSTALLATION"
            AiPackErrorCode.INTERNAL_ERROR -> "INTERNAL_ERROR"
            else -> "error $code"
        }
    }
}

/** Play services Task as a cancellable suspend call (no coroutines-play-services dependency in the app). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Executor(Runnable::run)) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.resumeWithException(IllegalStateException("Play task canceled"))
            else -> continuation.resume(task.result)
        }
    }
}
