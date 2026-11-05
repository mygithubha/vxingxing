package io.nekohasekai.sagernet.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/**
 * Previous/next from the status widget, non-exported in :bg: a running service switches in place, a stopped one only
 * moves the selection.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val step = when (intent.action) {
            Widgets.ACTION_NEXT -> 1
            Widgets.ACTION_PREVIOUS -> -1
            else -> return
        }
        val service = DataStore.baseService
        if (service != null && service.data.state.canStop) {
            service.switchRelative(step)
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        runOnDefaultDispatcher {
            try {
                ProfileOrder.neighbour(DataStore.selectedProxy, step)?.let { DataStore.selectedProxy = it.id }
                Widgets.render(app)
            } finally {
                pending.finish()
            }
        }
    }
}
