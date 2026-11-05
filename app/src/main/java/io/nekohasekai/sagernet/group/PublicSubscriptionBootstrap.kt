package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bootstrap the public, automatically refreshed node feed once on a fresh install.
 *
 * Public nodes are operated by unknown third parties. The app only imports their public
 * subscription data; every node is locally re-tested and country-filtered before use.
 */
internal object PublicSubscriptionBootstrap {

    private const val PREFS = "auto_connect_public_sources"
    private const val KEY_DEFAULTS_INSTALLED = "default_public_sources_installed"
    private const val DEFAULT_UPDATE_MINUTES = 360

    // Public feed maintained by free-nodes/v2rayfree; the publisher reports six-hour updates.
    // The feed is untrusted input and must pass the normal subscription parser and local health checks.
    private const val DEFAULT_SOURCE_URL =
        "https://raw.githubusercontent.com/free-nodes/v2rayfree/main/sub"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapMutex = Mutex()

    /** Create the default public source once; subsequent launches only keep the scheduler registered. */
    fun schedule() {
        scope.launch {
            bootstrapMutex.withLock {
                val prefs = app.getSharedPreferences(PREFS, 0)
                if (prefs.getBoolean(KEY_DEFAULTS_INSTALLED, false)) {
                    SubscriptionScheduler.schedule()
                    return@withLock
                }

                val regularGroup = GroupRepo.all().firstOrNull { !it.isSubscription }
                    ?: error("Default profile group not found")
                val existing = GroupRepo.all().firstOrNull { it.url == DEFAULT_SOURCE_URL }
                val group = existing ?: GroupRepo.add(
                    ProxyGroup(
                        name = "Public Nodes · free-nodes",
                        url = DEFAULT_SOURCE_URL,
                        skipAutoUpdate = false,
                        subOptions = SubscriptionOptions(
                            updateInterval = DEFAULT_UPDATE_MINUTES,
                            urlTest = true,
                            sortByLatency = true,
                            removeDuplicates = true,
                            removeUnavailable = false,
                        ),
                    )
                )

                // Do not override an existing user's update preferences. The seeded group gets six-hour updates.
                if (existing == null && DataStore.subAutoUpdate < SubscriptionScheduler.MIN_INTERVAL_MINUTES) {
                    DataStore.subAutoUpdate = DEFAULT_UPDATE_MINUTES
                }

                attachGroup(group.id)
                // SubscriptionScheduler's RemoteCoroutineWorker runs the refresh in the :bg process.
                // Do not call SubscriptionQueue directly from the main process: its queue is process-local.
                prefs.edit().putBoolean(KEY_DEFAULTS_INSTALLED, true).apply()
                SubscriptionScheduler.schedule()
                Logs.i("[Auto Connect] Default public node source installed; local testing and country filtering are required before connection.")
            }
        }
    }

    /**
     * Attach a user-added or bootstrapped subscription to the single selector. The selector ranks
     * candidates at connection start and monitors the active pool while connected.
     */
    suspend fun attachGroup(groupId: Long): Long {
        require(groupId > 0L) { "Invalid subscription group" }
        val groups = GroupRepo.all()
        require(groups.any { it.id == groupId && it.isSubscription }) { "Subscription group not found" }
        val regularGroup = groups.firstOrNull { !it.isSubscription }
            ?: error("Default profile group not found")

        val existing = groups.flatMap { ProfileManager.members(it.id) }
            .firstOrNull { it.outbound is AutoSelector }

        val selector = (existing?.outbound as? AutoSelector ?: AutoSelector()).apply {
            name = "Auto Connect · Best Node"
            gids = (gids + groupId).distinct().toMutableList()
            excludeUnavailable = true
            expected = 3
            intervalSec = 300
            benchIntervalSec = 600
            watchIntervalSec = 15
            // The core patch requires repeated dial failures before cooling a node; no per-request fallback.
            dialRetries = -1
            interruptOnSwitch = true
        }

        val selectorProfile: ProxyEntity = if (existing == null) {
            ProfileManager.addProfile(selector, regularGroup.id)
        } else {
            existing.putOutbound(selector)
            ProfileManager.updateOutbound(existing)
            existing
        }
        if (!DataStore.serviceState.started) DataStore.selectedProxy = selectorProfile.id
        Logs.i("[Auto Connect] Attached subscription group $groupId to the automatic node selector.")
        return selectorProfile.id
    }
}
