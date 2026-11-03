package com.example.chessscan

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val cap = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java).putExtra("code", r.resultCode).putExtra("data", d))
            moveTaskToBack(true)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val tv = TextView(this).apply {
            textSize = 16f; setPadding(48, 160, 48, 48)
            text = "ChessScan читает шахматную доску с экрана.\n\n" +
                "1. Нажмите «Запустить», разрешите показ поверх других окон и запись экрана (выберите «Весь экран»).\n" +
                "2. Откройте шахматное приложение или сайт. Поверх появятся прозрачная сетка и панель.\n" +
                "3. Двигайте сетку пальцем и тяните за углы, пока она не совпадёт с доской.\n" +
                "4. Первый раз откройте начальную позицию партии и нажмите «Старт» — приложение выучит вид фигур вашей темы. " +
                "Дальше «Старт» в любой позиции читает доску целиком."
        }
        val b = Button(this).apply { text = "Запустить"; setOnClickListener { go() } }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(b) })
    }

    private fun go() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        cap.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
