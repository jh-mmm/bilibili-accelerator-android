package io.github.jh_mmm.biliaccelerator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager

class StatsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_REWRITE = "io.github.jh_mmm.biliaccelerator.ACTION_RECORD_REWRITE"
        const val EXTRA_REWRITE_RESULT = "extra_rewrite_result"
        private val gson = Gson()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == ACTION_RECORD_REWRITE) {
            val json = intent.getStringExtra(EXTRA_REWRITE_RESULT)
            if (!json.isNullOrEmpty()) {
                try {
                    val result = gson.fromJson(json, RewriteResult::class.java)
                    StatsManager.recordRequest(result, context)
                    Log.i("BiliAccelerator-Stats", "通过 Broadcast 成功记录重定向: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
                    try {
                        de.robv.android.xposed.XposedBridge.log("BiliAccelerator-Stats: [Broadcast] 已记录重定向：${result.originalHost} -> ${result.targetHost} [${result.reason}]")
                    } catch (_: Throwable) {}
                } catch (e: Exception) {
                    Log.e("BiliAccelerator-Stats", "Broadcast 记录失败: $e")
                }
            }
        }
    }
}
