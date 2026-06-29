package com.fenittx.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private lateinit var wm: WindowManager
    private var panelView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentMode = Mode.VOICE

    private enum class Mode { VOICE, TEXT }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

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
            openPanel()
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

        wm.addView(bubble, params)
    }

    private fun openPanel() {
        if (panelView != null) return // already open

        // Root layout
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#222222"))
        root.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))

        // Close button (X) aligned to end
        val closeBtn = TextView(this)
        closeBtn.text = "X"
        closeBtn.setTextColor(Color.WHITE)
        closeBtn.textSize = 18f
        closeBtn.gravity = Gravity.END
        closeBtn.setOnClickListener { closePanel() }
        root.addView(closeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Mode toggle buttons container
        val modeContainer = LinearLayout(this)
        modeContainer.orientation = LinearLayout.HORIZONTAL

        val btnVoice = Button(this)
        btnVoice.text = "🎤 Voz"
        btnVoice.setBackgroundColor(Color.parseColor("#444444"))
        btnVoice.setTextColor(Color.WHITE)
        btnVoice.setOnClickListener { switchMode(Mode.VOICE) }

        val btnText = Button(this)
        btnText.text = "📝 Texto"
        btnText.setBackgroundColor(Color.parseColor("#444444"))
        btnText.setTextColor(Color.WHITE)
        btnText.setOnClickListener { switchMode(Mode.TEXT) }

        modeContainer.addView(btnVoice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeContainer.addView(btnText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(modeContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(8) })

        // Content container where we swap voice/text UI
        val contentContainer = LinearLayout(this)
        contentContainer.orientation = LinearLayout.VERTICAL
        root.addView(contentContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(8) })

        // ---- Voice mode UI ----
        val tvFrenchVoice = TextView(this)
        tvFrenchVoice.setTextColor(Color.WHITE)
        tvFrenchVoice.textSize = 16f
        tvFrenchVoice.text = "Frances: "

        val tvSpanishVoice = TextView(this)
        tvSpanishVoice.setTextColor(Color.WHITE)
        tvSpanishVoice.textSize = 16f
        tvSpanishVoice.text = "Español: "

        val btnListen = Button(this)
        btnListen.text = "Iniciar escucha"
        btnListen.setBackgroundColor(Color.parseColor("#444444"))
        btnListen.setTextColor(Color.WHITE)

        btnListen.setOnClickListener {
            if (isListening) {
                stopListening()
                btnListen.text = "Iniciar escucha"
            } else {
                if (checkAudioPermission()) {
                    startListening()
                    btnListen.text = "Detener escucha"
                }
            }
        }

        // ---- Text mode UI ----
        val etFrench = EditText(this)
        etFrench.setTextColor(Color.WHITE)
        etFrench.setHintTextColor(Color.parseColor("#AAAAAA"))
        etFrench.setHint("Texto en francés")
        etFrench.setBackgroundColor(Color.parseColor("#333333"))

        val btnTranslate = Button(this)
        btnTranslate.text = "Traducir"
        btnTranslate.setBackgroundColor(Color.parseColor("#444444"))
        btnTranslate.setTextColor(Color.WHITE)

        val btnCopy = Button(this)
        btnCopy.text = "Copiar"
        btnCopy.setBackgroundColor(Color.parseColor("#444444"))
        btnCopy.setTextColor(Color.WHITE)

        val tvSpanishText = TextView(this)
        tvSpanishText.setTextColor(Color.WHITE)
        tvSpanishText.textSize = 16f
        tvSpanishText.text = ""

        // Add listeners for text mode
        btnTranslate.setOnClickListener {
            val input = etFrench.text.toString()
            if (input.isNotBlank()) {
                translateAndShow(input) { result ->
                    tvSpanishText.text = result
                }
            }
        }

        btnCopy.setOnClickListener {
            val result = tvSpanishText.text.toString()
            if (result.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("traducción", result)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        // Helper to populate content container based on mode
        fun populateContent(mode: Mode) {
            contentContainer.removeAllViews()
            when (mode) {
                Mode.VOICE -> {
                    contentContainer.addView(tvFrenchVoice)
                    contentContainer.addView(tvSpanishVoice)
                    contentContainer.addView(btnListen)
                }
                Mode.TEXT -> {
                    contentContainer.addView(etFrench)
                    val btnRow = LinearLayout(this)
                    btnRow.orientation = LinearLayout.HORIZONTAL
                    btnRow.addView(btnTranslate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    btnRow.addView(btnCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    contentContainer.addView(btnRow)
                    contentContainer.addView(tvSpanishText)
                }
            }
        }

        // Initial mode
        populateContent(currentMode)

        // Store UI references for later updates
        val voiceUi = VoiceUI(tvFrenchVoice, tvSpanishVoice)
        val textUi = TextUI(etFrench, tvSpanishText)

        // Mode switch implementation
        fun switchMode(mode: Mode) {
            currentMode = mode
            populateContent(mode)
            // Reset listening state when leaving voice mode
            if (mode != Mode.VOICE) {
                stopListening()
                btnListen.text = "Iniciar escucha"
            }
        }

        // Assign to outer scope for later use
        this.switchMode = ::switchMode
        this.voiceUi = voiceUi
        this.textUi = textUi

        // LayoutParams for panel
        val panelParams = WindowManager.LayoutParams(
            dpToPx(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        panelParams.gravity = Gravity.CENTER

        panelView = root
        wm.addView(root, panelParams)
    }

    private fun closePanel() {
        panelView?.let {
            wm.removeView(it)
            panelView = null
            stopListening()
        }
    }

    // ---------- Speech Recognition ----------
    private fun startListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // Restart listening on error to keep it continuous
                    if (isListening) startListening()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    voiceUi?.tvFrench?.text = "Frances: $text"
                    translateAndShow(text) { translated ->
                        voiceUi?.tvSpanish?.text = "Español: $translated
                    }
                    // Continue listening
                    if (isListening) startListening()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("fr", "FR"))
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

        speechRecognizer?.startListening(intent)
        isListening = true
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    // ---------- Translation ----------
    private fun translateAndShow(text: String, onResult: (String) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val translated = translateText(text)
            onResult(translated)
        }
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

    // ---------- Helpers ----------
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun checkAudioPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start listening if user had pressed the button
                if (currentMode == Mode.VOICE && !isListening) {
                    startListening()
                }
            } else {
                Toast.makeText(this, "Permiso de audio necesario", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- UI holder classes ----------
    private data class VoiceUI(
        val tvFrench: TextView,
        val tvSpanish: TextView
    )

    private data class TextUI(
        val etFrench: EditText,
        val tvSpanish: TextView
    )

    // References used inside openPanel()
    private var voiceUi: VoiceUI? = null
    private var textUi: TextUI? = null
    private var switchMode: ((Mode) -> Unit)? = null
}