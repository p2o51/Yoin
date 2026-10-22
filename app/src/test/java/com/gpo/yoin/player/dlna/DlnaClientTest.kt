package com.gpo.yoin.player.dlna

import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaClientTest {
    @Test
    fun should_waitForStoppedObservation_when_stopAcknowledgementPrecedesAudioStop() = runTest {
        val fake = StopTransport(mutableListOf("PLAYING", "TRANSITIONING", "STOPPED"))
        fake.stopAndConfirm(fakeDevice())
        assertEquals(1, fake.stops)
        assertEquals(3, fake.observations)
    }

    @Test
    fun should_rejectHandback_when_rendererNeverConfirmsStopped() = runTest {
        val fake = StopTransport(mutableListOf("PLAYING"))
        val failure = runCatching { fake.stopAndConfirm(fakeDevice()) }.exceptionOrNull() as DlnaCommandException
        assertEquals("ConfirmStop", failure.action)
        assertEquals(1, fake.stops)
        assertTrue(fake.observations > 1)
    }

    @Test
    fun should_sendSoapActionAndPreserveAuthenticatedUri_when_loadingDirectStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(DlnaProtocolTest.response("SetAVTransportURI")))
            val device = device(server)
            DlnaClient().setUri(
                device,
                DlnaMetadata(
                    "track",
                    "Song",
                    null,
                    null,
                    null,
                    "http://192.0.2.1/stream?u=user&t=token&s=salt",
                    "audio/flac",
                    10000
                )
            )
            val request = server.takeRequest()
            assertEquals(
                "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
                request.getHeader("SOAPACTION")
            )
            assertEquals("/control", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("<InstanceID>0</InstanceID>"))
            assertTrue(body.contains("u=user&amp;t=token&amp;s=salt"))
            assertTrue(body.contains("audio/flac"))
        }
    }

    @Test
    fun should_returnRendererPositionAndTransport_when_polling() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    DlnaProtocolTest.response(
                        "GetTransportInfo",
                        "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>" +
                            "OK</CurrentTransportStatus>"
                    )
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    DlnaProtocolTest.response(
                        "GetPositionInfo",
                        "<RelTime>00:01:02</RelTime><TrackDuration>00:03:20</TrackDuration><TrackURI>" +
                            "http://192.0.2.1/track</TrackURI>"
                    )
                )
            )
            val client = DlnaClient()
            assertEquals("PLAYING", client.transportInfo(device(server)).state)
            assertEquals(62000L, client.position(device(server)).positionMs)
        }
    }

    @Test
    fun should_preserveSeekFailureCode_when_rendererRejectsTarget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(
                    500
                ).setBody(
                    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                        """<s:Fault><detail><UPnPError><errorCode>711</errorCode></UPnPError></detail>""" +
                        """</s:Fault></s:Body></s:Envelope>"""
                )
            )
            val failure = runCatching {
                DlnaClient().seek(
                    device(server),
                    65000
                )
            }.exceptionOrNull() as DlnaCommandException
            assertEquals(711, failure.code)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("<Unit>REL_TIME</Unit><Target>00:01:05</Target>"))
            assertFalse(failure.message.orEmpty().contains("http"))
        }
    }

    private fun device(server: MockWebServer): DlnaDevice {
        val location = URI(server.url("/device.xml").toString())
        return DlnaDevice(
            "id",
            "Speaker",
            "",
            "",
            location,
            DlnaService("urn:schemas-upnp-org:service:AVTransport:1", location.resolve("/control")),
            null
        )
    }

    private fun fakeDevice(): DlnaDevice = DlnaDevice(
        "id",
        "Speaker",
        "",
        "",
        URI("http://192.0.2.1/device"),
        DlnaService("urn:schemas-upnp-org:service:AVTransport:1", URI("http://192.0.2.1/control")),
        null
    )

    private class StopTransport(private val states: MutableList<String>) : DlnaTransport {
        var stops = 0
        var observations = 0
        override suspend fun stop(device: DlnaDevice) {
            stops++
        }
        override suspend fun transportInfo(device: DlnaDevice): DlnaTransportInfo {
            observations++
            return DlnaTransportInfo(if (states.size > 1) states.removeAt(0) else states.single(), "OK")
        }
        override suspend fun setUri(device: DlnaDevice, metadata: DlnaMetadata) = Unit
        override suspend fun play(device: DlnaDevice) = Unit
        override suspend fun pause(device: DlnaDevice) = Unit
        override suspend fun seek(device: DlnaDevice, positionMs: Long) = Unit
        override suspend fun position(device: DlnaDevice): DlnaPosition = DlnaPosition(0, 0, null)
        override suspend fun volume(device: DlnaDevice): Int = 20
        override suspend fun setVolume(device: DlnaDevice, volume: Int) = Unit
    }
}
