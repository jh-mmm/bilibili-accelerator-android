package io.github.jh_mmm.biliaccelerator.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * 现代 LibXposed 规范服务桥接组件。
 *
 * 在 LibXposed Modern API (API 101+) 下，LSPosed 守护进程通过向模块的
 * `${applicationId}.XposedService` Provider 发起 call(SendBinder) 推送 IXposedService Binder。
 * 此组件校验调用方身份与方法名，并维护框架在线绑定状态。
 */
class XposedServiceProvider : ContentProvider() {

    companion object {
        private const val TAG = "BiliAccelerator-XSP"
        const val METHOD_SEND_BINDER = "SendBinder"
        const val METHOD_SEND_BINDER_UPPER = "SEND_BINDER"
        const val EXTRA_BINDER = "binder"

        private val TRUSTED_PACKAGES = setOf(
            "org.lsposed.manager",
            "io.github.libxposed.manager"
        )

        private val lock = Any()

        @Volatile
        private var rawIsBound: Boolean = false

        val isServiceBound: Boolean
            get() {
                val binder = serviceBinder
                return rawIsBound && binder != null && binder.isBinderAlive
            }

        @Volatile
        var serviceBinder: IBinder? = null
            private set

        private var deathRecipient: IBinder.DeathRecipient? = null

        /**
         * 严格校验调用方身份：
         * 1. 仅信任 root (0)、system_server (1000) 或模块自身 UID；
         * 2. 兼容普通 UID 运行的管理组件：通过 PackageManager 校验包名白名单（由系统守护，不可伪造）。
         * 坚决不使用调用方自报的 interfaceDescriptor 作为凭证。
         */
        fun isCallerAuthorized(context: Context?, uid: Int): Boolean {
            if (uid == 0 || uid == Process.SYSTEM_UID || uid == Process.myUid()) {
                return true
            }
            if (context != null) {
                val pm = context.packageManager
                val packages = pm.getPackagesForUid(uid)
                if (packages != null && packages.any { it in TRUSTED_PACKAGES }) {
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()

        if (!METHOD_SEND_BINDER.equals(method, ignoreCase = true) && method != METHOD_SEND_BINDER_UPPER) {
            Log.w(TAG, "Rejected invalid method: $method (uid=$uid)")
            return null
        }

        if (extras == null) {
            Log.w(TAG, "Missing extras in call(method=$method)")
            return null
        }

        val binder = extras.getBinder(EXTRA_BINDER)
        if (binder == null) {
            Log.w(TAG, "No binder provided in extras (method=$method)")
            return null
        }

        if (!isCallerAuthorized(context, uid)) {
            Log.w(TAG, "Rejected unauthorized call from uid=$uid (method=$method)")
            return null
        }

        if (!binder.isBinderAlive) {
            Log.w(TAG, "Provided binder is not alive (method=$method)")
            return null
        }

        synchronized(lock) {
            val current = serviceBinder
            if (current != null && current.isBinderAlive && current == binder) {
                // 已处于相同存活绑定状态，无需重复重绑
                return Bundle().apply { putBoolean("result", true) }
            }

            // 替换前解绑旧 Binder 的 death recipient，防止内存泄漏与竞争
            val oldRecipient = deathRecipient
            if (current != null && oldRecipient != null) {
                runCatching { current.unlinkToDeath(oldRecipient, 0) }
            }

            val recipient = IBinder.DeathRecipient {
                Log.w(TAG, "Framework XposedService Binder died")
                synchronized(lock) {
                    if (serviceBinder == binder) {
                        rawIsBound = false
                        serviceBinder = null
                        deathRecipient = null
                    }
                }
            }

            try {
                binder.linkToDeath(recipient, 0)
                serviceBinder = binder
                deathRecipient = recipient
                rawIsBound = true
                Log.i(TAG, "Successfully bound framework XposedService Binder from uid=$uid")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to linkToDeath on binder: ${t.message}")
                rawIsBound = false
                serviceBinder = null
                deathRecipient = null
                return null
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
