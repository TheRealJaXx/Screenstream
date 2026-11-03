package com.example.relay

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Android MediaCodec Hardware H.264 Surface-Direct Encoder & Decoder.
 *
 * Direct Hardware GPU Encoding:
 * - Uses MediaCodec with COLOR_FormatSurface (inputSurface)
 * - Zero CPU copying: Android display composer renders screen directly to inputSurface
 * - Hardware GPU compresses at 30/60 FPS at target bitrate (2 - 12 Mbps) with sub-10ms latency
 * - Pre-caches SPS and PPS headers and prepends them to every IDR keyframe.
 */
class HardwareH264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitrateBps: Int = 4_000_000,
    private val targetFps: Int = 60,
    private val onEncodedFrame: (data: ByteArray, isKeyframe: Boolean) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var isRunning = false
    private var drainThread: Thread? = null

    // Cached SPS & PPS codec configuration to prepend to IDR keyframes
    @Volatile
    private var spsPpsConfig: ByteArray? = null

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun start(): Boolean {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            mediaCodec = encoder
            isRunning = true

            // Dedicated background thread to drain encoded H.264 NAL units
            drainThread = Thread({
                drainLoop()
            }, "H264EncoderDrain").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.d(TAG, "Hardware H.264 Surface Encoder started: ${width}x${height} @ ${targetFps}fps ($bitrateBps bps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 hardware encoder: ${e.message}", e)
            return false
        }
    }

    /**
     * Fallback for synthetic Bitmaps (e.g. LatencyTestEngine):
     * Draws bitmap directly to hardware inputSurface via Canvas without CPU pixel loops.
     */
    fun renderBitmap(bitmap: Bitmap) {
        val surface = inputSurface ?: return
        if (!surface.isValid) return
        try {
            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            }
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
            surface.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
    }

    private fun drainLoop() {
        val encoder = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L // 10ms

        while (isRunning) {
            try {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Extract SPS & PPS from format
                    val newFormat = encoder.outputFormat
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    val csd1 = newFormat.getByteBuffer("csd-1")
                    if (csd0 != null && csd1 != null) {
                        val sps = ByteArray(csd0.remaining())
                        csd0.get(sps)
                        val pps = ByteArray(csd1.remaining())
                        csd1.get(pps)
                        spsPpsConfig = sps + pps
                        Log.d(TAG, "Cached SPS/PPS headers: ${spsPpsConfig?.size} bytes")
                    }
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            val configBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(configBytes)
                            spsPpsConfig = configBytes
                        } else {
                            val frameBytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(frameBytes)

                            // Prepend SPS/PPS if this is an IDR keyframe to allow instant decoding
                            val finalPayload = if (isKeyframe && spsPpsConfig != null) {
                                val sps = spsPpsConfig!!
                                val combined = ByteArray(sps.size + frameBytes.size)
                                System.arraycopy(sps, 0, combined, 0, sps.size)
                                System.arraycopy(frameBytes, 0, combined, sps.size, frameBytes.size)
                                combined
                            } else {
                                frameBytes
                            }

                            onEncodedFrame(finalPayload, isKeyframe)
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Drain loop warning: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            drainThread?.interrupt()
            drainThread?.join(500)
        } catch (_: Exception) {}
        drainThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
        spsPpsConfig = null
    }
}

/**
 * Android MediaCodec Hardware H.264 Surface-Direct Decoder.
 * Direct Surface Rendering: Renders decoded video frames straight to the hardware SurfaceView.
 */
class HardwareH264Decoder(
    private val surface: Surface,
    private val onDecodedFrame: (width: Int, height: Int) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    @Volatile
    private var isConfigured = false
    @Volatile
    private var isRunning = false
    private var drainThread: Thread? = null

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun init(width: Int, height: Int, spsPps: ByteArray? = null): Boolean {
        try {
            stop()
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (spsPps != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(spsPps))
                }
            }
            val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder.configure(format, surface, null, 0)
            decoder.start()

            mediaCodec = decoder
            isConfigured = true
            isRunning = true

            drainThread = Thread({
                drainLoop()
            }, "H264DecoderDrain").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.d(TAG, "Hardware H.264 Decoder initialized: ${width}x$height")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init H.264 decoder: ${e.message}", e)
            return false
        }
    }

    fun decodeFrame(data: ByteArray): Boolean {
        val decoder = mediaCodec ?: return false
        if (!isRunning) return false

        try {
            val inputBufferIndex = decoder.dequeueInputBuffer(5_000L)
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: return false
                inputBuffer.clear()
                inputBuffer.put(data)
                decoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    data.size,
                    SystemClock.uptimeMillis() * 1000L,
                    0
                )
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeFrame error: ${e.message}")
        }
        return false
    }

    private fun drainLoop() {
        val decoder = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L

        while (isRunning) {
            try {
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    onDecodedFrame(w, h)
                } else if (outputBufferIndex >= 0) {
                    // Render directly to surface!
                    decoder.releaseOutputBuffer(outputBufferIndex, true)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Decoder drain loop warning: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        try {
            drainThread?.interrupt()
            drainThread?.join(500)
        } catch (_: Exception) {}
        drainThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}
