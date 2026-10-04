package com.realzza.biliaccelerator.ui

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
import com.realzza.biliaccelerator.R
import com.realzza.biliaccelerator.core.AcceleratorConfig
import com.realzza.biliaccelerator.core.StatsManager
import com.realzza.biliaccelerator.databinding.ActivityMainBinding
import com.realzza.biliaccelerator.provider.StatsProvider

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

    // Hooked by HookEntry to return true when active in LSPosed
    @Keep
    open fun isModuleActive(): Boolean = false

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
        refreshStats()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 2000L)
        pingTargetAppsForImplicitAccess()
    }

    private fun pingTargetAppsForImplicitAccess() {
        for (pkg in com.realzza.biliaccelerator.core.BiliAcceleratorCore.TARGET_PACKAGES) {
            try {
                val pingIntent = android.content.Intent("com.realzza.biliaccelerator.ACTION_PING").apply {
                    setPackage(pkg)
                }
                sendBroadcast(pingIntent)
            } catch (_: Throwable) {}
        }
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun initViews() {
        // Module Status Badge
        val active = isModuleActive()
        if (active) {
            binding.tvStatusBadge.text = getString(R.string.status_active)
            binding.tvStatusBadge.setTextColor(Color.parseColor("#2ECC71"))
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#202ECC71"))
        } else {
            binding.tvStatusBadge.text = getString(R.string.status_badge_inactive)
            binding.tvStatusBadge.setTextColor(Color.parseColor("#E74C3C"))
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#20E74C3C"))
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
