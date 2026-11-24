package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.io.IOException

/**
 * Where on-device Sing's model comes from and what runs it, for [OnDeviceSingStreamProvider]: the model
 * file(s) made ready ([ensure]), and a session opened on them ([open], one [SeparationModel] per
 * session, kept warm by [SingSessionPool]). LiteRT runs it ([LiteRtSingBackend]: Tensor TPU → GPU → CPU
 * over the builds bundled in the APK); [NoSingBackend] stands in where LiteRT can't be set up.
 */
interface SingSeparationBackend {
    /** Names what the separation's output comes from: part of the vocal cache key. */
    val modelId: String

    /** Tags the device gate's stored timings: the backend, its builds and threads. */
    val gateTag: String

    /** The model to open, if it is already here ([ensure] makes it so). */
    val readyModel: File?

    /** The model, verified and ready to open, fetching it first if need be. Throws IOException on failure. */
    suspend fun ensure(onProgress: (Float) -> Unit = {}): File

    /** A session on [model] (from [ensure]). Blocking; can take seconds. */
    fun open(model: File): SeparationModel
}

/**
 * No model on this device (the APK's LiteRT meta is missing or unreadable, or lists no build this device
 * may run): every Sing request fails as [SingFailure.ModelUnavailable].
 */
class NoSingBackend(private val reason: String) : SingSeparationBackend {
    override val modelId: String = "none"
    override val gateTag: String = "none"
    override val readyModel: File? = null

    override suspend fun ensure(onProgress: (Float) -> Unit): File = throw IOException(reason)

    override fun open(model: File): SeparationModel = throw IOException(reason)
}
