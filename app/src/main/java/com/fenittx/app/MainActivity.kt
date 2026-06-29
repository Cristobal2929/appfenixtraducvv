package com.fenittx.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnActivar = findViewById<Button>(R.id.btn_activar)
        btnActivar.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                addBubble()
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    private fun addBubble() {
        val bubble = TextView(this)
        bubble.text = "🌐"
        bubble.textSize = 60f
        bubble.setTextColor(Color.WHITE)

        val background = GradientDrawable()
        background.shape = GradientDrawable.OVAL
        background.setColor(Color.parseColor("#2196F3")) // azul
        bubble.background = background
        bubble.gravity = Gravity.CENTER

        bubble.setOnClickListener {
            Toast.makeText(this, "Burbuja activa", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                val translated = translateText("bonjour")
                Toast.makeText(this@MainActivity, translated, Toast.LENGTH_LONG).show()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(bubble, params)
    }

    private suspend fun translateText(text: String): String {
        return withContext(Dispatchers.IO) {
            val url = "https://api.mymemory.translated.net/get?q=${Uri.encode(text)}&langpair=fr|es"
            val request = Request.Builder()
                .url(url)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext "Error: ${response.code}"
                    val body = response.body?.string() ?: return@withContext "Respuesta vacía"
                    val json = JSONObject(body)
                    val responseData = json.getJSONObject("responseData")
                    return@withContext responseData.getString("translatedText")
                }
            } catch (e: IOException) {
                return@withContext "Excepción: ${e.message}"
            }
        }
    }
}