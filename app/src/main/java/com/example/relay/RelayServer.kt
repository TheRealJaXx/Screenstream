package com.example.relay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TxRelayStats(
    val framesCaptured: Long = 0L,
    val framesSubmitted: Long = 0L,
    val framesSent: Long = 0L,
    val framesDropped: Long = 0L,
    val lastFrameSizeBytes: Int = 0,
    val isClientConnected: Boolean = false,
    val connectedClientsCount: Int = 0,
    val audioChunksCaptured: Long = 0L,
    val audioChunksSent: Long = 0L,
    val activeCodec: String = "H.264"
)

class RelayServer(
    private val context: Context? = null,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val VIDEO_FRAME_MAGIC = 0x53523202.toInt()
    }

    private var videoServerSocket: ServerSocket? = null
    private var audioServerSocket: ServerSocket? = null
    private var rttServerSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val isRunning = AtomicBoolean(false)
    private var serverScope: CoroutineScope? = null

    private val _txStats = MutableStateFlow(TxRelayStats())
    val txStats: StateFlow<TxRelayStats> = _txStats.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _connectedClientAddress = MutableStateFlow<String?>(null)
    val connectedClientAddress: StateFlow<String?> = _connectedClientAddress.asStateFlow()

    private val _serverPort = MutableStateFlow(4120)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _isServerActive = MutableStateFlow(false)
    val isServerActive: StateFlow<Boolean> = _isServerActive.asStateFlow()

    class VideoFramePayload(
        val data: ByteArray,
        val size: Int,
        val sequenceNumber: Int,
        val flags: Int = 0,
        val onWritten: (() -> Unit)? = null
    )

    private class VideoClientSession(
        val id: Int,
        val socket: Socket,
        val job: Job,
        val channel: Channel<VideoFramePayload>
    )

    private class AudioClientSession(
        val id: Int,
        val socket: Socket,
        val job: Job,
        val channel: Channel<ByteArray>
    )

    private val clientCounter = AtomicInteger(0)
    private val videoSeqCounter = AtomicInteger(0)
    @Volatile
    private var currentVideoSession: VideoClientSession? = null
    @Volatile
    private var currentAudioSession: AudioClientSession? = null

    @Volatile
    var targetFps: Int = 60
    @Volatile
    var send30FpsOnly: Boolean = false
    private var send30FpsSkipNext = false

    private var framesCapturedAcc = 0L
    private var framesSubmittedAcc = 0L
    private var framesSentAcc = 0L
    private var framesDroppedAcc = 0L
    private var audioChunksCapturedAcc = 0L
    private var audioChunksSentAcc = 0L
    private var lastStatsUpdateMs = 0L

    fun start(port: Int = _serverPort.value) {
        if (isRunning.get()) {
            if (_serverPort.value == port) return
            stop()
        }

        _serverPort.value = port
        isRunning.set(true)
        _isServerActive.value = true

        context?.let { ctx ->
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenStream:TxWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                onLog("TX-Relay", "WakeLock warning: ${e.message}", false)
            }

            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(wifiMode, "ScreenStream:TxWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                onLog("TX-Relay", "WifiLock warning: ${e.message}", false)
            }
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverScope = scope

        scope.launch { startVideoServer(port) }
        scope.launch { startAudioServer(port + 1) }
        scope.launch { startRttServer(port + 2) }

        onLog("TX-Relay", "ScreenStream Low-Latency Server listening on TCP ports $port (Video), ${port + 1} (Audio), ${port + 2} (RTT)", false)
    }

    private suspend fun startVideoServer(port: Int) {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", port), 4)
            videoServerSocket = server

            while (isRunning.get() && serverScope?.isActive == true) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.sendBufferSize = 512 * 1024
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY

                val clientIp = socket.inetAddress.hostAddress
                val clientId = clientCounter.incrementAndGet()
                onLog("TX-Relay", "Receiver Video client connected from $clientIp (session #$clientId)", false)

                currentVideoSession?.let { old ->
                    try {
                        old.channel.close()
                        old.job.cancel()
                        old.socket.close()
                    } catch (_: Exception) {}
                }

                val channel = Channel<VideoFramePayload>(
                    capacity = 2,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )

                val job = serverScope!!.launch {
                    handleVideoClient(clientId, socket, channel)
                }

                currentVideoSession = VideoClientSession(clientId, socket, job, channel)
                _connectedClients.value = 1
                _connectedClientAddress.value = clientIp
                updateStats(isClientConnected = true, connectedCount = 1)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                onLog("TX-Relay", "Video server error: ${e.message}", true)
            }
        }
    }

    private suspend fun handleVideoClient(
        clientId: Int,
        socket: Socket,
        channel: Channel<VideoFramePayload>
    ) {
        val out = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
        val dataOut = DataOutputStream(out)
        try {
            for (frame in channel) {
                if (!isRunning.get()) break
                dataOut.writeInt(VIDEO_FRAME_MAGIC)
                dataOut.writeInt(frame.size)
                dataOut.writeInt(frame.sequenceNumber)
                dataOut.writeInt(frame.flags)
                dataOut.write(frame.data, 0, frame.size)
                dataOut.flush()

                frame.onWritten?.invoke()
                framesSentAcc++
                updateStats(lastSize = frame.size)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                onLog("TX-Relay", "Receiver Video client #$clientId disconnected: ${e.message}", false)
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
            if (currentVideoSession?.id == clientId) {
                currentVideoSession = null
                _connectedClients.value = 0
                _connectedClientAddress.value = null
                updateStats(isClientConnected = false, connectedCount = 0)
            }
        }
    }

    private suspend fun startAudioServer(port: Int) {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", port), 4)
            audioServerSocket = server

            while (isRunning.get() && serverScope?.isActive == true) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.sendBufferSize = 64 * 1024
                socket.trafficClass = 0x10

                val clientId = clientCounter.incrementAndGet()
                val channel = Channel<ByteArray>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

                val job = serverScope!!.launch {
                    val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
                    val dataOut = DataOutputStream(out)
                    try {
                        for (chunk in channel) {
                            if (!isRunning.get()) break
                            dataOut.writeInt(chunk.size)
                            dataOut.write(chunk)
                            dataOut.flush()
                            audioChunksSentAcc++
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                currentAudioSession = AudioClientSession(clientId, socket, job, channel)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                onLog("TX-Relay", "Audio server error: ${e.message}", true)
            }
        }
    }

    private suspend fun startRttServer(port: Int) {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", port), 4)
            rttServerSocket = server

            while (isRunning.get() && serverScope?.isActive == true) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                serverScope!!.launch {
                    try {
                        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                        while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                            val clientTime = input.readLong()
                            output.writeLong(clientTime)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun submitVideoFrame(data: ByteArray, size: Int = data.size, isKeyframe: Boolean = false, onWritten: (() -> Unit)? = null) {
        if (!isRunning.get()) return
        framesCapturedAcc++

        if (send30FpsOnly) {
            send30FpsSkipNext = !send30FpsSkipNext
            if (send30FpsSkipNext) {
                framesDroppedAcc++
                return
            }
        }

        framesSubmittedAcc++
        val session = currentVideoSession
        if (session == null) {
            updateStats(lastSize = size)
            onWritten?.invoke()
            return
        }

        val seq = videoSeqCounter.incrementAndGet()
        val flags = if (isKeyframe) 1 else 0
        val payload = VideoFramePayload(data, size, seq, flags, onWritten)

        val result = session.channel.trySend(payload)
        if (!result.isSuccess) {
            framesDroppedAcc++
            onWritten?.invoke()
        }
    }

    fun submitAudioChunk(chunk: ByteArray) {
        if (!isRunning.get()) return
        audioChunksCapturedAcc++
        currentAudioSession?.channel?.trySend(chunk)
    }

    private fun updateStats(
        lastSize: Int? = null,
        isClientConnected: Boolean? = null,
        connectedCount: Int? = null,
        codec: String? = null
    ) {
        val now = System.currentTimeMillis()
        if (now - lastStatsUpdateMs < 150 && lastSize == null) return
        lastStatsUpdateMs = now

        val cur = _txStats.value
        _txStats.value = cur.copy(
            framesCaptured = framesCapturedAcc,
            framesSubmitted = framesSubmittedAcc,
            framesSent = framesSentAcc,
            framesDropped = framesDroppedAcc,
            lastFrameSizeBytes = lastSize ?: cur.lastFrameSizeBytes,
            isClientConnected = isClientConnected ?: cur.isClientConnected,
            connectedClientsCount = connectedCount ?: cur.connectedClientsCount,
            audioChunksCaptured = audioChunksCapturedAcc,
            audioChunksSent = audioChunksSentAcc,
            activeCodec = codec ?: cur.activeCodec
        )
    }

    fun setCodec(codec: String) {
        _txStats.value = _txStats.value.copy(activeCodec = codec)
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        _isServerActive.value = false

        try {
            currentVideoSession?.let {
                it.channel.close()
                it.job.cancel()
                it.socket.close()
            }
        } catch (_: Exception) {}
        currentVideoSession = null

        try {
            currentAudioSession?.let {
                it.channel.close()
                it.job.cancel()
                it.socket.close()
            }
        } catch (_: Exception) {}
        currentAudioSession = null

        try { videoServerSocket?.close() } catch (_: Exception) {}
        videoServerSocket = null

        try { audioServerSocket?.close() } catch (_: Exception) {}
        audioServerSocket = null

        try { rttServerSocket?.close() } catch (_: Exception) {}
        rttServerSocket = null

        serverScope?.cancel()
        serverScope = null

        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null

        _connectedClients.value = 0
        _connectedClientAddress.value = null
        updateStats(isClientConnected = false, connectedCount = 0)
    }
}
