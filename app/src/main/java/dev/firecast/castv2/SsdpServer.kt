package dev.firecast.castv2

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

// Responds to SSDP M-SEARCH broadcasts so Chrome's DIAL discovery finds this device.
// Chrome sends M-SEARCH on 239.255.255.250:1900 looking for dial-multiscreen-org services;
// we reply with our DIAL server location so Chrome registers us as a dial: sink.
class SsdpServer(
    private val context: Context,
    private val deviceId: String,
    private val localIp: String,
    private val dialPort: Int,
) {
    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("SsdpServer").also {
            it.setReferenceCounted(true)
            it.acquire()
        }

        running = true
        thread(name = "SsdpServer") {
            try {
                val group = InetAddress.getByName(MULTICAST_ADDR)
                val socket = MulticastSocket(SSDP_PORT)
                socket.joinGroup(group)
                Log.i(TAG, "SSDP listening on $MULTICAST_ADDR:$SSDP_PORT")

                val buf = ByteArray(2048)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg.startsWith("M-SEARCH") && msg.contains("dial-multiscreen-org:service:dial:1")) {
                        val rb = buildResponse().toByteArray()
                        socket.send(DatagramPacket(rb, rb.size, packet.address, packet.port))
                        Log.d(TAG, "M-SEARCH reply → ${packet.address.hostAddress}:${packet.port}")
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

    private fun buildResponse() =
        "HTTP/1.1 200 OK\r\n" +
        "LOCATION: http://$localIp:$dialPort/ssdp/device-desc.xml\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "EXT:\r\n" +
        "SERVER: Linux UPnP/1.0\r\n" +
        "ST: urn:dial-multiscreen-org:service:dial:1\r\n" +
        "USN: uuid:$deviceId::urn:dial-multiscreen-org:service:dial:1\r\n" +
        "\r\n"

    companion object {
        private const val TAG            = "SsdpServer"
        private const val MULTICAST_ADDR = "239.255.255.250"
        private const val SSDP_PORT      = 1900
    }
}
