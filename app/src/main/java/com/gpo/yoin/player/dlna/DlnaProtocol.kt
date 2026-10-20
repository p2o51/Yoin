package com.gpo.yoin.player.dlna

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class DlnaService(val type: String, val controlUrl: URI)

data class DlnaDevice(
    val id: String,
    val name: String,
    val manufacturer: String,
    val modelName: String,
    val descriptionUrl: URI,
    val avTransport: DlnaService,
    val renderingControl: DlnaService?,
    val zoneGroupTopology: DlnaService? = null
) {
    val friendlyName: String get() = name
}

/** Neither exceptions nor diagnostics include a stream URL, which may contain credentials. */
class DlnaCommandException(val action: String, val code: Int? = null) :
    java.io.IOException("DLNA $action failed" + (code?.let { " ($it)" } ?: ""))

internal class DlnaGroupException : java.io.IOException(
    "Cannot find the Sonos group coordinator. Select the group coordinator or retry discovery."
)

internal data class DlnaMetadata(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val artwork: String?,
    val uri: String,
    val mimeType: String?,
    val durationMs: Long?
)

internal object DlnaProtocol {
    private const val MAX_XML_CHARS = 1_048_576

    fun httpUri(value: String): URI? = runCatching { URI(value.trim()) }.getOrNull()?.takeIf {
        (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
            !it.host.isNullOrBlank() && it.userInfo == null
    }

    fun ssdpLocation(response: String): URI? {
        val lines = response.split(Regex("\\r?\\n"))
        if (!lines.firstOrNull().orEmpty().startsWith("HTTP/1.1 200", true)) return null
        return lines.drop(1).firstNotNullOfOrNull { line ->
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).trim().equals("location", true)) {
                httpUri(line.substring(separator + 1))
            } else {
                null
            }
        }
    }

    fun devices(xml: String, location: URI): List<DlnaDevice> {
        val root = parse(xml)
        val base = root.childText("URLBase").takeIf(String::isNotBlank)?.let(::httpUri) ?: location
        val rootDevice = root.child("device")
        val sonosRoomName = rootDevice?.takeIf {
            it.childText("manufacturer").contains("Sonos", true)
        }?.childText("roomName")?.takeIf(String::isNotBlank)
        val topology = root.descendants("service").firstOrNull {
            it.childText("serviceType").startsWith("urn:schemas-upnp-org:service:ZoneGroupTopology:")
        }?.let { service ->
            runCatching { base.resolve(service.childText("controlURL")) }.getOrNull()
                ?.let { httpUri(it.toString()) }?.let { DlnaService(service.childText("serviceType"), it) }
        }
        return root.descendants("device").mapNotNull { device ->
            val services = device.child("serviceList")?.children("service").orEmpty().mapNotNull { service ->
                val type = service.childText("serviceType")
                val path = service.childText("controlURL")
                if (type.isBlank() || path.isBlank()) return@mapNotNull null
                val control = runCatching { base.resolve(path) }.getOrNull()?.let { httpUri(it.toString()) }
                    ?: return@mapNotNull null
                DlnaService(type, control)
            }
            // Sonos ZonePlayer devices also publish AVTransport without a MediaRenderer device type.
            val transport = services.firstOrNull { it.type.startsWith("urn:schemas-upnp-org:service:AVTransport:") }
                ?: return@mapNotNull null
            DlnaDevice(
                id = device.childText("UDN").ifBlank { location.toString() },
                name = sonosRoomName ?: device.childText("friendlyName").ifBlank { location.host },
                manufacturer = device.childText("manufacturer"),
                modelName = device.childText("modelName"),
                descriptionUrl = location,
                avTransport = transport,
                renderingControl = services.firstOrNull {
                    it.type.startsWith("urn:schemas-upnp-org:service:RenderingControl:")
                },
                zoneGroupTopology = topology
            )
        }.distinctBy(DlnaDevice::id)
    }

    /** Sonos groups accept AVTransport commands at their coordinator; never regroup a household. */
    fun coordinatorLocation(xml: String, rendererId: String): URI? {
        val uuid = rendererId.removePrefix("uuid:").removeSuffix("_MR")
        val groups = parse(xml).descendants("ZoneGroup")
        val group = groups.firstOrNull { group ->
            group.descendants("ZoneGroupMember").any { it.getAttribute("UUID") == uuid }
        } ?: throw DlnaGroupException()
        val coordinator = group.getAttribute("Coordinator").takeIf(String::isNotBlank) ?: throw DlnaGroupException()
        if (coordinator == uuid) return null
        return group.descendants("ZoneGroupMember").firstOrNull { it.getAttribute("UUID") == coordinator }
            ?.getAttribute("Location")?.let(::httpUri) ?: throw DlnaGroupException()
    }

    fun envelope(service: DlnaService, action: String, arguments: Map<String, String>): String {
        require(action.matches(Regex("[A-Za-z][A-Za-z0-9]*")))
        require(arguments.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) })
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope """ +
            """xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action """ +
            """xmlns:u="${escape(service.type)}">""" +
            arguments.entries.joinToString("") { (key, value) -> "<$key>${escape(value)}</$key>" } +
            "</u:$action></s:Body></s:Envelope>"
    }

    fun response(xml: String, action: String): Map<String, String> {
        val root = parse(xml)
        root.descendants("Fault").firstOrNull()?.let { fault ->
            throw DlnaCommandException(
                action,
                fault.descendants("errorCode").firstOrNull()?.textContent?.trim()?.toIntOrNull()
            )
        }
        val result = root.descendants("${action}Response").firstOrNull()
            ?: throw DlnaCommandException(action)
        return result.children().associate { it.localName.orEmpty().ifBlank { it.tagName } to it.textContent.trim() }
    }

    fun didl(metadata: DlnaMetadata): String = buildString {
        append(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
                """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id=""""
        )
        append(escape(metadata.id))
        append("\" parentID=\"0\" restricted=\"1\"><dc:title>")
        append(escape(metadata.title))
        append("</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        metadata.artist?.let { append("<upnp:artist>${escape(it)}</upnp:artist>") }
        metadata.album?.let { append("<upnp:album>${escape(it)}</upnp:album>") }
        metadata.artwork?.let { append("<upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>") }
        // A Subsonic original stream can be FLAC. Never falsely label unknown media as MP3.
        val mime = metadata.mimeType?.takeIf { it.matches(Regex("audio/[A-Za-z0-9.+-]+")) } ?: "*"
        append("<res protocolInfo=\"http-get:*:$mime:*\"")
        metadata.durationMs?.takeIf { it > 0 }?.let { append(" duration=\"${formatTime(it)}\"") }
        append(">${escape(metadata.uri)}</res></item></DIDL-Lite>")
    }

    fun parseTime(value: String?): Long? {
        val match = Regex("(\\d+):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{1,3}))?").matchEntire(value.orEmpty()) ?: return null
        val hours = match.groupValues[1].toLongOrNull()?.takeIf { it < 1_000_000 } ?: return null
        return ((hours * 60 + match.groupValues[2].toLong()) * 60 + match.groupValues[3].toLong()) * 1000 +
            match.groupValues[4].padEnd(3, '0').toLong()
    }

    fun formatTime(positionMs: Long): String {
        val seconds = positionMs.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun parse(xml: String): Element {
        require(xml.length <= MAX_XML_CHARS) { "DLNA XML too large" }
        // Android's parser does not implement all Xerces feature switches. Reject DTDs before
        // parsing and disable entities where supported; the resolver always rejects external IO.
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "DLNA XML contains a DTD" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External DLNA XML entity rejected") }
        }.parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.children(name: String? = null): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { name == null || (it.localName ?: it.tagName) == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()
    private fun Element.childText(name: String): String = child(name)?.textContent?.trim().orEmpty()
    private fun Element.descendants(name: String): List<Element> = getElementsByTagNameNS("*", name)
        .let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
}
