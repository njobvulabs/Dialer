package com.example.util

object QrCodeGenerator {

    private val exp = IntArray(256)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 256 != 0) x = x xor 285
        }
        exp[255] = exp[0]
    }

    private fun gfMul(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return exp[(log[x] + log[y]) % 255]
    }

    private fun rsPoly(ecLen: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until ecLen) {
            val nextG = IntArray(g.size + 1)
            val factor = exp[i]
            for (j in g.indices) {
                nextG[j] = nextG[j] xor gfMul(g[j], factor)
                nextG[j + 1] = nextG[j + 1] xor g[j]
            }
            g = nextG
        }
        return g
    }

    fun generateSvg(content: String, margin: Int = 2): String {
        val matrix = generateMatrix(content) ?: return ""
        val size = matrix.size
        val totalSize = size + margin * 2
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $totalSize $totalSize\" width=\"100%\" height=\"100%\">")
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
        sb.append("<path d=\"")
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (matrix[r][c]) {
                    sb.append("M${c + margin},${r + margin}h1v1h-1z ")
                }
            }
        }
        sb.append("\" fill=\"#0f172a\"/>")
        sb.append("</svg>")
        return sb.toString()
    }

    fun generateMatrix(content: String): Array<BooleanArray>? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        val (ver, totalData, ecLen, alignPos) = when {
            len <= 19 -> Quad(1, 19, 7, -1)
            len <= 34 -> Quad(2, 34, 10, 18)
            len <= 55 -> Quad(3, 55, 15, 22)
            len <= 78 -> Quad(4, 80, 20, 26)
            else -> return null
        }

        val size = 17 + ver * 4
        val bitBuf = mutableListOf<Int>()

        fun addBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bitBuf.add((value shr i) and 1)
            }
        }

        addBits(4, 4) // Byte mode
        addBits(len, 8) // Length
        for (b in bytes) addBits(b.toInt() and 0xFF, 8)

        val termLen = minOf(4, totalData * 8 - bitBuf.size)
        if (termLen > 0) addBits(0, termLen)
        while (bitBuf.size % 8 != 0) bitBuf.add(0)

        val pad = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bitBuf.size < totalData * 8) {
            addBits(pad[padIdx], 8)
            padIdx = (padIdx + 1) % 2
        }

        val dataBytes = IntArray(totalData)
        for (i in 0 until totalData) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bitBuf[i * 8 + b]
            dataBytes[i] = v
        }

        val poly = rsPoly(ecLen)
        val res = IntArray(totalData + ecLen)
        System.arraycopy(dataBytes, 0, res, 0, totalData)
        for (i in 0 until totalData) {
            val coef = res[i]
            if (coef != 0) {
                for (j in poly.indices) res[i + j] = res[i + j] xor gfMul(poly[j], coef)
            }
        }
        val ecBytes = res.copyOfRange(totalData, totalData + ecLen)

        val finalBits = mutableListOf<Int>()
        for (b in dataBytes) for (i in 7 downTo 0) finalBits.add((b shr i) and 1)
        for (b in ecBytes) for (i in 7 downTo 0) finalBits.add((b shr i) and 1)

        val grid = Array(size) { BooleanArray(size) }
        val isFunc = Array(size) { BooleanArray(size) }

        fun setFunc(r: Int, c: Int, v: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                grid[r][c] = v
                isFunc[r][c] = true
            }
        }

        fun drawFinder(topR: Int, topC: Int) {
            for (r in -1..7) for (c in -1..7) {
                val isBlack = (r in 0..6 && (c == 0 || c == 6)) ||
                              (c in 0..6 && (r == 0 || r == 6)) ||
                              (r in 2..4 && c in 2..4)
                setFunc(topR + r, topC + c, isBlack)
            }
        }

        drawFinder(0, 0)
        drawFinder(0, size - 7)
        drawFinder(size - 7, 0)

        if (alignPos > 0) {
            for (r in -2..2) for (c in -2..2) {
                val isBlack = r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0)
                setFunc(alignPos + r, alignPos + c, isBlack)
            }
        }

        for (i in 8 until size - 8) {
            setFunc(6, i, i % 2 == 0)
            setFunc(i, 6, i % 2 == 0)
        }

        setFunc(4 * ver + 9, 8, true)

        for (i in 0..8) {
            setFunc(8, i, false)
            setFunc(i, 8, false)
            setFunc(8, size - 1 - i, false)
            setFunc(size - 1 - i, 8, false)
        }

        var bitIdx = 0
        var right = size - 1
        var vertDir = -1
        while (right > 0) {
            if (right == 6) right--
            var r = if (vertDir < 0) size - 1 else 0
            while (r in 0 until size) {
                for (c in right downTo right - 1) {
                    if (!isFunc[r][c]) {
                        var bit = if (bitIdx < finalBits.size) finalBits[bitIdx++] else 0
                        if ((r + c) % 2 == 0) bit = bit xor 1 // Mask 0
                        grid[r][c] = (bit == 1)
                    }
                }
                r += vertDir
            }
            vertDir = -vertDir
            right -= 2
        }

        val fmtVal = 0x74B3 // Format bits for ECC Level L and Mask 0
        for (i in 0..14) {
            val bit = ((fmtVal shr i) and 1) == 1
            if (i < 6) grid[8][i] = bit
            else if (i < 8) grid[8][i + 1] = bit
            else if (i == 8) grid[7][8] = bit
            else grid[14 - i][8] = bit

            if (i < 8) grid[size - 1 - i][8] = bit
            else grid[8][size - 15 + i] = bit
        }

        return grid
    }

    private data class Quad(val v: Int, val d: Int, val ec: Int, val a: Int)
}
