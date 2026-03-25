package app.shul.display

import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.ComponentName
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
    }

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

        // Permissions section — shown only in settings mode (not first-time setup)
        val permissionsSection = findViewById<View>(R.id.permissionsSection)
        val exactAlarmButton = findViewById<Button>(R.id.exactAlarmButton)
        val exactAlarmStatus = findViewById<TextView>(R.id.exactAlarmStatus)
        val turnScreenOnButton = findViewById<Button>(R.id.turnScreenOnButton)
        val turnScreenOnStatus = findViewById<TextView>(R.id.turnScreenOnStatus)

        if (fromSettings) {
            permissionsSection.visibility = View.VISIBLE
            refreshPermissionStatus(exactAlarmButton, exactAlarmStatus, turnScreenOnButton, turnScreenOnStatus)

            exactAlarmButton.setOnClickListener { requestExactAlarmPermission() }
            turnScreenOnButton.setOnClickListener { requestTurnScreenOnPermission() }
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
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return am.canScheduleExactAlarms()
        }
        return true
    }

    private fun hasTurnScreenOnPermission(): Boolean {
        // TURN_SCREEN_ON was added in API 33; on older versions it's granted implicitly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission("android.permission.TURN_SCREEN_ON") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun refreshPermissionStatus(
        exactAlarmButton: Button,
        exactAlarmStatus: TextView,
        turnScreenOnButton: Button,
        turnScreenOnStatus: TextView
    ) {
        if (canScheduleExactAlarms()) {
            exactAlarmStatus.text = "✓ הרשאה ניתנה"
            exactAlarmStatus.setTextColor(0xFF22C55E.toInt())
            exactAlarmButton.isEnabled = false
            exactAlarmButton.alpha = 0.4f
        } else {
            exactAlarmStatus.text = "נדרשת לשעון שבת / הדלקת מסך בזמן קבוע"
            exactAlarmStatus.setTextColor(0xFF64748B.toInt())
            exactAlarmButton.isEnabled = true
            exactAlarmButton.alpha = 1f
        }

        if (hasTurnScreenOnPermission()) {
            turnScreenOnStatus.text = "✓ הרשאה ניתנה"
            turnScreenOnStatus.setTextColor(0xFF22C55E.toInt())
            turnScreenOnButton.isEnabled = false
            turnScreenOnButton.alpha = 0.4f
        } else {
            turnScreenOnStatus.text = "נדרשת כדי שהמסך יידלק אוטומטית"
            turnScreenOnStatus.setTextColor(0xFF64748B.toInt())
            turnScreenOnButton.isEnabled = true
            turnScreenOnButton.alpha = 1f
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
            try {
                // Opens Special App Access > Turn on screen
                val intent = Intent("android.settings.TURN_SCREEN_ON_SETTINGS").apply {
                    data = Uri.parse("package:$packageName")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_TURN_SCREEN_ON)
            } catch (e: Exception) {
                try {
                    // Fallback: open general special access settings
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")),
                        REQUEST_TURN_SCREEN_ON
                    )
                } catch (e2: Exception) {
                    Toast.makeText(this, "פתח הגדרות > אפליקציות > גישה מיוחדת > הפעלת המסך", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(this, "הרשאה ניתנת אוטומטית במכשיר זה", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestExactAlarmInFlow() {
        // Called during initial setup flow (not from settings button)
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
                        } catch (e: Exception) {
                            requestAccessibilityService()
                        }
                    }
                    .setNegativeButton("דלג") { _, _ -> requestAccessibilityService() }
                    .show()
                return
            }
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
            requestDefaultLauncher()
            return
        }

        // Already enabled — skip dialog
        if (ShulAccessibilityService.isEnabled(this)) {
            Log.d(TAG, "Accessibility service already enabled")
            requestDefaultLauncher()
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
                requestDefaultLauncher()
            }
            .setCancelable(false)
            .show()
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
                requestExactAlarmInFlow()
            }
            REQUEST_EXACT_ALARM, REQUEST_TURN_SCREEN_ON -> {
                // Refresh status badges if in settings mode
                if (fromSettings) {
                    val exactAlarmButton = findViewById<Button>(R.id.exactAlarmButton)
                    val exactAlarmStatus = findViewById<TextView>(R.id.exactAlarmStatus)
                    val turnScreenOnButton = findViewById<Button>(R.id.turnScreenOnButton)
                    val turnScreenOnStatus = findViewById<TextView>(R.id.turnScreenOnStatus)
                    refreshPermissionStatus(exactAlarmButton, exactAlarmStatus, turnScreenOnButton, turnScreenOnStatus)
                } else {
                    requestAccessibilityService()
                }
            }
            REQUEST_ACCESSIBILITY_SETTINGS -> {
                // Whether enabled or not, proceed to launcher step
                requestDefaultLauncher()
            }
            REQUEST_ROLE_HOME, REQUEST_HOME_SETTINGS -> {
                // Whether accepted or not, proceed to main
                launchMainActivity()
            }
        }
    }

    private fun isValidSlug(slug: String): Boolean {
        return slug.isNotBlank() && slug.matches(Regex("^[a-zA-Z0-9_-]{1,50}$"))
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
