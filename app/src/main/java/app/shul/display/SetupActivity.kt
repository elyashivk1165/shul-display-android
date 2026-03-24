package app.shul.display

import android.app.role.RoleManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("shul_display_prefs", MODE_PRIVATE)

        fromSettings = intent.getBooleanExtra("from_settings", false)
        val existingSlug = prefs.getString("slug", null)

        // If slug is already configured and we're not coming from settings, skip to MainActivity
        if (!existingSlug.isNullOrBlank() && !fromSettings) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            finish()
            return
        }

        setContentView(R.layout.activity_setup)

        val slugInput = findViewById<EditText>(R.id.etSlug)
        val saveButton = findViewById<Button>(R.id.btnSave)

        // Pre-fill existing slug when editing
        if (!existingSlug.isNullOrBlank()) {
            slugInput.setText(existingSlug)
            slugInput.selectAll()
        }

        saveButton.setOnClickListener {
            val slug = slugInput.text.toString().trim()
            if (slug.isBlank()) {
                slugInput.error = "נא להכניס קוד בית הכנסת"
                return@setOnClickListener
            }
            if (!isValidSlug(slug)) {
                slugInput.error = "קוד לא תקין — אותיות, מספרים, מקף בלבד"
                return@setOnClickListener
            }

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
                requestDefaultLauncher()
            }
        }
    }

    private fun requestDefaultLauncher() {
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
                        // Fall through to settings fallback
                    }
                } else {
                    // Already the default launcher — go straight to main
                    Log.d(TAG, "App already holds ROLE_HOME, launching main")
                    launchMainActivity()
                    return
                }
            } else {
                Log.w(TAG, "ROLE_HOME not available on this device, falling back to settings")
            }
            // Fallback for Android Q+: show HOME_SETTINGS with explanation dialog
            showManualLauncherDialog()
        } else {
            // Pre-Q fallback: open home settings with explanation dialog
            showManualLauncherDialog()
        }
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
        if (requestCode == REQUEST_ROLE_HOME || requestCode == REQUEST_HOME_SETTINGS) {
            // Whether accepted or not, proceed to main
            launchMainActivity()
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
