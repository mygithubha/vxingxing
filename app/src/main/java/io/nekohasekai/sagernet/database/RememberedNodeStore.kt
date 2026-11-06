package io.nekohasekai.sagernet.database

/**
 * Stores only a node that the VPN service actually brought to Connected.
 * The endpoint is the configured server address, not the VPN exit IP.
 */
object RememberedNodeStore {

    const val STATUS_UNKNOWN = "unknown"
    const val STATUS_SUCCESS = "success"
    const val STATUS_FAILED = "failed"

    fun recordConnected(profile: ProxyEntity, selectorId: Long = 0L) {
        if (!DataStore.rememberLastNodeEnabled || profile.id <= 0L) return
        val outbound = profile.outbound
        val address = outbound.getAddress().trim().ifEmpty { outbound.displayAddress() }
        if (address.isBlank()) return

        DataStore.lastNodeProfileId = profile.id
        if (selectorId > 0L) DataStore.lastNodeSelectorId = selectorId
        DataStore.lastNodeAddress = if (outbound.serverPort > 0) "$address:${outbound.serverPort}" else address
        DataStore.lastNodeCountry = profile.testCountry.orEmpty()
        DataStore.lastNodeStatus = STATUS_SUCCESS
        DataStore.lastNodeAt = System.currentTimeMillis()
        DataStore.lastNodeChangePending = false
    }

    fun markFailure(profileId: Long) {
        if (profileId > 0L && profileId == DataStore.lastNodeProfileId) {
            DataStore.lastNodeStatus = STATUS_FAILED
            DataStore.lastNodeAt = System.currentTimeMillis()
        }
        // A failed change attempt never replaces the previously remembered node.
        DataStore.lastNodeChangePending = false
    }

    fun rememberedProfile(): ProxyEntity? {
        if (!DataStore.rememberLastNodeEnabled || DataStore.lastNodeProfileId <= 0L) return null
        return ProfileManager.getProfile(DataStore.lastNodeProfileId)
    }
}
