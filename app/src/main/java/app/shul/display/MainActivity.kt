package app.shul.display

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    companion object {
        const val BASE_URL = "https://shul-display.vercel.app/"
        var instance: MainActivity? = null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        enableFullscreen()

        prefs = getSharedPreferences("shul_display_prefs", MODE_PRIVATE)
        val slug = prefs.getString("slug", "") ?: ""

        if (slug.isBlank()) {
            finish()
            return
        }

        webView = findViewById(R.id.webView)
        setupWebView()
        webView.loadUrl(BASE_URL + slug)

        // Long press anywhere on the screen → open settings dialog
        webView.setOnLongClickListener {
            showSettingsDialog()
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }

        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
    }

    private fun showSettingsDialog() {
        val currentSlug = prefs.getString("slug", "") ?: ""

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val slugInput = EditText(this).apply {
            hint = "Slug"
            setText(currentSlug)
            selectAll()
        }
        layout.addView(slugInput)

        AlertDialog.Builder(this)
            .setTitle("הגדרות תצוגה")
            .setView(layout)
            .setPositiveButton("שמור") { _, _ ->
                val newSlug = slugInput.text.toString().trim()
                if (newSlug.isNotBlank()) {
                    prefs.edit().putString("slug", newSlug).apply()
                    webView.loadUrl(BASE_URL + newSlug)
                    Toast.makeText(this, "טוען: $newSlug", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("רענן") { _, _ ->
                webView.reload()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun enableFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    fun reloadWebView() {
        runOnUiThread { webView.reload() }
    }

    fun updateSlug(newSlug: String) {
        prefs.edit().putString("slug", newSlug).apply()
        runOnUiThread { webView.loadUrl(BASE_URL + newSlug) }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullscreen()
    }
}
