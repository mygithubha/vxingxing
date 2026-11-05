package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NodeMonitorSubscription(
    val kind: String,
    val valid: Boolean,
    val nodeCount: Int
)

data class NodeMonitorPending(
    val targetDate: String,
    val status: String,
    val articleTitle: String,
    val articleUrl: String,
    val collectedAt: String,
    val subscriptions: List<NodeMonitorSubscription>
)

object NodeMonitorClient {
    private const val BASE_URL = "https://auto-connect-node-monitor.vercel.app"
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchPending(token: String): NodeMonitorPending = withContext(Dispatchers.IO) {
        val json = getJson("$BASE_URL/api/pending", token)
        val items = json.optJSONArray("subscriptions")
        val subscriptions = buildList {
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    add(
                        NodeMonitorSubscription(
                            kind = item.optString("kind"),
                            valid = item.optBoolean("valid", false),
                            nodeCount = item.optInt("node_count", 0)
                        )
                    )
                }
            }
        }
        NodeMonitorPending(
            targetDate = json.optString("target_date"),
            status = json.optString("status"),
            articleTitle = json.optString("article_title"),
            articleUrl = json.optString("article_url"),
            collectedAt = json.optString("collected_at"),
            subscriptions = subscriptions
        )
    }

    suspend fun fetchImportUrl(token: String, kind: String): String = withContext(Dispatchers.IO) {
        require(kind == "txt" || kind == "yaml")
        val json = getJson("$BASE_URL/api/pending?type=$kind", token)
        json.optString("subscription_url").takeIf { it.startsWith("https://") }
            ?: throw IOException("服务器没有返回有效订阅地址")
    }

    private fun getJson(url: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("节点监控服务返回 HTTP ${response.code}")
            }
            return try {
                JSONObject(body)
            } catch (_: Exception) {
                throw IOException("节点监控服务返回的数据格式异常")
            }
        }
    }
}
