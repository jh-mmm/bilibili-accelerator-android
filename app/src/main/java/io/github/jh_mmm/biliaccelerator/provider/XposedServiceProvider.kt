package io.github.jh_mmm.biliaccelerator.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting

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
        const val EXTRA_BINDER = "binder"

        const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
        const val TRANSACTION_GET_FRAMEWORK_NAME = IBinder.FIRST_CALL_TRANSACTION + 2 // Code 3
        const val TRANSACTION_GET_SCOPE = IBinder.FIRST_CALL_TRANSACTION + 10 // Code 11

        private val TRUSTED_PACKAGES = setOf(
            "org.lsposed.manager",
            "io.github.libxposed.manager"
        )

        private val lock = Any()

        @Volatile
        private var rawIsBound: Boolean = false

        val hasQueriedScope: Boolean
            get() = io.github.jh_mmm.biliaccelerator.core.StatsManager.isScopeQueried

        val isServiceBound: Boolean
            get() {
                val binder = serviceBinder
                return rawIsBound && binder != null && binder.isBinderAlive
            }

        @Volatile
        var serviceBinder: IBinder? = null
            private set

        private var deathRecipient: IBinder.DeathRecipient? = null

        private val lastScopeQueryTime = java.util.concurrent.atomic.AtomicLong(0L)
        private const val SCOPE_QUERY_THROTTLE_MS = 15_000L

        private val scopeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "XSP-ScopeQuery").apply { isDaemon = true }
        }

        /**
         * 校验调用方身份：
         * 1. 放行 root (0，LSPosed 守护进程/系统服务)、system_server (1000) 或模块自身 UID；
         * 2. 兼容管理组件 UID：通过 PackageManager 校验包名白名单。
         */
        fun isCallerAuthorized(context: Context?, uid: Int): Boolean {
            if (uid == 0 || uid == Process.SYSTEM_UID) {
                return true
            }
            val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
            if (myUid != -1 && uid == myUid) {
                return true
            }
            if (context != null) {
                val packages = runCatching { context.packageManager?.getPackagesForUid(uid) }.getOrNull()
                if (packages != null && packages.any { it in TRUSTED_PACKAGES }) {
                    return true
                }
            }
            return false
        }

        /**
         * 校验传入 Binder 的身份与有效性：
         * 1. 校验 Binder 处于存活状态 (isBinderAlive && pingBinder)；
         * 2. 校验 Interface Descriptor 为 "io.github.libxposed.service.IXposedService" (通过 interfaceDescriptor 或 transact INTERFACE_TRANSACTION)；
         * 3. 或通过 transact code 3 (TRANSACTION_getFrameworkName) 调 getFrameworkName() 要求返回包含 "LSPosed"。
         */
        fun verifyLibXposedBinder(binder: IBinder): Boolean {
            val isAlive = runCatching { binder.isBinderAlive && binder.pingBinder() }.getOrDefault(false)
            if (!isAlive) {
                Log.w(TAG, "verifyLibXposedBinder: Binder is not alive or ping failed")
                return false
            }

            // 1. 校验 Interface Descriptor 为 "io.github.libxposed.service.IXposedService"
            val descriptor = queryInterfaceDescriptor(binder)
            if (DESCRIPTOR == descriptor) {
                return true
            }

            // 2. 或通过 transact code 3 调 getFrameworkName() 校验返回 "LSPosed"
            val frameworkName = queryFrameworkName(binder)
            if (frameworkName != null && frameworkName.contains("LSPosed", ignoreCase = true)) {
                return true
            }

            Log.w(TAG, "verifyLibXposedBinder rejected: descriptor='$descriptor', frameworkName='$frameworkName'")
            return false
        }

        fun queryInterfaceDescriptor(binder: IBinder): String? {
            val descriptorFromApi = runCatching { binder.interfaceDescriptor }.getOrNull()
            if (!descriptorFromApi.isNullOrBlank()) {
                return descriptorFromApi
            }

            val data = runCatching { Parcel.obtain() }.getOrNull() ?: return null
            val reply = runCatching { Parcel.obtain() }.getOrNull() ?: run {
                runCatching { data.recycle() }
                return null
            }
            return try {
                val ok = binder.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0)
                if (ok) {
                    reply.readString()
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "transact INTERFACE_TRANSACTION failed: ${t.message}")
                null
            } finally {
                runCatching { reply.recycle() }
                runCatching { data.recycle() }
            }
        }

        fun queryFrameworkName(binder: IBinder): String? {
            val data = runCatching { Parcel.obtain() }.getOrNull() ?: return null
            val reply = runCatching { Parcel.obtain() }.getOrNull() ?: run {
                runCatching { data.recycle() }
                return null
            }
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                val ok = binder.transact(TRANSACTION_GET_FRAMEWORK_NAME, data, reply, 0)
                if (ok) {
                    reply.readException()
                    reply.readString()
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "transact TRANSACTION_GET_FRAMEWORK_NAME failed: ${t.message}")
                null
            } finally {
                runCatching { reply.recycle() }
                runCatching { data.recycle() }
            }
        }

        fun queryFrameworkScope(binder: IBinder): List<String>? {
            val data = runCatching { Parcel.obtain() }.getOrNull() ?: return null
            val reply = runCatching { Parcel.obtain() }.getOrNull() ?: run {
                runCatching { data.recycle() }
                return null
            }
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                val ok = binder.transact(TRANSACTION_GET_SCOPE, data, reply, 0)
                if (ok) {
                    reply.readException()
                    reply.createStringArrayList()
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "transact TRANSACTION_GET_SCOPE failed: ${t.message}")
                null
            } finally {
                runCatching { reply.recycle() }
                runCatching { data.recycle() }
            }
        }

        fun triggerScopeQueryAsync(binder: IBinder? = serviceBinder, force: Boolean = false) {
            val targetBinder = binder ?: serviceBinder ?: return
            if (!targetBinder.isBinderAlive) return

            val now = System.currentTimeMillis()
            if (!force && (now - lastScopeQueryTime.get()) < SCOPE_QUERY_THROTTLE_MS) {
                return
            }
            lastScopeQueryTime.set(now)

            scopeExecutor.execute {
                try {
                    val scope = queryFrameworkScope(targetBinder)
                    if (scope != null) {
                        Log.i(TAG, "Queried framework scope: $scope")
                        io.github.jh_mmm.biliaccelerator.core.StatsManager.recordFrameworkScope(scope)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Async scope query exception: ${t.message}")
                }
            }
        }

        private fun handleBinderDied(deadBinder: IBinder?) {
            synchronized(lock) {
                if (deadBinder == null || serviceBinder == deadBinder) {
                    rawIsBound = false
                    serviceBinder = null
                    deathRecipient = null
                    io.github.jh_mmm.biliaccelerator.core.StatsManager.clearFrameworkScope()
                }
            }
        }

        @VisibleForTesting
        fun resetForTesting() {
            synchronized(lock) {
                handleBinderDied(null)
            }
        }

        @VisibleForTesting
        fun setServiceBoundForTesting(bound: Boolean, mockBinder: IBinder? = null, scope: List<String>? = null) {
            synchronized(lock) {
                rawIsBound = bound
                serviceBinder = mockBinder
                if (bound && mockBinder != null) {
                    deathRecipient = IBinder.DeathRecipient {
                        handleBinderDied(mockBinder)
                    }
                } else {
                    deathRecipient = null
                }
                if (scope != null) {
                    io.github.jh_mmm.biliaccelerator.core.StatsManager.recordFrameworkScope(scope)
                } else if (!bound) {
                    io.github.jh_mmm.biliaccelerator.core.StatsManager.clearFrameworkScope()
                }
            }
        }

        @VisibleForTesting
        fun triggerBinderDiedForTesting() {
            synchronized(lock) {
                val recipient = deathRecipient
                if (recipient != null) {
                    recipient.binderDied()
                } else {
                    handleBinderDied(null)
                }
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()

        if (!METHOD_SEND_BINDER.equals(method, ignoreCase = true)) {
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

        if (!verifyLibXposedBinder(binder)) {
            Log.w(TAG, "Rejected binder with invalid identity/descriptor from uid=$uid (method=$method)")
            return null
        }

        synchronized(lock) {
            val current = serviceBinder
            if (current != null && current.isBinderAlive && current.pingBinder()) {
                if (current == binder) {
                    triggerScopeQueryAsync(binder, force = false)
                    return Bundle().apply { putBoolean("result", true) }
                }
                // 已有存活的框架 Binder 绑定时拒绝被其他调用顶替，防止状态假阳性劫持
                Log.w(TAG, "Ignored binder replacement while existing XposedService binder is alive (uid=$uid)")
                return null
            }

            // 替换前解绑旧 Binder 的 death recipient，防止内存泄漏与竞争
            val oldRecipient = deathRecipient
            if (current != null && oldRecipient != null) {
                runCatching { current.unlinkToDeath(oldRecipient, 0) }
            }

            val recipient = IBinder.DeathRecipient {
                Log.w(TAG, "Framework XposedService Binder died")
                handleBinderDied(binder)
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

        // 绑定成功后立即在后台异步查询 LSPosed 模块作用域
        triggerScopeQueryAsync(binder, force = true)

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
