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
        /** 已生效：模块当前处于活跃状态（服务已绑定或有近期心跳），且已实际产生视频流重定向（totalRewrites > 0） */
        ACTIVE_EFFECTIVE,

        /** 已激活：LSPosed 框架服务已连接或近期有活跃心跳，等待目标应用产生视频流量 */
        ACTIVE_HEARTBEAT,

        /** 未激活：无框架服务连接且无近期活跃心跳（即使存在历史统计也会降级为未激活） */
        INACTIVE;

        val isActive: Boolean
            get() = this != INACTIVE
    }

    companion object {
        const val DEFAULT_HEARTBEAT_THRESHOLD_MS = 30L * 60 * 1000L // 30 分钟近期活跃心跳窗口

        fun calculateActivationState(
            totalRewrites: Long,
            isServiceBound: Boolean,
            lastHeartbeat: Long,
            now: Long = System.currentTimeMillis(),
            heartbeatThresholdMs: Long = DEFAULT_HEARTBEAT_THRESHOLD_MS
        ): ActivationState {
            val diff = now - lastHeartbeat
            val hasRecentHeartbeat = lastHeartbeat > 0L && diff in 0..heartbeatThresholdMs
            val isCurrentlyActive = isServiceBound || hasRecentHeartbeat

            // 必须当前在线（服务存活或近期心跳有效）才可视为激活，防止关闭/卸载模块后因历史累计统计导致状态永远停留在“运行正常”
            if (!isCurrentlyActive) {
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
        return calculateActivationState(
            totalRewrites = snapshot.totalRewrites,
            isServiceBound = XposedServiceProvider.isServiceBound,
            lastHeartbeat = snapshot.lastHeartbeatTimestamp
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        StatsManager.init(this)
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
