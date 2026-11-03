package com.example.chessscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Прозрачная сетка 8x8 поверх других приложений: окно = сетка + поле для захвата за углы. Всегда квадратная. */
class GridView(c: Context, private val wm: WindowManager, private val moved: () -> Unit) : View(c) {
    private val dp = resources.displayMetrics.density
    val m = 24 * dp
    val p = WindowManager.LayoutParams(
        0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }
    var locked = false
        private set
    val side get() = p.width - 2 * m
    val gx get() = p.x + m
    val gy get() = p.y + m
    private val line = Paint().apply { color = Color.argb(130, 255, 255, 255); strokeWidth = 1.5f * dp; style = Paint.Style.STROKE }
    private val edge = Paint(line).apply { color = Color.argb(230, 255, 255, 255); strokeWidth = 2.5f * dp }
    private var mode = 0; private var ax = 0f; private var ay = 0f; private var dx = 0f; private var dy = 0f; private var px = 0; private var py = 0

    fun place(w: Int, h: Int) {
        val s = min(w, h) * .9f
        p.width = (s + 2 * m).toInt(); p.height = p.width
        p.x = ((w - s) / 2 - m).toInt(); p.y = ((h - s) / 2 - m).toInt()
    }

    /** Зафиксированную сетку не перехватывает касания: можно играть в приложении под ней. */
    fun lock(v: Boolean) {
        if (v == locked) return
        locked = v
        p.flags = if (v) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(this, p); invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val sd = width - 2 * m; val s = sd / 8
        for (i in 0..8) {
            cv.drawLine(m + i * s, m, m + i * s, m + sd, line)
            cv.drawLine(m, m + i * s, m + sd, m + i * s, line)
        }
        cv.drawRect(m, m, m + sd, m + sd, edge)
        if (!locked) for (cx in listOf(m, m + sd)) for (cy in listOf(m, m + sd)) cv.drawCircle(cx, cy, 10 * dp, edge)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) return false
        val sd = width - 2 * m
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cs = listOf(m to m, m + sd to m, m to m + sd, m + sd to m + sd)
                val k = cs.indices.minByOrNull { hypot(e.x - cs[it].first, e.y - cs[it].second) }!!
                if (hypot(e.x - cs[k].first, e.y - cs[k].second) < 44 * dp) {
                    mode = 1; ax = p.x + cs[3 - k].first; ay = p.y + cs[3 - k].second // тянем угол, противоположный неподвижен
                } else { mode = 2; dx = e.rawX; dy = e.rawY; px = p.x; py = p.y }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    val s = max(100 * dp, max(abs(e.rawX - ax), abs(e.rawY - ay)))
                    p.x = ((if (e.rawX < ax) ax - s else ax) - m).toInt()
                    p.y = ((if (e.rawY < ay) ay - s else ay) - m).toInt()
                    p.width = (s + 2 * m).toInt(); p.height = p.width
                } else { p.x = (px + e.rawX - dx).toInt(); p.y = (py + e.rawY - dy).toInt() }
                wm.updateViewLayout(this, p); moved()
            }
        }
        return true
    }
}

/** Служба: запись экрана (MediaProjection) + плавающие сетка и панель. Каждые 250 мс читает всю доску. */
class ScanService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var grid: GridView
    private lateinit var panel: LinearLayout
    private lateinit var pp: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var fenTv: TextView
    private lateinit var sv: ScrollView
    private lateinit var tracker: Tracker
    private lateinit var h: Handler
    private val th = HandlerThread("scan")
    private val ui = Handler(Looper.getMainLooper())
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var cw = 0; private var ch = 0; private var sc = .5f
    private var fr: ByteArray? = null; private var rs = 0
    private val loop = object : Runnable {
        override fun run() { try { tick() } catch (e: Exception) { }; h.postDelayed(this, 250) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (proj != null) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("scan", "ChessScan", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "scan").setContentTitle("ChessScan работает").setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
        @Suppress("DEPRECATION") val data = i?.getParcelableExtra<Intent>("data")
        val code = i?.getIntExtra("code", -1) ?: -1
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        cw = dm.widthPixels / 2; ch = dm.heightPixels / 2; sc = cw / dm.widthPixels.toFloat() // анализируем кадр вдвое меньше экрана
        val pr = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        pr.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        proj = pr
        val rd = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2); reader = rd
        vd = pr.createVirtualDisplay("scan", cw, ch, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null)
        tracker = Tracker(File(filesDir, "tpl.bin"))
        buildPanel()
        grid = GridView(this, wm) { placePanel() }
        grid.place(dm.widthPixels, dm.heightPixels)
        wm.addView(grid, grid.p)
        placePanel()
        th.start(); h = Handler(th.looper); h.post(loop)
        return START_NOT_STICKY
    }

    private fun buildPanel() {
        val dp = resources.displayMetrics.density
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 13f; minHeight = 0; minimumHeight = 0; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f)
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text = "Совместите сетку с доской. Первый раз откройте начальную позицию и нажмите «Старт»." }
        log = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        sv = ScrollView(this).apply { addView(log) }
        fenTv = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 10f }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(190, 0, 0, 0)); setPadding(12, 12, 12, 12)
            addView(status); addView(sv, LinearLayout.LayoutParams(-1, (64 * dp).toInt())); addView(fenTv)
            addView(LinearLayout(this@ScanService).apply {
                addView(btn("Старт") { tracker.cmd = 1 })
                addView(btn("Сброс") { tracker.cmd = 2 })
                addView(btn("↕") { tracker.cmd = 3 }) // перевернуть доску (если сторону определило неверно)
                addView(btn("✕") { stopSelf() })
            })
        }
        pp = WindowManager.LayoutParams(-1, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        pp.gravity = Gravity.TOP
        wm.addView(panel, pp)
    }

    /** Панель уходит на ту половину экрана, где нет доски, чтобы не попадать в кадр анализа. */
    private fun placePanel() {
        val g = if (grid.gy + grid.side / 2 > (ch / sc) / 2) Gravity.TOP else Gravity.BOTTOM
        if (pp.gravity != g) { pp.gravity = g; wm.updateViewLayout(panel, pp) }
    }

    private fun tick() {
        val img = reader?.acquireLatestImage()
        if (img != null) try { // экран не менялся — новых кадров нет, тогда анализируем последний
            val b = img.planes[0].buffer; b.rewind()
            val a = fr?.takeIf { it.size == b.remaining() } ?: ByteArray(b.remaining()).also { fr = it }
            b.get(a); rs = img.planes[0].rowStride
        } finally { img.close() }
        val bytes = fr ?: return
        val x = grid.gx * sc; val y = grid.gy * sc; val s = grid.side * sc
        if (x < 0 || y < 0 || x + s > cw || y + s > ch) { ui.post { status.text = "Сетка выходит за пределы экрана" }; return }
        val bb = ByteBuffer.wrap(bytes)
        val rp = Array(64) { cellRep(bb, rs, x + (it % 8) * s / 8, y + (it / 8) * s / 8, s / 8) }
        val o = tracker.step(rp)
        ui.post { status.text = o.status; log.text = o.log; fenTv.text = o.fen; grid.lock(o.lock); sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    }

    override fun onDestroy() {
        try { h.removeCallbacksAndMessages(null); th.quitSafely(); vd?.release(); reader?.close(); proj?.stop(); wm.removeView(grid); wm.removeView(panel) } catch (e: Exception) { }
        super.onDestroy()
    }
}
