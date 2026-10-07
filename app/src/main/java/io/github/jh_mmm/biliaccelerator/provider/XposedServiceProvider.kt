package io.github.jh_mmm.biliaccelerator.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * 现代 LibXposed 规范服务桥接组件。
 *
 * 在 LibXposed Modern API (API 101+) 下，LSPosed 守护进程通过向模块的
 * `${applicationId}.XposedService` Provider 发起 call 调用推送 IXposedService Binder。
 * 此组件用于捕获框架推送并维护框架在线绑定状态。
 */
class XposedServiceProvider : ContentProvider() {

    companion object {
        private const val TAG = "BiliAccelerator-XSP"

        @Volatile
        var isServiceBound: Boolean = false
            private set

        @Volatile
        var serviceBinder: IBinder? = null
            private set

        /**
         * 供测试或模拟调试重置状态使用
         */
        fun resetForTesting(bound: Boolean = false, binder: IBinder? = null) {
            isServiceBound = bound
            serviceBinder = binder
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.i(TAG, "Received call from framework: method=$method, arg=$arg")
        extras?.let { bundle ->
            val binder = bundle.getBinder("binder")
            if (binder != null) {
                serviceBinder = binder
                isServiceBound = true
                Log.i(TAG, "Successfully bound framework XposedService Binder")
                try {
                    binder.linkToDeath({
                        Log.w(TAG, "Framework XposedService Binder died")
                        isServiceBound = false
                        serviceBinder = null
                    }, 0)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to linkToDeath on binder: ${t.message}")
                }
            }
        }
        return Bundle().apply {
            putBoolean("result", true)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
