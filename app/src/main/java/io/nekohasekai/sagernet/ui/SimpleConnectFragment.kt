package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.CheckBox
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorRuntime
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.widget.ExitIp
import io.nekohasekai.sagernet.bg.test.TestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Minimal entry point for manual subscription import, automatic selector start and per-app routing.
 */
class SimpleConnectFragment : ToolbarFragment(R.layout.layout_simple_connect) {

    companion object {
        @Volatile
        private var autoConnectAttemptedInProcess = false
    }

    private val appPicker = registerForActivityResult(AppListActivity.Contract()) { packages ->
        if (packages != null) {
            // Allow-list mode: only explicitly selected apps enter the VPN. Everything else stays direct.
            DataStore.proxyApps = true
            DataStore.bypass = false
            val (safePackages, blockedPackages) = filterSensitiveProxyApps(packages)
            DataStore.individual = safePackages.joinToString("\n")
            updateAppsSummary()
            if (blockedPackages.isNotEmpty()) {
                status.text = "已阻止将疑似金融、支付、身份或密码类应用加入代理白名单，已保持直连：\n" +
                    blockedPackages.joinToString("\n")
            }
        }
    }

    private lateinit var urlInput: TextInputEditText
    private lateinit var status: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var rememberLastNode: CheckBox
    private lateinit var selectedNodeSummary: TextView
    private lateinit var selectNodeButton: MaterialButton
    private lateinit var automaticModeButton: MaterialButton
    private lateinit var appsSummary: TextView
    private lateinit var currentNode: TextView
    private lateinit var nodeHealth: TextView
    private lateinit var nodeMonitorStatus: TextView
    private lateinit var nodeMonitorDetails: TextView
    private lateinit var nodeMonitorTokenInput: TextInputEditText
    private lateinit var importNodeMonitorTxt: MaterialButton
    private lateinit var importNodeMonitorYaml: MaterialButton
    private var latestNodeMonitorPending: NodeMonitorPending? = null
    private var exitIpCheckKey: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.title = "Auto Connect"
        toolbar?.navigationIcon = null
        toolbar?.setNavigationOnClickListener(null)

        ensureSafeRoutingDefaults()
        urlInput = view.findViewById(R.id.subscription_url)
        status = view.findViewById(R.id.connection_status)
        connectButton = view.findViewById(R.id.connect_best_node)
        rememberLastNode = view.findViewById(R.id.remember_last_node)
        selectedNodeSummary = view.findViewById(R.id.selected_node_summary)
        selectNodeButton = view.findViewById(R.id.select_node)
        automaticModeButton = view.findViewById(R.id.automatic_mode)
        refreshNodeChoiceUi()
        automaticModeButton.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
            val selectorId = DataStore.selectedProxy
            val wasRunning = DataStore.serviceState.started
            val switchToAutomatic = {
                prefs.edit().putBoolean("manual_mode", false).putLong("manual_node_id", -1L).apply()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AutoSelectorProfiles.writes.withLock {
                            val selector = AutoSelectorProfiles.load(selectorId) ?: return@withLock
                            val buildKey = "auto_build_limit_$selectorId"
                            val capKey = "auto_pool_cap_$selectorId"
                            if (prefs.contains(buildKey)) {
                                selector.buildLimit = prefs.getInt(buildKey, selector.buildLimit)
                                selector.poolCap = prefs.getInt(capKey, selector.poolCap)
                                prefs.edit().remove(buildKey).remove(capKey).apply()
                            }
                            selector.pinnedID = -1L
                            selector.strictPinned = false
                            val excludedCountries = setOf("CN", "HK", "MO", "TW")
                            selector.pool = selector.gids.distinct()
                                .flatMap { ProfileManager.members(it) }
                                .filter { it.type != "autoselector" && it.testCountry?.uppercase() !in excludedCountries }
                                .map { it.id }.distinct().toMutableList()
                            selector.poolRankedAt = 0L
                            AutoSelectorProfiles.save(selectorId, selector)
                        }
                    }
                    refreshNodeChoiceUi()
                    if (wasRunning) {
                        status.text = "正在恢复自动候选池，连接将短暂重建…"
                        AutoSelectorRuntime.refreshRun(selectorId)
                    }
                }
            }
            if (wasRunning) {
                AlertDialog.Builder(requireContext())
                    .setTitle("切换到自动模式")
                    .setMessage("将恢复完整自动候选池并重建当前 VPN 配置，连接可能短暂中断。是否继续？")
                    .setPositiveButton("切换并重连") { _, _ -> switchToAutomatic() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                switchToAutomatic()
            }
        }
        selectNodeButton.setOnClickListener { showNodePicker() }
        val connectionPrefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
        // Remembering the last successful node is core behavior, not an optional optimization.
        connectionPrefs.edit().putBoolean("remember_last_node_enabled", true).apply()
        rememberLastNode.isChecked = true
        rememberLastNode.isEnabled = false
        appsSummary = view.findViewById(R.id.proxy_apps_summary)
        currentNode = view.findViewById(R.id.current_node_status)
        nodeHealth = view.findViewById(R.id.node_health_status)
        nodeMonitorStatus = view.findViewById(R.id.node_monitor_status)
        nodeMonitorDetails = view.findViewById(R.id.node_monitor_details)
        nodeMonitorTokenInput = view.findViewById(R.id.node_monitor_token)
        ExitIp.listener = { updateExitIpStatus() }
        importNodeMonitorTxt = view.findViewById(R.id.import_node_monitor_txt)
        importNodeMonitorYaml = view.findViewById(R.id.import_node_monitor_yaml)

        view.findViewById<MaterialButton>(R.id.save_node_monitor_token).setOnClickListener {
            val pairingCode = nodeMonitorTokenInput.text?.toString()?.trim().orEmpty()
            if (pairingCode.length < 20) {
                nodeMonitorStatus.text = "请输入首次配对码"
                return@setOnClickListener
            }
            val pairButton = view.findViewById<MaterialButton>(R.id.save_node_monitor_token)
            pairButton.isEnabled = false
            nodeMonitorStatus.text = "正在安全配对设备…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val deviceToken = NodeMonitorClient.pairDevice(pairingCode)
                    NodeMonitorTokenStore.save(requireContext(), deviceToken)
                    nodeMonitorTokenInput.text?.clear()
                    (nodeMonitorTokenInput.parent as? View)?.visibility = View.GONE
                    pairButton.visibility = View.GONE
                    nodeMonitorStatus.text = "设备配对成功，访问凭证已加密保存在本机"
                    refreshNodeMonitorPending()
                } catch (e: Exception) {
                    nodeMonitorStatus.text = e.message ?: "配对失败，请检查网络后重试"
                } finally {
                    pairButton.isEnabled = true
                }
            }
        }
        view.findViewById<MaterialButton>(R.id.refresh_node_monitor).setOnClickListener {
            refreshNodeMonitorPending()
        }
        importNodeMonitorTxt.setOnClickListener { confirmNodeMonitorImport("txt") }
        importNodeMonitorYaml.setOnClickListener { confirmNodeMonitorImport("yaml") }
        if (NodeMonitorTokenStore.load(requireContext()) != null) {
            (nodeMonitorTokenInput.parent as? View)?.visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.save_node_monitor_token).visibility = View.GONE
            nodeMonitorStatus.text = "设备已配对，正在检查前天节点…"
            refreshNodeMonitorPending()
        }

        view.findViewById<MaterialButton>(R.id.import_subscription).setOnClickListener {
            val url = urlInput.text?.toString()?.trim().orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                view.findViewById<com.google.android.material.textfield.TextInputLayout>(
                    R.id.subscription_input_layout
                ).error = "请输入有效的 http(s) 订阅链接"
                return@setOnClickListener
            }
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(
                R.id.subscription_input_layout
            ).error = null
            SubscribeFlows.addSubscriptionQuick(requireActivity(), url)
        }

        view.findViewById<MaterialButton>(R.id.select_apps).setOnClickListener {
            val selected = DataStore.individual.lines().map { it.trim() }.filter { it.isNotEmpty() }
            appPicker.launch(selected)
        }

        connectButton.setOnClickListener {
            val host = activity as? MainActivity ?: return@setOnClickListener
            if (DataStore.selectedProxy <= 0L) {
                status.text = "请先导入订阅链接"
                return@setOnClickListener
            }
            if (DataStore.serviceState.canStop) {
                host.toggleService()
                refreshConnectionState()
                return@setOnClickListener
            }
            // The proxy app list is an allow-list. Re-check it at connection time so a stale selection
            // from an older build cannot accidentally route a recognisably sensitive app through a node.
            val (safePackages, blockedPackages) = filterSensitiveProxyApps(
                DataStore.individual.lines().map { it.trim() }.filter { it.isNotEmpty() }
            )
            if (blockedPackages.isNotEmpty()) {
                DataStore.individual = safePackages.joinToString("\n")
                updateAppsSummary()
                status.text = "已自动移除疑似敏感应用，保持直连：\n" + blockedPackages.joinToString("\n")
            }
            val prefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
            connectButton.isEnabled = false
            val manualMode = prefs.getBoolean("manual_mode", false)
            val selectorId = DataStore.selectedProxy
            val preferredId = if (manualMode) prefs.getLong("manual_node_id", -1L)
                else if (prefs.getLong("last_selector_id", -1L) == selectorId)
                    prefs.getLong("last_node_id", -1L) else -1L
            status.text = when {
                manualMode -> "正在检查你选定的节点…"
                preferredId > 0L -> "只检查上次节点，正常则直接复用…"
                else -> "首次连接或上次节点不可用，正在自动选优…"
            }
            viewLifecycleOwner.lifecycleScope.launch {
                var manualRejected = false
                var preferredHealthy = false
                try {
                    if (manualMode) {
                        val belongsToCurrentSubscription = withContext(Dispatchers.IO) {
                            val currentSelector = AutoSelectorProfiles.load(selectorId) ?: return@withContext false
                            currentSelector.gids.distinct()
                                .flatMap { ProfileManager.members(it) }
                                .any { it.id == preferredId && it.type != "autoselector" }
                        }
                        if (!belongsToCurrentSubscription) {
                            manualRejected = true
                            return@launch
                        }
                    }
                    if (preferredId > 0L) {
                        withContext(Dispatchers.IO) {
                            // Geo lookup is a one-time prerequisite. Once country/IP are known, never repeat it
                            // on every tap; only run the lightweight health/URL probe for this one remembered node.
                            val beforeIp = ProfileManager.getProfile(preferredId)
                            if (beforeIp?.ipOut.isNullOrBlank() || beforeIp?.testCountry.isNullOrBlank()) {
                                TestEngine.queueIpTests(listOf(preferredId))
                            }
                            val afterIp = ProfileManager.getProfile(preferredId)
                            if (!afterIp?.ipOut.isNullOrBlank() && !afterIp?.testCountry.isNullOrBlank()) {
                                TestEngine.queueUrlTests(listOf(preferredId))
                            }
                            val checked = ProfileManager.getProfile(preferredId)
                            preferredHealthy = !checked?.ipOut.isNullOrBlank() &&
                                !checked?.testCountry.isNullOrBlank() &&
                                checked?.latency?.let { it > 0 || it == ProxyEntity.LATENCY_CONNECT_ONLY } == true &&
                                checked?.testCountry?.uppercase()?.let { it !in setOf("CN", "HK", "MO", "TW") } == true
                        }
                    }
                    if (manualMode && (preferredId <= 0L || !preferredHealthy)) {
                        manualRejected = true
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        AutoSelectorProfiles.writes.withLock {
                            val selector = AutoSelectorProfiles.load(selectorId) ?: return@withLock
                            val excludedCountries = setOf("CN", "HK", "MO", "TW")
                            val countryFilters = selector.countryFilter.split(',')
                                .map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                            excludedCountries.forEach { countryFilters.add("!$it") }
                            selector.countryFilter = countryFilters.joinToString(",")
                            when {
                                manualMode -> {
                                    // Strict manual mode: shrink the generated selector build to exactly one
                                    // member. pinnedID alone is only a preference in the core, not an exclusion
                                    // of every other subscription member.
                                    val buildKey = "auto_build_limit_$selectorId"
                                    val capKey = "auto_pool_cap_$selectorId"
                                    if (!prefs.contains(buildKey)) prefs.edit()
                                        .putInt(buildKey, selector.buildLimit)
                                        .putInt(capKey, selector.poolCap)
                                        .apply()
                                    selector.pinnedID = preferredId
                                    selector.strictPinned = true
                                    selector.pool = mutableListOf(preferredId)
                                    selector.poolCap = 1
                                    selector.buildLimit = 1
                                    selector.poolRankedAt = System.currentTimeMillis() / 1000
                                }
                                preferredHealthy -> {
                                    // Restore normal multi-node auto mode while keeping the last good node first.
                                    val buildKey = "auto_build_limit_$selectorId"
                                    val capKey = "auto_pool_cap_$selectorId"
                                    if (prefs.contains(buildKey)) {
                                        selector.buildLimit = prefs.getInt(buildKey, selector.buildLimit)
                                        selector.poolCap = prefs.getInt(capKey, selector.poolCap)
                                        prefs.edit().remove(buildKey).remove(capKey).apply()
                                    }
                                    selector.pinnedID = preferredId
                                    selector.strictPinned = false
                                    selector.pool = (listOf(preferredId) +
                                        selector.pool.filterNot { it == preferredId }).toMutableList()
                                }
                                else -> {
                                    // Restore every eligible subscription member before automatic ranking.
                                    val buildKey = "auto_build_limit_$selectorId"
                                    val capKey = "auto_pool_cap_$selectorId"
                                    if (prefs.contains(buildKey)) {
                                        selector.buildLimit = prefs.getInt(buildKey, selector.buildLimit)
                                        selector.poolCap = prefs.getInt(capKey, selector.poolCap)
                                        prefs.edit().remove(buildKey).remove(capKey).apply()
                                    }
                                    selector.pinnedID = -1L
                                    selector.strictPinned = false
                                    selector.pool = selector.gids.distinct()
                                        .flatMap { ProfileManager.members(it) }
                                        .filter { it.type != "autoselector" &&
                                            it.testCountry?.uppercase() !in excludedCountries }
                                        .map { it.id }.distinct().toMutableList()
                                    selector.poolRankedAt = 0L
                                }
                            }
                            AutoSelectorProfiles.save(selectorId, selector)
                        }
                    }
                    host.toggleService()
                } catch (e: Exception) {
                    status.text = "连接准备失败：${e.message ?: "请重试"}"
                } finally {
                    connectButton.isEnabled = true
                    refreshConnectionState()
                    if (manualRejected) status.text = "你选择的节点不可用或地区不符合过滤规则，请重新选择"
                }
            }
        }
        updateAppsSummary()
        refreshNodeChoiceUi()
        refreshConnectionState()
        updateNodeMonitor(AutoSelectorClient.status.value)
        viewLifecycleOwner.lifecycleScope.launch {
            AutoSelectorClient.status.collect { selectorStatus ->
                if (selectorStatus.phase == AutoSelectorStatus.PHASE_RUNNING && selectorStatus.selectedId > 0L &&
                    (selectorStatus.selectedState == AutoSelectorStatus.STATE_OK ||
                        selectorStatus.selectedState == AutoSelectorStatus.STATE_DEGRADED)) {
                    // Remember the node the core is actually using, including any automatic failover.
                    requireContext().getSharedPreferences("simple_auto_connect", 0).edit()
                        .putLong("last_selector_id", selectorStatus.selectorId)
                        .putLong("last_node_id", selectorStatus.selectedId)
                        .putString("last_node_name", selectorStatus.selectedName)
                        .apply()
                    // Verify the active tunnel's real egress independently of the per-node preflight test.
                    val key = "${selectorStatus.selectorId}:${selectorStatus.selectedId}"
                    if (DataStore.serviceState.connected && exitIpCheckKey != key) {
                        exitIpCheckKey = key
                        status.text = "节点已连接 · 正在核验真实出口 IP…"
                        ExitIp.refresh()
                    }
                } else if (selectorStatus.phase != AutoSelectorStatus.PHASE_RUNNING) {
                    exitIpCheckKey = null
                    if (!DataStore.serviceState.connected) ExitIp.clear()
                }
                updateNodeMonitor(selectorStatus)
            }
        }

        // On a fresh app process, wait for the first public subscription import before auto-connecting.
        // The process-level guard prevents reconnecting just because the user returns to this screen.
        if (!autoConnectAttemptedInProcess) {
            autoConnectAttemptedInProcess = true
            viewLifecycleOwner.lifecycleScope.launch {
                repeat(30) {
                    if (!isAdded || DataStore.serviceState.started) return@launch
                    val selectorId = DataStore.selectedProxy
                    val count = if (selectorId > 0L) withContext(Dispatchers.IO) {
                        val selector = AutoSelectorProfiles.load(selectorId)
                        selector?.gids?.distinct()
                            ?.sumOf { gid -> ProfileManager.members(gid).count { profile ->
                                profile.type != "autoselector" && profile.testCountry?.uppercase() !in setOf("CN", "HK", "MO", "TW")
                            } }
                            ?: 0
                    } else 0
                    if (count > 0) {
                        delay(1200)
                        if (isAdded && !DataStore.serviceState.started) {
                            status.text = "已获取节点，正在自动检测并连接…"
                            connectButton.performClick()
                        }
                        return@launch
                    }
                    delay(2000)
                }
                if (isAdded && !DataStore.serviceState.started) {
                    status.text = "暂未获取到节点；订阅将在后台继续更新，你也可以手动重试"
                }
            }
        }
    }

    private fun refreshNodeMonitorPending() {
        val token = NodeMonitorTokenStore.load(requireContext())
        if (token.isNullOrBlank()) {
            nodeMonitorStatus.text = "请先粘贴并安全保存访问令牌"
            importNodeMonitorTxt.isEnabled = false
            importNodeMonitorYaml.isEnabled = false
            return
        }
        nodeMonitorStatus.text = "正在查询云端采集结果…"
        importNodeMonitorTxt.isEnabled = false
        importNodeMonitorYaml.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pending = NodeMonitorClient.fetchPending(token)
                latestNodeMonitorPending = pending
                val txt = pending.subscriptions.firstOrNull { it.kind == "txt" }
                val yaml = pending.subscriptions.firstOrNull { it.kind == "yaml" }
                nodeMonitorDetails.text = buildString {
                    append("目标日期：").append(pending.targetDate.ifBlank { "未知" }).append("\n")
                    append("TXT：").append(if (txt?.valid == true) "可用 · ${txt.nodeCount} 个节点" else "不可用或未提取").append("\n")
                    append("YAML：").append(if (yaml?.valid == true) "可用 · ${yaml.nodeCount} 个节点" else "不可用或未提取")
                    if (pending.collectedAt.isNotBlank()) {
                        append("\n采集时间：").append(pending.collectedAt)
                    }
                    if (pending.articleTitle.isNotBlank()) {
                        append("\n文章：").append(pending.articleTitle)
                    }
                }
                nodeMonitorStatus.text = when (pending.status) {
                    "success" -> "已采集成功 · 等待你手动确认导入"
                    "invalid" -> "文章已找到，但订阅内容未通过有效性检测"
                    "not_found" -> "暂未找到前天文章，没有使用其他日期替代"
                    "protected_or_missing" -> "页面没有公开可提取的订阅地址，未尝试绕过密码"
                    else -> "采集状态：${pending.status}"
                }
                importNodeMonitorTxt.isEnabled = txt?.valid == true && txt.nodeCount > 0
                importNodeMonitorYaml.isEnabled = yaml?.valid == true && yaml.nodeCount > 0
            } catch (e: Exception) {
                latestNodeMonitorPending = null
                nodeMonitorDetails.text = "暂无可导入的采集结果"
                nodeMonitorStatus.text = "查询失败：${e.message ?: "请检查网络和访问令牌"}"
            }
        }
    }

    private fun confirmNodeMonitorImport(kind: String) {
        val pending = latestNodeMonitorPending ?: run {
            nodeMonitorStatus.text = "请先检查云端采集结果"
            return
        }
        val item = pending.subscriptions.firstOrNull { it.kind == kind && it.valid && it.nodeCount > 0 }
        if (item == null) {
            nodeMonitorStatus.text = "该订阅未通过检测，禁止导入"
            return
        }
        val typeLabel = if (kind == "txt") "TXT" else "YAML"
        AlertDialog.Builder(requireContext())
            .setTitle("确认导入 $typeLabel 订阅？")
            .setMessage(
                "文章日期：${pending.targetDate}\n" +
                    "预计节点数：${item.nodeCount}\n\n" +
                    "只有点击“确认导入”后，App 才会获取订阅地址并进入现有导入流程。\n" +
                    "确认后会创建独立订阅组并切换到新组，原订阅组会保留，不会被覆盖。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认导入") { _, _ ->
                val token = NodeMonitorTokenStore.load(requireContext())
                if (token.isNullOrBlank()) {
                    nodeMonitorStatus.text = "访问令牌已失效，请重新保存"
                    return@setPositiveButton
                }
                nodeMonitorStatus.text = "正在安全获取订阅地址…"
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val url = NodeMonitorClient.fetchImportUrl(token, kind)
                        // Existing import flow is invoked only after explicit confirmation.
                        SubscribeFlows.addSubscriptionQuick(requireActivity(), url)
                        nodeMonitorStatus.text = "$typeLabel 订阅已交给现有导入流程处理"
                    } catch (e: Exception) {
                        nodeMonitorStatus.text = "获取订阅失败：${e.message ?: "请重试"}"
                    }
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            updateAppsSummary()
            refreshConnectionState()
        }
    }

    private fun ensureSafeRoutingDefaults() {
        val prefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
        if (prefs.getBoolean("safe_routing_initialized", false)) return
        // Migrate old global/bypass configurations to a fail-closed allow-list before enabling this UI.
        DataStore.proxyApps = true
        DataStore.bypass = false
        DataStore.individual = ""
        prefs.edit().putBoolean("safe_routing_initialized", true).apply()
    }

    private fun refreshNodeChoiceUi() {
        if (!::selectedNodeSummary.isInitialized) return
        val prefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
        val manual = prefs.getBoolean("manual_mode", false)
        val id = prefs.getLong("manual_node_id", -1L)
        val profile = if (id > 0L) ProfileManager.getProfile(id) else null
        selectedNodeSummary.text = if (manual && profile != null) {
            "手动节点：${profile.displayName()}\nIP：${profile.ipOut ?: "待检测"} · 地区：${profile.testCountry ?: "待检测"}"
        } else if (manual) {
            "尚未选择节点，请从列表中选择"
        } else {
            "自动模式：优先复用上次成功节点，失效后才重新选优"
        }
        automaticModeButton.isEnabled = manual
        selectNodeButton.text = if (manual) "更换节点" else "手动选择节点"
    }

    private fun showNodePicker() {
        val selectorId = DataStore.selectedProxy
        if (selectorId <= 0L) {
            status.text = "请先导入订阅链接"
            return
        }
        selectNodeButton.isEnabled = false
        status.text = "正在读取本地节点缓存…"
        viewLifecycleOwner.lifecycleScope.launch {
            var displayedProfiles = mutableListOf<ProxyEntity>()
            try {
                val candidateIds = withContext(Dispatchers.IO) {
                    val selector = AutoSelectorProfiles.load(selectorId)
                        ?: throw IllegalStateException("当前自动选择器不存在，请重新导入订阅")
                    selector.gids.distinct()
                        .flatMap { ProfileManager.members(it) }
                        .filter { it.type != "autoselector" }
                        .map { it.id }
                        .distinct()
                }
                if (candidateIds.isEmpty()) {
                    status.text = "当前订阅组没有节点"
                    return@launch
                }

                // Render cached data first. Opening the picker must never wait for a full health sweep.
                displayedProfiles = withContext(Dispatchers.IO) {
                    candidateIds.mapNotNull { ProfileManager.getProfile(it) }.toMutableList()
                }
                if (displayedProfiles.isEmpty()) {
                    status.text = "当前订阅组没有可读取的节点"
                    return@launch
                }

                fun label(profile: ProxyEntity): String {
                    val country = profile.testCountry?.trim()?.uppercase().orEmpty()
                    val ip = profile.ipOut?.takeIf { it.isNotBlank() } ?: "IP 待核验"
                    val region = when {
                        country.isBlank() -> "地区待核验"
                        country in setOf("CN", "HK", "MO", "TW") -> "已过滤：$country"
                        else -> country
                    }
                    val latency = when {
                        profile.latency > 0 -> "${profile.latency} ms"
                        profile.latency == ProxyEntity.LATENCY_CONNECT_ONLY -> "已连通"
                        else -> "延迟待检测"
                    }
                    return "${profile.displayName()} · $ip · $region · $latency"
                }

                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    displayedProfiles.map(::label).toMutableList()
                )
                AlertDialog.Builder(requireContext())
                    .setTitle("选择节点（本地缓存）")
                    .setMessage("列表立即显示缓存。未知地区的节点核验完成前不可选择；打开列表不会执行全量连通性测速。")
                    .setAdapter(adapter) { _, which ->
                        val selected = displayedProfiles.getOrNull(which) ?: return@setAdapter
                        val country = selected.testCountry?.trim()?.uppercase().orEmpty()
                        when {
                            selected.ipOut.isNullOrBlank() || country.isBlank() -> {
                                status.text = "该节点地区尚未核验，暂不能选择"
                            }
                            country in setOf("CN", "HK", "MO", "TW") -> {
                                status.text = "该节点属于过滤地区（$country），不能选择"
                            }
                            else -> {
                                AlertDialog.Builder(requireContext())
                                    .setTitle("确认选择节点")
                                    .setMessage("${selected.displayName()}\n${selected.ipOut} · $country")
                                    .setPositiveButton("确认选择") { _, _ ->
                                        requireContext().getSharedPreferences("simple_auto_connect", 0).edit()
                                            .putBoolean("manual_mode", true)
                                            .putLong("manual_node_id", selected.id)
                                            .apply()
                                        refreshNodeChoiceUi()
                                        AlertDialog.Builder(requireContext())
                                            .setTitle("是否立即连接？")
                                            .setMessage("已锁定所选节点。是否现在连接？手动模式连接失败不会自动更换节点。")
                                            .setPositiveButton("立即连接") { _, _ ->
                                                if (DataStore.serviceState.connected || DataStore.serviceState.started) {
                                                    status.text = "已保存手动节点。请先断开当前连接，再点击连接按钮使用该节点。"
                                                } else {
                                                    connectButton.performClick()
                                                }
                                            }
                                            .setNegativeButton("稍后连接", null)
                                            .show()
                                    }
                                    .setNegativeButton("重新选择", null)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton("关闭", null)
                    .show()

                selectNodeButton.isEnabled = true
                status.text = "已从缓存加载 ${displayedProfiles.size} 个节点；正在后台补充未知地区信息"

                // Only enrich unknown egress IP/country metadata. Do not run URL tests across the entire pool here.
                val unknownGeoIds = displayedProfiles.filter {
                    it.ipOut.isNullOrBlank() || it.testCountry.isNullOrBlank()
                }.map { it.id }.distinct()
                if (unknownGeoIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) { TestEngine.queueIpTests(unknownGeoIds) }
                    val refreshed = withContext(Dispatchers.IO) {
                        candidateIds.mapNotNull { ProfileManager.getProfile(it) }
                    }
                    displayedProfiles.clear()
                    displayedProfiles.addAll(refreshed)
                    adapter.clear()
                    adapter.addAll(refreshed.map(::label))
                    adapter.notifyDataSetChanged()
                    status.text = "地区核验完成；节点列表已更新。连通性将在连接时检查"
                } else {
                    status.text = "已显示本地缓存；连通性将在连接时检查"
                }
            } catch (e: Exception) {
                status.text = "节点列表加载失败：${e.message ?: "请重试"}"
            } finally {
                selectNodeButton.isEnabled = true
            }
        }
    }

    /**
     * Conservative guard for the proxy allow-list. Apps whose package id or visible label clearly identifies
     * banking, payments, finance, identity or password/authentication functions are kept outside the VPN.
     * Unknown apps are not guessed; the default allow-list still leaves every unselected app direct.
     */
    private fun filterSensitiveProxyApps(packages: Collection<String>): Pair<List<String>, List<String>> {
        val sensitiveTerms = listOf(
            "bank", "banking", "银行", "金融", "证券", "券商",
            "wallet", "alipay", "unionpay", "payment", "pay", "支付", "钱包",
            "password", "authenticator", "2fa", "otp", "密码", "身份", "证件",
            "crypto-wallet", "cryptowallet"
        )
        val blocked = ArrayList<String>()
        val allowed = ArrayList<String>()
        val pm = requireContext().packageManager
        for (packageName in packages.distinct()) {
            val label = runCatching {
                pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
            }.getOrDefault("")
            val identity = "$packageName $label".lowercase()
            if (sensitiveTerms.any { identity.contains(it) }) blocked.add(packageName)
            else allowed.add(packageName)
        }
        return allowed to blocked
    }

    private fun updateAppsSummary() {
        val packages = DataStore.individual.lines().map { it.trim() }.filter { it.isNotEmpty() }
        appsSummary.text = if (packages.isEmpty()) {
            "当前没有勾选应用 · 全部直连"
        } else {
            "已选择 ${packages.size} 个应用使用节点网络"
        }
    }

    fun refreshUiState() = refreshConnectionState()

    private fun updateNodeMonitor(selectorStatus: AutoSelectorStatus) {
        if (!::currentNode.isInitialized || !::nodeHealth.isInitialized) return
        val prefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
        val rememberedName = prefs.getString("last_node_name", null).orEmpty()
        when (selectorStatus.phase) {
            AutoSelectorStatus.PHASE_MEASURING -> {
                currentNode.text = if (rememberedName.isNotBlank()) "上次节点：$rememberedName" else "当前节点：正在检测节点池"
                nodeHealth.text = "检测中"
                nodeHealth.setTextColor(Color.rgb(190, 120, 0))
            }
            AutoSelectorStatus.PHASE_RUNNING -> {
                currentNode.text = "当前节点：${selectorStatus.selectedName.ifBlank { "正在选择" }}"
                when {
                    selectorStatus.pinnedId > 0L && selectorStatus.selectedId > 0L &&
                        selectorStatus.pinnedId != selectorStatus.selectedId -> {
                        when (selectorStatus.selectedState) {
                            AutoSelectorStatus.STATE_OK -> {
                                nodeHealth.text = "上次节点不可用 · 当前节点可用"
                                nodeHealth.setTextColor(Color.rgb(30, 145, 70))
                            }
                            AutoSelectorStatus.STATE_DEGRADED -> {
                                nodeHealth.text = "上次节点不可用 · 当前节点可用但较慢"
                                nodeHealth.setTextColor(Color.rgb(190, 120, 0))
                            }
                            AutoSelectorStatus.STATE_DEAD, AutoSelectorStatus.STATE_COOLDOWN -> {
                                nodeHealth.text = "当前节点不可用 · 正在寻找新节点"
                                nodeHealth.setTextColor(Color.rgb(190, 45, 45))
                            }
                            else -> {
                                nodeHealth.text = "上次节点不可用 · 新节点检测中"
                                nodeHealth.setTextColor(Color.rgb(190, 120, 0))
                            }
                        }
                    }
                    selectorStatus.selectedState == AutoSelectorStatus.STATE_OK -> {
                        nodeHealth.text = "● 可用"
                        nodeHealth.setTextColor(Color.rgb(30, 145, 70))
                    }
                    selectorStatus.selectedState == AutoSelectorStatus.STATE_DEGRADED -> {
                        nodeHealth.text = "● 可用 · 质量下降"
                        nodeHealth.setTextColor(Color.rgb(190, 120, 0))
                    }
                    selectorStatus.selectedState == AutoSelectorStatus.STATE_DEAD ||
                        selectorStatus.selectedState == AutoSelectorStatus.STATE_COOLDOWN -> {
                        nodeHealth.text = "● 不可用"
                        nodeHealth.setTextColor(Color.rgb(190, 45, 45))
                    }
                    else -> {
                        nodeHealth.text = "检测中"
                        nodeHealth.setTextColor(Color.rgb(190, 120, 0))
                    }
                }
            }
            else -> {
                currentNode.text = if (rememberedName.isNotBlank()) "上次节点：$rememberedName" else "当前节点：尚未连接"
                nodeHealth.text = if (rememberedName.isNotBlank()) "待检测" else "未连接"
                nodeHealth.setTextColor(Color.GRAY)
            }
        }
    }

    private fun updateExitIpStatus() {
        if (!::status.isInitialized || !DataStore.serviceState.connected) return
        val info = ExitIp.info
        status.text = when {
            info != null && info.ip.isNotBlank() ->
                "已连接 · 出口 IP：${info.ip} · 地区：${info.country.ifBlank { info.countryCode }}"
            ExitIp.refreshing -> "节点已连接 · 正在核验真实出口 IP…"
            else -> "节点已连接 · 出口 IP 暂时无法核验（节点仍运行中）"
        }
    }

    override fun onDestroyView() {
        ExitIp.listener = null
        ExitIp.clear()
        exitIpCheckKey = null
        super.onDestroyView()
    }

    private fun refreshConnectionState() {
        if (!::status.isInitialized) return
        if (!DataStore.serviceState.connected && !DataStore.serviceState.started) {
            exitIpCheckKey = null
            ExitIp.clear()
        }
        when {
            DataStore.serviceState.connected -> {
                status.text = "已连接 · 自动选优运行中"
                connectButton.text = "断开连接"
            }
            DataStore.serviceState.started -> {
                status.text = "正在连接或测速…"
                connectButton.text = "连接中…"
            }
            DataStore.selectedProxy > 0L -> {
                status.text = "订阅已就绪 · 连接时自动测速并选择可用节点"
                connectButton.text = "测速并连接最佳节点"
            }
            else -> {
                status.text = "等待导入订阅"
                connectButton.text = "测速并连接最佳节点"
            }
        }
        if (DataStore.serviceState.connected && exitIpCheckKey != null) updateExitIpStatus()
    }
}
