package pl.jardol.apkupdater

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Non-exported target of the app-created, immutable, session-specific PendingIntent. */
class InstallConfirmationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InstallConfirmation.openSystemConfirmation(this, intent)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
