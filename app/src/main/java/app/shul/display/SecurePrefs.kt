package app.shul.display

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Provides encrypted SharedPreferences with automatic migration from plaintext.
 * Falls back to plaintext SharedPreferences if encryption is unavailable
 * (e.g., device doesn't support Android Keystore).
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val ENCRYPTED_PREFS_NAME = "shul_display_secure_prefs"
    private const val PLAIN_PREFS_NAME = "shul_display_prefs"
    private const val MIGRATION_KEY = "migrated_to_encrypted"

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        instance?.let { return it }

        synchronized(this) {
            instance?.let { return it }

            val plainPrefs = context.applicationContext.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

            val prefs = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val encrypted = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                // Migrate from plaintext if not already done
                migrateFromPlaintext(context, encrypted)

                // Verify migration didn't lose the slug
                val encSlug = encrypted.getString("slug", null)
                val plainSlug = plainPrefs.getString("slug", null)
                if (encSlug == null && plainSlug != null) {
                    Log.w(TAG, "Encrypted prefs lost slug, re-migrating...")
                    encrypted.edit()
                        .putString("slug", plainSlug)
                        .putBoolean(MIGRATION_KEY, false) // force re-migration
                        .apply()
                    migrateFromPlaintext(context, encrypted)
                }

                Log.d(TAG, "Using encrypted SharedPreferences")
                encrypted
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable, using plaintext: ${e.message}")
                plainPrefs
            }

            instance = prefs
            return prefs
        }
    }

    private fun migrateFromPlaintext(context: Context, encrypted: SharedPreferences) {
        if (encrypted.getBoolean(MIGRATION_KEY, false)) return

        val plain = context.applicationContext.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
        val slug = plain.getString("slug", null)
        val fallbackDeviceId = plain.getString("fallback_device_id", null)

        if (slug != null || fallbackDeviceId != null) {
            Log.i(TAG, "Migrating plaintext prefs to encrypted...")
            val editor = encrypted.edit()
            if (slug != null) editor.putString("slug", slug)
            if (fallbackDeviceId != null) editor.putString("fallback_device_id", fallbackDeviceId)
            editor.putBoolean(MIGRATION_KEY, true).apply()
            Log.i(TAG, "Migration complete (slug=${slug != null}, deviceId=${fallbackDeviceId != null})")
        } else {
            encrypted.edit().putBoolean(MIGRATION_KEY, true).apply()
        }
    }
}
