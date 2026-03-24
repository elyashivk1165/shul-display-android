package app.shul.display

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Long press → settings menu
        webView.setOnLongClickListener {
            showSettingsDialog()
            true
        }

        // Register / refresh device in Supabase (runs every launch to catch missed registrations)
        CoroutineScope(Dispatchers.IO).launch {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val appVersion = DeviceUtils.getAppVersion(applicationContext)
            SupabaseClient.registerDevice(deviceId, slug, appVersion)
        }

        // Silent update check on startup
        checkForUpdateSilent()
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

    // ── Settings dialog ─────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val items = arrayOf(
            "🔄  רענן מסך",
            "✏️  שנה סלאג",
            "⬆️  בדוק עדכונים",
            "🚪  סגור אפליקציה"
        )
        AlertDialog.Builder(this)
            .setTitle("הגדרות")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> showChangeSlugDialog()
                    2 -> checkForUpdateManual()
                    3 -> finishAndRemoveTask()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun showChangeSlugDialog() {
        val currentSlug = prefs.getString("slug", "") ?: ""
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val input = EditText(this).apply {
            hint = "הזן סלאג"
            setText(currentSlug)
            selectAll()
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("שנה סלאג")
            .setView(layout)
            .setPositiveButton("שמור") { _, _ ->
                val newSlug = input.text.toString().trim()
                if (newSlug.isNotBlank()) {
                    prefs.edit().putString("slug", newSlug).apply()
                    CoroutineScope(Dispatchers.IO).launch {
                        SupabaseClient.updateDeviceSlug(DeviceUtils.getDeviceId(applicationContext), newSlug)
                    }
                    webView.loadUrl(BASE_URL + newSlug)
                    Toast.makeText(this, "טוען: $newSlug", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ── Update checker ──────────────────────────────────────────────────────

    private fun checkForUpdateSilent() {
        val currentVersion = DeviceUtils.getAppVersion(this)
        CoroutineScope(Dispatchers.IO).launch {
            val release = UpdateChecker.checkForUpdate(currentVersion) ?: return@launch
            withContext(Dispatchers.Main) {
                showUpdateDialog(release, silent = true)
            }
        }
    }

    private fun checkForUpdateManual() {
        val currentVersion = DeviceUtils.getAppVersion(this)
        Toast.makeText(this, "בודק עדכונים...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val release = UpdateChecker.checkForUpdate(currentVersion)
            withContext(Dispatchers.Main) {
                if (release == null) {
                    Toast.makeText(this@MainActivity, "✓ הגרסה עדכנית (v$currentVersion)", Toast.LENGTH_LONG).show()
                } else {
                    showUpdateDialog(release, silent = false)
                }
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo, silent: Boolean) {
        val msg = buildString {
            append("גרסה חדשה ${release.version} זמינה.")
            if (release.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(release.releaseNotes.take(250))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("⬆️ עדכון זמין")
            .setMessage(msg)
            .setPositiveButton("הורד ועדכן") { _, _ ->
                UpdateChecker.downloadAndInstall(this, release) { status ->
                    runOnUiThread { Toast.makeText(this, status, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton(if (silent) "אחר כך" else "סגור", null)
            .show()
    }

    // ── Fullscreen ──────────────────────────────────────────────────────────

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
