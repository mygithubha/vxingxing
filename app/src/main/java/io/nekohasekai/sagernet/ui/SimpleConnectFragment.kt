package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.title = "Auto Connect"
        toolbar?.navigationIcon = null
        toolbar?.setNavigationOnClickListener(null)

        urlInput = view.findViewById(R.id.subscription_url)
        status = view.findViewById(R.id.connection_status)
        connectButton = view.findViewById(R.id.connect_best_node)
        appsSummary = view.findViewById(R.id.proxy_apps_summary)

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
            SubscribeFlows.addSubscription(requireActivity(), url, "")
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
            // AutoSelectorRuntime performs URL tests and ranks healthy candidates before starting.
            host.toggleService()
            refreshConnectionState()
        }
        updateAppsSummary()
        refreshConnectionState()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            updateAppsSummary()
            refreshConnectionState()
        }
    }

    private fun updateAppsSummary() {
        val packages = DataStore.individual.lines().map { it.trim() }.filter { it.isNotEmpty() }
        appsSummary.text = if (packages.isEmpty()) {
            "当前没有勾选应用 · 全部直连"
        } else {
            "已选择 ${packages.size} 个应用使用节点网络"
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
