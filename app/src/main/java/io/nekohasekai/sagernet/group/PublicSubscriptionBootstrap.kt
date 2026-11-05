package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.types.AutoSelector

/**
 * Manual-source mode: never install third-party feeds automatically.
 * Every subscription added by the user is attached to one AutoSelector profile.
 */
internal object PublicSubscriptionBootstrap {

    /** Kept as a no-op for the existing startup call; public feeds are no longer added automatically. */
    fun schedule() = Unit

    /**
     * Attach a user-added subscription to the single selector. The selector's runtime ranks and
     * monitors the candidates when the user starts the VPN.
     */
    suspend fun attachGroup(groupId: Long): Long {
        require(groupId > 0L) { "Invalid subscription group" }
        val groups = GroupRepo.all()
        require(groups.any { it.id == groupId && it.isSubscription }) { "Subscription group not found" }
        val regularGroup = groups.firstOrNull { !it.isSubscription }
            ?: error("Default profile group not found")

        val existing = groups.asSequence()
            .flatMap { ProfileManager.members(it.id).asSequence() }
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
