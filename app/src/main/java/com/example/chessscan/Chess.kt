package com.example.chessscan

import kotlin.math.abs

class Mv(val from: Int, val to: Int, val promo: Int = 0)

/** b[sq]: 0 пусто, 1..6 белые P N B R Q K, 7..12 чёрные; sq = rank*8+file (a1 = 0). ca: 1 K, 2 Q, 4 k, 8 q. ep: поле взятия на проходе или -1. */
class Pos(val b: IntArray, var white: Boolean, var ca: Int, var ep: Int) {
    companion object {
        private val KN = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(-1, 2), intArrayOf(-2, 1), intArrayOf(1, -2), intArrayOf(2, -1), intArrayOf(-1, -2), intArrayOf(-2, -1))
        private val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))

        fun start(): Pos {
            val b = IntArray(64); val back = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
            for (f in 0..7) { b[f] = back[f]; b[8 + f] = 1; b[48 + f] = 7; b[56 + f] = back[f] + 6 }
            return Pos(b, true, 15, -1)
        }

        fun fromRec(r: IntArray, w: Boolean): Pos {
            val ca = (if (r[4] == 6 && r[7] == 4) 1 else 0) or (if (r[4] == 6 && r[0] == 4) 2 else 0) or
                (if (r[60] == 12 && r[63] == 10) 4 else 0) or (if (r[60] == 12 && r[56] == 10) 8 else 0)
            return Pos(r.copyOf(), w, ca, -1)
        }
    }

    fun copy() = Pos(b.copyOf(), white, ca, ep)
    private fun own(k: Int) = k != 0 && (k <= 6) == white
    private fun foe(k: Int) = k != 0 && (k <= 6) != white

    fun attacked(s: Int, byW: Boolean): Boolean {
        val o = if (byW) 0 else 6; val r = s / 8; val f = s % 8
        val pr = if (byW) r - 1 else r + 1
        if (pr in 0..7) for (df in intArrayOf(-1, 1)) { val pf = f + df; if (pf in 0..7 && b[pr * 8 + pf] == 1 + o) return true }
        for (d in KN) { val rr = r + d[0]; val ff = f + d[1]; if (rr in 0..7 && ff in 0..7 && b[rr * 8 + ff] == 2 + o) return true }
        for (dr in -1..1) for (df in -1..1) if (dr != 0 || df != 0) {
            val rr = r + dr; val ff = f + df; if (rr in 0..7 && ff in 0..7 && b[rr * 8 + ff] == 6 + o) return true
        }
        for (d in DIRS) {
            var rr = r + d[0]; var ff = f + d[1]
            while (rr in 0..7 && ff in 0..7) {
                val k = b[rr * 8 + ff]
                if (k != 0) { if (k == 5 + o || k == (if (d[0] != 0 && d[1] != 0) 3 else 4) + o) return true; break }
                rr += d[0]; ff += d[1]
            }
        }
        return false
    }

    fun inCheck(w: Boolean): Boolean { val k = b.indexOf(if (w) 6 else 12); return k >= 0 && attacked(k, !w) }

    private fun step(out: ArrayList<Mv>, s: Int, r: Int, f: Int) { if (r in 0..7 && f in 0..7 && !own(b[r * 8 + f])) out.add(Mv(s, r * 8 + f)) }
    private fun addP(out: ArrayList<Mv>, s: Int, to: Int, promo: Boolean, o: Int) {
        if (promo) for (c in intArrayOf(5, 4, 3, 2)) out.add(Mv(s, to, c + o)) else out.add(Mv(s, to))
    }

    private fun pseudo(): ArrayList<Mv> {
        val out = ArrayList<Mv>(); val w = white; val o = if (w) 0 else 6
        for (s in 0 until 64) {
            val k = b[s]; if (!own(k)) continue
            val t = k - o; val r = s / 8; val f = s % 8
            when (t) {
                1 -> {
                    val dr = if (w) 1 else -1; val r1 = r + dr; val last = if (w) 7 else 0
                    if (r1 in 0..7) {
                        if (b[r1 * 8 + f] == 0) {
                            addP(out, s, r1 * 8 + f, r1 == last, o)
                            if (r == (if (w) 1 else 6) && b[(r + 2 * dr) * 8 + f] == 0) out.add(Mv(s, (r + 2 * dr) * 8 + f))
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val ff = f + df; if (ff !in 0..7) continue
                            val to = r1 * 8 + ff
                            if (foe(b[to])) addP(out, s, to, r1 == last, o) else if (to == ep && b[to] == 0) out.add(Mv(s, to))
                        }
                    }
                }
                2 -> for (d in KN) step(out, s, r + d[0], f + d[1])
                6 -> {
                    for (dr in -1..1) for (df in -1..1) if (dr != 0 || df != 0) step(out, s, r + dr, f + df)
                    val rk = if (w) 0 else 7
                    if (s == rk * 8 + 4 && !attacked(s, !w)) {
                        if ((ca and (if (w) 1 else 4)) != 0 && b[rk * 8 + 5] == 0 && b[rk * 8 + 6] == 0 && b[rk * 8 + 7] == 4 + o && !attacked(rk * 8 + 5, !w))
                            out.add(Mv(s, rk * 8 + 6))
                        if ((ca and (if (w) 2 else 8)) != 0 && b[rk * 8 + 3] == 0 && b[rk * 8 + 2] == 0 && b[rk * 8 + 1] == 0 && b[rk * 8] == 4 + o && !attacked(rk * 8 + 3, !w))
                            out.add(Mv(s, rk * 8 + 2))
                    }
                }
                else -> for (d in DIRS) {
                    val diag = d[0] != 0 && d[1] != 0
                    if (t == 3 && !diag) continue
                    if (t == 4 && diag) continue
                    var rr = r + d[0]; var ff = f + d[1]
                    while (rr in 0..7 && ff in 0..7) {
                        val q = b[rr * 8 + ff]; if (own(q)) break
                        out.add(Mv(s, rr * 8 + ff)); if (q != 0) break
                        rr += d[0]; ff += d[1]
                    }
                }
            }
        }
        return out
    }

    fun legal(): List<Mv> = pseudo().filter { m -> val c = copy(); c.play(m); !c.inCheck(white) }
    fun mated() = inCheck(white) && legal().isEmpty()

    fun play(m: Mv) {
        val k = b[m.from]; val w = white; val t = if (w) k else k - 6; val cap = b[m.to]
        b[m.to] = if (m.promo != 0) m.promo else k; b[m.from] = 0
        if (t == 1 && m.to == ep && cap == 0 && m.from % 8 != m.to % 8) b[m.to + (if (w) -8 else 8)] = 0
        if (t == 6 && abs(m.to - m.from) == 2) {
            val rk = m.from / 8 * 8
            if (m.to % 8 == 6) { b[rk + 5] = b[rk + 7]; b[rk + 7] = 0 } else { b[rk + 3] = b[rk]; b[rk] = 0 }
        }
        if (k == 6) ca = ca and 12 else if (k == 12) ca = ca and 3
        for (s in intArrayOf(m.from, m.to)) when (s) {
            0 -> ca = ca and 2.inv(); 7 -> ca = ca and 1.inv(); 56 -> ca = ca and 8.inv(); 63 -> ca = ca and 4.inv()
        }
        ep = if (t == 1 && abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        white = !w
    }
}
