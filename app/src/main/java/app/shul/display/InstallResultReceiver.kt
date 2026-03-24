package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i("InstallReceiver", "Silent install succeeded")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w("InstallReceiver", "User action required — showing dialog")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmIntent != null) context.startActivity(confirmIntent)
            }
            else -> {
                Log.e("InstallReceiver", "Install failed: status=$status message=$message")
            }
        }
    }
}
