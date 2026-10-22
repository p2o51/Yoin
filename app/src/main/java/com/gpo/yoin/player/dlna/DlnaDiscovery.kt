package com.gpo.yoin.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class DlnaDiscovery(context: Context, private val client: DlnaClient) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    suspend fun discover(): List<DlnaDevice> = coroutineScope {
        val locations = search()
        // Bound description requests: untrusted multicast traffic must not create unlimited IO jobs.
        locations.take(32).map { location ->
            async {
                try {
                    client.describe(location)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy(DlnaDevice::id).sortedBy { it.name.lowercase() }
    }

    private suspend fun search(): Set<URI> = withContext(Dispatchers.IO) {
        val lock = wifi?.createMulticastLock("YoinDlnaDiscovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            MulticastSocket().use { socket ->
                socket.timeToLive = 2
                socket.soTimeout = 500
                val destination = InetAddress.getByName("239.255.255.250")
                val targets =
                    listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")
                repeat(2) {
                    targets.forEach { target ->
                        val bytes = (
                            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(bytes, bytes.size, destination, 1900))
                    }
                }
                val locations = linkedSetOf<URI>()
                val deadline = System.nanoTime() + 4_000_000_000L
                val buffer = ByteArray(16_384)
                while (System.nanoTime() < deadline && locations.size < 32) {
                    coroutineContext.ensureActive()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        DlnaProtocol.ssdpLocation(
                            String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        )?.let(locations::add)
                    } catch (_: SocketTimeoutException) {
                        // Recheck cancellation and scan deadline.
                    }
                }
                locations
            }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }
}
