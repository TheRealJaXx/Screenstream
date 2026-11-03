package com.example.relay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.model.ScreenShareConfig
import com.example.model.VideoCodec
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureEngine manages Android MediaProjection and VirtualDisplay.
 *
 * Provides two high-speed capture pipelines:
 * 1. Hardware H.264 Pipeline: Display composer renders screen directly to MediaCodec inputSurface.
 *    Zero CPU copying, GPU-accelerated compression, sub-20ms latency.
 * 2. Universal Web MJPEG Pipeline: Captures frames into an ImageReader for instant browser viewing
 *    (Chrome/Safari/Edge/VLC/OBS) and in-app live monitor preview.
 */
class ScreenCaptureEngine(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val config: ScreenShareConfig,
    private val onH264Frame: (ByteArray, Boolean) -> Unit,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onBitmapFrame: ((Bitmap) -> Unit)? = null,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ScreenCaptureEngine"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var h264Encoder: HardwareH264Encoder? = null
    private var imageReader: ImageReader? = null
    private var imageHandlerThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private var lastFrameTimeMs = 0L
    private val minFrameIntervalMs = 1000L / config.fps.fps

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onLog(TAG, "MediaProjection session stopped by system or user", false)
            stop()
        }
    }

    fun start(): Boolean {
        if (isRunning.get()) return true

        try {
            mediaProjection.registerCallback(projectionCallback, null)

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val densityDpi = metrics.densityDpi

            // Determine dimensions maintaining aspect ratio
            val targetMaxW = config.resolution.width
            val targetMaxH = config.resolution.height
            val isPortrait = screenH > screenW

            val captureWidth: Int
            val captureHeight: Int

            if (isPortrait) {
                captureWidth = minOf(targetMaxH, screenW)
                captureHeight = (captureWidth * (screenH.toDouble() / screenW.toDouble())).toInt() and -2
            } else {
                captureWidth = minOf(targetMaxW, screenW) and -2
                captureHeight = (captureWidth * (screenH.toDouble() / screenW.toDouble())).toInt() and -2
            }

            onLog(TAG, "Starting Screen Capture: ${captureWidth}x${captureHeight} @ ${config.fps.fps}fps (${config.codec.label})", false)

            if (config.codec == VideoCodec.H264) {
                // Initialize Hardware H.264 Surface Encoder
                val encoder = HardwareH264Encoder(
                    width = captureWidth,
                    height = captureHeight,
                    bitrateBps = config.bitrate.bps,
                    targetFps = config.fps.fps,
                    onEncodedFrame = { data, isKeyframe ->
                        onH264Frame(data, isKeyframe)
                    }
                )

                if (!encoder.start()) {
                    onLog(TAG, "Hardware H.264 Encoder failed, falling back to Web MJPEG", true)
                    return startMjpegCapture(captureWidth, captureHeight, densityDpi)
                }

                h264Encoder = encoder

                // Attach VirtualDisplay straight to MediaCodec inputSurface!
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenStreamH264",
                    captureWidth,
                    captureHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    encoder.inputSurface,
                    null,
                    null
                )
            } else {
                return startMjpegCapture(captureWidth, captureHeight, densityDpi)
            }

            isRunning.set(true)
            return true
        } catch (e: Exception) {
            onLog(TAG, "Failed to start ScreenCaptureEngine: ${e.message}", true)
            return false
        }
    }

    private fun startMjpegCapture(width: Int, height: Int, densityDpi: Int): Boolean {
        try {
            imageHandlerThread = HandlerThread("ScreenCaptureReader").apply { start() }
            imageHandler = Handler(imageHandlerThread!!.looper)

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ ir ->
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameTimeMs < minFrameIntervalMs) {
                    val discard = ir.acquireLatestImage()
                    discard?.close()
                    return@setOnImageAvailableListener
                }
                lastFrameTimeMs = now

                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Crop out padding if needed
                    val finalBitmap = if (rowPadding == 0) {
                        bitmap
                    } else {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    }

                    onBitmapFrame?.invoke(finalBitmap)

                    // Compress to JPEG for Web Viewer
                    val jpegOut = ByteArrayOutputStream(128 * 1024)
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, jpegOut)
                    val jpegBytes = jpegOut.toByteArray()
                    onJpegFrame(jpegBytes)

                } catch (e: Exception) {
                    Log.w(TAG, "Image read error: ${e.message}")
                } finally {
                    image.close()
                }
            }, imageHandler)

            imageReader = reader

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenStreamMJPEG",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            isRunning.set(true)
            return true
        } catch (e: Exception) {
            onLog(TAG, "Failed to start MJPEG capture: ${e.message}", true)
            return false
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            h264Encoder?.stop()
            h264Encoder = null

            imageReader?.close()
            imageReader = null

            imageHandlerThread?.quitSafely()
            imageHandlerThread = null
            imageHandler = null

            mediaProjection.unregisterCallback(projectionCallback)
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenCaptureEngine: ${e.message}")
        }
    }
}
