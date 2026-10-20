package com.gpo.yoin.player.dlna

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {
    private val location = URI("http://192.0.2.4:1400/xml/device.xml")
    private val service = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", location.resolve("/control"))

    @Test
    fun should_resolveRelativeUrlsOnRenderer_when_descriptionContainsMultipleEmbeddedDevices() {
        val devices = DlnaProtocol.devices(description(), location)
        assertEquals(1, devices.size)
        assertEquals("uuid:speaker_MR", devices.single().id)
        assertEquals(
            URI("http://192.0.2.4:1400/MediaRenderer/AVTransport/Control"),
            devices.single().avTransport.controlUrl
        )
        assertEquals(URI("http://192.0.2.4:1400/xml/volume"), devices.single().renderingControl?.controlUrl)
        assertEquals("http://192.0.2.4:1400/topology", devices.single().zoneGroupTopology?.controlUrl.toString())
    }

    @Test
    fun should_honorUrlBase_when_descriptionSuppliesOne() {
        val xml = description().replace("<device>", "<URLBase>http://192.0.2.6:1500/base/</URLBase><device>", false)
        assertEquals(
            "http://192.0.2.6:1500/base/volume",
            DlnaProtocol.devices(xml, location).single().renderingControl?.controlUrl.toString()
        )
    }

    @Test
    fun should_useRoomNameWithoutHardwareSuffix_when_rendererBelongsToSonos() {
        val xml = description().replace(
            "<friendlyName>Root</friendlyName>",
            "<friendlyName>Root - MAC</friendlyName><manufacturer>Sonos, Inc.</manufacturer>" +
                "<roomName>Living Room Front</roomName>"
        )
        assertEquals("Living Room Front", DlnaProtocol.devices(xml, location).single().name)
    }

    @Test
    fun should_decodeSsdpHeadersCaseInsensitively_when_discoveryResponseIsValid() {
        assertEquals(location, DlnaProtocol.ssdpLocation("HTTP/1.1 200 OK\r\nLoCaTiOn: $location\r\n\r\n"))
        assertNull(DlnaProtocol.ssdpLocation("NOTIFY * HTTP/1.1\r\nLOCATION: $location\r\n"))
        assertNull(DlnaProtocol.ssdpLocation("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n"))
    }

    @Test
    fun should_escapeNestedMetadataExactlyOncePerLayer_when_titleAndUriContainXmlCharacters() {
        val metadata =
            DlnaMetadata(
                "a&b",
                "Tea < Milk",
                "A & B",
                null,
                null,
                "http://192.0.2.3/stream?t=secret&s=one",
                "audio/flac",
                65000
            )
        val didl = DlnaProtocol.didl(metadata)
        assertTrue(didl.contains("audio/flac"))
        assertTrue(didl.contains("duration=\"00:01:05\""))
        assertTrue(didl.contains("Tea &lt; Milk"))
        val soap = DlnaProtocol.envelope(service, "SetAVTransportURI", mapOf("CurrentURIMetaData" to didl))
        assertTrue(soap.contains("Tea &amp;lt; Milk"))
        assertTrue(soap.contains("?t=secret&amp;amp;s=one"))
        assertTrue(DlnaProtocol.didl(metadata.copy(mimeType = null)).contains("http-get:*:*:*"))
    }

    @Test
    fun should_parseNamespacedActionResult_when_responseIsSoap() {
        val result = DlnaProtocol.response(
            response("GetTransportInfo", "<CurrentTransportState>PLAYING</CurrentTransportState>"),
            "GetTransportInfo"
        )
        assertEquals("PLAYING", result["CurrentTransportState"])
    }

    @Test
    fun should_surfaceUpnpErrorCodeWithoutSecrets_when_responseIsSoapFault() {
        val failure = runCatching {
            DlnaProtocol.response(
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                    """<s:Fault><detail><UPnPError><errorCode>711</errorCode><errorDescription>secret """ +
                    """URI</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
                "Seek"
            )
        }.exceptionOrNull() as DlnaCommandException
        assertEquals(711, failure.code)
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test
    fun should_rejectExternalEntities_when_descriptionContainsDoctype() {
        assertTrue(
            runCatching {
                DlnaProtocol.devices(
                    "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>",
                    location
                )
            }.isFailure
        )
    }

    @Test
    fun should_parseFractionalTimesAndRejectUnavailableValues_when_positionInfoIsRead() {
        assertEquals(3_723_125L, DlnaProtocol.parseTime("1:02:03.125"))
        assertEquals(3_723_100L, DlnaProtocol.parseTime("1:02:03.1"))
        assertEquals("01:02:03", DlnaProtocol.formatTime(3_723_999))
        assertNull(DlnaProtocol.parseTime("NOT_IMPLEMENTED"))
        assertNull(DlnaProtocol.parseTime("00:99:00"))
    }

    @Test
    fun should_resolveCoordinatorWithoutRegrouping_when_sonosSelectedMemberIsNotCoordinator() {
        val groups = """<ZoneGroups><ZoneGroup Coordinator="leader"><ZoneGroupMember UUID="member" """ +
            """Location="http://192.0.2.4:1400/xml/device.xml"/><ZoneGroupMember UUID="leader" """ +
            """Location="http://192.0.2.5:1400/xml/device.xml"/></ZoneGroup></ZoneGroups>"""
        assertEquals(
            URI("http://192.0.2.5:1400/xml/device.xml"),
            DlnaProtocol.coordinatorLocation(groups, "uuid:member_MR")
        )
        assertNull(DlnaProtocol.coordinatorLocation(groups, "uuid:leader_MR"))
        assertTrue(
            runCatching {
                DlnaProtocol.coordinatorLocation(groups, "uuid:missing_MR")
            }.exceptionOrNull() is DlnaGroupException
        )
    }

    private fun description(): String = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>""" +
        """Root</friendlyName><serviceList><service><serviceType>""" +
        """urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType><controlURL>""" +
        """/topology</controlURL></service></serviceList><deviceList><device><deviceType>""" +
        """urn:schemas-upnp-org:device:MediaServer:1</deviceType><serviceList><service>""" +
        """<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>""" +
        """<controlURL>/wrong</controlURL></service></serviceList></device><device>""" +
        """<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>""" +
        """<friendlyName>Speaker</friendlyName><UDN>uuid:speaker_MR</UDN><serviceList>""" +
        """<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>""" +
        """<controlURL>/MediaRenderer/AVTransport/Control</controlURL></service><service>""" +
        """<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>""" +
        """<controlURL>volume</controlURL></service></serviceList></device></deviceList>""" +
        """</device></root>"""

    companion object {
        fun response(action: String, values: String = ""): String =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
                """$values</u:${action}Response></s:Body></s:Envelope>"""
    }
}
