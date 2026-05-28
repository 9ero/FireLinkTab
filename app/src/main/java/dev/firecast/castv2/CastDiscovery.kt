package dev.firecast.castv2

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

// Advertises this device on the local network as a Chromecast-compatible receiver.
// Chrome/Brave scans for _googlecast._tcp services to populate the Cast menu.
class CastDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null
    var onRegistered: ((String) -> Unit)? = null

    fun start(deviceId: String, friendlyName: String, castPort: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = friendlyName
            serviceType = SERVICE_TYPE
            port        = castPort
            // TXT records Chrome looks for
            setAttribute("id",  deviceId)          // unique device ID
            setAttribute("ve",  "05")              // protocol version
            setAttribute("ca",  "4101")            // capabilities: video + audio
            setAttribute("st",  "0")               // status: idle
            setAttribute("fn",  friendlyName)      // friendly name shown in Cast menu
            setAttribute("md",  "Chromecast")      // model name
            setAttribute("rs",  "")               // currently running app (empty = idle)
            setAttribute("bs",  "FA8FCA000000")    // placeholder BSSID
            setAttribute("ic",  "/setup/icon.png") // icon path
        }

        listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered: ${registered.serviceName}")
                onRegistered?.invoke(registered.serviceName)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS unregistration failed: $code")
            }
        }

        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        private const val TAG          = "CastDiscovery"
        private const val SERVICE_TYPE = "_googlecast._tcp"
    }
}
