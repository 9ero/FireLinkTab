package dev.firecast.castv2

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

// Advertises <hostname>.local → device IP as an mDNS A record (RFC 6762).
// Uses SO_REUSEADDR to coexist with Android's system mDNS daemon on port 5353.
class MdnsLocalServer(
    private val context: Context,
    private val localIp: String,
    private val hostname: String,   // e.g. "firelink-sala" (without .local)
) {
    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("MdnsLocalServer").also {
            it.setReferenceCounted(true)
            it.acquire()
        }
        running = true
        thread(name = "MdnsLocalServer") {
            try {
                val group = InetAddress.getByName(MDNS_ADDR)
                val socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_PORT))
                }
                socket.joinGroup(group)
                Log.i(TAG, "mDNS listening → $hostname.local = $localIp")
                announce(socket, group)

                val buf = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (isQuery(packet.data, packet.length)) {
                        val resp = buildResponse()
                        socket.send(DatagramPacket(resp, resp.size, group, MDNS_PORT))
                        Log.d(TAG, "mDNS reply → $hostname.local = $localIp")
                    }
                }
                socket.leaveGroup(group)
                socket.close()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        multicastLock?.release()
        multicastLock = null
    }

    private fun announce(socket: MulticastSocket, group: InetAddress) {
        try {
            val resp = buildResponse()
            socket.send(DatagramPacket(resp, resp.size, group, MDNS_PORT))
            Log.i(TAG, "mDNS announcement sent → $hostname.local = $localIp")
        } catch (e: Exception) {
            Log.w(TAG, "announcement failed: ${e.message}")
        }
    }

    private fun isQuery(data: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return false  // bit 15 = 1 means response, not query
        return String(data, 0, len, Charsets.ISO_8859_1).contains(hostname, ignoreCase = true)
    }

    // DNS name encoding: each label is length-prefixed, terminated with \x00.
    // e.g. "firelink-sala.local" → \x0Cfirelink-sala\x05local\x00
    private fun encodeName(): ByteArray {
        val label = hostname.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(label.size.toByte()) + label +
               byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
    }

    private fun buildResponse(): ByteArray {
        val ip  = InetAddress.getByName(localIp).address
        val ttl = 120
        val header = byteArrayOf(
            0x00, 0x00,                    // Transaction ID: 0 (mDNS standard)
            0x84.toByte(), 0x00,           // Flags: QR=1 (response) | AA=1 (authoritative)
            0x00, 0x00,                    // QDCOUNT: 0
            0x00, 0x01,                    // ANCOUNT: 1
            0x00, 0x00,                    // NSCOUNT: 0
            0x00, 0x00,                    // ARCOUNT: 0
        )
        val rr = byteArrayOf(
            0x00, 0x01,                    // Type: A
            0x80.toByte(), 0x01,           // Class: IN (0x0001) + cache-flush bit (0x8000)
            (ttl ushr 24).toByte(),
            (ttl ushr 16).toByte(),
            (ttl ushr 8).toByte(),
            ttl.toByte(),                  // TTL: 120 seconds
            0x00, 0x04,                    // RDLENGTH: 4 bytes
        ) + ip                             // RDATA: IPv4 address
        return header + encodeName() + rr
    }

    companion object {
        private const val TAG       = "MdnsLocalServer"
        private const val MDNS_ADDR = "224.0.0.251"
        private const val MDNS_PORT = 5353
    }
}
