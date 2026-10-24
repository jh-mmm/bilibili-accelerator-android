package io.github.jh_mmm.biliaccelerator.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Bolt
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jh_mmm.biliaccelerator.R
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.ui.components.AcceleratorPage
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceActionItem
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceDivider
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceDropdownItem
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceGroupCard
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceGroupTitle
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceSliderItem
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceSwitchItem
import io.github.jh_mmm.biliaccelerator.ui.theme.TagPcdn
import io.github.jh_mmm.biliaccelerator.ui.theme.UiPreferences
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiThemeMode
import io.github.jh_mmm.biliaccelerator.ui.theme.isAcceleratorDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

enum class SettingsSubPage {
    MAIN,
    MCDN,
    BTR
}

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
    var currentSubPage by rememberSaveable { mutableStateOf(SettingsSubPage.MAIN) }

    var showUposDialog by rememberSaveable { mutableStateOf(false) }
    var showSpeedTestDialog by rememberSaveable { mutableStateOf(false) }
    var showProxyHostDialog by rememberSaveable { mutableStateOf(false) }
    var showMcdnStrategyDialog by rememberSaveable { mutableStateOf(false) }
    var showStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeModeDialog by rememberSaveable { mutableStateOf(false) }
    var showConfirmClearDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = currentSubPage != SettingsSubPage.MAIN) {
        currentSubPage = SettingsSubPage.MAIN
    }

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

    // Dialog 1.6: Select MCDN Strategy
    if (showMcdnStrategyDialog) {
        McdnStrategyDialog(
            currentStrategy = config.mcdnStrategy,
            onSelect = { newStrategy ->
                onConfigChange(config.copy(mcdnStrategy = newStrategy))
                showMcdnStrategyDialog = false
            },
            onDismiss = { showMcdnStrategyDialog = false }
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
            title = { Text(stringResource(R.string.dialog_clear_title)) },
            text = { Text(stringResource(R.string.dialog_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearStats()
                    showConfirmClearDialog = false
                }) {
                    Text(stringResource(R.string.dialog_confirm), color = TagPcdn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    Crossfade(
        targetState = currentSubPage,
        label = "settings_subpage_crossfade"
    ) { subPage ->
        when (subPage) {
            SettingsSubPage.MAIN -> {
                AcceleratorPage(title = stringResource(R.string.title_settings)) { pagePadding, scrollBehavior ->
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
                            PreferenceGroupTitle(stringResource(R.string.group_core_routing))
                        }
                        item(key = "card-core") {
                            PreferenceGroupCard {
                                PreferenceSwitchItem(
                                    title = stringResource(R.string.label_module_enabled),
                                    summary = stringResource(R.string.summary_module_enabled),
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
                                    title = stringResource(R.string.label_target_host),
                                    summary = config.targetHost,
                                    selectedValue = currentUposLabel,
                                    onClick = { showUposDialog = true }
                                )

                                PreferenceDivider()

                                PreferenceActionItem(
                                    title = stringResource(R.string.label_speed_test),
                                    summary = stringResource(R.string.summary_speed_test),
                                    icon = Icons.Outlined.Speed,
                                    onClick = { showSpeedTestDialog = true }
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = stringResource(R.string.label_force_upos),
                                    summary = stringResource(R.string.summary_force_upos),
                                    checked = config.forceUpos,
                                    onCheckedChange = { onConfigChange(config.copy(forceUpos = it)) },
                                    enabled = config.enabled
                                )
                            }
                        }

                        // Group 2: PCDN 与流媒体优化
                        item(key = "header-network") {
                            PreferenceGroupTitle(stringResource(R.string.group_pcdn_network))
                        }
                        item(key = "card-network") {
                            PreferenceGroupCard {
                                PreferenceSwitchItem(
                                    title = stringResource(R.string.label_enable_pcdn_block),
                                    summary = stringResource(R.string.summary_enable_pcdn_block),
                                    checked = config.blockPcdn,
                                    onCheckedChange = { onConfigChange(config.copy(blockPcdn = it)) },
                                    icon = Icons.Outlined.Security,
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                // 1. MCDN 合并选项 -> 二级页面
                                val currentProxyLabel = when (config.proxyHost) {
                                    "proxy-tf-all-ws.bilivideo.com" -> "网宿"
                                    "proxy-tf-all-tx.bilivideo.com" -> "腾讯云"
                                    "proxy-tf-all-hw.bilivideo.com" -> "华为云"
                                    else -> config.proxyHost.substringBefore('.').removePrefix("proxy-")
                                }
                                val currentMcdnStrategyLabel = when (config.mcdnStrategy.lowercase()) {
                                    "proxy-all" -> "中继全部"
                                    "proxy-v1" -> "仅中继资源"
                                    "replace" -> "替换为UPOS"
                                    else -> config.mcdnStrategy
                                }
                                val mcdnSummary = if (!config.proxyMcdn) {
                                    "已停用 MCDN 中继代理 (点击进入策略配置)"
                                } else {
                                    "已启用 · $currentProxyLabel · $currentMcdnStrategyLabel (点击进入策略配置)"
                                }

                                PreferenceDropdownItem(
                                    title = "MCDN 策略配置",
                                    summary = mcdnSummary,
                                    selectedValue = if (config.proxyMcdn) "已开启" else "已停用",
                                    icon = Icons.Outlined.AltRoute,
                                    onClick = { currentSubPage = SettingsSubPage.MCDN },
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                // 2. BTR 合并选项 -> 二级页面
                                val btrThreadDesc = if (config.btrAutoConcurrency) "自适应 ${config.btrConcurrency} 线程" else "${config.btrConcurrency} 线程"
                                val btrChunkDesc = if (config.btrAutoChunkSize) "自适应 ${config.btrChunkSizeKb} KB" else "${config.btrChunkSizeKb} KB"
                                val btrSummary = if (!config.enableBtr) {
                                    "已停用多线程切片加速 (点击进入高级配置)"
                                } else {
                                    "已启用 · $btrThreadDesc · $btrChunkDesc · ${config.btrCdnPoolSize} 节点"
                                }

                                PreferenceDropdownItem(
                                    title = "BTR 并发切片加速",
                                    summary = btrSummary,
                                    selectedValue = if (config.enableBtr) "已开启" else "已停用",
                                    icon = Icons.Outlined.Bolt,
                                    onClick = { currentSubPage = SettingsSubPage.BTR },
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = "改写 Akamai 官方节点",
                                    summary = "默认放行官方 Akamai 线路；开启后将其重定向至首选 UPOS 镜像（适合当地 Akamai 连接较慢的情况）",
                                    checked = config.rewriteAkamai,
                                    onCheckedChange = { onConfigChange(config.copy(rewriteAkamai = it)) },
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = stringResource(R.string.label_port_heuristic),
                                    summary = stringResource(R.string.summary_port_heuristic),
                                    checked = config.portHeuristic,
                                    onCheckedChange = { onConfigChange(config.copy(portHeuristic = it)) },
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = stringResource(R.string.label_enable_moss),
                                    summary = stringResource(R.string.summary_enable_moss),
                                    checked = config.enableMossHook,
                                    onCheckedChange = { onConfigChange(config.copy(enableMossHook = it)) },
                                    enabled = config.enabled && config.blockPcdn
                                )
                            }
                        }

                        // Group 3: 数据统计与诊断
                        item(key = "header-diag") {
                            PreferenceGroupTitle(stringResource(R.string.group_data_diag))
                        }
                        item(key = "card-diag") {
                            PreferenceGroupCard {
                                PreferenceActionItem(
                                    title = stringResource(R.string.btn_export_diag),
                                    summary = stringResource(R.string.summary_export_diag),
                                    icon = Icons.Outlined.ContentCopy,
                                    onClick = onExportDiagnostic
                                )

                                PreferenceDivider()

                                PreferenceActionItem(
                                    title = stringResource(R.string.btn_clear_stats),
                                    summary = stringResource(R.string.summary_clear_stats),
                                    icon = Icons.Outlined.Delete,
                                    tintColor = TagPcdn,
                                    onClick = { showConfirmClearDialog = true }
                                )
                            }
                        }

                        // Group 4: 界面风格与外观
                        item(key = "header-ui") {
                            PreferenceGroupTitle(stringResource(R.string.group_ui_appearance))
                        }
                        item(key = "card-ui") {
                            PreferenceGroupCard {
                                PreferenceDropdownItem(
                                    title = stringResource(R.string.label_ui_style),
                                    summary = stringResource(R.string.summary_ui_style),
                                    selectedValue = uiPreferences.style.label,
                                    icon = Icons.Outlined.Palette,
                                    onClick = { showStyleDialog = true }
                                )

                                PreferenceDivider()

                                PreferenceDropdownItem(
                                    title = stringResource(R.string.label_theme_mode),
                                    summary = stringResource(R.string.summary_theme_mode),
                                    selectedValue = uiPreferences.themeMode.label,
                                    onClick = { showThemeModeDialog = true }
                                )
                            }
                        }
                    }
                }
            }

            SettingsSubPage.MCDN -> {
                AcceleratorPage(
                    title = "MCDN 策略配置",
                    navigationIcon = {
                        IconButton(onClick = { currentSubPage = SettingsSubPage.MAIN }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回设置"
                            )
                        }
                    }
                ) { pagePadding, scrollBehavior ->
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
                        item(key = "header-mcdn-relay") {
                            PreferenceGroupTitle("MCDN 边缘节点中继与调度")
                        }
                        item(key = "card-mcdn-relay") {
                            PreferenceGroupCard {
                                PreferenceSwitchItem(
                                    title = stringResource(R.string.label_enable_mcdn_proxy),
                                    summary = stringResource(R.string.summary_enable_mcdn_proxy),
                                    checked = config.proxyMcdn,
                                    onCheckedChange = { onConfigChange(config.copy(proxyMcdn = it)) },
                                    icon = Icons.Outlined.AltRoute,
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
                                    title = stringResource(R.string.label_proxy_host),
                                    summary = "当前中继: ${config.proxyHost}",
                                    selectedValue = currentProxyLabel,
                                    onClick = { showProxyHostDialog = true },
                                    enabled = config.enabled && config.proxyMcdn
                                )

                                PreferenceDivider()

                                val currentMcdnStrategyLabel = when (config.mcdnStrategy.lowercase()) {
                                    "proxy-all" -> "中继全部 (proxy-all)"
                                    "proxy-v1" -> "仅中继资源 (/v1/resource/)"
                                    "replace" -> "直接替换为 UPOS (replace)"
                                    else -> config.mcdnStrategy
                                }

                                PreferenceDropdownItem(
                                    title = "MCDN 调度策略",
                                    summary = "当前策略: $currentMcdnStrategyLabel",
                                    selectedValue = currentMcdnStrategyLabel.substringBefore(" ("),
                                    onClick = { showMcdnStrategyDialog = true },
                                    enabled = config.enabled && config.proxyMcdn
                                )
                            }
                        }
                    }
                }
            }

            SettingsSubPage.BTR -> {
                AcceleratorPage(
                    title = "BTR 并发切片加速",
                    navigationIcon = {
                        IconButton(onClick = { currentSubPage = SettingsSubPage.MAIN }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回设置"
                            )
                        }
                    }
                ) { pagePadding, scrollBehavior ->
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
                        item(key = "header-btr-main") {
                            PreferenceGroupTitle("BTR 多线程并发调度核心")
                        }
                        item(key = "card-btr-main") {
                            PreferenceGroupCard {
                                PreferenceSwitchItem(
                                    title = "BTR 多线程并发切片加速",
                                    summary = "通过本地回环代理并发拉取 Range 分片并执行多源 CDN 智能调度",
                                    checked = config.enableBtr,
                                    onCheckedChange = { onConfigChange(config.copy(enableBtr = it)) },
                                    icon = Icons.Outlined.Bolt,
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = "BTR 自动动态线程调节",
                                    summary = "智能感知播放缓冲状态，开播与Seek时高并发冲刺充填缓冲，稳定后回落以降低功耗",
                                    checked = config.btrAutoConcurrency,
                                    onCheckedChange = { onConfigChange(config.copy(btrAutoConcurrency = it)) },
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                // 1. BTR 并发线程数 - 滑块调节
                                val concurrencyDesc = when (config.btrConcurrency) {
                                    2 -> "2 线程"
                                    4 -> "4 线程"
                                    6 -> "6 线程"
                                    8 -> "8 线程"
                                    12 -> "12 线程"
                                    16 -> "16 线程"
                                    else -> "${config.btrConcurrency} 线程"
                                }
                                val concurrencySummary = if (config.btrAutoConcurrency) {
                                    "基准 / 冲刺上限: $concurrencyDesc (自动动态调节中)"
                                } else {
                                    "固定并发: $concurrencyDesc"
                                }

                                PreferenceSliderItem(
                                    title = "BTR 并发线程数",
                                    summary = concurrencySummary,
                                    value = config.btrConcurrency.coerceIn(2, 16).toFloat(),
                                    onValueChange = { newVal ->
                                        val rounded = newVal.roundToInt()
                                        if (rounded != config.btrConcurrency) {
                                            onConfigChange(config.copy(btrConcurrency = rounded))
                                        }
                                    },
                                    valueRange = 2f..16f,
                                    steps = 13,
                                    showKeyPoints = true,
                                    keyPoints = listOf(2f, 4f, 6f, 8f, 12f, 16f),
                                    valueLabel = "${config.btrConcurrency} 线程",
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                // 2. 并发 CDN 节点池 - 滑块调节
                                val poolSummary = when (config.btrCdnPoolSize) {
                                    1 -> "1 个节点 · 仅单源拉取，不进行多源并发调度"
                                    in 2..3 -> "${config.btrCdnPoolSize} 个节点 · 基础多源互备，低握手开销"
                                    in 4..6 -> "${config.btrCdnPoolSize} 个节点 · 骨干云厂商节点均衡并发"
                                    8 -> "8 个节点 · 推荐配置，官方多源全量并发拉取"
                                    in 9..11 -> "${config.btrCdnPoolSize} 个节点 · 深度竞速，含全国混流节点"
                                    else -> "12 个节点 · 全量白名单池跨源分片抢占"
                                }

                                PreferenceSliderItem(
                                    title = "并发 CDN 节点池",
                                    summary = poolSummary,
                                    value = config.btrCdnPoolSize.coerceIn(1, 12).toFloat(),
                                    onValueChange = { newVal ->
                                        val rounded = newVal.roundToInt()
                                        if (rounded != config.btrCdnPoolSize) {
                                            onConfigChange(config.copy(btrCdnPoolSize = rounded))
                                        }
                                    },
                                    valueRange = 1f..12f,
                                    steps = 10,
                                    showKeyPoints = true,
                                    keyPoints = listOf(1f, 2f, 4f, 6f, 8f, 12f),
                                    valueLabel = "${config.btrCdnPoolSize} 个节点",
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = "BTR 自适应切片尺寸",
                                    summary = "根据视频体积与清晰度在 256KB~2MB 动态匹配切片大小，兼顾秒开与高码率吞吐",
                                    checked = config.btrAutoChunkSize,
                                    onCheckedChange = { onConfigChange(config.copy(btrAutoChunkSize = it)) },
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                // 3. 基准分片大小 - 滑块调节
                                val chunkOptions = listOf(256, 512, 1024, 2048)
                                val currentChunkIndex = chunkOptions.indexOf(config.btrChunkSizeKb).let { if (it >= 0) it else 1 }
                                val chunkSizeDesc = when (config.btrChunkSizeKb) {
                                    256 -> "256 KB"
                                    512 -> "512 KB (标准均衡推荐)"
                                    1024 -> "1024 KB"
                                    2048 -> "2048 KB"
                                    else -> "${config.btrChunkSizeKb} KB"
                                }
                                val chunkSizeSummary = if (config.btrAutoChunkSize) {
                                    "基准: $chunkSizeDesc (自适应动态调节中)"
                                } else {
                                    "固定分片大小: $chunkSizeDesc"
                                }

                                PreferenceSliderItem(
                                    title = "基准分片大小",
                                    summary = chunkSizeSummary,
                                    value = currentChunkIndex.toFloat(),
                                    onValueChange = { newIdx ->
                                        val idx = newIdx.roundToInt().coerceIn(0, chunkOptions.lastIndex)
                                        val newChunkSize = chunkOptions[idx]
                                        if (newChunkSize != config.btrChunkSizeKb) {
                                            onConfigChange(config.copy(btrChunkSizeKb = newChunkSize))
                                        }
                                    },
                                    valueRange = 0f..3f,
                                    steps = 2,
                                    showKeyPoints = true,
                                    keyPoints = listOf(0f, 1f, 2f, 3f),
                                    valueLabel = "${config.btrChunkSizeKb} KB",
                                    enabled = config.enabled && config.enableBtr
                                )
                            }
                        }
                    }
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
    var testJob by remember { mutableStateOf<Job?>(null) }
    var testStates by remember {
        mutableStateOf(uposValues.associateWith { host ->
            UposTestState(host = host, isTesting = false)
        })
    }

    fun probeUposHostLatencyMs(host: String): Long {
        val t0 = System.currentTimeMillis()
        val conn = (URL("https://$host/crossdomain.xml").openConnection() as HttpsURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 2500
            readTimeout = 2500
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 BiliDroid/8.0.0 (bbcallen@gmail.com)")
            setRequestProperty("Connection", "close")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..499) {
                throw IllegalStateException("HTTP $code")
            }
            return (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun startSpeedTest() {
        if (isTestingAll) return
        testJob?.cancel()
        isTestingAll = true
        testStates = uposValues.associateWith { host ->
            UposTestState(host = host, isTesting = true)
        }
        testJob = coroutineScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    uposValues.map { host ->
                        async {
                            try {
                                val lat = withTimeout(3000L) {
                                    probeUposHostLatencyMs(host)
                                }
                                host to UposTestState(host = host, latencyMs = lat, isTesting = false)
                            } catch (e: Exception) {
                                val err = if (e is kotlinx.coroutines.TimeoutCancellationException) "超时" else (e.message ?: "失败")
                                host to UposTestState(host = host, latencyMs = null, isTesting = false, error = err)
                            }
                        }
                    }.awaitAll()
                }
                testStates = results.toMap()
            } finally {
                isTestingAll = false
            }
        }
    }

    LaunchedEffect(Unit) {
        startSpeedTest()
    }

    val sortedHosts = remember(testStates, uposValues) {
        uposValues.sortedWith(
            compareBy<String> { host ->
                val state = testStates[host]
                when {
                    state?.isTesting == true -> 1
                    state?.latencyMs != null -> 0
                    else -> 2
                }
            }.thenBy { host ->
                testStates[host]?.latencyMs ?: Long.MAX_VALUE
            }
        )
    }

    val dark = isAcceleratorDarkTheme()

    AlertDialog(
        onDismissRequest = {
            testJob?.cancel()
            onDismiss()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.speed_test_dialog_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    text = if (isTestingAll) stringResource(R.string.speed_test_testing_desc) else stringResource(R.string.speed_test_done_desc),
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
                                        text = stringResource(R.string.speed_test_current),
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
                                text = stringResource(R.string.speed_test_testing),
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
                                text = state.error ?: stringResource(R.string.speed_test_timeout),
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
                    Text(stringResource(R.string.speed_test_retest))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun McdnStrategyDialog(
    currentStrategy: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strategies = listOf(
        "proxy-all" to ("中继代理全部 (默认推荐)" to "通过云厂商官方 CDN 中继所有 MCDN 节点"),
        "proxy-v1" to ("仅中继资源接口 (/v1/resource/)" to "仅对资源接口走中继代理，常规视频流直接转为主 UPOS 镜像"),
        "replace" to ("直接替换为 UPOS 镜像" to "不经中继代理，全量重定向至首选 UPOS 镜像")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCDN 调度策略") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                strategies.forEach { (key, info) ->
                    val (label, desc) = info
                    val isSelected = key.equals(currentStrategy, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { onSelect(key) }
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(key) }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = desc,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
