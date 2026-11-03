package com.example.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

object JpegUtils {
    /**
     * Extracts clean JPEG bytes starting at SOI (0xFF, 0xD8) and ending at EOI (0xFF, 0xD9).
     */
    fun extractCleanJpeg(data: ByteArray): ByteArray {
        val size = data.size
        if (size < 4) return data

        val isCleanStart = (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
        val isCleanEnd = (data[size - 2].toInt() and 0xFF) == 0xFF && (data[size - 1].toInt() and 0xFF) == 0xD9
        if (isCleanStart && isCleanEnd) {
            return data
        }

        var startIndex = -1
        for (i in 0 until minOf(size - 1, 512)) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                startIndex = i
                break
            }
        }
        if (startIndex == -1) return data

        var endIndex = -1
        for (j in (startIndex + 64) until (size - 1)) {
            if ((data[j].toInt() and 0xFF) == 0xFF && (data[j + 1].toInt() and 0xFF) == 0xD9) {
                endIndex = j + 2
                break
            }
        }

        return if (endIndex > startIndex) {
            data.copyOfRange(startIndex, endIndex)
        } else {
            data
        }
    }

    fun isJpeg(data: ByteArray): Boolean {
        if (data.size < 2) return false
        return (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
    }

    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
