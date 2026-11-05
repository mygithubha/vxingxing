package io.nekohasekai.sagernet.bg

/**
 * Fail closed for the selected-app proxy mode: an empty allow-list must never silently become a global VPN.
 * In bypass mode an empty exclusion list intentionally leaves the normal global VPN behavior unchanged.
 */
internal fun shouldRestrictToSelfOnly(proxyAppsEnabled: Boolean, bypassMode: Boolean, includePackageCount: Int): Boolean =
    proxyAppsEnabled && !bypassMode && includePackageCount == 0
