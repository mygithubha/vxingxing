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
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.bg.test.TestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Minimal entry point for manual subscription import, automatic selector start and per-app routing.
 */
class SimpleConnectFragment : ToolbarFragment(R.layout.layout_simple_connect) {

    private val appPicker = registerForActivityResult(AppListActivity.Contract()) { packages ->
        if (packages != null) {
            // Allow-list mode: only explicitly selected apps enter the VPN. Everything else stays direct.
            DataStore.proxyApps = true
            DataStore.bypass = false
            DataStore.individual = packages.distinct().joinToString("\n")
            updateAppsSummary()
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
            prefs.edit().putBoolean("manual_mode", false).putLong("manual_node_id", -1L).apply()
            refreshNodeChoiceUi()
        }
        selectNodeButton.setOnClickListener { showNodePicker() }
        val connectionPrefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
        rememberLastNode.isChecked = connectionPrefs.getBoolean("remember_last_node_enabled", false)
        rememberLastNode.setOnCheckedChangeListener { _, checked ->
            connectionPrefs.edit().putBoolean("remember_last_node_enabled", checked).apply()
            updateNodeMonitor(AutoSelectorClient.status.value)
        }
        appsSummary = view.findViewById(R.id.proxy_apps_summary)
        currentNode = view.findViewById(R.id.current_node_status)
        nodeHealth = view.findViewById(R.id.node_health_status)
        nodeMonitorStatus = view.findViewById(R.id.node_monitor_status)
        nodeMonitorDetails = view.findViewById(R.id.node_monitor_details)
        nodeMonitorTokenInput = view.findViewById(R.id.node_monitor_token)
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
            val prefs = requireContext().getSharedPreferences("simple_auto_connect", 0)
            connectButton.isEnabled = false
            val manualMode = prefs.getBoolean("manual_mode", false)
            val rememberEnabled = prefs.getBoolean("remember_last_node_enabled", false)
            val selectorId = DataStore.selectedProxy
            val preferredId = if (manualMode) prefs.getLong("manual_node_id", -1L)
                else if (rememberEnabled && prefs.getLong("last_selector_id", -1L) == selectorId)
                    prefs.getLong("last_node_id", -1L) else -1L
            status.text = when {
                manualMode -> "正在检查你选定的节点…"
                preferredId > 0L -> "只检查上次节点，正常则直接复用…"
                else -> "首次连接或上次节点不可用，正在自动选优…"
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    var preferredHealthy = false
                    if (preferredId > 0L) {
                        withContext(Dispatchers.IO) {
                            TestEngine.queueIpTests(listOf(preferredId))
                            val afterIp = ProfileManager.getProfile(preferredId)
                            if (!afterIp?.ipOut.isNullOrBlank() && !afterIp?.testCountry.isNullOrBlank()) {
                                TestEngine.queueUrlTests(listOf(preferredId))
                            }
                            val checked = ProfileManager.getProfile(preferredId)
                            preferredHealthy = !checked?.ipOut.isNullOrBlank() &&
                                !checked?.testCountry.isNullOrBlank() &&
                                checked?.latency?.let { it > 0 || it == ProxyEntity.LATENCY_CONNECT_ONLY } == true &&
                                checked?.testCountry?.uppercase() !in setOf("CN", "HK", "MO", "TW")
                        }
                    }
                    if (manualMode && !preferredHealthy) {
                        status.text = "你选择的节点不可用或地区不符合过滤规则，请重新选择"
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
                            if (preferredHealthy) {
                                // The core gets a fresh result for this one preferred member. Keep it first in the
                                // persisted pool; the normal failover members remain available behind it.
                                selector.pinnedID = preferredId
                                selector.pool = (listOf(preferredId) + selector.pool.filterNot { it == preferredId }).toMutableList()
                            } else {
                                // The previous member failed: return to the complete automatic candidate pool.
                                selector.pinnedID = -1L
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
                }
            }
        }
        updateAppsSummary()
        refreshNodeChoiceUi()
        refreshConnectionState()
        updateNodeMonitor(AutoSelectorClient.status.value)
        viewLifecycleOwner.lifecycleScope.launch {
            AutoSelectorClient.status.collect { selectorStatus ->
                if (requireContext().getSharedPreferences("simple_auto_connect", 0)
                        .getBoolean("remember_last_node_enabled", false) &&
                    selectorStatus.phase == AutoSelectorStatus.PHASE_RUNNING && selectorStatus.selectedId > 0L &&
                    (selectorStatus.selectedState == AutoSelectorStatus.STATE_OK ||
                        selectorStatus.selectedState == AutoSelectorStatus.STATE_DEGRADED)) {
                    // Remember the node the core is actually using, including any automatic failover.
                    requireContext().getSharedPreferences("simple_auto_connect", 0).edit()
                        .putLong("last_selector_id", selectorStatus.selectorId)
                        .putLong("last_node_id", selectorStatus.selectedId)
                        .putString("last_node_name", selectorStatus.selectedName)
                        .apply()
                }
                updateNodeMonitor(selectorStatus)
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
        val selector = AutoSelectorProfiles.load(DataStore.selectedProxy) ?: return
        val members = selector.gids.distinct().flatMap { ProfileManager.members(it) }
            .distinctBy { it.id }
            .filter { it.type != "autoselector" && it.testCountry?.uppercase() !in setOf("CN", "HK", "MO", "TW") }
        if (members.isEmpty()) {
            status.text = "当前订阅组没有可供选择的节点"
            return
        }
        val labels = members.map { profile ->
            val ip = profile.ipOut?.takeIf { it.isNotBlank() } ?: "IP待检测"
            val country = profile.testCountry?.takeIf { it.isNotBlank() } ?: "地区待检测"
            val latency = when {
                profile.latency > 0 -> "${profile.latency} ms"
                profile.latency == ProxyEntity.LATENCY_CONNECT_ONLY -> "已连通"
                profile.latency < 0 -> "不可用"
                else -> "未测速"
            }
            "${profile.displayName()}\n$ip · $country · $latency"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("选择连接节点")
            .setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)) { dialog, which ->
                val selected = members[which]
                requireContext().getSharedPreferences("simple_auto_connect", 0).edit()
                    .putBoolean("manual_mode", true)
                    .putLong("manual_node_id", selected.id)
                    .apply()
                refreshNodeChoiceUi()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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
        val rememberedName = if (prefs.getBoolean("remember_last_node_enabled", false)) {
            prefs.getString("last_node_name", null).orEmpty()
        } else ""
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

    private fun refreshConnectionState() {
        if (!::status.isInitialized) return
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
    }
}
