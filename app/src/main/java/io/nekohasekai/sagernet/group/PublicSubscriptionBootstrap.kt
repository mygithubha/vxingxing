package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Installs public, third-party node feeds once on first launch and creates one selector over their groups.
 * Public nodes are untrusted; per-app routing remains restricted by the VPN builder's allow-list.
 */
internal object PublicSubscriptionBootstrap {

    private data class Feed(val name: String, val url: String)

    private val feeds = listOf(
        Feed(
            "Auto Connect · Hourly public feed",
            "https://github.com/Au1rxx/free-vpn-subscriptions/raw/main/output/v2ray-base64.txt",
        ),
        Feed(
            "Auto Connect · 6-hour public feed",
            "https://radarhub.top/v2ray.txt",
        ),
    )

    private const val PREFS = "auto_connect_public_sources"
    private const val KEY_INTERVAL_INITIALIZED = "interval_initialized"
    private const val KEY_COMPLETE = "bootstrap_complete"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule() {
        scope.launch {
            val preferences = app.getSharedPreferences(PREFS, 0)
            try {
                // New installations get a six-hour refresh interval. Do not overwrite this choice on later launches.
                if (!preferences.getBoolean(KEY_INTERVAL_INITIALIZED, false)) {
                    if (DataStore.subAutoUpdate < SubscriptionScheduler.MIN_INTERVAL_MINUTES) {
                        DataStore.subAutoUpdate = 360
                    }
                    preferences.edit().putBoolean(KEY_INTERVAL_INITIALIZED, true).apply()
                }

                val groupIds = LinkedHashSet<Long>()
                for (feed in feeds) {
                    var group = GroupRepo.all().firstOrNull { it.url == feed.url }
                    if (group == null) {
                        val id = SubscriptionClient.subscribeUrl(feed.url, feed.name, autoUpdate = true)
                        if (id > 0L) group = GroupRepo.get(id)
                    }
                    if (group != null && group.id > 0L) groupIds.add(group.id)
                }

                if (groupIds.isNotEmpty()) {
                    val regularGroup = GroupRepo.all().firstOrNull { !it.isSubscription }
                    if (regularGroup != null) {
                        val allProfiles = GroupRepo.all().flatMap { ProfileManager.members(it.id) }
                        val existing = allProfiles.firstOrNull { it.outbound is AutoSelector }
                        val selector = (existing?.outbound as? AutoSelector ?: AutoSelector()).apply {
                            name = "Auto Connect · Best Node"
                            gids = groupIds.toMutableList()
                            excludeUnavailable = true
                            expected = 3
                            intervalSec = 300
                            benchIntervalSec = 600
                            watchIntervalSec = 15
                            dialRetries = 2
                            interruptOnSwitch = true
                        }
                        val selectorProfile: ProxyEntity = if (existing == null) {
                            ProfileManager.addProfile(selector, regularGroup.id)
                        } else {
                            existing.putOutbound(selector)
                            ProfileManager.updateOutbound(existing)
                            existing
                        }
                        if (DataStore.selectedProxy <= 0L) {
                            DataStore.selectedProxy = selectorProfile.id
                        }
                    }
                }

                val allFeedsInstalled = feeds.all { feed ->
                    GroupRepo.all().any { it.url == feed.url }
                }
                if (allFeedsInstalled) {
                    preferences.edit().putBoolean(KEY_COMPLETE, true).apply()
                }
                SubscriptionScheduler.schedule()
                Logs.i("[Auto Connect] Public source bootstrap: ${groupIds.size}/${feeds.size} feeds ready.")
            } catch (e: Exception) {
                Logs.w("[Auto Connect] Public source bootstrap failed; it will retry on next app start.", e)
            }
        }
    }
}
