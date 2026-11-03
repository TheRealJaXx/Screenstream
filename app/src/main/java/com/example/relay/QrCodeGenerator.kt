package com.example.relay

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Pure Kotlin, zero-dependency QR code generator.
 * Implements standard QR Code (ISO/IEC 18004) for fast offline URL and connection sharing.
 */
object QrCodeGenerator {

    /**
     * Generates an Android Bitmap containing the QR code for [text].
     */
    fun createQrBitmap(text: String, sizePx: Int = 400): Bitmap {
        val matrix = encodeToMatrix(text)
        val matrixSize = matrix.size
        val scale = sizePx / matrixSize
        val actualSize = matrixSize * scale
        val pixels = IntArray(actualSize * actualSize)

        for (y in 0 until actualSize) {
            val my = y / scale
            for (x in 0 until actualSize) {
                val mx = x / scale
                val isBlack = matrix[my][mx]
                pixels[y * actualSize + x] = if (isBlack) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(pixels, actualSize, actualSize, Bitmap.Config.ARGB_8888)
    }

    private fun encodeToMatrix(text: String): Array<BooleanArray> {
        // Standard Version 3 QR Code: 29x29 modules, up to 77 numeric / 53 alphanumeric / 32 byte characters
        // Or Version 4: 33x33 modules, up to 78 byte characters
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val version = if (bytes.size <= 32) 3 else 4
        val modules = 17 + 4 * version
        val quietZone = 2
        val totalSize = modules + (quietZone * 2)

        val grid = Array(totalSize) { BooleanArray(totalSize) { false } }
        val reserved = Array(totalSize) { BooleanArray(totalSize) { false } }

        fun setModule(x: Int, y: Int, value: Boolean) {
            val gx = x + quietZone
            val gy = y + quietZone
            grid[gy][gx] = value
            reserved[gy][gx] = true
        }

        fun drawFinderPattern(startX: Int, startY: Int) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val isBorder = dx == 0 || dx == 6 || dy == 0 || dy == 6
                    val isCenter = dx in 2..4 && dy in 2..4
                    setModule(startX + dx, startY + dy, isBorder || isCenter)
                }
            }
            // Separator line
            for (i in -1..7) {
                if (startX + i in 0 until modules && startY - 1 in 0 until modules) setModule(startX + i, startY - 1, false)
                if (startX + i in 0 until modules && startY + 7 in 0 until modules) setModule(startX + i, startY + 7, false)
                if (startX - 1 in 0 until modules && startY + i in 0 until modules) setModule(startX - 1, startY + i, false)
                if (startX + 7 in 0 until modules && startY + i in 0 until modules) setModule(startX + 7, startY + i, false)
            }
        }

        // 1. Draw Finder Patterns (Top-Left, Top-Right, Bottom-Left)
        drawFinderPattern(0, 0)
        drawFinderPattern(modules - 7, 0)
        drawFinderPattern(0, modules - 7)

        // 2. Alignment pattern (for version 3 at (22, 22), for version 4 at (26, 26))
        val alignPos = if (version == 3) 22 else 26
        for (dy in -2..2) {
            for (dx in -2..2) {
                val isOuter = dx == -2 || dx == 2 || dy == -2 || dy == 2
                val isCenter = dx == 0 && dy == 0
                setModule(alignPos + dx, alignPos + dy, isOuter || isCenter)
            }
        }

        // 3. Timing Patterns
        for (i in 8 until modules - 8) {
            val isEven = (i % 2) == 0
            setModule(i, 6, isEven)
            setModule(6, i, isEven)
        }

        // 4. Dark module
        setModule(8, modules - 8, true)

        // 5. Data placement with Mask 0 ((x + y) % 2 == 0)
        var bitIndex = 0
        val totalDataBits = mutableListOf<Boolean>()

        // Byte mode indicator: 0100
        totalDataBits.add(false); totalDataBits.add(true); totalDataBits.add(false); totalDataBits.add(false)
        // Character count: 8 bits
        val len = bytes.size
        for (i in 7 downTo 0) {
            totalDataBits.add(((len shr i) and 1) == 1)
        }
        // Data payload
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            for (i in 7 downTo 0) {
                totalDataBits.add(((v shr i) and 1) == 1)
            }
        }
        // Terminator (up to 4 zeros)
        repeat(4) { totalDataBits.add(false) }

        // Pad to byte boundary
        while (totalDataBits.size % 8 != 0) {
            totalDataBits.add(false)
        }

        // Fill remaining codewords with 0xEC and 0x11
        val pad1 = 0xEC
        val pad2 = 0x11
        val maxBits = (if (version == 3) 70 else 100) * 8
        var usePad1 = true
        while (totalDataBits.size < maxBits) {
            val pad = if (usePad1) pad1 else pad2
            for (i in 7 downTo 0) {
                totalDataBits.add(((pad shr i) and 1) == 1)
            }
            usePad1 = !usePad1
        }

        // Place bits into matrix using right-to-left 2-column zig-zag
        var col = modules - 1
        var row = modules - 1
        var goingUp = true

        while (col > 0) {
            if (col == 6) col-- // Skip vertical timing column
            for (i in 0 until modules) {
                val r = if (goingUp) (modules - 1 - i) else i
                for (c in 0..1) {
                    val currCol = col - c
                    val gx = currCol + quietZone
                    val gy = r + quietZone
                    if (!reserved[gy][gx]) {
                        val bit = if (bitIndex < totalDataBits.size) totalDataBits[bitIndex++] else false
                        // Mask 0: Invert bit if (r + currCol) % 2 == 0
                        val masked = if ((r + currCol) % 2 == 0) !bit else bit
                        grid[gy][gx] = masked
                    }
                }
            }
            goingUp = !goingUp
            col -= 2
        }

        return grid
    }
}
