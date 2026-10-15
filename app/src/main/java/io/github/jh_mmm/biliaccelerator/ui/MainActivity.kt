package io.github.jh_mmm.biliaccelerator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jh_mmm.biliaccelerator.R
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.RewriteLogEntry
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import io.github.jh_mmm.biliaccelerator.core.StatsSnapshot
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import io.github.jh_mmm.biliaccelerator.ui.navigation.AcceleratorApp
import io.github.jh_mmm.biliaccelerator.ui.theme.AcceleratorTheme
import io.github.jh_mmm.biliaccelerator.ui.theme.UiPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var uiPreferencesStore: UiPreferencesStore

    private val currentConfig = MutableStateFlow(AcceleratorConfig())
    private val currentSnapshot = MutableStateFlow(StatsSnapshot())
    private val currentActivationState = MutableStateFlow(ActivationState.INACTIVE)

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    enum class ActivationState {
        /** 已生效：模块已激活且已实际产生视频流重定向（totalRewrites > 0） */
        ACTIVE_EFFECTIVE,

        /** 已激活：LSPosed 模块开关已开启且作用域生效（或有心跳通信/服务连接），等待目标应用产生视频流量 */
        ACTIVE_HEARTBEAT,

        /** 未激活：未检测到框架激活、服务绑定或通信回执 */
        INACTIVE;

        val isActive: Boolean
            get() = this != INACTIVE
    }

    companion object {
        const val DEFAULT_HEARTBEAT_THRESHOLD_MS = 30L * 60 * 1000L // 30 分钟近期活跃心跳窗口

        /**
         * 经典 Xposed / LSPosed 模块自检测入口。
         * 默认返回 false；当模块自身处于作用域中并被框架成功注入加载时，
         * HookEntry 会将此方法 hook 替换返回 true。
         */
        @Keep
        @JvmStatic
        fun isModuleActive(): Boolean = false

        fun calculateActivationState(
            totalRewrites: Long,
            isServiceBound: Boolean,
            lastHeartbeat: Long,
            isSelfInjected: Boolean = false,
            hasHistoricalActivation: Boolean = false,
            now: Long = System.currentTimeMillis(),
            heartbeatThresholdMs: Long = DEFAULT_HEARTBEAT_THRESHOLD_MS
        ): ActivationState {
            val diff = now - lastHeartbeat
            val hasRecentHeartbeat = lastHeartbeat > 0L && diff in 0..heartbeatThresholdMs

            // 只要满足任一激活凭据，模块即处于激活状态：
            // 1. 模块自身被框架注入 (isSelfInjected: LSPosed 开启开关并勾选作用域)
            // 2. 现代 LibXposed 框架服务已绑定 (isServiceBound)
            // 3. 目标应用近期有活跃心跳通信 (hasRecentHeartbeat)
            // 4. 目标应用曾有历史心跳记录 (lastHeartbeat > 0L)
            // 5. 本地持久化记录曾被激活 (hasHistoricalActivation)
            // 6. 已产生加速重写记录 (totalRewrites > 0L)
            val isActivated = isSelfInjected ||
                    isServiceBound ||
                    hasRecentHeartbeat ||
                    hasHistoricalActivation ||
                    lastHeartbeat > 0L ||
                    totalRewrites > 0L

            if (!isActivated) {
                return ActivationState.INACTIVE
            }

            return if (totalRewrites > 0L) {
                ActivationState.ACTIVE_EFFECTIVE
            } else {
                ActivationState.ACTIVE_HEARTBEAT
            }
        }
    }

    fun getActivationState(): ActivationState {
        val snapshot = StatsManager.getSnapshot()
        val isSelfInjected = isModuleActive() || StatsManager.isFrameworkSelfInjected

        return calculateActivationState(
            totalRewrites = snapshot.totalRewrites,
            isServiceBound = XposedServiceProvider.isServiceBound,
            lastHeartbeat = snapshot.lastHeartbeatTimestamp,
            isSelfInjected = isSelfInjected,
            hasHistoricalActivation = StatsManager.hasHistoricalActivation(this) || snapshot.isFrameworkActivated
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        StatsManager.init(this)
        if (isModuleActive() || StatsManager.isFrameworkSelfInjected) {
            StatsManager.recordFrameworkActivated(this)
        }
        uiPreferencesStore = UiPreferencesStore(this)

        val loadedConfig = StatsProvider.loadConfig(this)
        currentConfig.value = loadedConfig

        val uposEntries = resources.getStringArray(R.array.upos_entries).toList()
        val uposValues = resources.getStringArray(R.array.upos_values).toList()

        val (versionName, versionCode) = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            (pInfo.versionName ?: "1.0.7") to code
        } catch (_: Exception) {
            "1.0.7" to 107L
        }

        refreshStats()

        setContent {
            val uiPreferences by uiPreferencesStore.preferences.collectAsStateWithLifecycle()
            val state by currentActivationState.collectAsStateWithLifecycle()
            val snapshot by currentSnapshot.collectAsStateWithLifecycle()
            val config by currentConfig.collectAsStateWithLifecycle()

            AcceleratorTheme(preferences = uiPreferences) {
                AcceleratorApp(
                    state = state,
                    snapshot = snapshot,
                    config = config,
                    onConfigChange = { newConfig ->
                        currentConfig.value = newConfig
                        StatsProvider.saveConfig(this@MainActivity, newConfig)
                    },
                    uiPreferences = uiPreferences,
                    onUiPreferencesChange = { newPrefs ->
                        if (newPrefs.style != uiPreferences.style) {
                            uiPreferencesStore.updateStyle(newPrefs.style)
                        }
                        if (newPrefs.themeMode != uiPreferences.themeMode) {
                            uiPreferencesStore.updateThemeMode(newPrefs.themeMode)
                        }
                    },
                    onClearStats = {
                        StatsManager.clearStats(this@MainActivity)
                        refreshStats()
                        Toast.makeText(this@MainActivity, R.string.toast_stats_cleared, Toast.LENGTH_SHORT).show()
                    },
                    onExportDiagnostic = {
                        val report = StatsManager.buildDiagnosticReport(currentConfig.value, "$versionName ($versionCode)")
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("BiliAccelerator Diagnostic", report))
                        Toast.makeText(this@MainActivity, R.string.toast_diag_copied, Toast.LENGTH_SHORT).show()
                    },
                    onRefresh = {
                        refreshStats()
                    },
                    onLogClick = { logEntry ->
                        copyLogToClipboard(logEntry)
                    },
                    uposEntries = uposEntries,
                    uposValues = uposValues,
                    versionName = versionName,
                    versionCode = versionCode
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 2000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshStats() {
        val snapshot = StatsManager.getSnapshot()
        currentSnapshot.value = snapshot
        currentActivationState.value = calculateActivationState(
            totalRewrites = snapshot.totalRewrites,
            isServiceBound = XposedServiceProvider.isServiceBound,
            lastHeartbeat = snapshot.lastHeartbeatTimestamp
        )
    }

    private fun copyLogToClipboard(entry: RewriteLogEntry) {
        val detail = "时间: ${entry.timeFormatted}\n来源: ${entry.originalHost}\n重定向至: ${entry.targetHost}\n策略: ${entry.reason}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rewrite Log", detail))
        Toast.makeText(this, "日志信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
