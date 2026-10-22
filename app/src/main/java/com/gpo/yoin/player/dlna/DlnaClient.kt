package com.gpo.yoin.player.dlna

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class DlnaPosition(val positionMs: Long?, val durationMs: Long?, val trackUri: String?)
internal data class DlnaTransportInfo(val state: String, val status: String?)

internal interface DlnaTransport {
    suspend fun setUri(device: DlnaDevice, metadata: DlnaMetadata)
    suspend fun play(device: DlnaDevice)
    suspend fun pause(device: DlnaDevice)
    suspend fun stop(device: DlnaDevice)
    suspend fun seek(device: DlnaDevice, positionMs: Long)
    suspend fun position(device: DlnaDevice): DlnaPosition
    suspend fun transportInfo(device: DlnaDevice): DlnaTransportInfo
    suspend fun volume(device: DlnaDevice): Int
    suspend fun setVolume(device: DlnaDevice, volume: Int)
}

/** A SOAP acknowledgement may precede the renderer stopping its audio pipeline. */
internal suspend fun DlnaTransport.stopAndConfirm(device: DlnaDevice) {
    stop(device)
    try {
        kotlinx.coroutines.withTimeout(5_000) {
            while (true) {
                if (transportInfo(device).state in setOf("STOPPED", "NO_MEDIA_PRESENT")) return@withTimeout
                kotlinx.coroutines.delay(250)
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        throw DlnaCommandException("ConfirmStop")
    }
}

internal class DlnaClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
) : DlnaTransport {
    suspend fun resolveCoordinator(device: DlnaDevice): DlnaDevice {
        val topology = device.zoneGroupTopology ?: return device
        try {
            val response = action(topology, "GetZoneGroupState", includeInstance = false)
            val location = DlnaProtocol.coordinatorLocation(response["ZoneGroupState"].orEmpty(), device.id)
                ?: return device
            return describe(location).firstOrNull() ?: throw DlnaGroupException()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw DlnaGroupException()
        }
    }

    suspend fun describe(location: URI): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(location.toString()).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DLNA description unavailable")
            DlnaProtocol.devices(readXml(response.body), location)
        }
    }

    override suspend fun setUri(device: DlnaDevice, metadata: DlnaMetadata) {
        require(DlnaProtocol.httpUri(metadata.uri) != null) { "DLNA requires an HTTP stream" }
        action(
            device.avTransport,
            "SetAVTransportURI",
            mapOf("CurrentURI" to metadata.uri, "CurrentURIMetaData" to DlnaProtocol.didl(metadata))
        )
    }

    override suspend fun play(device: DlnaDevice) {
        action(device.avTransport, "Play", mapOf("Speed" to "1"))
    }
    override suspend fun pause(device: DlnaDevice) {
        action(device.avTransport, "Pause")
    }
    override suspend fun stop(device: DlnaDevice) {
        action(device.avTransport, "Stop")
    }
    override suspend fun seek(device: DlnaDevice, positionMs: Long) {
        action(device.avTransport, "Seek", mapOf("Unit" to "REL_TIME", "Target" to DlnaProtocol.formatTime(positionMs)))
    }

    override suspend fun position(device: DlnaDevice): DlnaPosition {
        val values = action(device.avTransport, "GetPositionInfo")
        return DlnaPosition(
            DlnaProtocol.parseTime(values["RelTime"]),
            DlnaProtocol.parseTime(values["TrackDuration"]),
            values["TrackURI"]
        )
    }

    override suspend fun transportInfo(device: DlnaDevice): DlnaTransportInfo {
        val values = action(device.avTransport, "GetTransportInfo")
        return DlnaTransportInfo(values["CurrentTransportState"].orEmpty(), values["CurrentTransportStatus"])
    }

    override suspend fun volume(device: DlnaDevice): Int = action(
        requireNotNull(device.renderingControl), "GetVolume", mapOf("Channel" to "Master")
    )["CurrentVolume"]?.toIntOrNull()?.coerceIn(0, 100) ?: throw DlnaCommandException("GetVolume")

    override suspend fun setVolume(device: DlnaDevice, volume: Int) {
        action(
            requireNotNull(device.renderingControl),
            "SetVolume",
            mapOf("Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString())
        )
    }

    private suspend fun action(
        service: DlnaService,
        name: String,
        arguments: Map<String, String> = emptyMap(),
        includeInstance: Boolean = true
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val body = DlnaProtocol.envelope(
            service,
            name,
            (if (includeInstance) mapOf("InstanceID" to "0") else emptyMap()) + arguments
        )
        val request = Request.Builder().url(service.controlUrl.toString())
            .header("SOAPACTION", "\"${service.type}#$name\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        try {
            http.newCall(request).execute().use { response ->
                val xml = readXml(response.body)
                val result = DlnaProtocol.response(xml, name)
                if (!response.isSuccessful) throw DlnaCommandException(name, response.code)
                result
            }
        } catch (failure: DlnaCommandException) {
            throw failure
        } catch (_: Exception) {
            // OkHttp exceptions can include credential-bearing URLs. Do not propagate those.
            throw DlnaCommandException(name)
        }
    }

    private fun readXml(body: okhttp3.ResponseBody?): String {
        val source = body?.source() ?: throw IOException("Empty DLNA response")
        if (source.request(MAX_XML_BYTES + 1)) throw IOException("DLNA XML too large")
        return source.readUtf8()
    }

    private companion object {
        const val MAX_XML_BYTES = 1_048_576L
    }
}
