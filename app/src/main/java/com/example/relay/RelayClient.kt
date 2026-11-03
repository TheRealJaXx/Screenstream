package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class RxConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class AudioLatencyProfile(val label: String, val targetMinMs: Int, val capMaxMs: Int) {
    GAME("Ultra Game (30 ms)", 30, 100),
    BALANCED("Balanced (50 ms)", 50, 160),
    SMOOTH("Smooth (80 ms)", 80, 240)
}

data class RxStats(
    val videoPacketsReceived: Long = 0L,
    val videoBytesPerSecond: Long = 0L,
    val audioPacketsReceived: Long = 0L,
    val fps: Float = 0f,
    val decodeTimeMs: Long = 0L,
    val decodeTimeMsAvg: Float = 0f,
    val frameAgeMs: Float = 0f,
    val droppedFrames: Long = 0L,
    val rttAvgMs: Float = 0f,
    val frameSizeBytes: Int = 0,
    val statusDetail: String = "Disconnected",
    val videoCodec: String = "H.264"
)

class RelayClient(
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_MS = (SAMPLE_RATE * 4) / 1000 // 192 bytes
    }

    private val isRunning = AtomicBoolean(false)
    private var clientScope: CoroutineScope? = null

    private val _connectionState = MutableStateFlow(RxConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RxConnectionState> = _connectionState.asStateFlow()

    private val _rxStats = MutableStateFlow(RxStats())
    val rxStats: StateFlow<RxStats> = _rxStats.asStateFlow()

    private val _selectedLatencyProfile = MutableStateFlow(AudioLatencyProfile.GAME)
    val selectedLatencyProfile: StateFlow<AudioLatencyProfile> = _selectedLatencyProfile.asStateFlow()

    @Volatile
    var targetSurfaceHolder: SurfaceHolder? = null

    private var h264Decoder: HardwareH264Decoder? = null
    private var activeDecoderSurface: Surface? = null

    // Telemetry accumulators
    private var videoPacketsCount = 0L
    private var audioPacketsCount = 0L
    private var droppedFramesCount = 0L
    private var bytesInWindow = 0L
    private var windowStartTime = SystemClock.uptimeMillis()
    private val frameTimes = ArrayDeque<Long>(64)
    private var lastDecodedWidth = 0
    private var lastDecodedHeight = 0

    // RTT probe telemetry
    private val rttSamples = ArrayDeque<Long>(10)

    fun setLatencyProfile(profile: AudioLatencyProfile) {
        _selectedLatencyProfile.value = profile
    }

    fun start(host: String, port: Int = 4120) {
        if (isRunning.get()) stop()

        isRunning.set(true)
        _connectionState.value = RxConnectionState.CONNECTING
        val scope = CoroutineScope(Dispatchers.IO)
        clientScope = scope

        onLog("RX-Client", "Connecting to ScreenStream Host $host on ports $port (Video), ${port + 1} (Audio)...", false)

        scope.launch { runVideoLoop(host, port) }
        scope.launch { runAudioLoop(host, port + 1) }
        scope.launch { runRttLoop(host, port + 2) }
    }

    private suspend fun runVideoLoop(host: String, port: Int) {
        while (isRunning.get() && clientScope?.isActive == true) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.trafficClass = 0x10
                socket.connect(InetSocketAddress(host, port), 4000)

                _connectionState.value = RxConnectionState.CONNECTED
                onLog("RX-Client", "Connected to Video stream at $host:$port", false)

                val inputStream = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
                val frameBuffer = ByteArray(4 * 1024 * 1024)

                while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                    val magic = inputStream.readInt()
                    if (magic != RelayServer.VIDEO_FRAME_MAGIC) {
                        onLog("RX-Client", "Protocol sync error (magic: 0x${Integer.toHexString(magic)})", true)
                        break
                    }

                    val frameSize = inputStream.readInt()
                    val seq = inputStream.readInt()
                    val flags = inputStream.readInt()

                    if (frameSize <= 0 || frameSize > frameBuffer.size) {
                        onLog("RX-Client", "Invalid frame size: $frameSize bytes", true)
                        break
                    }

                    inputStream.readFully(frameBuffer, 0, frameSize)
                    videoPacketsCount++
                    bytesInWindow += (frameSize + 16)

                    val startDecode = SystemClock.uptimeMillis()
                    val isH264 = isH264Stream(frameBuffer, frameSize)

                    if (isH264) {
                        renderH264Frame(frameBuffer, frameSize)
                    } else {
                        renderMjpegFrame(frameBuffer, frameSize)
                    }

                    val decodeTime = SystemClock.uptimeMillis() - startDecode
                    recordFrameArrival(decodeTime, frameSize, if (isH264) "H.264" else "MJPEG")
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    _connectionState.value = RxConnectionState.RECONNECTING
                    onLog("RX-Client", "Video connection dropped: ${e.message}. Reconnecting in 1.5s...", false)
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }

            if (isRunning.get()) {
                delay(1500)
            }
        }
    }

    private fun isH264Stream(buffer: ByteArray, size: Int): Boolean {
        if (size < 4) return false
        // Check for NAL start code (0x00 0x00 0x00 0x01) or (0x00 0x00 0x01)
        if (buffer[0] == 0.toByte() && buffer[1] == 0.toByte() &&
            ((buffer[2] == 0.toByte() && buffer[3] == 1.toByte()) || buffer[2] == 1.toByte())) {
            return true
        }
        return false
    }

    private fun renderH264Frame(buffer: ByteArray, size: Int) {
        val holder = targetSurfaceHolder ?: return
        val surface = holder.surface
        if (!surface.isValid) return

        if (h264Decoder == null || activeDecoderSurface != surface) {
            h264Decoder?.stop()
            activeDecoderSurface = surface
            val decoder = HardwareH264Decoder(surface) { w, h ->
                lastDecodedWidth = w
                lastDecodedHeight = h
            }
            decoder.init(1280, 720)
            h264Decoder = decoder
        }

        val frameData = ByteArray(size)
        System.arraycopy(buffer, 0, frameData, 0, size)
        h264Decoder?.decodeFrame(frameData)
    }

    private fun renderMjpegFrame(buffer: ByteArray, size: Int) {
        val holder = targetSurfaceHolder ?: return
        if (!holder.surface.isValid) return

        try {
            val bitmap = BitmapFactory.decodeByteArray(buffer, 0, size) ?: return
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val destRect = Rect(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bitmap, null, destRect, null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {}
    }

    private suspend fun runAudioLoop(host: String, port: Int) {
        var audioTrack: AudioTrack? = null
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

        try {
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    minBufSize * 2,
                    AudioTrack.MODE_STREAM
                )
            }

            track.play()
            audioTrack = track
        } catch (e: Exception) {
            onLog("RX-Audio", "Failed to init AudioTrack: ${e.message}", true)
        }

        while (isRunning.get() && clientScope?.isActive == true) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 4000)

                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 32 * 1024))
                val buffer = ByteArray(16 * 1024)

                while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                    val chunkSize = input.readInt()
                    if (chunkSize <= 0 || chunkSize > buffer.size) break

                    input.readFully(buffer, 0, chunkSize)
                    audioPacketsCount++

                    audioTrack?.write(buffer, 0, chunkSize)
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }

            if (isRunning.get()) delay(2000)
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
    }

    private suspend fun runRttLoop(host: String, port: Int) {
        while (isRunning.get() && clientScope?.isActive == true) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 4000)

                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                    val t0 = SystemClock.uptimeMillis()
                    out.writeLong(t0)
                    out.flush()

                    val echo = input.readLong()
                    val rtt = SystemClock.uptimeMillis() - echo
                    if (rtt in 0..5000) {
                        synchronized(rttSamples) {
                            if (rttSamples.size >= 10) rttSamples.removeFirst()
                            rttSamples.addLast(rtt)
                        }
                    }
                    delay(500)
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            if (isRunning.get()) delay(2000)
        }
    }

    private fun recordFrameArrival(decodeTimeMs: Long, sizeBytes: Int, codec: String) {
        val now = SystemClock.uptimeMillis()
        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1000) {
            frameTimes.removeFirst()
        }

        val fps = frameTimes.size.toFloat()

        val windowElapsed = now - windowStartTime
        val bps = if (windowElapsed >= 1000) {
            val rate = (bytesInWindow * 8 * 1000) / windowElapsed
            bytesInWindow = 0
            windowStartTime = now
            rate
        } else {
            _rxStats.value.videoBytesPerSecond
        }

        val avgRtt = synchronized(rttSamples) {
            if (rttSamples.isEmpty()) 0f else rttSamples.average().toFloat()
        }

        _rxStats.value = RxStats(
            videoPacketsReceived = videoPacketsCount,
            videoBytesPerSecond = bps,
            audioPacketsReceived = audioPacketsCount,
            fps = fps,
            decodeTimeMs = decodeTimeMs,
            decodeTimeMsAvg = decodeTimeMs.toFloat(),
            droppedFrames = droppedFramesCount,
            rttAvgMs = avgRtt,
            frameSizeBytes = sizeBytes,
            statusDetail = "Connected (${_connectionState.value})",
            videoCodec = codec
        )
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        _connectionState.value = RxConnectionState.DISCONNECTED

        h264Decoder?.stop()
        h264Decoder = null
        activeDecoderSurface = null

        try {
            clientScope?.launch {
                // Cancel running loops
            }
        } catch (_: Exception) {}
        clientScope = null
    }
}
