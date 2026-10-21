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
import io.github.jh_mmm.biliaccelerator.provider.RewriteResultDto

class StatsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_REWRITE = "io.github.jh_mmm.biliaccelerator.ACTION_RECORD_REWRITE"
        const val ACTION_HANDSHAKE = "io.github.jh_mmm.biliaccelerator.ACTION_HANDSHAKE"
        const val EXTRA_REWRITE_RESULT = "extra_rewrite_result"
        const val EXTRA_REWRITE_BATCH_JSON = "extra_rewrite_batch_json"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"
        const val EXTRA_CLIENT_PACKAGE = "extra_client_package"
        const val EXTRA_CLIENT_VERSION = "extra_client_version"
        const val EXTRA_CLIENT_PROCESS = "extra_client_process"
        const val EXTRA_HOOK_STATUS_JSON = "extra_hook_status_json"

        private const val TAG = "BiliAccelerator-StatsReceiver"
        private val gson = Gson()

        private val VALID_REASONS = setOf(
            "szbdyd-source",
            "mountaintoys-source",
            "mcdn-proxy",
            "pcdn-host",
            "mcdn-host",
            "force-upos",
            "ok",
            "already-proxied",
            "live-skip",
            "live-szbdyd-source",
            "live-mountaintoys-source",
            "live-pcdn-filter",
            "live-pcdn-detected",
            "live-ok",
            "akamai-pass",
            "akamai-redirect"
        )

        internal fun isPayloadLegitimate(batchJson: String?, singleJson: String?): Boolean {
            if (!batchJson.isNullOrEmpty()) {
                return try {
                    val listType = object : TypeToken<List<RewriteResultDto>>() {}.type
                    val results: List<RewriteResultDto> = gson.fromJson(batchJson, listType) ?: return false
                    if (results.isEmpty() || results.size > 200) return false
                    results.all { isValidResultItem(it.toDomain()) }
                } catch (_: Throwable) {
                    false
                }
            }
            if (!singleJson.isNullOrEmpty()) {
                return try {
                    val result: RewriteResultDto = gson.fromJson(singleJson, RewriteResultDto::class.java) ?: return false
                    isValidResultItem(result.toDomain())
                } catch (_: Throwable) {
                    false
                }
            }
            return false
        }

        private fun isValidResultItem(item: RewriteResult): Boolean {
            val orig = item.originalHost.trim()
            val tgt = item.targetHost.trim()
            val reason = item.reason.trim()
            val isKnownReason = reason in VALID_REASONS ||
                    reason.startsWith("BTR") ||
                    reason.startsWith("live-") ||
                    reason.startsWith("akamai-")
            if (orig.isBlank() || tgt.isBlank() || !isKnownReason) {
                return false
            }
            if (item.isBtr || tgt.startsWith("127.0.0.1") || tgt.contains("BTR") || tgt.contains("镜像")) {
                return true
            }
            return BiliAcceleratorCore.isBiliFamilyHost(orig) ||
                    BiliAcceleratorCore.isBiliFamilyHost(tgt) ||
                    BiliAcceleratorCore.CANDIDATE_POOL.contains(tgt) ||
                    BiliAcceleratorCore.isIpAddress(orig)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action != ACTION_RECORD_REWRITE && action != ACTION_HANDSHAKE) return

        // 1. 严格鉴权：优先校验由 AMS 担保的 PendingIntent 来源包名；
        // 若在 Android 14+ / 沙箱多用户分身环境导致 creatorPackage 为 null，且广播定向给本包名，则进入 Payload 结构兜底鉴权
        val authToken: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { intent.getParcelableExtra(EXTRA_AUTH_TOKEN, PendingIntent::class.java) }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { intent.getParcelableExtra<PendingIntent>(EXTRA_AUTH_TOKEN) }.getOrNull()
        }

        val creatorPackage = authToken?.creatorPackage
        val hasValidCreator = creatorPackage != null && (
                BiliAcceleratorCore.isTargetPackage(creatorPackage) ||
                        creatorPackage == context.packageName
                )

        // 2. 确保 StatsManager 状态先从 SharedPreferences 载入
        StatsManager.ensureInitialized(context)

        // 3. 处理独立握手广播 (ACTION_HANDSHAKE)
        if (action == ACTION_HANDSHAKE) {
            val clientPkg = intent.getStringExtra(EXTRA_CLIENT_PACKAGE)?.trim()
            val isAuthorized = hasValidCreator || (
                    !clientPkg.isNullOrBlank() &&
                            BiliAcceleratorCore.isTargetPackage(clientPkg) &&
                            isDirectedToModule(intent, context)
                    )

            if (!isAuthorized) {
                Log.w(TAG, "拒绝未鉴权或伪造的握手广播：creatorPackage=$creatorPackage, clientPkg=$clientPkg")
                return
            }

            val finalPkg = if (hasValidCreator) creatorPackage else clientPkg
            val clientVer = intent.getStringExtra(EXTRA_CLIENT_VERSION)?.trim()?.take(64)
            val clientProc = intent.getStringExtra(EXTRA_CLIENT_PROCESS)?.trim()?.take(128)

            StatsManager.recordHandshake(
                pkg = finalPkg,
                version = clientVer,
                process = clientProc,
                context = context
            )

            intent.getStringExtra(EXTRA_HOOK_STATUS_JSON)?.let { hookJson ->
                try {
                    val mapType = object : TypeToken<Map<String, String?>>() {}.type
                    val rawHookMap: Map<String, String?> = gson.fromJson(hookJson, mapType)
                    val sanitizedMap = rawHookMap.entries
                        .take(50)
                        .filter { it.key.isNotBlank() && it.value != null }
                        .associate { it.key.trim().take(64) to it.value!!.trim().take(256) }
                    StatsManager.recordHookStatus(sanitizedMap, context)
                } catch (_: Exception) {}
            }
            Log.i(TAG, "通过 Broadcast 成功记录宿主握手心跳: pkg=$finalPkg ver=$clientVer proc=$clientProc")
            return
        }

        // 4. 处理重定向统计广播 (ACTION_RECORD_REWRITE)
        val batchJson = intent.getStringExtra(EXTRA_REWRITE_BATCH_JSON)
        val singleJson = intent.getStringExtra(EXTRA_REWRITE_RESULT)

        val isAuthorized = hasValidCreator || (
                isDirectedToModule(intent, context) &&
                        isPayloadLegitimate(batchJson, singleJson)
                )

        if (!isAuthorized) {
            Log.w(TAG, "拒绝未鉴权或伪造的统计广播上报：creatorPackage=$creatorPackage")
            return
        }

        val reportPkg = creatorPackage ?: intent.getStringExtra(EXTRA_CLIENT_PACKAGE)
        val reportProc = intent.getStringExtra(EXTRA_CLIENT_PROCESS)?.trim()?.take(128)

        // 优先处理批量上报
        if (!batchJson.isNullOrEmpty()) {
            try {
                val listType = object : TypeToken<List<RewriteResultDto>>() {}.type
                val dtos: List<RewriteResultDto> = gson.fromJson(batchJson, listType)
                val results = dtos.take(200).map { it.toDomain() }
                StatsManager.recordRequests(results, context)
                StatsManager.recordHeartbeat(context)
                if (!reportPkg.isNullOrBlank()) {
                    StatsManager.recordHandshake(pkg = reportPkg, version = null, process = reportProc, context = context)
                }
                Log.i(TAG, "通过 Broadcast 成功记录批量重定向 (${results.size} 条)")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 批量记录解析失败: $e")
            }
        }

        // 单条记录兜底兼容
        if (!singleJson.isNullOrEmpty()) {
            try {
                val dto = gson.fromJson(singleJson, RewriteResultDto::class.java)
                val result = dto.toDomain()
                StatsManager.recordRequests(listOf(result), context)
                StatsManager.recordHeartbeat(context)
                if (!reportPkg.isNullOrBlank()) {
                    StatsManager.recordHandshake(pkg = reportPkg, version = null, process = reportProc, context = context)
                }
                Log.i(TAG, "通过 Broadcast 成功记录单条重定向: ${result.originalHost} -> ${result.targetHost}")
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 记录失败: $e")
            }
        }
    }

    private fun isDirectedToModule(intent: Intent, context: Context): Boolean {
        return intent.`package` == context.packageName ||
                intent.component?.packageName == context.packageName
    }
}
