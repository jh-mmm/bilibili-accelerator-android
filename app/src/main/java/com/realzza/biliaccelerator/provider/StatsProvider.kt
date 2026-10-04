package com.realzza.biliaccelerator.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.google.gson.Gson
import com.realzza.biliaccelerator.core.AcceleratorConfig
import com.realzza.biliaccelerator.core.BiliAcceleratorCore
import com.realzza.biliaccelerator.core.RewriteResult
import com.realzza.biliaccelerator.core.StatsManager

class StatsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.realzza.biliaccelerator.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_RECORD_REWRITE = "recordRewrite"
        const val METHOD_GET_STATS = "getStats"
        const val METHOD_CLEAR_STATS = "clearStats"
        const val METHOD_GET_CONFIG = "getConfig"

        const val EXTRA_REWRITE_RESULT = "extra_rewrite_result"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_STATS_JSON = "extra_stats_json"

        const val PREFS_CONFIG = "bili_accelerator_config"
        const val KEY_ENABLED = "cfg_enabled"
        const val KEY_TARGET_HOST = "cfg_target_host"
        const val KEY_BLOCK_PCDN = "cfg_block_pcdn"
        const val KEY_PROXY_MCDN = "cfg_proxy_mcdn"
        const val KEY_FORCE_UPOS = "cfg_force_upos"
        const val KEY_PORT_HEURISTIC = "cfg_port_heuristic"
        const val EXTRA_SUCCESS = "success"

        private val gson = Gson()

        fun loadConfig(context: android.content.Context): AcceleratorConfig {
            val sp = context.getSharedPreferences(PREFS_CONFIG, android.content.Context.MODE_PRIVATE)
            return AcceleratorConfig(
                enabled = sp.getBoolean(KEY_ENABLED, true),
                targetHost = sp.getString(KEY_TARGET_HOST, "upos-sz-mirrorcos.bilivideo.com") ?: "upos-sz-mirrorcos.bilivideo.com",
                blockPcdn = sp.getBoolean(KEY_BLOCK_PCDN, true),
                proxyMcdn = sp.getBoolean(KEY_PROXY_MCDN, true),
                forceUpos = sp.getBoolean(KEY_FORCE_UPOS, false),
                portHeuristic = sp.getBoolean(KEY_PORT_HEURISTIC, true)
            )
        }

        fun saveConfig(context: android.content.Context, config: AcceleratorConfig) {
            val sp = context.getSharedPreferences(PREFS_CONFIG, android.content.Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_TARGET_HOST, config.targetHost)
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
        val ctx = context ?: return null

        // 校验调用方 UID，仅允许模块自身与目标 B 站应用访问
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            val pkgs = ctx.packageManager.getPackagesForUid(uid).orEmpty()
            val isTargetPackage = pkgs.any { it in BiliAcceleratorCore.TARGET_PACKAGES }
            if (!isTargetPackage) {
                val matchesTargetUid = BiliAcceleratorCore.TARGET_PACKAGES.any { pkg ->
                    try {
                        val expectedUid = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            ctx.packageManager.getPackageUid(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            ctx.packageManager.getPackageUid(pkg, 0)
                        }
                        expectedUid == uid
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!matchesTargetUid) {
                    return null
                }
            }
        }

        val response = Bundle()

        when (method) {
            METHOD_RECORD_REWRITE -> {
                val json = extras?.getString(EXTRA_REWRITE_RESULT)
                if (!json.isNullOrEmpty()) {
                    try {
                        val result = gson.fromJson(json, RewriteResult::class.java)
                        StatsManager.recordRequest(result, ctx)
                        response.putBoolean("success", true)
                    } catch (e: Exception) {
                        response.putBoolean("success", false)
                    }
                }
            }
            METHOD_GET_STATS -> {
                val snapshot = StatsManager.getSnapshot()
                response.putString(EXTRA_STATS_JSON, gson.toJson(snapshot))
            }
            METHOD_CLEAR_STATS -> {
                StatsManager.clearStats(ctx)
                response.putBoolean("success", true)
            }
            METHOD_GET_CONFIG -> {
                val config = loadConfig(ctx)
                response.putString(EXTRA_CONFIG_JSON, gson.toJson(config))
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
