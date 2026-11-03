package com.example.relay

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.model.AudioMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low-latency Audio Capture Engine.
 *
 * Captures 48kHz Stereo 16-bit PCM audio in 20ms chunks (3840 bytes).
 * Supports:
 * - Internal system audio capture via Android 10+ AudioPlaybackCaptureConfiguration
 * - Standard microphone capture fallback
 */
class AudioCaptureEngine(
    private val context: Context,
    private val audioMode: AudioMode,
    private val mediaProjection: MediaProjection? = null,
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 20ms of 48kHz stereo 16-bit = 48 * 20 * 4 = 3840 bytes
        const val CHUNK_SIZE_BYTES = 3840
    }

    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (audioMode == AudioMode.MUTED) {
            onLog(TAG, "Audio streaming is muted by user", false)
            return true
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES * 4)

            val record: AudioRecord = if (
                (audioMode == AudioMode.SYSTEM_AND_MIC || audioMode == AudioMode.SYSTEM_ONLY) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                mediaProjection != null
            ) {
                // Device Internal Audio Playback Capture
                try {
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } catch (e: Exception) {
                    onLog(TAG, "Internal audio capture failed (${e.message}), falling back to Mic", true)
                    createMicRecord(bufferSize)
                }
            } else {
                createMicRecord(bufferSize)
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onLog(TAG, "AudioRecord failed to initialize", true)
                return false
            }

            audioRecord = record
            record.startRecording()
            isRunning.set(true)

            recordThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                recordLoop(record)
            }, "ScreenStreamAudioCapture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            onLog(TAG, "Audio capture active: 48kHz Stereo 16-bit ($audioMode)", false)
            return true
        } catch (e: Exception) {
            onLog(TAG, "Failed to start AudioCaptureEngine: ${e.message}", true)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMicRecord(bufferSize: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
    }

    private fun recordLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        while (isRunning.get()) {
            val bytesRead = record.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                val chunk = if (bytesRead == buffer.size) buffer.clone() else buffer.copyOf(bytesRead)
                onAudioChunk(chunk)
            } else if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord read error: $bytesRead")
                Thread.sleep(10)
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            recordThread?.interrupt()
            recordThread?.join(300)
        } catch (_: Exception) {}
        recordThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
