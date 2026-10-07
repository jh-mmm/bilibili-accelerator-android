package io.github.jh_mmm.biliaccelerator.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager

class StatsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "${BiliAcceleratorCore.MODULE_PACKAGE}.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_RECORD_REWRITE = "recordRewrite"
        const val METHOD_RECORD_BATCH_REWRITE = "recordBatchRewrite"
        const val METHOD_GET_STATS = "getStats"
        const val METHOD_CLEAR_STATS = "clearStats"
        const val METHOD_GET_CONFIG = "getConfig"
        const val METHOD_HEARTBEAT = "heartbeat"

        const val EXTRA_REWRITE_RESULT = "extra_rewrite_result"
        const val EXTRA_REWRITE_BATCH_JSON = "extra_rewrite_batch_json"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_STATS_JSON = "extra_stats_json"

        const val PREFS_CONFIG = "bili_accelerator_config"
        const val KEY_ENABLED = "cfg_enabled"
        const val KEY_TARGET_HOST = "cfg_target_host"
        const val KEY_PROXY_HOST = "cfg_proxy_host"
        const val KEY_BLOCK_PCDN = "cfg_block_pcdn"
        const val KEY_PROXY_MCDN = "cfg_proxy_mcdn"
        const val KEY_FORCE_UPOS = "cfg_force_upos"
        const val KEY_PORT_HEURISTIC = "cfg_port_heuristic"
        const val EXTRA_SUCCESS = "success"

        private val gson = Gson()

        private fun log(msg: String) {
            Log.i("BiliAccelerator-Stats", msg)
        }

        fun isCallerAuthorized(context: Context, uid: Int): Boolean {
            if (uid == Process.myUid()) return true
            val pkgs = context.packageManager.getPackagesForUid(uid).orEmpty()
            if (pkgs.any { it in BiliAcceleratorCore.TARGET_PACKAGES }) return true
            return BiliAcceleratorCore.TARGET_PACKAGES.any { pkg ->
                try {
                    val expectedUid = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageUid(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageUid(pkg, 0)
                    }
                    expectedUid == uid
                } catch (_: Exception) {
                    false
                }
            }
        }

        fun loadConfig(context: Context): AcceleratorConfig {
            val sp = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            return AcceleratorConfig(
                enabled = sp.getBoolean(KEY_ENABLED, true),
                targetHost = sp.getString(KEY_TARGET_HOST, "upos-sz-mirrorcos.bilivideo.com") ?: "upos-sz-mirrorcos.bilivideo.com",
                proxyHost = sp.getString(KEY_PROXY_HOST, "proxy-tf-all-ws.bilivideo.com") ?: "proxy-tf-all-ws.bilivideo.com",
                blockPcdn = sp.getBoolean(KEY_BLOCK_PCDN, true),
                proxyMcdn = sp.getBoolean(KEY_PROXY_MCDN, true),
                forceUpos = sp.getBoolean(KEY_FORCE_UPOS, false),
                portHeuristic = sp.getBoolean(KEY_PORT_HEURISTIC, true)
            )
        }

        fun saveConfig(context: Context, config: AcceleratorConfig) {
            val sp = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_TARGET_HOST, config.targetHost)
                .putString(KEY_PROXY_HOST, config.proxyHost)
                .putBoolean(KEY_BLOCK_PCDN, config.blockPcdn)
                .putBoolean(KEY_PROXY_MCDN, config.proxyMcdn)
                .putBoolean(KEY_FORCE_UPOS, config.forceUpos)
                .putBoolean(KEY_PORT_HEURISTIC, config.portHeuristic)
                .apply()
        }
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            StatsManager.init(ctx)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context
        if (ctx == null) {
            log("调用失败：Provider Context 为空 (method=$method)")
            return null
        }

        // 校验调用方 UID，仅允许模块自身与目标 B 站应用访问
        val uid = Binder.getCallingUid()
        if (!isCallerAuthorized(ctx, uid)) {
            val pkgs = ctx.packageManager.getPackagesForUid(uid).orEmpty()
            log("拒绝调用：method=$method uid=$uid pkgs=${pkgs.joinToString()}")
            return null
        }

        val response = Bundle()

        when (method) {
            METHOD_RECORD_BATCH_REWRITE -> {
                val json = extras?.getString(EXTRA_REWRITE_BATCH_JSON)
                if (!json.isNullOrEmpty()) {
                    try {
                        val listType = object : TypeToken<List<RewriteResult>>() {}.type
                        val results: List<RewriteResult> = gson.fromJson(json, listType)
                        StatsManager.recordRequests(results, ctx)
                        StatsManager.recordHeartbeat(ctx)
                        response.putBoolean(EXTRA_SUCCESS, true)
                        log("已批量记录重定向: ${results.size} 条记录 (来自 uid=$uid)")
                    } catch (e: Exception) {
                        response.putBoolean(EXTRA_SUCCESS, false)
                        log("批量记录重定向失败: $e")
                    }
                } else {
                    response.putBoolean(EXTRA_SUCCESS, false)
                    log("批量记录重定向失败：extras 为空 (来自 uid=$uid)")
                }
            }
            METHOD_RECORD_REWRITE -> {
                val json = extras?.getString(EXTRA_REWRITE_RESULT)
                if (!json.isNullOrEmpty()) {
                    try {
                        val result = gson.fromJson(json, RewriteResult::class.java)
                        StatsManager.recordRequest(result, ctx)
                        StatsManager.recordHeartbeat(ctx)
                        response.putBoolean(EXTRA_SUCCESS, true)
                        log("已记录单条重定向：${result.originalHost} -> ${result.targetHost} [${result.reason}] (来自 uid=$uid)")
                    } catch (e: Exception) {
                        response.putBoolean(EXTRA_SUCCESS, false)
                        log("记录单条重定向失败：$e")
                    }
                } else {
                    response.putBoolean(EXTRA_SUCCESS, false)
                    log("记录重定向失败：extras 为空 (来自 uid=$uid)")
                }
            }
            METHOD_GET_STATS -> {
                val snapshot = StatsManager.getSnapshot()
                response.putString(EXTRA_STATS_JSON, gson.toJson(snapshot))
            }
            METHOD_CLEAR_STATS -> {
                StatsManager.clearStats(ctx)
                response.putBoolean(EXTRA_SUCCESS, true)
            }
            METHOD_GET_CONFIG -> {
                StatsManager.recordHeartbeat(ctx)
                val config = loadConfig(ctx)
                response.putString(EXTRA_CONFIG_JSON, gson.toJson(config))
            }
            METHOD_HEARTBEAT -> {
                StatsManager.recordHeartbeat(ctx)
                response.putBoolean(EXTRA_SUCCESS, true)
                log("收到来自目标应用的心跳回执 (来自 uid=$uid)")
            }
        }
        return response
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
