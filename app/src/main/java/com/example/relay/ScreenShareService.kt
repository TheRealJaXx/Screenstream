package com.example.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.AudioMode
import com.example.model.ScreenResolution
import com.example.model.ScreenShareConfig
import com.example.model.StreamBitrate
import com.example.model.TargetFps
import com.example.model.VideoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service responsible for:
 * 1. Maintaining the Android MediaProjection lifecycle across app minimize / app switches.
 * 2. Running RelayServer (TCP socket streamer) and ScreenWebServer (HTTP browser player).
 * 3. Holding CPU WakeLock and Low-Latency WifiLock so screen streaming never sleeps.
 */
class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val NOTIFICATION_ID = 4040
        private const val CHANNEL_ID = "screenshare_channel"

        const val ACTION_START = "com.example.relay.ACTION_START"
        const val ACTION_STOP = "com.example.relay.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        @Volatile
        var activeInstance: ScreenShareService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        val service: ScreenShareService get() = this@ScreenShareService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main)

    var relayServer: RelayServer? = null
        private set
    var webServer: ScreenWebServer? = null
        private set
    var discoveryManager: OmtDiscoveryManager? = null
        private set
    var screenCaptureEngine: ScreenCaptureEngine? = null
        private set
    var audioCaptureEngine: AudioCaptureEngine? = null
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null

    val latestThumbnailBitmap = MutableStateFlow<Bitmap?>(null)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopScreenShare()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != 0 && resultData != null) {
                startForegroundWithNotification()
                startScreenShare(resultCode, resultData)
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScreenStream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground Service for Low Latency Screen Sharing"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val localIp = NetworkUtils.getLocalIpAddress()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenStream Live")
            .setContentText("Broadcasting screen • Web: http://$localIp:8080")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Sharing", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenShare(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: return
        mediaProjection = projection

        // Acquire WakeLock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenStream:ForegroundWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }

        // Initialize Relay Server (Native Socket)
        val server = RelayServer(this) { tag, msg, err ->
            Log.d(tag, msg)
        }
        server.start(4120)
        relayServer = server

        // Initialize Web Server (HTTP Web Player & MJPEG)
        val web = ScreenWebServer { tag, msg, err ->
            Log.d(tag, msg)
        }
        web.start(8080)
        webServer = web

        // Initialize LAN Discovery
        val discovery = OmtDiscoveryManager(this) { tag, msg, err ->
            Log.d(tag, msg)
        }
        discovery.startBroadcasting(4120, 8080)
        discoveryManager = discovery

        val config = ScreenShareConfig()

        // Initialize Screen Capture
        val capture = ScreenCaptureEngine(
            context = this,
            mediaProjection = projection,
            config = config,
            onH264Frame = { data, isKeyframe ->
                server.submitVideoFrame(data, data.size, isKeyframe)
            },
            onJpegFrame = { jpeg ->
                web.submitJpegFrame(jpeg)
            },
            onBitmapFrame = { bmp ->
                latestThumbnailBitmap.value = bmp
            },
            onLog = { tag, msg, err ->
                Log.d(tag, msg)
            }
        )
        capture.start()
        screenCaptureEngine = capture

        // Initialize Audio Capture
        val audio = AudioCaptureEngine(
            context = this,
            audioMode = AudioMode.SYSTEM_AND_MIC,
            mediaProjection = projection,
            onAudioChunk = { chunk ->
                server.submitAudioChunk(chunk)
            },
            onLog = { tag, msg, err ->
                Log.d(tag, msg)
            }
        )
        audio.start()
        audioCaptureEngine = audio

        _isServiceRunning.value = true
    }

    fun stopScreenShare() {
        _isServiceRunning.value = false

        try { audioCaptureEngine?.stop() } catch (_: Exception) {}
        audioCaptureEngine = null

        try { screenCaptureEngine?.stop() } catch (_: Exception) {}
        screenCaptureEngine = null

        try { discoveryManager?.stopBroadcasting() } catch (_: Exception) {}
        discoveryManager = null

        try { webServer?.stop() } catch (_: Exception) {}
        webServer = null

        try { relayServer?.stop() } catch (_: Exception) {}
        relayServer = null

        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        latestThumbnailBitmap.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopScreenShare()
        activeInstance = null
        super.onDestroy()
    }
}
