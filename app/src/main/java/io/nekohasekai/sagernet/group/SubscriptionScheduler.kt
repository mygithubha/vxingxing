package io.nekohasekai.sagernet.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The automatic subscription update (GroupUpdater::CheckAutoUpdate on PeriodicRunner, mainwindow_setup.cpp): each
 * group follows its own [interval] and is due that long after its last successful update or automatic attempt.
 * WorkManager polls at the common period of those intervals and the worker queues the due groups in :bg. Anything
 * [interval] reads changing calls [schedule]; a poll that finds the plan changed reschedules itself.
 *
 * WorkManager honours the initial delay only on the first enqueue, so the polls keep their own grid while manual
 * refreshes move the due times off it: a poll takes every group due before the middle of the next period, which never
 * skips a cycle and starts a group at most half a period early.
 */
object SubscriptionScheduler {

    const val MIN_INTERVAL_MINUTES = 30

    private const val WORK_NAME = "SubscriptionScheduler"
    private const val INITIAL_WORK_NAME = "SubscriptionScheduler.Initial"

    /** The per-group updater of v11 and older, whose worker class is gone. */
    private const val LEGACY_WORK_NAME = "SubscriptionUpdater"

    private const val KEY_PERIOD = "period"

    /** autoAttempts (GroupUpdater.hpp): in :bg memory, so a new :bg process retries a failing group once. */
    private val attempts = ConcurrentHashMap<Long, Long>()

    /**
     * ResolveAutoUpdate (GroupUpdater.cpp:429-440) in seconds, 0 = not updated automatically: the server's
     * profile-update-interval when followed and sent, else the group's interval, else sub_auto_update. Nothing is
     * automatic while sub_auto_update is off.
     */
    fun interval(
        group: ProxyGroup,
        global: Int = DataStore.subAutoUpdate,
        respectServer: Boolean = DataStore.subRespectServerInterval,
    ): Long {
        if (global < MIN_INTERVAL_MINUTES || group.url.isEmpty() || group.archive || group.skipAutoUpdate) return 0L
        val options = group.subOptions
        val serverHours = group.subInfo.serverInterval
        if ((options.respectServerInterval ?: respectServer) && serverHours > 0) return serverHours * 3600L
        if (options.updateInterval > 0) return maxOf(options.updateInterval, MIN_INTERVAL_MINUTES) * 60L
        return global * 60L
    }

    /** Whether [group]'s automatic update follows the server's interval when it sends one. */
    fun followsServerInterval(group: ProxyGroup): Boolean =
        group.subOptions.respectServerInterval ?: DataStore.subRespectServerInterval

    /** autoUpdateDue (GroupUpdater.cpp:519-523): a failed attempt waits the interval too. */
    private fun due(group: ProxyGroup, interval: Long): Long {
        val last = maxOf(group.subLastUpdate, attempts[group.id] ?: 0L)
        return if (last <= 0) 0 else last + interval
    }

    private class Plan(val period: Long, val initialDelay: Long)

    /** The poll period is the intervals' greatest common divisor (at least [MIN_INTERVAL_MINUTES]); null = no poll. */
    private fun plan(now: Long): Plan? {
        val global = DataStore.subAutoUpdate
        if (global < MIN_INTERVAL_MINUTES) return null
        val respectServer = DataStore.subRespectServerInterval
        var period = 0L
        var next = Long.MAX_VALUE
        for (group in GroupRepo.all()) {
            val interval = interval(group, global, respectServer)
            if (interval <= 0) continue
            period = gcd(period, interval)
            next = minOf(next, due(group, interval))
        }
        if (period <= 0) return null
        period = period.coerceAtLeast(MIN_INTERVAL_MINUTES * 60L)
        return Plan(period, (next - now).coerceIn(0L, period))
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * Plans the periodic poll from the groups and the settings, or cancels it while nothing is updated automatically.
     * UPDATE (app start included) registers the job again whatever happened to it, and keeps the current period's start.
     */
    /**
     * Auto Connect uses manual subscription refresh only. Cancel any previously queued
     * periodic or first-run work; explicit calls to SubscriptionQueue.refreshGroup remain intact.
     */
    fun schedule() {
        runOnDefaultDispatcher {
            try {
                val workManager = RemoteWorkManager.getInstance(app)
                workManager.cancelUniqueWork(LEGACY_WORK_NAME).await()
                workManager.cancelUniqueWork(WORK_NAME).await()
                workManager.cancelUniqueWork(INITIAL_WORK_NAME).await()
                Logs.i("SubscriptionScheduler: background subscription updates are disabled; manual refresh remains available.")
            } catch (e: Throwable) {
                Logs.w("SubscriptionScheduler: cancellation failed", e)
            }
        }
    }

    /**
     * CheckAutoUpdate (GroupUpdater.cpp:525-535) for a poll every [period] seconds: queues every group due before the
     * middle of the next period in tab order; returns how many.
     */
    private fun checkAutoUpdate(period: Long): Int {
        val now = nowSeconds()
        val horizon = now + period / 2
        val global = DataStore.subAutoUpdate
        val respectServer = DataStore.subRespectServerInterval
        var queued = 0
        for (group in GroupRepo.all()) {
            val interval = interval(group, global, respectServer)
            if (interval <= 0 || due(group, interval) > horizon) continue
            attempts[group.id] = now
            Logs.i(app.getString(R.string.subs_auto_update_running, group.displayName()))
            // Failures go to the log only (notifyErrors is off for a background refresh).
            SubscriptionQueue.refreshGroup(group.id, showDiff = false, foreground = false)
            queued++
        }
        return queued
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            val period = inputData.getLong(KEY_PERIOD, 0L)
            if (checkAutoUpdate(period.takeIf { it > 0 } ?: (MIN_INTERVAL_MINUTES * 60L)) > 0) SubscriptionQueue.awaitIdle()
            // The plan moved without a reschedule (a restored backup, a server interval, a removed group): follow it.
            if (plan(nowSeconds())?.period != period) schedule()
            return Result.success()
        }
    }
}
