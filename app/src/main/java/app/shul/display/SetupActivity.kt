package app.shul.display

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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

    companion object {
        private const val TAG = "SetupActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("shul_display_prefs", MODE_PRIVATE)

        val existingSlug = prefs.getString("slug", null)

        // If no slug at all, go straight to setup UI (first launch)
        // If slug exists but we were launched from MainActivity (editing), also show UI
        // We detect editing mode by checking if there is a running MainActivity instance
        val isEditingMode = existingSlug != null && MainActivity.instance != null

        if (!existingSlug.isNullOrBlank() && !isEditingMode) {
            scheduleCommandPoller()
            launchMainActivity()
            return
        }

        setContentView(R.layout.activity_setup)

        val slugInput = findViewById<EditText>(R.id.etSlug)
        val saveButton = findViewById<Button>(R.id.btnSave)

        // Pre-fill existing slug when in editing mode
        if (!existingSlug.isNullOrBlank()) {
            slugInput.setText(existingSlug)
            slugInput.selectAll()
        }

        saveButton.setOnClickListener {
            val slug = slugInput.text.toString().trim()
            if (slug.isEmpty()) {
                Toast.makeText(this, R.string.slug_required, Toast.LENGTH_SHORT).show()
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

            if (isEditingMode) {
                // Update the running MainActivity and go back to it
                MainActivity.instance?.updateSlug(slug)
                finish()
            } else {
                launchMainActivity()
            }
        }
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
