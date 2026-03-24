package app.shul.display

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var reloadOverlay: TextView
    private var lastSuccessfulLoad = System.currentTimeMillis()

    companion object {
        private const val TAG = "MainActivity"
        const val BASE_URL = "https://shul-display.vercel.app/"
        private var instanceRef: WeakReference<MainActivity>? = null
        val instance: MainActivity? get() = instanceRef?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceRef = WeakReference(this)

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
        reloadOverlay = findViewById(R.id.reloadOverlay)
        setupWebView()
        webView.loadUrl(BASE_URL + slug)

        // Long press → settings menu
        webView.setOnLongClickListener {
            showSettingsDialog()
            true
        }

        // Register / refresh device in Supabase
        lifecycleScope.launch(Dispatchers.IO) {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val appVersion = DeviceUtils.getAppVersion(applicationContext)
            SupabaseClient.registerDevice(deviceId, slug, appVersion)
        }

        // Start persistent background service
        DisplayForegroundService.start(this)

        // Start WebView watchdog
        startWebViewWatchdog()
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
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false  // Not needed for internal app, saves startup time
            }
        }
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (isDestroyed || isFinishing) return true
                Log.e(TAG, "WebView renderer ${if (detail?.didCrash() == true) "crashed" else "killed"}, recreating...")
                try {
                    val container = findViewById<android.widget.FrameLayout>(R.id.webViewContainer)
                    // Remove ONLY the webview, not the overlay
                    container?.removeView(view)
                    if (::webView.isInitialized) {
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                    webView = WebView(this@MainActivity).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    container?.addView(webView, 0)  // add at index 0, below overlay
                    setupWebView()
                    val slug = prefs.getString("slug", "") ?: ""
                    webView.loadUrl(BASE_URL + slug)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to recreate WebView", e)
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "WebView load error: ${error?.description}")
                    lifecycleScope.launch {
                        delay(30_000)
                        if (!isDestroyed && !isFinishing && ::webView.isInitialized) {
                            webView.reload()
                        }
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lastSuccessfulLoad = System.currentTimeMillis()
            }
        }
        webView.webChromeClient = WebChromeClient()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = WebSettings.FORCE_DARK_OFF
        }

        // WebView renderer priority
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }
    }

    // ── WebView watchdog ─────────────────────────────────────────────────────

    private fun startWebViewWatchdog() {
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                val elapsed = System.currentTimeMillis() - lastSuccessfulLoad
                if (elapsed > 5 * 60_000L) {
                    Log.w(TAG, "WebView watchdog: no successful load in ${elapsed/1000}s, force reloading")
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && ::webView.isInitialized) {
                            val slug = prefs.getString("slug", "") ?: ""
                            webView.loadUrl(BASE_URL + slug)
                        }
                    }
                    lastSuccessfulLoad = System.currentTimeMillis()
                }
            }
        }
    }

    // ── Public methods for DisplayForegroundService ──────────────────────────

    fun reloadWebViewWithIndicator() {
        if (!isDestroyed && !isFinishing && ::webView.isInitialized) {
            reloadOverlay?.visibility = View.VISIBLE
            lifecycleScope.launch {
                delay(800)
                reloadOverlay?.visibility = View.GONE
                webView.reload()
            }
        }
    }

    fun clearWebViewCache() {
        if (!isDestroyed && !isFinishing && ::webView.isInitialized) {
            webView.clearCache(true)
            webView.reload()
        }
    }

    fun reloadWebView() {
        runOnUiThread {
            if (!isDestroyed && !isFinishing && ::webView.isInitialized) {
                webView.reload()
            }
        }
    }

    fun updateSlug(newSlug: String) {
        prefs.edit().putString("slug", newSlug).apply()
        runOnUiThread {
            if (!isDestroyed && !isFinishing && ::webView.isInitialized) {
                webView.loadUrl(BASE_URL + newSlug)
            }
        }
    }

    // ── Settings dialog ─────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return
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
        if (isFinishing || isDestroyed) return
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
                    lifecycleScope.launch(Dispatchers.IO) {
                        SupabaseClient.updateDeviceSlug(DeviceUtils.getDeviceId(applicationContext), newSlug)
                    }
                    webView.loadUrl(BASE_URL + newSlug)
                    Toast.makeText(this, "טוען: $newSlug", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ── Update checker (manual only — auto-update moved to service) ─────────

    private fun checkForUpdateManual() {
        val currentVersion = DeviceUtils.getAppVersion(this)
        Toast.makeText(this, "בודק עדכונים...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val release = UpdateChecker.checkForUpdate(currentVersion)
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                if (release == null) {
                    Toast.makeText(this@MainActivity, "✓ הגרסה עדכנית (v$currentVersion)", Toast.LENGTH_LONG).show()
                } else {
                    showUpdateDialog(release)
                }
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        if (isFinishing || isDestroyed) return
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
                lifecycleScope.launch(Dispatchers.IO) {
                    UpdateChecker.downloadAndInstall(this@MainActivity, release) { status ->
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                Toast.makeText(this@MainActivity, status, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("סגור", null)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kiosk mode — ignore back button
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onDestroy() {
        instanceRef = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullscreen()
    }
}
