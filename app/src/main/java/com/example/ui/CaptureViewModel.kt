package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.AppMode
import com.example.model.AudioMode
import com.example.model.DiscoveredTransmitter
import com.example.model.LogEntry
import com.example.model.ScreenResolution
import com.example.model.ScreenShareConfig
import com.example.model.StreamBitrate
import com.example.model.TargetFps
import com.example.model.VideoCodec
import com.example.relay.AudioLatencyProfile
import com.example.relay.LatencyTestEngine
import com.example.relay.NetworkUtils
import com.example.relay.OmtDiscoveryManager
import com.example.relay.QrCodeGenerator
import com.example.relay.RelayClient
import com.example.relay.RelayServer
import com.example.relay.RxConnectionState
import com.example.relay.RxStats
import com.example.relay.ScreenShareService
import com.example.relay.TxRelayStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _currentMode = MutableStateFlow(AppMode.UNSELECTED)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    private val _config = MutableStateFlow(ScreenShareConfig())
    val config: StateFlow<ScreenShareConfig> = _config.asStateFlow()

    private val _localIp = MutableStateFlow(NetworkUtils.getLocalIpAddress())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _txPort = MutableStateFlow(4120)
    val txPort: StateFlow<Int> = _txPort.asStateFlow()

    private val _txConnectedClients = MutableStateFlow(0)
    val txConnectedClients: StateFlow<Int> = _txConnectedClients.asStateFlow()

    private val _isTxServerActive = MutableStateFlow(false)
    val isTxServerActive: StateFlow<Boolean> = _isTxServerActive.asStateFlow()

    private val _webUrl = MutableStateFlow("http://${_localIp.value}:8080")
    val webUrl: StateFlow<String> = _webUrl.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Discovery & Receiver
    private val discoveryManager = OmtDiscoveryManager(application) { tag, msg, err ->
        addLog(tag, msg, err)
    }
    val discoveredTransmitters = discoveryManager.discoveredTransmitters

    val relayClient = RelayClient { tag, msg, err ->
        addLog(tag, msg, err)
    }
    val rxStats = relayClient.rxStats
    val rxConnectionState = relayClient.connectionState
    val selectedLatencyProfile = relayClient.selectedLatencyProfile

    // Transmitter Telemetry
    private val _txStats = MutableStateFlow(TxRelayStats())
    val txStats: StateFlow<TxRelayStats> = _txStats.asStateFlow()

    private val _connectedWebViewers = MutableStateFlow(0)
    val connectedWebViewers: StateFlow<Int> = _connectedWebViewers.asStateFlow()

    val isSharingScreen = ScreenShareService.isServiceRunning

    // Standalone Precision Latency Test Engine
    val latencyTestEngine: LatencyTestEngine = LatencyTestEngine(
        onFrameGenerated = { jpegBytes ->
            // If RelayServer is running locally or in service, submit frame
            val service = ScreenShareService.activeInstance
            service?.relayServer?.submitVideoFrame(jpegBytes, jpegBytes.size, false)
            service?.webServer?.submitJpegFrame(jpegBytes)
        },
        onAudioChunkGenerated = { audioBytes ->
            val service = ScreenShareService.activeInstance
            service?.relayServer?.submitAudioChunk(audioBytes)
        }
    )

    private val _testFps = MutableStateFlow(60)
    val testFps: StateFlow<Int> = _testFps.asStateFlow()

    private val _testAudioTick = MutableStateFlow(true)
    val testAudioTick: StateFlow<Boolean> = _testAudioTick.asStateFlow()

    val testFramesGenerated: StateFlow<Long> = latencyTestEngine.framesGenerated

    init {
        refreshNetworkInfo()
        generateQr()

        // Sync telemetry with ScreenShareService
        viewModelScope.launch {
            ScreenShareService.isServiceRunning.collect { isRunning ->
                _isTxServerActive.value = isRunning
                if (isRunning) {
                    val service = ScreenShareService.activeInstance
                    service?.relayServer?.let { server ->
                        launch {
                            server.txStats.collect { stats ->
                                _txStats.value = stats
                                _txConnectedClients.value = stats.connectedClientsCount
                            }
                        }
                    }
                    service?.webServer?.let { web ->
                        launch {
                            web.connectedWebViewers.collect { viewers ->
                                _connectedWebViewers.value = viewers
                            }
                        }
                    }
                } else {
                    _connectedWebViewers.value = 0
                    _txConnectedClients.value = 0
                }
            }
        }
    }

    fun selectMode(mode: AppMode) {
        _currentMode.value = mode
        if (mode == AppMode.RX) {
            discoveryManager.startScanning()
        } else {
            discoveryManager.stopScanning()
        }

        if (mode == AppMode.TEST_TX) {
            latencyTestEngine.start()
        } else {
            latencyTestEngine.stop()
        }
    }

    fun setTestFps(fps: Int) {
        _testFps.value = fps
        latencyTestEngine.setTargetFps(fps)
    }

    fun setTestAudioTick(enabled: Boolean) {
        _testAudioTick.value = enabled
        latencyTestEngine.setAudioTick(enabled)
    }

    fun refreshLocalIp() {
        refreshNetworkInfo()
    }

    fun refreshNetworkInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        _localIp.value = ip
        val url = "http://$ip:${_config.value.webPort}"
        _webUrl.value = url
        generateQr()
    }

    fun generateQr() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bmp = QrCodeGenerator.createQrBitmap(_webUrl.value, 480)
                _qrBitmap.value = bmp
            } catch (_: Exception) {}
        }
    }

    fun updateResolution(resolution: ScreenResolution) {
        _config.value = _config.value.copy(resolution = resolution)
    }

    fun updateFps(fps: TargetFps) {
        _config.value = _config.value.copy(fps = fps)
    }

    fun updateBitrate(bitrate: StreamBitrate) {
        _config.value = _config.value.copy(bitrate = bitrate)
    }

    fun updateCodec(codec: VideoCodec) {
        _config.value = _config.value.copy(codec = codec)
        ScreenShareService.activeInstance?.relayServer?.setCodec(codec.label)
    }

    fun updateAudioMode(mode: AudioMode) {
        _config.value = _config.value.copy(audioMode = mode)
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(app, intent)
        addLog("ScreenShare", "Starting ScreenStream Foreground Service...", false)
    }

    fun stopScreenShare() {
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        app.startService(intent)
        addLog("ScreenShare", "Stopping ScreenStream Foreground Service...", false)
    }

    fun connectRx(host: String, port: Int = 4120) {
        relayClient.start(host, port)
    }

    fun disconnectRx() {
        relayClient.stop()
    }

    fun setRxSurfaceHolder(holder: SurfaceHolder?) {
        relayClient.targetSurfaceHolder = holder
    }

    fun setAudioLatencyProfile(profile: AudioLatencyProfile) {
        relayClient.setLatencyProfile(profile)
    }

    fun addLog(tag: String, message: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            tag = tag,
            message = message,
            isError = isError
        )
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 80) current.removeAt(current.size - 1)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopScanning()
        relayClient.stop()
        latencyTestEngine.stop()
    }
}
