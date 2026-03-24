package app.shul.display

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("shul_display_prefs", MODE_PRIVATE)

        val existingSlug = prefs.getString("slug", null)
        if (!existingSlug.isNullOrBlank()) {
            launchMainActivity()
            return
        }

        setContentView(R.layout.activity_setup)

        val slugInput = findViewById<EditText>(R.id.slugInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        saveButton.setOnClickListener {
            val slug = slugInput.text.toString().trim()
            if (slug.isEmpty()) {
                Toast.makeText(this, R.string.slug_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString("slug", slug).apply()

            val deviceId = DeviceUtils.getDeviceId(this)
            val appVersion = DeviceUtils.getAppVersion(this)
            CoroutineScope(Dispatchers.IO).launch {
                SupabaseClient.registerDevice(deviceId, slug, appVersion)
            }

            scheduleCommandPoller()
            launchMainActivity()
        }
    }

    private fun scheduleCommandPoller() {
        val workRequest = PeriodicWorkRequestBuilder<CommandPollingWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "command_poller",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
