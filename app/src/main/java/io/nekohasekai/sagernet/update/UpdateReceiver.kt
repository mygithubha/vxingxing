package io.nekohasekai.sagernet.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.database.DataStore

/** Not exported: PackageInstaller results and the notification actions (cancel download, skip version). */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL = "io.nekohasekai.sagernet.update.CANCEL"
        const val ACTION_SKIP = "io.nekohasekai.sagernet.update.SKIP"
        const val EXTRA_VERSION_CODE = "versionCode"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UpdateInstaller.ACTION_STATUS -> UpdateManager.onInstallStatus(context, intent)
            ACTION_CANCEL -> UpdateManager.cancel()
            ACTION_SKIP -> {
                DataStore.updateSkippedVersionCode = intent.getLongExtra(EXTRA_VERSION_CODE, 0)
                UpdateNotifications.cancelAvailable(context)
            }
        }
    }
}
