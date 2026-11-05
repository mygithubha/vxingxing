package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.DataStore
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
    private lateinit var appsSummary: TextView
    private lateinit var currentNode: TextView
    private lateinit var nodeHealth: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.title = "Auto Connect"
        toolbar?.navigationIcon = null
        toolbar?.setNavigationOnClickListener(null)

        ensureSafeRoutingDefaults()
        urlInput = view.findViewById(R.id.subscription_url)
        status = view.findViewById(R.id.connection_status)
        connectButton = view.findViewById(R.id.connect_best_node)
        appsSummary = view.findViewById(R.id.proxy_apps_summary)
        currentNode = view.findViewById(R.id.current_node_status)
        nodeHealth = view.findViewById(R.id.node_health_status)

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
            status.text = "正在检查上次连接的节点…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val selectorId = DataStore.selectedProxy
                        AutoSelectorProfiles.writes.withLock {
                            val selector = AutoSelectorProfiles.load(selectorId) ?: return@withLock
                            val rememberedId = if (prefs.getLong("last_selector_id", -1L) == selectorId) {
                                prefs.getLong("last_node_id", -1L)
                            } else {
                                -1L
                            }
                            // A pin is a preference, not a hard lock: the core uses another healthy member
                            // while this remembered member is dead, then can return to it after recovery.
                            selector.pinnedID = rememberedId.takeIf { it > 0L } ?: -1L
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
        refreshConnectionState()
        updateNodeMonitor(AutoSelectorClient.status.value)
        viewLifecycleOwner.lifecycleScope.launch {
            AutoSelectorClient.status.collect { selectorStatus ->
                if (selectorStatus.phase == AutoSelectorStatus.PHASE_RUNNING && selectorStatus.selectedId > 0L) {
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
                        nodeHealth.text = "上次节点不可用 · 已自动切换"
                        nodeHealth.setTextColor(Color.rgb(190, 120, 0))
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
