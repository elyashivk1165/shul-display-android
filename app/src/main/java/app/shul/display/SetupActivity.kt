package app.shul.display

import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var fromSettings = false

    companion object {
        private const val TAG = "SetupActivity"
        private const val REQUEST_ROLE_HOME = 1001
        private const val REQUEST_HOME_SETTINGS = 1002
        private const val REQUEST_OVERLAY_PERMISSION = 1003
        private const val REQUEST_ACCESSIBILITY_SETTINGS = 1004
        private const val REQUEST_EXACT_ALARM = 1005
        private const val REQUEST_TURN_SCREEN_ON = 1006
        private const val REQUEST_NOTIFICATIONS = 1007
        private val SLUG_REGEX = Regex("^[a-zA-Z0-9_-]{1,50}$")

        /** True while SetupActivity is in the foreground — prevents accessibility
         *  service from auto-launching MainActivity over the setup flow. */
        @Volatile var isVisible = false
    }

    override fun onStart() { super.onStart(); isVisible = true }
    override fun onStop() { super.onStop(); isVisible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("shul_display_prefs", MODE_PRIVATE)

        fromSettings = intent.getBooleanExtra("from_settings", false)
        val existingSlug = prefs.getString("slug", null)

        setContentView(R.layout.activity_setup)

        val slugInput = findViewById<EditText>(R.id.slugInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val errorText = findViewById<android.widget.TextView>(R.id.errorText)

        // Pre-fill existing slug when editing
        if (!existingSlug.isNullOrBlank()) {
            slugInput.setText(existingSlug)
            slugInput.selectAll()
        }

        // Permissions section — shown only in settings mode
        val permissionsSection = findViewById<View>(R.id.permissionsSection)
        if (fromSettings) {
            permissionsSection.visibility = View.VISIBLE
            refreshAllPermissionStatus()
            findViewById<Button>(R.id.overlayButton).setOnClickListener { requestOverlayPermission() }
            findViewById<Button>(R.id.exactAlarmButton).setOnClickListener { requestExactAlarmPermission() }
            findViewById<Button>(R.id.turnScreenOnButton).setOnClickListener { requestTurnScreenOnPermission() }
            findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            findViewById<Button>(R.id.notificationsButton).setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                }
            }
            findViewById<Button>(R.id.hdmiCecButton).setOnClickListener { openHdmiCecSettings() }
        }

        saveButton.setOnClickListener {
            val slug = slugInput.text.toString().trim()
            if (slug.isBlank()) {
                errorText.text = "נא להכניס קוד בית הכנסת"
                errorText.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (!isValidSlug(slug)) {
                errorText.text = "קוד לא תקין — אותיות, מספרים, מקף בלבד"
                errorText.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            errorText.visibility = android.view.View.GONE

            prefs.edit().putString("slug", slug).apply()

            val deviceId = DeviceUtils.getDeviceId(this)
            val appVersion = DeviceUtils.getAppVersion(this)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    SupabaseClient.registerDevice(deviceId, slug, appVersion)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register device", e)
                }
            }

            scheduleCommandPoller()

            if (fromSettings) {
                // Update running MainActivity and return to it
                MainActivity.instance?.updateSlug(slug)
                launchMainActivity()
            } else {
                requestOverlayPermission()
            }
        }
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.canScheduleExactAlarms()
            } catch (e: Exception) {
                Log.w(TAG, "canScheduleExactAlarms check failed: ${e.message}")
                false
            }
        }
        return true
    }

    private fun hasTurnScreenOnPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val appOps = getSystemService(android.app.AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    "android:turn_screen_on",
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    "android:turn_screen_on",
                    android.os.Process.myUid(),
                    packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "hasTurnScreenOnPermission check failed: ${e.message}")
            false
        }
    }

    private fun refreshAllPermissionStatus() {
        setPermissionRow(
            button = findViewById(R.id.overlayButton),
            status = findViewById(R.id.overlayStatus),
            granted = Settings.canDrawOverlays(this),
            descGranted = "✓ הרשאה ניתנה",
            descNeeded = "נדרשת להצגת ממשק מעל הכל"
        )
        setPermissionRow(
            button = findViewById(R.id.exactAlarmButton),
            status = findViewById(R.id.exactAlarmStatus),
            granted = canScheduleExactAlarms(),
            descGranted = "✓ הרשאה ניתנה",
            descNeeded = "נדרשת לשעון שבת / הדלקת מסך בזמן קבוע"
        )
        setPermissionRow(
            button = findViewById(R.id.turnScreenOnButton),
            status = findViewById(R.id.turnScreenOnStatus),
            granted = hasTurnScreenOnPermission(),
            descGranted = "✓ הרשאה ניתנה",
            descNeeded = "נדרשת כדי שהמסך יידלק אוטומטית"
        )
        setPermissionRow(
            button = findViewById(R.id.accessibilityButton),
            status = findViewById(R.id.accessibilityStatus),
            granted = ShulAccessibilityService.isEnabled(this),
            descGranted = "✓ שירות פעיל",
            descNeeded = "נדרש להפעלה אוטומטית לאחר הדלקת המכשיר"
        )
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        setPermissionRow(
            button = findViewById(R.id.notificationsButton),
            status = findViewById(R.id.notificationsStatus),
            granted = notificationsGranted,
            descGranted = "✓ הרשאה ניתנה",
            descNeeded = "נדרשת לקבלת התראות עדכונים"
        )
    }

    private fun setPermissionRow(button: Button, status: TextView, granted: Boolean, descGranted: String, descNeeded: String) {
        if (granted) {
            status.text = descGranted
            status.setTextColor(0xFF22C55E.toInt())
            button.isEnabled = false
            button.alpha = 0.4f
        } else {
            status.text = descNeeded
            status.setTextColor(0xFF64748B.toInt())
            button.isEnabled = true
            button.alpha = 1f
        }
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        ),
                        REQUEST_EXACT_ALARM
                    )
                } catch (e: Exception) {
                    // Fallback to general alarm settings
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
            }
        } else {
            Toast.makeText(this, "הרשאה ניתנת אוטומטית במכשיר זה", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestTurnScreenOnPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Note: "android.settings.TURN_SCREEN_ON_SETTINGS" renders as a black screen
            // on some Android TV boxes — skip it and open app details directly.
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")),
                    REQUEST_TURN_SCREEN_ON
                )
                Toast.makeText(this, "הגדרות → הרשאות → הפעלת המסך → אפשר", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "פתח הגדרות > אפליקציות > shul-display > הרשאות > הפעלת המסך", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "הרשאה ניתנת אוטומטית במכשיר זה", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHdmiCecSettings() {
        val intents = listOf(
            // Xiaomi / MIUI TV
            Intent("com.android.tv.settings.HDMI_CEC"),
            // Generic Android TV
            Intent("android.settings.HDMI_CEC_SETTINGS"),
            // Sony / other TV brands
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.connectivity.HdmiCecFragment"),
            // Fallback: Device preferences (Android TV)
            Intent("android.settings.DEVICE_PREFERENCES"),
            // Last resort: general settings
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "פתח הגדרות → העדפות מכשיר → HDMI-CEC", Toast.LENGTH_LONG).show()
    }

    private fun requestExactAlarmInFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("הרשאת שעון מעורר")
                    .setMessage("כדי שהמסך יידלק ויכבה בשעות שהגדרת, יש לאפשר קביעת שעוני מעורר מדויקים.\n\nלחץ אישור ואפשר את ההרשאה.")
                    .setPositiveButton("אישור") { _, _ ->
                        try {
                            @Suppress("DEPRECATION")
                            startActivityForResult(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:$packageName")),
                                REQUEST_EXACT_ALARM
                            )
                        } catch (e: Exception) { requestTurnScreenOnInFlow() }
                    }
                    .setNegativeButton("דלג") { _, _ -> requestTurnScreenOnInFlow() }
                    .show()
                return
            }
        }
        requestTurnScreenOnInFlow()
    }

    private fun requestTurnScreenOnInFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasTurnScreenOnPermission()) {
            AlertDialog.Builder(this)
                .setTitle("הפעלת המסך")
                .setMessage("כדי שהמסך יידלק אוטומטית, יש לאפשר הרשאת \"הפעלת המסך\".\n\nבדף שייפתח:\nלחץ על «הרשאות» ← אפשר «הפעלת המסך»")
                .setPositiveButton("אישור") { _, _ ->
                    try {
                        // Open app details — TURN_SCREEN_ON_SETTINGS shows black screen on some boxes
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")),
                            REQUEST_TURN_SCREEN_ON
                        )
                    } catch (e: Exception) { requestAccessibilityService() }
                }
                .setNegativeButton("דלג") { _, _ -> requestAccessibilityService() }
                .show()
            return
        }
        requestAccessibilityService()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("הרשאה נדרשת")
                .setMessage("כדי שהתצוגה תופיע אוטומטית לאחר הפעלת המכשיר, נדרשת הרשאת \"הצגה מעל אפליקציות אחרות\".\n\nלחץ אישור ואפשר את ההרשאה.")
                .setPositiveButton("אישור") { _, _ ->
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ),
                        REQUEST_OVERLAY_PERMISSION
                    )
                }
                .setNegativeButton("דלג") { _, _ ->
                    requestExactAlarmInFlow()
                }
                .show()
        } else {
            requestExactAlarmInFlow()
        }
    }

    private fun requestAccessibilityService() {
        if (fromSettings) {
            requestNotificationsPermission()
            return
        }

        // Already enabled — skip dialog
        if (ShulAccessibilityService.isEnabled(this)) {
            Log.d(TAG, "Accessibility service already enabled")
            requestNotificationsPermission()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("הפעלה אוטומטית")
            .setMessage(
                "כדי שהאפליקציה תפעל אוטומטית לאחר הדלקת המכשיר, יש להפעיל את שירות הנגישות.\n\n" +
                "בדף שייפתח:\n" +
                "1. מצא את \"Shul Display\"\n" +
                "2. לחץ עליו ואפשר את השירות\n" +
                "3. חזור לאפליקציה"
            )
            .setPositiveButton("פתח הגדרות נגישות") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    REQUEST_ACCESSIBILITY_SETTINGS
                )
            }
            .setNegativeButton("דלג") { _, _ ->
                requestNotificationsPermission()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                return
            }
        }
        requestDefaultLauncher()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (fromSettings) refreshAllPermissionStatus()
            else requestDefaultLauncher()
        }
    }

    private fun requestDefaultLauncher() {
        if (fromSettings) {
            launchMainActivity()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    try {
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQUEST_ROLE_HOME)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "RoleManager failed: ${e.message}")
                        // Fall through to FakeLauncher trick
                    }
                } else {
                    // Already the default launcher — go straight to main
                    Log.d(TAG, "App already holds ROLE_HOME, launching main")
                    launchMainActivity()
                    return
                }
            } else {
                Log.w(TAG, "ROLE_HOME not available on this device, falling back to FakeLauncher")
            }
        }

        // Fallback: FakeLauncher trick
        triggerLauncherChooser()
        // After short delay, proceed to main
        Handler(Looper.getMainLooper()).postDelayed({
            launchMainActivity()
        }, 3000L)
    }

    private fun triggerLauncherChooser() {
        val pm = packageManager
        val fakeComponent = ComponentName(this, FakeLauncherActivity::class.java)

        // Enable fake launcher to trigger chooser
        pm.setComponentEnabledSetting(
            fakeComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // Open home intent to trigger chooser
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)

        // Re-disable after short delay
        Handler(Looper.getMainLooper()).postDelayed({
            pm.setComponentEnabledSetting(
                fakeComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }, 2000L)
    }

    private fun showManualLauncherDialog() {
        AlertDialog.Builder(this)
            .setTitle("הגדרת מפעיל ברירת מחדל")
            .setMessage(
                "כדי שהאפליקציה תפעל כצג בית הכנסת, יש להגדיר אותה כמפעיל (Launcher) ברירת מחדל.\n\n" +
                "בחר את \"Shul Display\" כ-Home App בתפריט שייפתח."
            )
            .setPositiveButton("פתח הגדרות") { _, _ ->
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Settings.ACTION_HOME_SETTINGS), REQUEST_HOME_SETTINGS)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open HOME_SETTINGS: ${e.message}")
                    Toast.makeText(
                        this,
                        "לא ניתן לפתוח הגדרות. אנא הגדר את האפליקציה כמפעיל ברירת מחדל ידנית בהגדרות המכשיר.",
                        Toast.LENGTH_LONG
                    ).show()
                    launchMainActivity()
                }
            }
            .setNegativeButton("דלג") { _, _ ->
                launchMainActivity()
            }
            .setCancelable(false)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (fromSettings) refreshAllPermissionStatus() else requestExactAlarmInFlow()
            }
            REQUEST_EXACT_ALARM -> {
                if (fromSettings) refreshAllPermissionStatus() else requestTurnScreenOnInFlow()
            }
            REQUEST_TURN_SCREEN_ON -> {
                if (fromSettings) refreshAllPermissionStatus() else requestAccessibilityService()
            }
            REQUEST_ACCESSIBILITY_SETTINGS -> {
                if (fromSettings) refreshAllPermissionStatus() else requestNotificationsPermission()
            }
            REQUEST_ROLE_HOME, REQUEST_HOME_SETTINGS -> {
                launchMainActivity()
            }
        }
    }

    private fun isValidSlug(slug: String): Boolean {
        return slug.isNotBlank() && slug.matches(SLUG_REGEX)
    }

    private fun scheduleCommandPoller() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<CommandPollingWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "command_poller",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager scheduling failed", e)
        }
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }
}
