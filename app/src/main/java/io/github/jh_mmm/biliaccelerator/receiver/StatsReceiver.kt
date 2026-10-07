package io.github.jh_mmm.biliaccelerator.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager

class StatsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_REWRITE = "${BiliAcceleratorCore.MODULE_PACKAGE}.ACTION_RECORD_REWRITE"
        const val EXTRA_REWRITE_RESULT = "extra_rewrite_result"
        const val EXTRA_REWRITE_BATCH_JSON = "extra_rewrite_batch_json"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"
        private const val TAG = "BiliAccelerator-StatsReceiver"
        private val gson = Gson()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_RECORD_REWRITE) return

        // 1. 严格鉴权：校验由 AMS 担保的 PendingIntent 来源包名，阻断恶意第三方 App 伪造数据或重置统计
        val authToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_AUTH_TOKEN, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_AUTH_TOKEN)
        }

        val creatorPackage = authToken?.creatorPackage
        val isAuthorized = creatorPackage != null && (
                creatorPackage in BiliAcceleratorCore.TARGET_PACKAGES ||
                        creatorPackage == context.packageName
                )

        if (!isAuthorized) {
            Log.w(TAG, "拒绝未鉴权或伪造的广播上报：creatorPackage=$creatorPackage")
            return
        }

        // 2. 确保 StatsManager 状态先从 SharedPreferences 载入，杜绝冷启动 0 计数覆盖历史数据
        StatsManager.init(context)

        // 3. 优先处理批量上报
        val batchJson = intent.getStringExtra(EXTRA_REWRITE_BATCH_JSON)
        if (!batchJson.isNullOrEmpty()) {
            try {
                val listType = object : TypeToken<List<RewriteResult>>() {}.type
                val results: List<RewriteResult> = gson.fromJson(batchJson, listType)
                StatsManager.recordRequests(results, context)
                StatsManager.recordHeartbeat(context)
                Log.i(TAG, "通过 Broadcast 成功记录批量重定向 (${results.size} 条)")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 批量记录解析失败: $e")
            }
        }

        // 4. 单条记录兜底兼容
        val singleJson = intent.getStringExtra(EXTRA_REWRITE_RESULT)
        if (!singleJson.isNullOrEmpty()) {
            try {
                val result = gson.fromJson(singleJson, RewriteResult::class.java)
                StatsManager.recordRequest(result, context)
                Log.i(TAG, "通过 Broadcast 成功记录单条重定向: ${result.originalHost} -> ${result.targetHost}")
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 记录失败: $e")
            }
        }
    }
}
