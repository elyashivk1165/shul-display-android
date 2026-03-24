package app.shul.display

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
                safeBrowsingEnabled = true
            }
        }
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (isDestroyed || isFinishing) return true
                Log.e(TAG, "WebView renderer gone, recreating...")
                try {
                    view.webViewClient = null
                    view.webChromeClient = null
                    val container = findViewById<android.widget.FrameLayout>(R.id.webViewContainer)
                    container?.removeView(view)
                    view.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up crashed WebView: ${e.message}")
                }
                setupWebView()
                loadUrl()
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

    private fun loadUrl() {
        val slug = prefs.getString("slug", "") ?: ""
        webView.loadUrl(BASE_URL + slug)
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
        val items = buildList {
            add("🔄  רענן מסך")
            add("✏️  שנה סלאג")
            add("⬆️  בדוק עדכונים")
            val (offTime, onTime) = ScreenScheduleManager.getSchedule(this@MainActivity)
            if (offTime != null) {
                add("🌙  לוח זמנים: כיבוי $offTime | הדלקה $onTime")
            } else {
                add("🌙  הגדר לוח זמנים למסך")
            }
            if (!ScreenScheduleManager.isDeviceAdminActive(this@MainActivity)) {
                add("🔑  הפעל שליטת מסך (נדרש פעם אחת)")
            }
            add("🔲  כבה מסך עכשיו")
            add("🚪  סגור אפליקציה")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("הגדרות")
            .setItems(items) { _, which ->
                val label = items[which]
                when {
                    label.startsWith("🔄") -> webView.reload()
                    label.startsWith("✏️") -> showChangeSlugDialog()
                    label.startsWith("⬆️") -> checkForUpdateManual()
                    label.startsWith("🌙") -> showScheduleDialog()
                    label.startsWith("🔑") -> requestDeviceAdmin()
                    label.startsWith("🔲") -> ScreenScheduleManager.lockScreen(this)
                    label.startsWith("🚪") -> finishAndRemoveTask()
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

    private fun showScheduleDialog() {
        if (isFinishing || isDestroyed) return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val offInput = EditText(this).apply {
            hint = "שעת כיבוי (לדוגמה: 23:00)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val (offTime, _) = ScreenScheduleManager.getSchedule(this@MainActivity)
            setText(offTime ?: "")
        }
        val onInput = EditText(this).apply {
            hint = "שעת הדלקה (לדוגמה: 07:00)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val (_, onTime) = ScreenScheduleManager.getSchedule(this@MainActivity)
            setText(onTime ?: "")
        }

        layout.addView(TextView(this).apply { text = "⏰ לוח זמנים למסך" })
        layout.addView(offInput)
        layout.addView(onInput)

        AlertDialog.Builder(this)
            .setTitle("כיבוי/הדלקה אוטומטיים")
            .setView(layout)
            .setPositiveButton("שמור") { _, _ ->
                val off = offInput.text.toString().trim()
                val on = onInput.text.toString().trim()
                if (off.isNotBlank() && on.isNotBlank()) {
                    ScreenScheduleManager.setSchedule(this, off, on)
                    Toast.makeText(this, "✅ לוח זמנים הוגדר: כיבוי $off | הדלקה $on", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("בטל לוח זמנים") { _, _ ->
                ScreenScheduleManager.setSchedule(this, null, null)
                Toast.makeText(this, "לוח הזמנים בוטל", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, ShulDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "נדרש כדי לאפשר כיבוי והדלקה אוטומטיים של המסך לפי לוח זמנים")
        }
        startActivity(intent)
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

    // ── TV remote long press → settings ────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            openSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun openSettings() {
        if (!isDestroyed && !isFinishing) {
            startActivity(Intent(this, SetupActivity::class.java))
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
