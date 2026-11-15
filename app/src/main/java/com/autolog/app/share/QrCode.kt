package com.autolog.app.share

import kotlin.math.abs

/**
 * QR 코드 만들기. 바이트 모드, 오류 정정 수준 M, 버전 1~6(106바이트까지)만 다룬다.
 * 폰으로 받을 주소 한 줄을 담는 데 쓰므로 이 범위면 충분하다. 외부 라이브러리 없이 둔다.
 */
object QrCode {

    /** 버전별(1~6) 데이터 코드워드 수, 블록 하나의 오류 정정 코드워드 수, 블록 수. 오류 정정 수준 M 기준. */
    private val DATA_CODEWORDS = intArrayOf(16, 28, 44, 64, 86, 108)
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16)
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4)

    /** 담을 수 있는 가장 긴 글(바이트). */
    const val MAX_BYTES = 106

    /** [text]를 담은 QR 코드. 바깥 배열이 줄(위에서 아래), 안쪽이 칸(왼쪽에서 오른쪽)이고 true가 어두운 칸이다. 너무 길면 null. */
    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 모드 4비트 + 길이 8비트 + 글
        val version = (1..6).firstOrNull { DATA_CODEWORDS[it - 1] * 8 >= 12 + bytes.size * 8 } ?: return null
        val codewords = withErrorCorrection(dataCodewords(bytes, DATA_CODEWORDS[version - 1]), version)
        val size = 17 + 4 * version
        val base = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(base, function, version)
        drawCodewords(base, function, codewords)

        // 여덟 가지 마스크를 모두 입혀 보고 읽기 어려운 무늬가 가장 적은 것을 고른다.
        var best = base
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val candidate = Array(size) { base[it].clone() }
            applyMask(candidate, function, mask)
            drawFormatBits(candidate, mask)
            val penalty = penalty(candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                best = candidate
            }
        }
        return best
    }

    private fun dataCodewords(bytes: ByteArray, capacity: Int): IntArray {
        val bits = ArrayList<Boolean>(capacity * 8)
        fun put(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) == 1
        }
        put(0b0100, 4)
        put(bytes.size, 8)
        bytes.forEach { put(it.toInt() and 0xFF, 8) }
        put(0, minOf(4, capacity * 8 - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity * 8) {
            put(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return IntArray(capacity) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) } }
    }

    /** 데이터를 블록으로 나눠 리드 솔로몬 오류 정정 코드워드를 붙이고, 블록을 번갈아 섞는다. */
    private fun withErrorCorrection(data: IntArray, version: Int): IntArray {
        val blocks = BLOCKS[version - 1]
        val eccLength = ECC_PER_BLOCK[version - 1]
        val blockLength = data.size / blocks // 버전 1~6의 M 수준은 블록 길이가 모두 같다
        val divisor = reedSolomonDivisor(eccLength)
        val dataBlocks = List(blocks) { data.copyOfRange(it * blockLength, (it + 1) * blockLength) }
        val eccBlocks = dataBlocks.map { reedSolomonRemainder(it, divisor) }
        val out = IntArray(data.size + blocks * eccLength)
        var k = 0
        for (i in 0 until blockLength) for (block in dataBlocks) out[k++] = block[i]
        for (i in 0 until eccLength) for (block in eccBlocks) out[k++] = block[i]
        return out
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = multiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (value in data) {
            val factor = value xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return result
    }

    /** GF(2^8/0x11D)에서의 곱. */
    private fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    /** 타이밍 줄, 위치 찾기 무늬 셋, 정렬 무늬, 형식 정보 자리를 그리고 그 칸들을 [function]에 표시한다. */
    private fun drawFunctionPatterns(modules: Array<BooleanArray>, function: Array<BooleanArray>, version: Int) {
        val size = modules.size
        fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            function[y][x] = true
        }
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val distance = maxOf(abs(dx), abs(dy))
                    set(x, y, distance != 2 && distance != 4)
                }
            }
        }
        if (version >= 2) {
            val center = size - 7
            for (dy in -2..2) for (dx in -2..2) set(center + dx, center + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
        // 형식 정보 자리. 값은 마스크를 고른 뒤에 채운다.
        for (i in 0..8) {
            function[i][8] = true
            function[8][i] = true
        }
        for (i in 0..7) {
            function[8][size - 1 - i] = true
            function[size - 1 - i][8] = true
        }
        set(8, size - 8, true)
    }

    /** 코드워드의 비트를 오른쪽 아래에서부터 두 칸 폭으로 지그재그로 채운다. */
    private fun drawCodewords(modules: Array<BooleanArray>, function: Array<BooleanArray>, codewords: IntArray) {
        val size = modules.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!function[y][x] && i < codewords.size * 8) {
                        modules[y][x] = ((codewords[i ushr 3] ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(modules: Array<BooleanArray>, function: Array<BooleanArray>, mask: Int) {
        for (y in modules.indices) for (x in modules.indices) {
            if (function[y][x]) continue
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
            if (invert) modules[y][x] = !modules[y][x]
        }
    }

    /** 오류 정정 수준(M)과 마스크 번호를 BCH 부호로 감싸 두 군데에 적는다. */
    private fun drawFormatBits(modules: Array<BooleanArray>, mask: Int) {
        val size = modules.size
        val data = mask // 오류 정정 수준 M의 표시는 00이다
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = ((data shl 10) or remainder) xor 0x5412
        fun bit(i: Int) = ((bits ushr i) and 1) != 0
        for (i in 0..5) modules[i][8] = bit(i)
        modules[7][8] = bit(6)
        modules[8][8] = bit(7)
        modules[8][7] = bit(8)
        for (i in 9..14) modules[8][14 - i] = bit(i)
        for (i in 0..7) modules[8][size - 1 - i] = bit(i)
        for (i in 8..14) modules[size - 15 + i][8] = bit(i)
        modules[size - 8][8] = true
    }

    /** 읽기 어려운 무늬에 매기는 벌점. 낮을수록 좋다. */
    private fun penalty(modules: Array<BooleanArray>): Int {
        val size = modules.size
        var result = 0
        // 같은 색이 다섯 칸 넘게 이어지는 줄
        for (line in 0 until size) for (horizontal in listOf(true, false)) {
            var run = 1
            for (i in 1 until size) {
                val current = if (horizontal) modules[line][i] else modules[i][line]
                val before = if (horizontal) modules[line][i - 1] else modules[i - 1][line]
                if (current == before) {
                    run++
                    if (run == 5) result += 3 else if (run > 5) result++
                } else {
                    run = 1
                }
            }
        }
        // 같은 색 2x2
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val color = modules[y][x]
            if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) result += 3
        }
        // 위치 찾기 무늬처럼 보이는 줄
        for (line in 0 until size) for (horizontal in listOf(true, false)) {
            for (i in 0..size - 11) {
                if (FINDER_LIKE.any { pattern ->
                        pattern.indices.all { k -> (if (horizontal) modules[line][i + k] else modules[i + k][line]) == pattern[k] }
                    }) result += 40
            }
        }
        // 어두운 칸의 비율이 절반에서 멀수록
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        result += ((abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
        return result
    }

    private val FINDER_LIKE = listOf(
        booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true),
    )
}
