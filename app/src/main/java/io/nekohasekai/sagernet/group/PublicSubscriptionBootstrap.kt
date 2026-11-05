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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manual-source mode: never install third-party feeds automatically.
 * Every subscription added by the user is attached to one AutoSelector profile.
 */
internal object PublicSubscriptionBootstrap {

    private const val PREFS = "auto_connect_public_sources"
    private const val KEY_LEGACY_CLEANED = "legacy_public_sources_removed"
    private val legacyUrls = setOf(
        "https://github.com/Au1rxx/free-vpn-subscriptions/raw/main/output/v2ray-base64.txt",
        "https://radarhub.top/v2ray.txt",
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupMutex = Mutex()

    /** Remove only the two feeds that the previous app version silently installed. */
    fun schedule() {
        scope.launch { removeLegacySources() }
    }

    private suspend fun removeLegacySources() = cleanupMutex.withLock {
        val prefs = app.getSharedPreferences(PREFS, 0)
        if (prefs.getBoolean(KEY_LEGACY_CLEANED, false)) return@withLock

        val oldGroups = GroupRepo.all().filter { it.url in legacyUrls }
        oldGroups.forEach { GroupRepo.delete(it.id) }
        val validGroupIds = GroupRepo.all().mapTo(HashSet()) { it.id }

        for (group in GroupRepo.all()) {
            for (profile in ProfileManager.members(group.id)) {
                val selector = profile.outbound as? AutoSelector ?: continue
                val cleaned = selector.gids.filter { it in validGroupIds }.toMutableList()
                if (cleaned != selector.gids) {
                    selector.gids = cleaned
                    profile.putOutbound(selector)
                    ProfileManager.updateOutbound(profile)
                }
            }
        }
        prefs.edit().putBoolean(KEY_LEGACY_CLEANED, true).apply()
        Logs.i("[Auto Connect] Removed legacy auto-added public feeds; manual subscriptions only.")
    }

    /**
     * Attach a user-added subscription to the single selector. The selector's runtime ranks and
     * monitors the candidates when the user starts the VPN.
     */
    suspend fun attachGroup(groupId: Long): Long {
        removeLegacySources()
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
        DataStore.selectedProxy = selectorProfile.id
        Logs.i("[Auto Connect] Added subscription group $groupId to automatic node selector.")
        return selectorProfile.id
    }
}
