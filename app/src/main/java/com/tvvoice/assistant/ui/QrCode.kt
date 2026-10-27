package com.tvvoice.assistant.ui

import android.graphics.Bitmap

/**
 * A QR code (ISO/IEC 18004) for a link, so a source on the TV opens on the phone pointed at it. Byte mode, the
 * smallest version that fits, every mask tried and the best kept, as the standard asks. After Project Nayuki's
 * reference generator (MIT), cut down to what a URL needs.
 */
class QrCode private constructor(val version: Int, val ecc: Ecc, data: ByteArray, forcedMask: Int) {
    enum class Ecc(val formatBits: Int) { L(1), M(0), Q(3), H(2) }

    val size = version * 4 + 17
    /** Dark modules, row by row: `modules[y][x]`. */
    val modules = Array(size) { BooleanArray(size) }
    private val function = Array(size) { BooleanArray(size) }
    var mask = 0
        private set

    init {
        drawFunctionPatterns()
        drawCodewords(withEcc(data))
        mask = if (forcedMask >= 0) forcedMask else (0..7).minBy { m ->
            applyMask(m)
            drawFormatBits(m)
            penalty().also { applyMask(m) }
        }
        applyMask(mask)
        drawFormatBits(mask)
    }

    /** Black modules on white with a [quiet]-module margin, one pixel a module: scale it up without filtering. */
    fun toBitmap(quiet: Int = 3): Bitmap {
        val side = size + quiet * 2
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until size) for (x in 0 until size) {
            if (modules[y][x]) pixels[(y + quiet) * side + x + quiet] = BLACK
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    // ---- layout --------------------------------------------------------------------------------------------------

    private fun set(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        function[y][x] = true
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        finder(3, 3)
        finder(size - 4, 3)
        finder(3, size - 4)
        val align = alignmentPositions()
        val n = align.size
        for (i in 0 until n) for (j in 0 until n) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
        drawFormatBits(0)
        drawVersion()
    }

    private fun finder(x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until size && yy in 0 until size) {
                val dist = maxOf(abs(dx), abs(dy))
                set(xx, yy, dist != 2 && dist != 4)
            }
        }
    }

    private fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val result = IntArray(count)
        result[0] = 6
        var pos = size - 7
        for (i in count - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    private fun drawFormatBits(mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = (data shl 10 or rem) xor 0x5412
        for (i in 0..5) set(8, i, bit(bits, i))
        set(8, 7, bit(bits, 6))
        set(8, 8, bit(bits, 7))
        set(7, 8, bit(bits, 8))
        for (i in 9 until 15) set(14 - i, 8, bit(bits, i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(bits, i))
        set(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = version shl 12 or rem
        for (i in 0 until 18) {
            val dark = bit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            set(a, b, dark)
            set(b, a, dark)
        }
    }

    /** The codewords in the zigzag the standard reads them in, two columns at a time from the right. */
    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = (right + 1) and 2 == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y][x] && i < data.size * 8) {
                    modules[y][x] = bit(data[i ushr 3].toInt(), 7 - (i and 7))
                    i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
        }
    }

    // ---- error correction ----------------------------------------------------------------------------------------

    /** The data split into blocks, each with its Reed-Solomon bytes, interleaved as the symbol stores them. */
    private fun withEcc(data: ByteArray): ByteArray {
        val blocks = BLOCKS[ecc.ordinal][version]
        val eccLen = ECC_PER_BLOCK[ecc.ordinal][version]
        val raw = rawDataModules(version) / 8
        val shortBlocks = blocks - raw % blocks
        val shortLen = raw / blocks
        val divisor = rsDivisor(eccLen)
        val all = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until blocks) {
            val dataLen = shortLen - eccLen + if (i < shortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + dataLen)
            k += dataLen
            val block = ByteArray(shortLen + 1)
            dat.copyInto(block)
            rsRemainder(dat, divisor).copyInto(block, shortLen + 1 - eccLen)
            all += block
        }
        val out = ByteArray(raw)
        var n = 0
        for (i in 0 until shortLen + 1) for (j in all.indices) {
            // The short blocks have no byte at the last data position; that slot is a placeholder.
            if (i != shortLen - eccLen || j >= shortBlocks) out[n++] = all[j][i]
        }
        return out
    }

    // ---- choosing the mask ---------------------------------------------------------------------------------------

    private fun penalty(): Int {
        var result = 0
        // Runs of five or more alike, and finder-like 1:1:3:1:1 patterns, along rows and columns.
        for (horizontal in listOf(true, false)) for (a in 0 until size) {
            var run = 0
            var last = false
            val line = BooleanArray(size) { b -> if (horizontal) modules[a][b] else modules[b][a] }
            for (b in 0 until size) {
                if (b > 0 && line[b] == last) {
                    run++
                    if (run == 5) result += 3 else if (run > 5) result++
                } else {
                    run = 1
                    last = line[b]
                }
            }
            for (b in 0..size - 7) {
                if (line[b] && !line[b + 1] && line[b + 2] && line[b + 3] && line[b + 4] && !line[b + 5] && line[b + 6]) {
                    val lightBefore = (b - 4 until b).all { it < 0 || !line[it] }
                    val lightAfter = (b + 7 until b + 11).all { it >= size || !line[it] }
                    if (lightBefore || lightAfter) result += 40
                }
            }
        }
        // 2x2 blocks of one colour.
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
        }
        // Balance of dark and light.
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        result += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1).coerceAtLeast(0) * 10
        return result
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        /** The smallest code holding [text] as UTF-8 at [ecc]; [mask] fixes the mask (tests), -1 picks the best. */
        fun encode(text: String, ecc: Ecc = Ecc.M, mask: Int = -1): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            for (version in 1..40) {
                val countBits = if (version < 10) 8 else 16
                val capacity = dataCodewords(version, ecc) * 8
                val used = 4 + countBits + bytes.size * 8
                if (used > capacity) continue
                val bits = BitBuffer()
                bits.append(0b0100, 4)
                bits.append(bytes.size, countBits)
                for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
                bits.append(0, minOf(4, capacity - bits.size))
                bits.append(0, (8 - bits.size % 8) % 8)
                var pad = 0xEC
                while (bits.size < capacity) {
                    bits.append(pad, 8)
                    pad = pad xor 0xEC xor 0x11
                }
                return QrCode(version, ecc, bits.toBytes(), mask)
            }
            throw IllegalArgumentException("too long for a QR code: ${bytes.size} bytes")
        }

        private fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int, ecc: Ecc) =
            rawDataModules(version) / 8 - ECC_PER_BLOCK[ecc.ordinal][version] * BLOCKS[ecc.ordinal][version]

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun bit(x: Int, i: Int) = (x ushr i) and 1 != 0

        private fun abs(x: Int) = if (x < 0) -x else x

        // Error correction bytes per block and number of blocks, by level (L, M, Q, H) and version (index 1-40).
        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0
        }

        fun toBytes() = ByteArray(bits.size / 8) { i ->
            var b = 0
            for (j in 0 until 8) b = b shl 1 or if (bits[i * 8 + j]) 1 else 0
            b.toByte()
        }
    }
}
