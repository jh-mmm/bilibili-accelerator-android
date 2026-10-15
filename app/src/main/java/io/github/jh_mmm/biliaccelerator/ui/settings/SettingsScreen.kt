package io.github.jh_mmm.biliaccelerator.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.jh_mmm.biliaccelerator.ui.theme.isAcceleratorDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.ui.components.AcceleratorPage
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceActionItem
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceDivider
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceDropdownItem
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceGroupCard
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceGroupTitle
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceSwitchItem
import io.github.jh_mmm.biliaccelerator.ui.theme.TagPcdn
import io.github.jh_mmm.biliaccelerator.ui.theme.UiPreferences
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiThemeMode

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: AcceleratorConfig,
    onConfigChange: (AcceleratorConfig) -> Unit,
    uiPreferences: UiPreferences,
    onUiPreferencesChange: (UiPreferences) -> Unit,
    onClearStats: () -> Unit,
    onExportDiagnostic: () -> Unit,
    uposEntries: List<String>,
    uposValues: List<String>,
    bottomContentPadding: Dp = 80.dp
) {
    var showUposDialog by rememberSaveable { mutableStateOf(false) }
    var showSpeedTestDialog by rememberSaveable { mutableStateOf(false) }
    var showProxyHostDialog by rememberSaveable { mutableStateOf(false) }
    var showStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeModeDialog by rememberSaveable { mutableStateOf(false) }
    var showConfirmClearDialog by rememberSaveable { mutableStateOf(false) }

    // Dialog 1: Select UPOS
    if (showUposDialog) {
        SingleChoiceDialog(
            title = "首选 UPOS 调度镜像",
            items = uposEntries,
            selectedIndex = uposValues.indexOf(config.targetHost).coerceAtLeast(0),
            onSelect = { index ->
                val newHost = uposValues[index]
                onConfigChange(config.copy(targetHost = newHost))
                showUposDialog = false
            },
            onDismiss = { showUposDialog = false }
        )
    }

    // Dialog 1.2: UPOS Speed Test
    if (showSpeedTestDialog) {
        UposSpeedTestDialog(
            uposEntries = uposEntries,
            uposValues = uposValues,
            currentTargetHost = config.targetHost,
            onSelectTargetHost = { newHost ->
                onConfigChange(config.copy(targetHost = newHost))
                showSpeedTestDialog = false
            },
            onDismiss = { showSpeedTestDialog = false }
        )
    }

    // Dialog 1.5: Select MCDN Proxy Host
    if (showProxyHostDialog) {
        ProxyHostDialog(
            currentHost = config.proxyHost,
            onSelect = { newHost ->
                onConfigChange(config.copy(proxyHost = newHost))
                showProxyHostDialog = false
            },
            onDismiss = { showProxyHostDialog = false }
        )
    }

    // Dialog 2: Select UI Style
    if (showStyleDialog) {
        val styles = UiStyle.values()
        SingleChoiceDialog(
            title = "界面渲染风格",
            items = styles.map { it.label },
            selectedIndex = styles.indexOf(uiPreferences.style),
            onSelect = { index ->
                onUiPreferencesChange(uiPreferences.copy(style = styles[index]))
                showStyleDialog = false
            },
            onDismiss = { showStyleDialog = false }
        )
    }

    // Dialog 3: Select Theme Mode
    if (showThemeModeDialog) {
        val modes = UiThemeMode.values()
        SingleChoiceDialog(
            title = "主题外观模式",
            items = modes.map { it.label },
            selectedIndex = modes.indexOf(uiPreferences.themeMode),
            onSelect = { index ->
                onUiPreferencesChange(uiPreferences.copy(themeMode = modes[index]))
                showThemeModeDialog = false
            },
            onDismiss = { showThemeModeDialog = false }
        )
    }

    // Dialog 4: Confirm Clear
    if (showConfirmClearDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmClearDialog = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空当前的拦截与重定向统计数据吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearStats()
                    showConfirmClearDialog = false
                }) {
                    Text("清空", color = TagPcdn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearDialog = false }) {
                    Text("取消")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    AcceleratorPage(title = "设置") { pagePadding, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Group 1: 核心路由与拦截策略
            item(key = "header-core") {
                PreferenceGroupTitle("线路与加速策略")
            }
            item(key = "card-core") {
                PreferenceGroupCard {
                    PreferenceSwitchItem(
                        title = "启用视频加速与防卡顿",
                        summary = "控制全局 Hook 重定向与加速核心",
                        checked = config.enabled,
                        onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                        icon = Icons.Outlined.Route
                    )

                    PreferenceDivider()

                    val currentUposLabel = io.github.jh_mmm.biliaccelerator.ui.dashboard.resolveUposDisplayName(
                        targetHost = config.targetHost,
                        uposEntries = uposEntries,
                        uposValues = uposValues
                    )

                    PreferenceDropdownItem(
                        title = "首选 UPOS 镜像",
                        summary = config.targetHost,
                        selectedValue = currentUposLabel,
                        onClick = { showUposDialog = true }
                    )

                    PreferenceDivider()

                    PreferenceActionItem(
                        title = "测试 UPOS 镜像速度",
                        summary = "测试各镜像节点延迟并推荐最佳线路",
                        icon = Icons.Outlined.Speed,
                        onClick = { showSpeedTestDialog = true }
                    )

                    PreferenceDivider()

                    PreferenceSwitchItem(
                        title = "全局强制统一 UPOS 线路",
                        summary = "忽略服务端下发调度，强行覆盖全部视频流",
                        checked = config.forceUpos,
                        onCheckedChange = { onConfigChange(config.copy(forceUpos = it)) },
                        enabled = config.enabled
                    )
                }
            }

            // Group 2: PCDN 与网络过滤
            item(key = "header-network") {
                PreferenceGroupTitle("防卡顿与 PCDN 过滤")
            }
            item(key = "card-network") {
                PreferenceGroupCard {
                    PreferenceSwitchItem(
                        title = "拦截 PCDN 调度节点",
                        summary = "阻断低质 P2P/CDN 调度，回退至官方高质量骨干网",
                        checked = config.blockPcdn,
                        onCheckedChange = { onConfigChange(config.copy(blockPcdn = it)) },
                        icon = Icons.Outlined.Security,
                        enabled = config.enabled
                    )

                    PreferenceDivider()

                    PreferenceSwitchItem(
                        title = "MCDN 官方代理中继",
                        summary = "将边缘小运营商节点中继至官方高速 CDN 代理",
                        checked = config.proxyMcdn,
                        onCheckedChange = { onConfigChange(config.copy(proxyMcdn = it)) },
                        enabled = config.enabled
                    )

                    PreferenceDivider()

                    val currentProxyLabel = when (config.proxyHost) {
                        "proxy-tf-all-ws.bilivideo.com" -> "网宿"
                        "proxy-tf-all-tx.bilivideo.com" -> "腾讯云"
                        "proxy-tf-all-hw.bilivideo.com" -> "华为云"
                        else -> config.proxyHost.substringBefore('.').removePrefix("proxy-")
                    }

                    PreferenceDropdownItem(
                        title = "MCDN 中继代理节点",
                        summary = "当前中继: ${config.proxyHost}",
                        selectedValue = currentProxyLabel,
                        onClick = { showProxyHostDialog = true },
                        enabled = config.enabled && config.proxyMcdn
                    )

                    PreferenceDivider()

                    PreferenceSwitchItem(
                        title = "端口特征启发式拦截",
                        summary = "将非标准 HTTP/HTTPS 端口视为 PCDN 拦截",
                        checked = config.portHeuristic,
                        onCheckedChange = { onConfigChange(config.copy(portHeuristic = it)) },
                        enabled = config.enabled
                    )

                    PreferenceDivider()

                    PreferenceSwitchItem(
                        title = "实验性：Moss gRPC TF 标记注入",
                        summary = "尝试在协议层注入 TF=1 请求官方镜像（默认关闭，仅在确认兼容时开启）",
                        checked = config.enableMossHook,
                        onCheckedChange = { onConfigChange(config.copy(enableMossHook = it)) },
                        enabled = config.enabled && config.blockPcdn
                    )
                }
            }

            // Group 3: 数据统计与诊断
            item(key = "header-diag") {
                PreferenceGroupTitle("数据与诊断")
            }
            item(key = "card-diag") {
                PreferenceGroupCard {
                    PreferenceActionItem(
                        title = "复制诊断报告",
                        summary = "一键导出系统环境、配置与 Hook 挂载报告",
                        icon = Icons.Outlined.ContentCopy,
                        onClick = onExportDiagnostic
                    )

                    PreferenceDivider()

                    PreferenceActionItem(
                        title = "清空统计数据",
                        summary = "重置总重定向、拦截及已规避节点记录",
                        icon = Icons.Outlined.Delete,
                        tintColor = TagPcdn,
                        onClick = { showConfirmClearDialog = true }
                    )
                }
            }

            // Group 4: 界面风格与外观
            item(key = "header-ui") {
                PreferenceGroupTitle("界面与外观")
            }
            item(key = "card-ui") {
                PreferenceGroupCard {
                    PreferenceDropdownItem(
                        title = "界面渲染风格",
                        summary = "支持 MIUIX 与 Material 3 实时切换",
                        selectedValue = uiPreferences.style.label,
                        icon = Icons.Outlined.Palette,
                        onClick = { showStyleDialog = true }
                    )

                    PreferenceDivider()

                    PreferenceDropdownItem(
                        title = "主题外观模式",
                        summary = "选择浅色、深色或随系统自动切换",
                        selectedValue = uiPreferences.themeMode.label,
                        onClick = { showThemeModeDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEachIndexed { index, text ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (index == selectedIndex),
                                onClick = { onSelect(index) }
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (index == selectedIndex),
                            onClick = { onSelect(index) }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = text,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ProxyHostDialog(
    currentHost: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(
        "proxy-tf-all-ws.bilivideo.com" to "网宿 CDN (默认)",
        "proxy-tf-all-tx.bilivideo.com" to "腾讯云 CDN",
        "proxy-tf-all-hw.bilivideo.com" to "华为云 CDN"
    )
    val presetIndex = presets.indexOfFirst { it.first == currentHost }
    val isPreset = presetIndex >= 0
    var selectedPresetIndex by rememberSaveable {
        mutableStateOf(if (isPreset) presetIndex else presets.size)
    }
    var customHost by rememberSaveable {
        mutableStateOf(if (isPreset) "" else currentHost)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCDN 中继代理节点") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                presets.forEachIndexed { index, (host, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (selectedPresetIndex == index),
                                onClick = { selectedPresetIndex = index }
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedPresetIndex == index),
                            onClick = { selectedPresetIndex = index }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = label, fontSize = 14.sp)
                            Text(
                                text = host,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (selectedPresetIndex == presets.size),
                            onClick = { selectedPresetIndex = presets.size }
                        )
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedPresetIndex == presets.size),
                        onClick = { selectedPresetIndex = presets.size }
                    )
                    Text(
                        text = "自定义中继域名",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                if (selectedPresetIndex == presets.size) {
                    OutlinedTextField(
                        value = customHost,
                        onValueChange = { customHost = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        placeholder = { Text("例: proxy-tf-all-ws.bilivideo.com", fontSize = 13.sp) },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosenHost = if (selectedPresetIndex in presets.indices) {
                    presets[selectedPresetIndex].first
                } else {
                    val cleaned = BiliAcceleratorCore.cleanHost(customHost).substringBefore(':')
                    cleaned.ifBlank { "proxy-tf-all-ws.bilivideo.com" }
                }
                onSelect(chosenHost)
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

data class UposTestState(
    val host: String,
    val latencyMs: Long? = null,
    val isTesting: Boolean = false,
    val error: String? = null
)

@Composable
private fun UposSpeedTestDialog(
    uposEntries: List<String>,
    uposValues: List<String>,
    currentTargetHost: String,
    onSelectTargetHost: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isTestingAll by remember { mutableStateOf(false) }
    var testStates by remember {
        mutableStateOf(uposValues.associateWith { host ->
            UposTestState(host = host, isTesting = false)
        })
    }

    fun startSpeedTest() {
        if (isTestingAll) return
        isTestingAll = true
        testStates = uposValues.associateWith { host ->
            UposTestState(host = host, isTesting = true)
        }
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                uposValues.map { host ->
                    async {
                        val result = runCatching {
                            val t0 = System.currentTimeMillis()
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(host, 443), 2500)
                            }
                            val cost = System.currentTimeMillis() - t0
                            UposTestState(host = host, latencyMs = cost, isTesting = false)
                        }.getOrElse {
                            UposTestState(host = host, latencyMs = null, isTesting = false, error = "超时/失败")
                        }
                        withContext(Dispatchers.Main) {
                            testStates = testStates + (host to result)
                        }
                    }
                }.awaitAll()
            }
            isTestingAll = false
        }
    }

    LaunchedEffect(Unit) {
        startSpeedTest()
    }

    val dark = isAcceleratorDarkTheme()

    val sortedHosts = remember(testStates, isTestingAll) {
        if (isTestingAll && testStates.values.all { it.isTesting }) {
            uposValues
        } else {
            uposValues.sortedWith { a, b ->
                val latA = testStates[a]?.latencyMs ?: Long.MAX_VALUE
                val latB = testStates[b]?.latencyMs ?: Long.MAX_VALUE
                latA.compareTo(latB)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "UPOS 镜像测速", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (isTestingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (isTestingAll) "正在并发探测各节点连接时延 (TTFB)..." else "测速完成。点击任意镜像线路可直接切换应用：",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                sortedHosts.forEach { host ->
                    val state = testStates[host] ?: UposTestState(host = host)
                    val isCurrent = host.equals(currentTargetHost, ignoreCase = true)
                    val displayName = io.github.jh_mmm.biliaccelerator.ui.dashboard.resolveUposDisplayName(
                        targetHost = host,
                        uposEntries = uposEntries,
                        uposValues = uposValues
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSelectTargetHost(host)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = displayName,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isCurrent) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "当前使用",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = host,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        if (state.isTesting) {
                            Text(
                                text = "测试中…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (state.latencyMs != null) {
                            val latency = state.latencyMs
                            val (badgeBg, badgeTextColor) = when {
                                latency < 80L -> (if (dark) Color(0xFF143320) else Color(0xFFE8F8F0)) to Color(0xFF2ECC71)
                                latency < 200L -> (if (dark) Color(0xFF112938) else Color(0xFFE3F2FD)) to Color(0xFF00AEEC)
                                latency < 400L -> (if (dark) Color(0xFF382E11) else Color(0xFFFFF8E1)) to Color(0xFFF39C12)
                                else -> (if (dark) Color(0xFF381818) else Color(0xFFFFEBEE)) to Color(0xFFE74C3C)
                            }

                            Text(
                                text = "${latency} ms",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeTextColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        } else {
                            Text(
                                text = state.error ?: "超时",
                                fontSize = 11.sp,
                                color = Color(0xFFE74C3C),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (dark) Color(0xFF381818) else Color(0xFFFFEBEE))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { startSpeedTest() },
                enabled = !isTestingAll
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重新测速")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

