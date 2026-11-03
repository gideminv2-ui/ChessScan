package com.example.chessscan

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

const val N = 16          // клетка сжимается до N×N пикселей
const val L = N * N * 3   // длина «снимка»; [L] = доля не-фона, [L+1] = яркость переднего плана
const val BG = -100f      // метка «этот пиксель — фон клетки»

/**
 * Снимок клетки. Цвет пустого поля оцениваем медианой по рамке клетки (так подсветка последнего хода,
 * буквы координат и светлые/тёмные поля не мешают), всё похожее на фон заменяем меткой BG.
 * Остаётся «вырезанный» спрайт фигуры — он одинаков на любом поле.
 */
fun cellRep(buf: ByteBuffer, rs: Int, x0: Float, y0: Float, s: Float): FloatArray {
    val mg = s * .07f; val bs = (s - 2 * mg) / N; val w = max(1, bs.toInt()); val n = (w * w).toFloat()
    val px = FloatArray(L)
    for (j in 0 until N) for (i in 0 until N) {
        val xa = (x0 + mg + i * bs).toInt(); val ya = (y0 + mg + j * bs).toInt()
        var r = 0; var g = 0; var b = 0
        for (yy in ya until ya + w) for (xx in xa until xa + w) {
            val o = yy * rs + xx * 4
            r += buf.get(o).toInt() and 255; g += buf.get(o + 1).toInt() and 255; b += buf.get(o + 2).toInt() and 255
        }
        val q = (j * N + i) * 3; px[q] = r / n; px[q + 1] = g / n; px[q + 2] = b / n
    }
    val bg = FloatArray(3) { ch ->
        val v = ArrayList<Float>()
        for (j in 0 until N) for (i in 0 until N) if (i == 0 || j == 0 || i == N - 1 || j == N - 1) v.add(px[(j * N + i) * 3 + ch])
        v.sort(); v[v.size / 2]
    }
    val out = FloatArray(L + 2); var cnt = 0; var lm = 0f
    for (k in 0 until N * N) {
        val q = k * 3
        if (abs(px[q] - bg[0]) + abs(px[q + 1] - bg[1]) + abs(px[q + 2] - bg[2]) < 45f) { out[q] = BG; out[q + 1] = BG; out[q + 2] = BG }
        else { out[q] = px[q]; out[q + 1] = px[q + 1]; out[q + 2] = px[q + 2]; cnt++; lm += .299f * px[q] + .587f * px[q + 1] + .114f * px[q + 2] }
    }
    out[L] = cnt / (N * N).toFloat(); out[L + 1] = if (cnt > 0) lm / cnt else 0f
    return out
}

/** Расстояние между снимком клетки и эталоном со сдвигом (dx,dy) — терпит неточность ручной сетки. */
fun dist(a: FloatArray, t: FloatArray, dx: Int, dy: Int): Float {
    var d = 0f
    for (y in 0 until N) for (x in 0 until N) {
        val xs = x + dx; val ys = y + dy; val q = (y * N + x) * 3
        if (xs in 0 until N && ys in 0 until N) {
            val u = (ys * N + xs) * 3
            d += abs(a[q] - t[u]) + abs(a[q + 1] - t[u + 1]) + abs(a[q + 2] - t[u + 2])
        } else d += abs(a[q] - BG) + abs(a[q + 1] - BG) + abs(a[q + 2] - BG)
    }
    return d
}

/** Класс клетки: 0 пусто, 1..6 белые P N B R Q K, 7..12 чёрные. */
fun classify(a: FloatArray, tpl: Array<FloatArray>): Int {
    if (a[L] < .03f) return 0
    var best = 0; var bd = Float.MAX_VALUE
    for (k in 0..12) for (dy in -1..1) for (dx in -1..1) { val d = dist(a, tpl[k], dx, dy); if (d < bd) { bd = d; best = k } }
    return best
}

fun saveTpl(f: File, t: Array<FloatArray>) { DataOutputStream(f.outputStream().buffered()).use { o -> for (a in t) for (v in a) o.writeFloat(v) } }
fun loadTpl(f: File): Array<FloatArray>? = try {
    DataInputStream(f.inputStream().buffered()).use { i -> Array(13) { FloatArray(L) { i.readFloat() } } }
} catch (e: Exception) { null }

class Out(val status: String, val log: String, val fen: String, val lock: Boolean)

/**
 * Каждый устойчивый кадр читается ВЕСЬ (все клетки, любые фигуры) и сверяется с внутренней позицией:
 * ищется легальный ход (в т.ч. превращение в любую фигуру), после которого позиция совпадёт с экраном;
 * если не нашёлся один ход — пробуем два (соперник сходил быстро). Если расхождение держится — позиция
 * берётся с экрана заново (синхронизация).
 */
class Tracker(private val file: File) {
    @Volatile var cmd = 0 // 1 старт, 2 сброс, 3 перевернуть доску
    private val hint = "Совместите сетку с доской. Первый раз откройте начальную позицию и нажмите «Старт»."
    private var tpl = loadTpl(file)
    private var wb = true // белые внизу экрана
    private var hyp = ArrayList<Board>() // гипотезы позиции (2, если неизвестно, чей ход)
    private val moves = ArrayList<String>()
    private var num = 1
    private var last: IntArray? = null
    private var same = 0
    private var bad = 0
    private var cur = 0
    private var msg = hint
    private var locked = false
    private val names = Array(64) { Square.valueOf("${'A' + it % 8}${it / 8 + 1}") }
    private val back = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
    private val types = listOf(PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN, PieceType.KING)

    private fun sq(i: Int) = (if (wb) 7 - i / 8 else i / 8) * 8 + (if (wb) i % 8 else 7 - i % 8)
    private fun code(p: Piece) = if (p == Piece.NONE) 0 else types.indexOf(p.pieceType) + 1 + (if (p.pieceSide == Side.WHITE) 0 else 6)
    private fun occ(b: Board) = IntArray(64) { code(b.getPiece(names[it])) }
    private fun diff(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }
    private fun startCode(s: Int): Int { val f = s % 8; return when (s / 8) { 0 -> back[f]; 1 -> 1; 6 -> 7; 7 -> back[f] + 6; else -> 0 } }
    private fun valid(r: IntArray) = r.count { it == 6 } == 1 && r.count { it == 12 } == 1 && (0..7).all { r[it] != 1 && r[it] != 7 && r[56 + it] != 1 && r[56 + it] != 7 }

    private fun fen(r: IntArray, w: Boolean): String {
        val sb = StringBuilder()
        for (rk in 7 downTo 0) {
            var e = 0
            for (f in 0..7) { val k = r[rk * 8 + f]; if (k == 0) e++ else { if (e > 0) { sb.append(e); e = 0 }; sb.append("PNBRQKpnbrqk"[k - 1]) } }
            if (e > 0) sb.append(e)
            if (rk > 0) sb.append('/')
        }
        val ca = (if (r[4] == 6 && r[7] == 4) "K" else "") + (if (r[4] == 6 && r[0] == 4) "Q" else "") +
            (if (r[60] == 12 && r[63] == 10) "k" else "") + (if (r[60] == 12 && r[56] == 10) "q" else "")
        return "$sb ${if (w) 'w' else 'b'} ${ca.ifEmpty { "-" }} - 0 1"
    }

    /** Позиция с экрана: чей ход, по картинке не видно — держим две гипотезы, первый же ход всё решит. */
    private fun sync(r: IntArray): Boolean {
        if (!valid(r)) return false
        val hs = ArrayList<Board>()
        for (w in listOf(true, false)) try { hs.add(Board().apply { loadFromFen(fen(r, w)) }) } catch (e: Exception) { }
        if (hs.isEmpty()) return false
        hyp = hs; num = 1; return true
    }

    /** Сторона: у белых пешки обычно ниже (ближе к низу экрана), чем у чёрных; плюс положение королей. */
    private fun guess(c: IntArray): Boolean {
        var sw = 0f; var nw = 0; var sb = 0f; var nb = 0
        for (i in 0 until 64) { if (c[i] == 1) { sw += i / 8; nw++ } else if (c[i] == 7) { sb += i / 8; nb++ } }
        val pawns = if (nw > 0 && nb > 0) sw / nw - sb / nb else 0f
        return pawns + .5f * (c.indexOf(6) / 8 - c.indexOf(12) / 8) >= 0
    }

    private fun start(rp: Array<FloatArray>) {
        val o = BooleanArray(64) { rp[it][L] > .07f }
        val miss = (0 until 64).count { if (it / 8 in 2..5) o[it] else !o[it] }
        if (miss <= 4) { // на экране начальная позиция: выучиваем вид всех 12 фигур этой темы
            val top = (0..15).map { rp[it][L + 1] }.average(); val bot = (48..63).map { rp[it][L + 1] }.average()
            if (abs(top - bot) < 25) { msg = "Не удалось отличить белые фигуры от чёрных"; return }
            wb = bot > top // ближние к вам фигуры светлее => вы играете белыми
            val sum = Array(13) { FloatArray(L) }; val cnt = IntArray(13)
            for (i in 0 until 64) { val k = startCode(sq(i)); if (k > 0) { for (q in 0 until L) sum[k][q] += rp[i][q]; cnt[k]++ } }
            val t = Array(13) { k -> FloatArray(L) { if (k == 0) BG else sum[k][it] / cnt[k] } }
            tpl = t; saveTpl(file, t)
            hyp = arrayListOf(Board()); moves.clear(); num = 1
            msg = "Фигуры выучены, партия началась"
        } else {
            val t = tpl
            if (t == null) { msg = "Нет эталонов фигур: откройте начальную позицию и нажмите «Старт»"; return }
            val cls = IntArray(64) { classify(rp[it], t) }
            wb = guess(cls)
            val rec = IntArray(64); for (i in 0 until 64) rec[sq(i)] = cls[i]
            if (!sync(rec)) { msg = "Не удалось прочитать позицию"; return }
            moves.clear(); msg = "Позиция прочитана"
        }
        locked = true; last = null; same = 0; bad = 0; cur = 0
    }

    private fun letter(t: PieceType) = when (t) {
        PieceType.KNIGHT -> "N"; PieceType.BISHOP -> "B"; PieceType.ROOK -> "R"; PieceType.QUEEN -> "Q"; PieceType.KING -> "K"; else -> ""
    }

    private fun notate(b: Board, m: Move): String {
        val p = b.getPiece(m.from); val a = m.from.name; val z = m.to.name
        if (p.pieceType == PieceType.KING && abs(a[0] - z[0]) == 2) return if (z[0] > a[0]) "O-O" else "O-O-O"
        val cap = b.getPiece(m.to) != Piece.NONE || (p.pieceType == PieceType.PAWN && a[0] != z[0])
        return letter(p.pieceType) + a.lowercase() + (if (cap) "x" else "-") + z.lowercase() +
            (if (m.promotion != Piece.NONE) "=" + letter(m.promotion.pieceType) else "")
    }

    private fun commit(b: Board, m: Move) {
        val s = notate(b, m); val w = b.sideToMove == Side.WHITE
        b.doMove(m)
        moves.add((if (w) "$num. " else "$num… ") + s + (if (b.isMated()) "#" else if (b.isKingAttacked()) "+" else ""))
        if (!w) num++
        hyp = arrayListOf(b); bad = 0
    }

    private fun tryMove(rec: IntArray): Boolean {
        var bb: Board? = null; var bm: Move? = null; var b1 = 99; var b2 = 99
        for (h in hyp) for (m in h.legalMoves()) {
            val nb = h.clone(); nb.doMove(m)
            val d = diff(rec, occ(nb))
            if (d < b1) { b2 = b1; b1 = d; bb = h; bm = m } else if (d < b2) b2 = d
        }
        if (bb != null && bm != null && b1 <= 2 && b1 < cur && b2 > b1) { commit(bb, bm); return true }
        if (cur < 3) return false
        for (h in hyp) for (m1 in h.legalMoves()) {
            val n1 = h.clone(); n1.doMove(m1)
            for (m2 in n1.legalMoves()) {
                val n2 = n1.clone(); n2.doMove(m2)
                if (diff(rec, occ(n2)) == 0) { commit(h, m1); commit(h, m2); return true }
            }
        }
        return false
    }

    private fun status() = (if (msg.isNotEmpty()) "$msg • " else "") + "Вы: " + (if (wb) "белые" else "чёрные") +
        (if (hyp.size == 1) " • ход " + (if (hyp[0].sideToMove == Side.WHITE) "белых" else "чёрных") else "") +
        (if (cur == 0) " • позиция ✓" else " • расхождение: $cur") + (if (same < 2) " …" else "")

    fun step(rp: Array<FloatArray>): Out {
        val c = cmd.also { cmd = 0 }
        if (c == 2) { hyp.clear(); moves.clear(); locked = false; last = null; msg = hint }
        if (c == 1) start(rp)
        if (c == 3 && hyp.isNotEmpty()) { wb = !wb; last = null; same = 0; bad = 99 }
        val t = tpl
        if (t == null || hyp.isEmpty()) return Out(msg, "", "", locked)
        val cls = IntArray(64) { classify(rp[it], t) }
        val rec = IntArray(64); for (i in 0 until 64) rec[sq(i)] = cls[i]
        val l = last; same = if (l != null && l.contentEquals(rec)) same + 1 else 0; last = rec
        if (same >= 2) { // картинка не менялась 3 чтения подряд (~0,75 с): анимация хода закончилась
            if (!valid(rec)) msg = "Не удалось прочитать доску"
            else {
                msg = ""
                cur = hyp.minOf { diff(rec, occ(it)) }
                if (cur == 0) bad = 0
                else if (!tryMove(rec) && ++bad >= 6 && sync(rec)) { moves.add("⟳"); bad = 0; cur = 0 } // расхождение держится — берём позицию с экрана
            }
        }
        return Out(status(), moves.joinToString("  "), fen(rec, true).substringBefore(' '), locked)
    }
}
