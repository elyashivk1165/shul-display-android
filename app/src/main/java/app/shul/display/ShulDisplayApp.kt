package app.shul.display

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ShulDisplayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Force light mode globally — must happen before any Activity is created
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
