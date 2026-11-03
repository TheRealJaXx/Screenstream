package com.example.model

enum class AppMode {
    UNSELECTED,
    TX,          // Screen Share Broadcaster (Transmitter)
    RX,          // Screen Receiver / Viewer
    TEST_TX,     // Precision Latency Test Pattern
    ROUND_TRIP   // Optical Glass-to-Glass Latency Benchmark
}

enum class VideoCodec(val label: String, val description: String) {
    H264("H.264 (Hardware AVC)", "Ultra-low latency (15-30ms) GPU encoded, best for app-to-app"),
    MJPEG("Universal MJPEG (Web)", "Instant browser playback on PC/Mac/Mobile, zero config")
}

enum class ScreenResolution(val label: String, val width: Int, val height: Int) {
    RES_1080P("1080p FHD (1920x1080)", 1920, 1080),
    RES_720P("720p HD (1280x720) [Recommended]", 1280, 720),
    RES_540P("540p qHD (960x540)", 960, 540)
}

enum class TargetFps(val label: String, val fps: Int) {
    FPS_60("60 FPS (Ultra Smooth)", 60),
    FPS_30("30 FPS (Power Saver)", 30)
}

enum class StreamBitrate(val label: String, val bps: Int) {
    BITRATE_2M("2 Mbps (Low Bandwidth)", 2_000_000),
    BITRATE_4M("4 Mbps (Balanced)", 4_000_000),
    BITRATE_8M("8 Mbps (High Quality)", 8_000_000),
    BITRATE_12M("12 Mbps (Studio Quality)", 12_000_000)
}

enum class AudioMode(val label: String) {
    SYSTEM_AND_MIC("System Audio + Mic"),
    SYSTEM_ONLY("System Audio (Games/Media)"),
    MIC_ONLY("Microphone Only"),
    MUTED("Muted")
}

data class DiscoveredTransmitter(
    val name: String,
    val host: String,
    val port: Int,
    val webPort: Int = 8080,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false
)

data class ScreenShareConfig(
    val resolution: ScreenResolution = ScreenResolution.RES_720P,
    val fps: TargetFps = TargetFps.FPS_60,
    val bitrate: StreamBitrate = StreamBitrate.BITRATE_4M,
    val codec: VideoCodec = VideoCodec.H264,
    val audioMode: AudioMode = AudioMode.SYSTEM_AND_MIC,
    val tcpPort: Int = 4120,
    val webPort: Int = 8080
)
