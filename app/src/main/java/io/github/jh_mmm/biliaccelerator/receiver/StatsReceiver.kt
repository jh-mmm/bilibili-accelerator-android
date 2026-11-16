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
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider

class StatsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_REWRITE = "io.github.jh_mmm.biliaccelerator.ACTION_RECORD_REWRITE"
        const val ACTION_HANDSHAKE = "io.github.jh_mmm.biliaccelerator.ACTION_HANDSHAKE"
        const val ACTION_SYNC_CONFIG = "io.github.jh_mmm.biliaccelerator.ACTION_SYNC_CONFIG"
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
            "mcdn-v1-pass",
            "force-upos",
            "ok",
            "already-proxied",
            "already-local-proxy",
            "smtcdns-unwrap",
            "drm-protected",
            "ignored",
            "parse-failed",
            "live-skip",
            "live-szbdyd-source",
            "live-mountaintoys-source",
            "live-smtcdns-unwrap",
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
            if (!isKnownReason) {
                return false
            }
            if (reason == "parse-failed" || reason == "ignored") {
                return orig.isNotBlank() || tgt.isNotBlank() || !item.changed
            }
            if (orig.isBlank() || tgt.isBlank()) {
                return item.isBtr && tgt.isNotBlank()
            }
            if (item.isBtr || tgt.startsWith("127.0.0.1") || orig == "127.0.0.1" || orig == "localhost" || tgt.contains("BTR") || tgt.contains("镜像")) {
                return true
            }
            return BiliAcceleratorCore.isBiliFamilyHost(orig) ||
                    BiliAcceleratorCore.isBiliFamilyHost(tgt) ||
                    BiliAcceleratorCore.CANDIDATE_POOL.contains(tgt) ||
                    BiliAcceleratorCore.isIpAddress(orig) ||
                    reason == "drm-protected" ||
                    reason == "smtcdns-unwrap"
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action != ACTION_RECORD_REWRITE && action != ACTION_HANDSHAKE) return

        // 1. 严格系统级身份鉴权：
        //    - API 34+ 结合系统担保的 getSentFromUid() / getSentFromPackage()
        //    - PendingIntent 由 AMS 担保的 creatorPackage 或 creatorUid（经 StatsProvider.isCallerAuthorized 校验多用户/双开）
        //    - 严禁仅凭可控的 EXTRA_CLIENT_PACKAGE 或 Payload 内容放行未鉴权调用
        val sentFromUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { sentFromUid }.getOrDefault(-1)
        } else {
            -1
        }
        val sentFromPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { sentFromPackage }.getOrNull()
        } else {
            null
        }
        if (sentFromUid > 0 && !StatsProvider.isCallerAuthorized(context, sentFromUid)) {
            Log.w(TAG, "拒绝未鉴权系统发送方广播：sentFromUid=$sentFromUid, sentFromPkg=$sentFromPkg")
            return
        }

        val authToken: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { intent.getParcelableExtra(EXTRA_AUTH_TOKEN, PendingIntent::class.java) }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { intent.getParcelableExtra<PendingIntent>(EXTRA_AUTH_TOKEN) }.getOrNull()
        }

        val creatorPackage = authToken?.creatorPackage
        val creatorUid = authToken?.creatorUid ?: -1
        val hasValidCreatorPkg = creatorPackage != null && (
                BiliAcceleratorCore.isTargetPackage(creatorPackage) ||
                        creatorPackage == context.packageName
                )
        val hasValidCreatorUid = creatorUid > 0 && StatsProvider.isCallerAuthorized(context, creatorUid)
        val hasValidSystemSender = (sentFromUid > 0 && StatsProvider.isCallerAuthorized(context, sentFromUid)) ||
                (sentFromPkg != null && (BiliAcceleratorCore.isTargetPackage(sentFromPkg) || sentFromPkg == context.packageName))

        val isVerifiedSender = hasValidCreatorPkg || hasValidCreatorUid || hasValidSystemSender
        if (!isVerifiedSender) {
            Log.w(TAG, "拒绝未鉴权广播：creatorPackage=$creatorPackage, creatorUid=$creatorUid, sentFromUid=$sentFromUid, sentFromPkg=$sentFromPkg")
            return
        }

        val resolvedSenderPkg = when {
            hasValidCreatorPkg -> creatorPackage
            !sentFromPkg.isNullOrBlank() && BiliAcceleratorCore.isTargetPackage(sentFromPkg) -> sentFromPkg
            else -> intent.getStringExtra(EXTRA_CLIENT_PACKAGE)?.trim()?.takeIf { BiliAcceleratorCore.isTargetPackage(it) }
        }

        // 2. 确保 StatsManager 状态先从 SharedPreferences 载入
        StatsManager.ensureInitialized(context)

        // 3. 处理独立握手广播 (ACTION_HANDSHAKE)
        if (action == ACTION_HANDSHAKE) {
            if (resolvedSenderPkg.isNullOrBlank()) {
                Log.w(TAG, "拒绝缺少有效目标包名的握手广播：creatorPackage=$creatorPackage, creatorUid=$creatorUid")
                return
            }

            val clientVer = intent.getStringExtra(EXTRA_CLIENT_VERSION)?.trim()?.take(64)
            val clientProc = intent.getStringExtra(EXTRA_CLIENT_PROCESS)?.trim()?.take(128)

            StatsManager.recordHandshake(
                pkg = resolvedSenderPkg,
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
            // 反向推送最新配置至宿主 App（兜底 Android 11+ 包可见性拦截 StatsProvider 的场景）
            StatsProvider.pushConfigToPackageAsync(context, resolvedSenderPkg)
            Log.i(TAG, "通过 Broadcast 成功记录宿主握手心跳: pkg=$resolvedSenderPkg ver=$clientVer proc=$clientProc")
            return
        }

        // 4. 处理重定向统计广播 (ACTION_RECORD_REWRITE)
        val batchJson = intent.getStringExtra(EXTRA_REWRITE_BATCH_JSON)
        val singleJson = intent.getStringExtra(EXTRA_REWRITE_RESULT)
        val reportProc = intent.getStringExtra(EXTRA_CLIENT_PROCESS)?.trim()?.take(128)

        // 优先处理批量上报
        if (!batchJson.isNullOrEmpty()) {
            try {
                val listType = object : TypeToken<List<RewriteResultDto>>() {}.type
                val dtos: List<RewriteResultDto> = gson.fromJson(batchJson, listType)
                val results = dtos.take(200).map { it.toDomain() }.filter { isValidResultItem(it) }
                if (results.isEmpty()) return
                StatsManager.recordRequests(results, context)
                StatsManager.recordHeartbeat(context)
                if (!resolvedSenderPkg.isNullOrBlank()) {
                    StatsManager.recordHandshake(pkg = resolvedSenderPkg, version = null, process = reportProc, context = context)
                    StatsProvider.pushConfigToPackageAsync(context, resolvedSenderPkg)
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
                if (!isValidResultItem(result)) return
                StatsManager.recordRequests(listOf(result), context)
                StatsManager.recordHeartbeat(context)
                if (!resolvedSenderPkg.isNullOrBlank()) {
                    StatsManager.recordHandshake(pkg = resolvedSenderPkg, version = null, process = reportProc, context = context)
                    StatsProvider.pushConfigToPackageAsync(context, resolvedSenderPkg)
                }
                Log.i(TAG, "通过 Broadcast 成功记录单条重定向: ${result.originalHost} -> ${result.targetHost}")
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 记录失败: $e")
            }
        }
    }
}
