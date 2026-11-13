package com.rfsentinel.app.sdr

/**
 * P25's Reed-Solomon codes over GF(64) (TIA-102.BAAA 4.9): six-bit symbols, the field from x^6 + x + 1, generator
 * roots α^1 .. α^(n-k), shortened from n = 63. RS(24,12,13) guards an LDU1's link control, RS(24,16,9) an LDU2's
 * encryption sync. Same code as SDRTrunk's ReedSolomon_63_P25 (Dennis Sheirer, GPL-3.0); decoded here with
 * Berlekamp-Massey, a Chien search and Forney's formula. Pure: unit-tested.
 */
object P25ReedSolomon {

    private val EXP = IntArray(126)
    private val LOG = IntArray(64)
    init {
        var x = 1
        for (i in 0 until 63) { EXP[i] = x; EXP[i + 63] = x; LOG[x] = i; x = x shl 1; if (x and 64 != 0) x = x xor 0x43 }
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun div(a: Int, b: Int) = if (a == 0) 0 else EXP[LOG[a] + 63 - LOG[b]]
    private fun pow(i: Int) = EXP[((i % 63) + 63) % 63]

    /**
     * Corrects [words] in place: the code's symbols in the order sent (data first, parity last), [parity] of them parity.
     * The errors corrected (0 when it was intact), or -1 when there are more than [parity] / 2 and nothing changed.
     */
    fun decode(words: IntArray, parity: Int): Int {
        val n = words.size
        // Symbol sent at t is the coefficient of x^(n-1-t).
        fun coef(power: Int) = words[n - 1 - power]
        val s = IntArray(parity) { j -> var v = 0; for (p in 0 until n) v = v xor mul(coef(p), pow((j + 1) * p)); v }
        if (s.all { it == 0 }) return 0
        // Berlekamp-Massey: the error locator.
        var c = IntArray(parity + 1).also { it[0] = 1 }
        var b = c.copyOf(); var l = 0; var m = 1; var bd = 1
        for (k in 0 until parity) {
            var d = s[k]
            for (i in 1..l) d = d xor mul(c[i], s[k - i])
            if (d == 0) { m++; continue }
            val t = c.copyOf(); val f = div(d, bd)
            for (i in 0..parity - m) c[i + m] = c[i + m] xor mul(f, b[i])
            if (2 * l <= k) { l = k + 1 - l; b = t; bd = d; m = 1 } else m++
        }
        if (l > parity / 2) return -1
        // Chien search: error at power p where the locator has a root at α^-p; only the n powers sent count.
        val at = (0 until n).filter { p -> var v = 0; for (i in 0..l) v = v xor mul(c[i], pow(-p * i)); v == 0 }
        if (at.size != l) return -1
        // Forney (first root α^1): e = Ω(X⁻¹) / Λ'(X⁻¹), Ω = S·Λ mod x^parity.
        val omega = IntArray(parity) { i -> var v = 0; for (j in 0..minOf(i, l)) v = v xor mul(c[j], s[i - j]); v }
        val fixes = at.map { p ->
            val xi = -p
            var num = 0; for (i in omega.indices) num = num xor mul(omega[i], pow(xi * i))
            var den = 0; for (i in 1..l step 2) den = den xor mul(c[i], pow(xi * (i - 1)))
            if (den == 0) return -1
            p to div(num, den)
        }
        for ((p, e) in fixes) words[n - 1 - p] = words[n - 1 - p] xor e
        return l
    }

    /** The [parity] parity symbols for [data] (systematic), in the order sent. For the tests. */
    internal fun encode(data: IntArray, parity: Int): IntArray {
        var g = intArrayOf(1) // highest power first
        for (j in 1..parity) {
            val r = pow(j)
            g = IntArray(g.size + 1) { i -> (if (i < g.size) g[i] else 0) xor (if (i > 0) mul(g[i - 1], r) else 0) }
        }
        val rem = IntArray(parity)
        for (v in data) {
            val f = v xor rem[0]
            for (i in 0 until parity - 1) rem[i] = rem[i + 1] xor mul(f, g[i + 1])
            rem[parity - 1] = mul(f, g[parity])
        }
        return rem
    }
}
