package app.shul.display

import android.app.Activity
import android.os.Bundle

/**
 * Dummy launcher activity used to trigger the "Choose Home App" dialog.
 * Normally disabled. Temporarily enabled via PackageManager to force launcher selection.
 */
class FakeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
