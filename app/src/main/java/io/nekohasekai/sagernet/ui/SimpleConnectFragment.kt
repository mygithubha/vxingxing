package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RememberedNodeStore

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
    private lateinit var rememberLastNodeSwitch: SwitchMaterial
    private lateinit var rememberedNodeInfo: TextView
    private lateinit var changeLastNodeButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.title = "Auto Connect"
        toolbar?.navigationIcon = null
        toolbar?.setNavigationOnClickListener(null)

        urlInput = view.findViewById(R.id.subscription_url)
        status = view.findViewById(R.id.connection_status)
        connectButton = view.findViewById(R.id.connect_best_node)
        appsSummary = view.findViewById(R.id.proxy_apps_summary)
        rememberLastNodeSwitch = view.findViewById(R.id.remember_last_node_switch)
        rememberedNodeInfo = view.findViewById(R.id.remembered_node_info)
        changeLastNodeButton = view.findViewById(R.id.change_last_node)
        rememberLastNodeSwitch.isChecked = DataStore.rememberLastNodeEnabled
        rememberLastNodeSwitch.setOnCheckedChangeListener { _, checked ->
            DataStore.rememberLastNodeEnabled = checked
            if (!checked && DataStore.selectedProxy == DataStore.lastNodeProfileId &&
                DataStore.lastNodeSelectorId > 0L
            ) {
                DataStore.selectedProxy = DataStore.lastNodeSelectorId
            }
            updateRememberedNodeSummary()
        }
        changeLastNodeButton.setOnClickListener { confirmChangeLastNode() }

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
            if (DataStore.serviceState.canStop) {
                host.toggleService()
                refreshConnectionState()
                return@setOnClickListener
            }
            if (!DataStore.lastNodeChangePending && DataStore.rememberLastNodeEnabled &&
                DataStore.lastNodeProfileId > 0L
            ) {
                val remembered = ProfileManager.getProfile(DataStore.lastNodeProfileId)
                if (remembered == null) {
                    status.text = "上次连接的节点已不在本地订阅中，记录已保留。请手动更新订阅或点击“改变上次连接 IP”。"
                    updateRememberedNodeSummary()
                    return@setOnClickListener
                }
                // Start the remembered node directly; this bypasses the selector's full-pool ranking sweep.
                DataStore.selectedProxy = remembered.id
            }
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
            updateRememberedNodeSummary()
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

    private fun confirmChangeLastNode() {
        val selectorId = DataStore.lastNodeSelectorId
        if (selectorId <= 0L) {
            status.text = "还没有自动选择器连接记录。请先成功连接一次自动选优节点。"
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("改变上次连接 IP")
            .setMessage(
                "确认后将按自动选优逻辑寻找新节点。只有新节点实际连接成功后才会替换旧记录；如果失败，旧 IP 和历史记录都会保留。"
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("确认更换") { _, _ ->
                DataStore.lastNodeChangePending = true
                DataStore.selectedProxy = selectorId
                (activity as? MainActivity)?.restartWithSelectedProfile(selectorId)
            }
            .show()
    }

    private fun updateRememberedNodeSummary() {
        if (!::rememberedNodeInfo.isInitialized) return
        val id = DataStore.lastNodeProfileId
        val text = if (id <= 0L) {
            "还没有成功连接记录。首次自动连接成功后，将保存实际使用的节点地址。"
        } else {
            val statusText = when (DataStore.lastNodeStatus) {
                RememberedNodeStore.STATUS_SUCCESS -> "已验证可用（实际连接成功）"
                RememberedNodeStore.STATUS_FAILED -> "连接失败（最近一次实际连接失败）"
                else -> "状态未知（没有新的连接结果）"
            }
            val country = DataStore.lastNodeCountry.takeIf { it.isNotBlank() }
                ?.let { "\n最近测试国家/地区：$it" }.orEmpty()
            val exists = ProfileManager.getProfile(id) != null
            val missing = if (exists) "" else "\n提示：节点已不在当前本地订阅中，记忆记录仍保留。"
            "节点地址：${DataStore.lastNodeAddress}\n状态：$statusText$country$missing"
        }
        rememberedNodeInfo.text = text
        changeLastNodeButton.isEnabled = DataStore.rememberLastNodeEnabled && id > 0L && DataStore.lastNodeSelectorId > 0L
    }

    private fun refreshConnectionState() {
        if (!::status.isInitialized) return
        updateRememberedNodeSummary()
        when {
            DataStore.serviceState.connected -> {
                status.text = if (DataStore.currentProfile == DataStore.lastNodeProfileId) {
                    "已连接 · 上次成功节点"
                } else {
                    "已连接 · 自动选优运行中"
                }
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
