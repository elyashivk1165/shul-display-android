package app.shul.display

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
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
                    Log.w(TAG, "No APK asset found")
                    return@withContext null
                }

                ReleaseInfo(version = latestVersion, downloadUrl = downloadUrl, releaseNotes = body)
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate failed", e)
                null
            }
        }
    }

    /**
     * Downloads APK directly via HttpURLConnection to cacheDir,
     * then triggers system install dialog. Runs on IO thread.
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: ReleaseInfo,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onStatus("מוריד גרסה ${info.version}...")

            val apkFile = File(context.cacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            // Follow redirects (GitHub releases redirect to CDN)
            var downloadUrl = info.downloadUrl
            var redirects = 0
            while (redirects < 5) {
                val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    downloadUrl = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    onStatus("שגיאה בהורדה: HTTP $code")
                    return@withContext
                }

                FileOutputStream(apkFile).use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                }
                conn.disconnect()
                break
            }

            if (!apkFile.exists() || apkFile.length() < 1000) {
                onStatus("ההורדה נכשלה — קובץ ריק")
                return@withContext
            }

            onStatus("ההורדה הושלמה — מתקין...")
            installApk(context, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInstall failed", e)
            onStatus("שגיאה: ${e.message}")
        }
    }

    private fun installApk(context: Context, file: File) {
        val contentUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

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
