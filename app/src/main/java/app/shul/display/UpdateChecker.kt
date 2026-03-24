package app.shul.display

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "elyashivk1165/shul-display-android"

    /** Returns ReleaseInfo if a newer version is available, null otherwise. */
    suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$REPO/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                }

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                    conn.disconnect()
                    return@withContext null
                }

                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                conn.disconnect()

                val release = JSONObject(json)
                val latestVersion = release.getString("tag_name").trimStart('v')
                val body = release.optString("body", "")

                if (!isNewerVersion(latestVersion, currentVersion)) {
                    Log.d(TAG, "Up to date ($currentVersion >= $latestVersion)")
                    return@withContext null
                }

                // Find APK asset
                val assets = release.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (downloadUrl == null) {
                    Log.w(TAG, "No APK asset found in release")
                    return@withContext null
                }

                ReleaseInfo(version = latestVersion, downloadUrl = downloadUrl, releaseNotes = body)
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate failed", e)
                null
            }
        }
    }

    /** Downloads APK via DownloadManager and triggers install when complete. */
    fun downloadAndInstall(context: Context, info: ReleaseInfo, onStatus: (String) -> Unit) {
        val fileName = "shul-display-update.apk"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Remove previous download
        val prefs = context.getSharedPreferences("shul_display_prefs", Context.MODE_PRIVATE)
        val prevId = prefs.getLong("update_download_id", -1L)
        if (prevId != -1L) runCatching { dm.remove(prevId) }

        val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
            setTitle("Shul Display v${info.version}")
            setDescription("מוריד עדכון...")
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = dm.enqueue(request)
        prefs.edit().putLong("update_download_id", downloadId).apply()
        onStatus("מוריד גרסה ${info.version}...")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)

                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        cursor.close()
                        installApk(ctx, localUri)
                    } else {
                        cursor.close()
                        onStatus("ההורדה נכשלה")
                    }
                } else {
                    cursor.close()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, localUri: String) {
        try {
            val file = java.io.File(Uri.parse(localUri).path!!)
            val contentUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
        }
    }

    /** Is latestVersion strictly newer than currentVersion? (semver) */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.toInt() }
            val c = current.split(".").map { it.toInt() }
            for (i in 0 until maxOf(l.size, c.size)) {
                val diff = l.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
                if (diff != 0) return diff > 0
            }
            false
        } catch (_: Exception) { false }
    }
}
