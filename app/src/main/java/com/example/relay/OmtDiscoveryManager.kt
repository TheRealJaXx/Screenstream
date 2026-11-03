package com.example.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.model.DiscoveredTransmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Local Wi-Fi Discovery Manager for ScreenStream.
 * Implements dual-layer zero-configuration discovery:
 * 1. Standard DNS-SD / mDNS via Android NsdManager (_screenshare._tcp)
 * 2. High-speed UDP Broadcast Beacons on port 6399
 */
class OmtDiscoveryManager(
    private val context: Context,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val SERVICE_TYPE = "_screenshare._tcp."
        const val DISCOVERY_PORT = 6399
        private const val PRUNE_TIMEOUT_MS = 6000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    // Broadcast (TX) state
    private var isBroadcasting = false
    private var broadcastJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var activeBroadcastName: String? = null

    // Scanner (RX) state
    private var isScanning = false
    private var udpListenerJob: Job? = null
    private var pruneJob: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredMap = ConcurrentHashMap<String, DiscoveredTransmitter>()

    private val _discoveredTransmitters = MutableStateFlow<List<DiscoveredTransmitter>>(emptyList())
    val discoveredTransmitters: StateFlow<List<DiscoveredTransmitter>> = _discoveredTransmitters.asStateFlow()

    fun startBroadcasting(port: Int, webPort: Int = 8080) {
        if (isBroadcasting) return
        isBroadcasting = true

        val deviceName = "ScreenStream-${Build.MODEL.replace(" ", "-")}"
        activeBroadcastName = deviceName

        // 1. mDNS Registration via NsdManager
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    onLog("Discovery-TX", "mDNS broadcast registered: ${info.serviceName} on port $port", false)
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    onLog("Discovery-TX", "mDNS registration failed (error $errorCode)", true)
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = listener
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            onLog("Discovery-TX", "NsdManager error: ${e.message}", true)
        }

        // 2. High-speed UDP Broadcast Beacons on port 6399
        broadcastJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true

                while (isActive && isBroadcasting) {
                    val localIp = NetworkUtils.getLocalIpAddress()
                    val beacon = JSONObject().apply {
                        put("type", "screen_share_beacon")
                        put("name", deviceName)
                        put("ip", localIp)
                        put("port", port)
                        put("webPort", webPort)
                    }.toString()

                    val data = beacon.toByteArray()
                    val packet = DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName("255.255.255.255"),
                        DISCOVERY_PORT
                    )
                    try {
                        socket.send(packet)
                    } catch (_: Exception) {}

                    delay(1200)
                }
            } catch (e: Exception) {
                onLog("Discovery-TX", "UDP beacon error: ${e.message}", true)
            } finally {
                socket?.close()
            }
        }
    }

    fun stopBroadcasting() {
        if (!isBroadcasting) return
        isBroadcasting = false

        broadcastJob?.cancel()
        broadcastJob = null

        registrationListener?.let {
            try { nsdManager?.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null
        activeBroadcastName = null
    }

    fun startScanning() {
        if (isScanning) return
        isScanning = true
        discoveredMap.clear()
        _discoveredTransmitters.value = emptyList()

        try {
            multicastLock = wifiManager?.createMulticastLock("ScreenStreamScanLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        // Listen for UDP beacons
        udpListenerJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                    soTimeout = 3000
                }

                val buf = ByteArray(1024)
                while (isActive && isScanning) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)

                        val text = String(packet.data, 0, packet.length)
                        val json = JSONObject(text)
                        if (json.optString("type") == "screen_share_beacon") {
                            val name = json.optString("name", "ScreenStream Host")
                            val ip = packet.address.hostAddress ?: json.optString("ip")
                            val port = json.optInt("port", 4120)
                            val webPort = json.optInt("webPort", 8080)

                            val tx = DiscoveredTransmitter(
                                name = name,
                                host = ip,
                                port = port,
                                webPort = webPort,
                                lastSeenMs = System.currentTimeMillis()
                            )
                            discoveredMap["$ip:$port"] = tx
                            _discoveredTransmitters.value = discoveredMap.values.toList()
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                onLog("Discovery-RX", "UDP listener notice: ${e.message}", false)
            } finally {
                socket?.close()
            }
        }

        // Periodic pruning of dead hosts
        pruneJob = scope.launch {
            while (isActive && isScanning) {
                delay(3000)
                val cutoff = System.currentTimeMillis() - PRUNE_TIMEOUT_MS
                var changed = false
                discoveredMap.entries.removeIf { (_, tx) ->
                    val dead = tx.lastSeenMs < cutoff
                    if (dead) changed = true
                    dead
                }
                if (changed) {
                    _discoveredTransmitters.value = discoveredMap.values.toList()
                }
            }
        }
    }

    fun stopScanning() {
        if (!isScanning) return
        isScanning = false

        udpListenerJob?.cancel()
        udpListenerJob = null

        pruneJob?.cancel()
        pruneJob = null

        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
