package app.shul.display

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
import android.widget.Button
import android.widget.EditText
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
                    requestAccessibilityService()
                }
                .show()
        } else {
            requestAccessibilityService()
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
                // Whether granted or not, proceed to accessibility service step
                requestAccessibilityService()
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
