package io.github.jh_mmm.biliaccelerator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.jh_mmm.biliaccelerator.R
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import io.github.jh_mmm.biliaccelerator.databinding.ActivityMainBinding
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private var currentConfig = AcceleratorConfig()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    enum class ActivationState {
        /** 已生效：已实际重写或过滤视频请求（totalRewrites > 0） */
        ACTIVE_EFFECTIVE,

        /** 已激活：LSPosed 框架服务已连接，或 7 天内有心跳通信，等待目标应用产生视频流量 */
        ACTIVE_HEARTBEAT,

        /** 未激活：无框架服务连接且无近期活跃心跳 */
        INACTIVE;

        val isActive: Boolean
            get() = this != INACTIVE
    }

    /**
     * 判定模块激活与生效状态：
     * 1. 运行时统计（totalRewrites > 0）：确认已在目标进程生效并执行过加速/拦截；
     * 2. 框架 Service 绑定状态（XposedServiceProvider.isServiceBound）：确认 LSPosed 已推送 Binder；
     * 3. 运行时心跳回执（7 天内）：确认目标 B 站进程已注入并成功与伴侣 App 握手。
     */
    fun getActivationState(): ActivationState {
        val snapshot = StatsManager.getSnapshot()
        if (snapshot.totalRewrites > 0) {
            return ActivationState.ACTIVE_EFFECTIVE
        }
        if (XposedServiceProvider.isServiceBound) {
            return ActivationState.ACTIVE_HEARTBEAT
        }
        if (StatsManager.isRecentlyActive(7L * 24 * 3600 * 1000L)) {
            return ActivationState.ACTIVE_HEARTBEAT
        }
        return ActivationState.INACTIVE
    }

    // 保留向后兼容
    @Keep
    open fun isModuleActive(): Boolean = getActivationState().isActive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StatsManager.init(this)
        currentConfig = StatsProvider.loadConfig(this)

        initViews()
        initSettings()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        updateStatusBadge()
        refreshStats()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 2000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun updateStatusBadge() {
        val state = getActivationState()
        when (state) {
            ActivationState.ACTIVE_EFFECTIVE -> {
                binding.tvStatusBadge.text = getString(R.string.status_badge_in_effect)
                binding.tvStatusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_green))
                binding.tvStatusBadge.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_green_bg))
            }
            ActivationState.ACTIVE_HEARTBEAT -> {
                binding.tvStatusBadge.text = getString(R.string.status_badge_active)
                binding.tvStatusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_green))
                binding.tvStatusBadge.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_green_bg))
            }
            ActivationState.INACTIVE -> {
                binding.tvStatusBadge.text = getString(R.string.status_badge_inactive)
                binding.tvStatusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatusBadge.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_red_bg))
            }
        }
    }

    private fun showStatusDialog() {
        val state = getActivationState()
        val snapshot = StatsManager.getSnapshot()
        val hookReport = io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker.formatReport()
        val isServiceBound = XposedServiceProvider.isServiceBound

        val message = StringBuilder()
        when (state) {
            ActivationState.ACTIVE_EFFECTIVE -> {
                message.appendLine("状态：已生效 (运行正常)")
                message.appendLine("已成功拦截并加速视频播放，累计重写 ${snapshot.totalRewrites} 次。")
            }
            ActivationState.ACTIVE_HEARTBEAT -> {
                message.appendLine("状态：已激活 (等待流量)")
                if (isServiceBound) {
                    message.appendLine("LSPosed 框架服务已成功连接。")
                } else {
                    message.appendLine("目标应用（B站）已成功挂载并向本模块握手。")
                }
                message.appendLine("播放任意 B 站视频即可开始加速并生成重写记录。")
            }
            ActivationState.INACTIVE -> {
                message.appendLine("状态：未激活")
                message.appendLine("排查指引：")
                message.appendLine("1. 打开 LSPosed 管理器，确认本模块开关已开启；")
                message.appendLine("2. 在模块作用域中勾选“哔哩哔哩”（无需且无法勾选模块自身）；")
                message.appendLine("3. 强行停止哔哩哔哩后重新打开，播放视频测试。")
            }
        }

        message.appendLine()
        message.appendLine("【框架与运行时状态】")
        message.appendLine("框架 Service 绑定: ${if (isServiceBound) "已连接" else "未连接"}")
        val lastHb = snapshot.lastHeartbeatTimestamp
        val lastHbDesc = if (lastHb > 0L) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(lastHb), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } else {
            "无记录"
        }
        message.appendLine("最近心跳回执: $lastHbDesc")
        message.appendLine()
        message.appendLine("【Hook 挂载状态】")
        message.append(hookReport)

        AlertDialog.Builder(this)
            .setTitle(R.string.title_status_detail)
            .setMessage(message.toString())
            .setPositiveButton(R.string.dialog_confirm, null)
            .show()
    }

    private fun initViews() {
        // Module Status Badge
        updateStatusBadge()
        binding.tvStatusBadge.setOnClickListener {
            showStatusDialog()
        }

        // RecyclerView
        logAdapter = LogAdapter(emptyList())
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter

        // Clear Stats Button
        binding.btnClearStats.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_title)
                .setMessage(R.string.dialog_clear_message)
                .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                    StatsManager.clearStats(this)
                    refreshStats()
                    Toast.makeText(this, R.string.toast_stats_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        // Export Diagnostic Report Button
        binding.btnExportDiag.setOnClickListener {
            val appVersion = try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                "${pInfo.versionName} (${pInfo.versionCode})"
            } catch (_: Exception) {
                "Unknown"
            }
            val report = StatsManager.buildDiagnosticReport(currentConfig, appVersion)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BiliAccelerator Diagnostic", report))
            Toast.makeText(this, R.string.toast_diag_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun initSettings() {
        binding.switchEnabled.isChecked = currentConfig.enabled
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(enabled = isChecked)
            saveCurrentConfig()
        }

        val uposEntries = resources.getStringArray(R.array.upos_entries)
        val uposValues = resources.getStringArray(R.array.upos_values)

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, uposEntries)
        binding.spinnerUpos.adapter = spinnerAdapter

        // 防止 targetHost 不在列表时（历史遗留配置）造成 Spinner 显示第一项但配置仍为旧值的假象
        val rawIndex = uposValues.indexOf(currentConfig.targetHost)
        val selectedIndex = if (rawIndex >= 0) {
            rawIndex
        } else {
            currentConfig = currentConfig.copy(targetHost = uposValues[0])
            saveCurrentConfig()
            0
        }
        binding.spinnerUpos.setSelection(selectedIndex)

        binding.spinnerUpos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedHost = uposValues[position]
                if (selectedHost != currentConfig.targetHost) {
                    currentConfig = currentConfig.copy(targetHost = selectedHost)
                    saveCurrentConfig()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.switchBlockPcdn.isChecked = currentConfig.blockPcdn
        binding.switchBlockPcdn.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(blockPcdn = isChecked)
            saveCurrentConfig()
        }

        binding.switchProxyMcdn.isChecked = currentConfig.proxyMcdn
        binding.switchProxyMcdn.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(proxyMcdn = isChecked)
            saveCurrentConfig()
        }

        binding.switchForceUpos.isChecked = currentConfig.forceUpos
        binding.switchForceUpos.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(forceUpos = isChecked)
            saveCurrentConfig()
        }

        binding.switchPortHeuristic.isChecked = currentConfig.portHeuristic
        binding.switchPortHeuristic.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(portHeuristic = isChecked)
            saveCurrentConfig()
        }
    }

    private fun saveCurrentConfig() {
        StatsProvider.saveConfig(this, currentConfig)
    }

    private fun refreshStats() {
        updateStatusBadge()
        val snapshot = StatsManager.getSnapshot()
        binding.tvStatTotalRewrites.text = snapshot.totalRewrites.toString()
        binding.tvStatPcdnBlocked.text = snapshot.pcdnBlocked.toString()
        binding.tvStatMcdnProxied.text = snapshot.mcdnProxied.toString()
        binding.tvStatAvoidedHosts.text = snapshot.avoidedHosts.size.toString()

        if (snapshot.recentLogs.isEmpty()) {
            binding.tvEmptyLogs.visibility = View.VISIBLE
            binding.rvLogs.visibility = View.GONE
        } else {
            binding.tvEmptyLogs.visibility = View.GONE
            binding.rvLogs.visibility = View.VISIBLE
            logAdapter.updateLogs(snapshot.recentLogs)
        }
    }
}
