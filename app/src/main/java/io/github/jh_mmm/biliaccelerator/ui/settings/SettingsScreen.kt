package io.github.jh_mmm.biliaccelerator.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.jh_mmm.biliaccelerator.ui.theme.BottomBarStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.LocalUiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.TagPcdn
import io.github.jh_mmm.biliaccelerator.ui.theme.UiPreferences
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiThemeMode
import io.github.jh_mmm.biliaccelerator.ui.theme.dialogCornerRadius
import io.github.jh_mmm.biliaccelerator.ui.theme.resolveUseFloatingBottomBar
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
    var showProxyHostDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomCdnDialog by rememberSaveable { mutableStateOf(false) }
    var showMcdnStrategyDialog by rememberSaveable { mutableStateOf(false) }
    var showStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showBottomBarStyleDialog by rememberSaveable { mutableStateOf(false) }
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

    if (showCustomCdnDialog) {
        CustomCdnPoolDialog(
            customPool = config.customCdnPool,
            onSave = { newPool ->
                onConfigChange(config.copy(customCdnPool = newPool))
                showCustomCdnDialog = false
            },
            onDismiss = { showCustomCdnDialog = false }
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

    // Dialog 2.5: Select Bottom Bar Style
    if (showBottomBarStyleDialog) {
        val bottomBarStyles = BottomBarStyle.values()
        SingleChoiceDialog(
            title = stringResource(R.string.label_bottom_bar_style),
            items = bottomBarStyles.map { it.label },
            selectedIndex = bottomBarStyles.indexOf(uiPreferences.bottomBarStyle),
            onSelect = { index ->
                onUiPreferencesChange(uiPreferences.copy(bottomBarStyle = bottomBarStyles[index]))
                showBottomBarStyleDialog = false
            },
            onDismiss = { showBottomBarStyleDialog = false }
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
            shape = RoundedCornerShape(LocalUiStyle.current.dialogCornerRadius())
        )
    }

    Crossfade(
        targetState = currentSubPage,
        animationSpec = if (LocalUiStyle.current == UiStyle.MATERIAL3_EXPRESSIVE) {
            MaterialTheme.motionScheme.defaultEffectsSpec()
        } else {
            tween()
        },
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
                                    summary = if (config.enableBtr) {
                                        "${config.targetHost}（启用 BTR 时由分片真实吞吐动态择优，不以 RTT 测速排序）"
                                    } else {
                                        config.targetHost
                                    },
                                    selectedValue = currentUposLabel,
                                    onClick = { showUposDialog = true },
                                    enabled = config.enabled
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
                                val normalizedStrategy = BiliAcceleratorCore.normalizeMcdnStrategy(config.mcdnStrategy)
                                val currentMcdnStrategyLabel = when (normalizedStrategy) {
                                    "proxy-all" -> "中继全部"
                                    "proxy-v1" -> "仅中继资源"
                                    else -> normalizedStrategy
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
                                    icon = Icons.AutoMirrored.Outlined.AltRoute,
                                    onClick = { currentSubPage = SettingsSubPage.MCDN },
                                    enabled = config.enabled
                                )

                                PreferenceDivider()

                                // 2. BTR 合并选项 -> 二级页面
                                val btrThreadDesc = if (config.btrAutoConcurrency) "自适应 ${config.btrConcurrency} 线程" else "${config.btrConcurrency} 线程"
                                val btrChunkDesc = if (config.btrAutoChunkSize) "自适应 ${config.btrChunkSizeKb} KB" else "${config.btrChunkSizeKb} KB"
                                val scenarioTags = (if (config.enableEpisodeAcceleration) " · 剧集" else "") + (if (config.enableLiveAcceleration) " · 直播" else "")
                                val btrSummary = if (!config.enableBtr) {
                                    "已停用多线程切片加速 (点击进入高级配置)"
                                } else {
                                    "已启用 · $btrThreadDesc · $btrChunkDesc · ${config.btrCdnPoolSize} 节点$scenarioTags"
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
                                    summary = if (config.enableBtr) {
                                        "默认放行官方 Akamai 线路；开启后将其重定向至首选 UPOS 镜像（适合当地 Akamai 连接较慢的情况）"
                                    } else {
                                        "仅在启用 BTR 多线程加速后生效；当前 BTR 已关闭，将继续直连官方 Akamai 节点"
                                    },
                                    checked = config.rewriteAkamai,
                                    onCheckedChange = { onConfigChange(config.copy(rewriteAkamai = it)) },
                                    enabled = config.enabled && config.enableBtr
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
                            val bottomBarSelectedLabel = when (uiPreferences.bottomBarStyle) {
                                BottomBarStyle.FOLLOW_STYLE -> {
                                    val resolved = if (resolveUseFloatingBottomBar(uiPreferences.style, uiPreferences.bottomBarStyle)) {
                                        "悬浮"
                                    } else {
                                        "固定"
                                    }
                                    "跟随 ($resolved)"
                                }
                                BottomBarStyle.FLOATING, BottomBarStyle.DOCKED -> uiPreferences.bottomBarStyle.label
                            }
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
                                    title = stringResource(R.string.label_bottom_bar_style),
                                    summary = stringResource(R.string.summary_bottom_bar_style),
                                    selectedValue = bottomBarSelectedLabel,
                                    onClick = { showBottomBarStyleDialog = true }
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
                                    icon = Icons.AutoMirrored.Outlined.AltRoute,
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

                                val normalizedStrategy = BiliAcceleratorCore.normalizeMcdnStrategy(config.mcdnStrategy)
                                val currentMcdnStrategyLabel = when (normalizedStrategy) {
                                    "proxy-all" -> "中继全部 (proxy-all)"
                                    "proxy-v1" -> "仅中继资源 (/v1/resource/)"
                                    else -> normalizedStrategy
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

                                // 1. BTR 并发线程数 - 滑块调节（拖动中仅更新本地瞬态，松手/点击完成时统一提交 IPC）
                                var sliderConcurrency by remember(config.btrConcurrency) {
                                    mutableIntStateOf(config.btrConcurrency.coerceIn(2, 16))
                                }
                                val concurrencyDesc = when (sliderConcurrency) {
                                    2 -> "2 线程"
                                    4 -> "4 线程"
                                    6 -> "6 线程"
                                    8 -> "8 线程"
                                    12 -> "12 线程"
                                    16 -> "16 线程"
                                    else -> "$sliderConcurrency 线程"
                                }
                                val concurrencySummary = if (config.btrAutoConcurrency) {
                                    "最高并发上限: $concurrencyDesc (在 2~$concurrencyDesc 间自适应调节)"
                                } else {
                                    "固定并发: $concurrencyDesc"
                                }

                                PreferenceSliderItem(
                                    title = "BTR 并发线程数",
                                    summary = concurrencySummary,
                                    value = sliderConcurrency.toFloat(),
                                    onValueChange = { newVal ->
                                        sliderConcurrency = newVal.roundToInt().coerceIn(2, 16)
                                    },
                                    onValueChangeFinished = {
                                        if (sliderConcurrency != config.btrConcurrency) {
                                            onConfigChange(config.copy(btrConcurrency = sliderConcurrency))
                                        }
                                    },
                                    valueRange = 2f..16f,
                                    steps = 13,
                                    showKeyPoints = true,
                                    keyPoints = listOf(2f, 4f, 6f, 8f, 12f, 16f),
                                    valueLabel = "$sliderConcurrency 线程",
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                // 2. 并发 CDN 节点池 - 滑块调节
                                var sliderPoolSize by remember(config.btrCdnPoolSize) {
                                    mutableIntStateOf(config.btrCdnPoolSize.coerceIn(1, 16))
                                }
                                val poolSummary = when (sliderPoolSize) {
                                    1 -> "1 个节点 · 仅单源拉取，不进行多源并发调度"
                                    in 2..3 -> "$sliderPoolSize 个节点 · 基础多源互备，低握手开销"
                                    in 4..6 -> "$sliderPoolSize 个节点 · 骨干云厂商节点均衡并发"
                                    8 -> "8 个节点 · 推荐配置，官方多源全量并发拉取"
                                    in 9..12 -> "$sliderPoolSize 个节点 · 深度竞速，含全国混流节点"
                                    else -> "$sliderPoolSize 个节点 · 极限全量并发，极高吞吐 (全池竞速)"
                                }

                                PreferenceSliderItem(
                                    title = "并发 CDN 节点池",
                                    summary = poolSummary,
                                    value = sliderPoolSize.toFloat(),
                                    onValueChange = { newVal ->
                                        sliderPoolSize = newVal.roundToInt().coerceIn(1, 16)
                                    },
                                    onValueChangeFinished = {
                                        if (sliderPoolSize != config.btrCdnPoolSize) {
                                            onConfigChange(config.copy(btrCdnPoolSize = sliderPoolSize))
                                        }
                                    },
                                    valueRange = 1f..16f,
                                    steps = 14,
                                    showKeyPoints = true,
                                    keyPoints = listOf(1f, 2f, 4f, 6f, 8f, 12f, 16f),
                                    valueLabel = "$sliderPoolSize 个节点",
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                PreferenceActionItem(
                                    title = "自定义 CDN 节点池",
                                    summary = if (config.customCdnPool.isEmpty()) "未自定义 (使用系统默认 CDN 节点池)" else "已配置 ${config.customCdnPool.size} 个自定义节点",
                                    onClick = { showCustomCdnDialog = true },
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
                                var sliderChunkIdx by remember(config.btrChunkSizeKb) {
                                    mutableIntStateOf(chunkOptions.indexOf(config.btrChunkSizeKb).let { if (it >= 0) it else 1 })
                                }
                                val displayChunkSizeKb = chunkOptions[sliderChunkIdx.coerceIn(0, chunkOptions.lastIndex)]
                                val chunkSizeDesc = when (displayChunkSizeKb) {
                                    256 -> "256 KB"
                                    512 -> "512 KB (标准均衡推荐)"
                                    1024 -> "1024 KB"
                                    2048 -> "2048 KB"
                                    else -> "$displayChunkSizeKb KB"
                                }
                                val chunkSizeSummary = if (config.btrAutoChunkSize) {
                                    "标准档基准: $chunkSizeDesc (按文件体积在 256KB~2048KB 间比例缩放)"
                                } else {
                                    "固定分片大小: $chunkSizeDesc"
                                }

                                PreferenceSliderItem(
                                    title = "基准分片大小",
                                    summary = chunkSizeSummary,
                                    value = sliderChunkIdx.toFloat(),
                                    onValueChange = { newIdx ->
                                        sliderChunkIdx = newIdx.roundToInt().coerceIn(0, chunkOptions.lastIndex)
                                    },
                                    onValueChangeFinished = {
                                        val committedChunkSize = chunkOptions[sliderChunkIdx.coerceIn(0, chunkOptions.lastIndex)]
                                        if (committedChunkSize != config.btrChunkSizeKb) {
                                            onConfigChange(config.copy(btrChunkSizeKb = committedChunkSize))
                                        }
                                    },
                                    valueRange = 0f..3f,
                                    steps = 2,
                                    showKeyPoints = true,
                                    keyPoints = listOf(0f, 1f, 2f, 3f),
                                    valueLabel = "$displayChunkSizeKb KB",
                                    enabled = config.enabled && config.enableBtr
                                )
                            }
                        }

                        item(key = "header-btr-scenarios") {
                            PreferenceGroupTitle("专项场景加速与调度")
                        }

                        item(key = "card-btr-scenarios") {
                            PreferenceGroupCard {
                                PreferenceSwitchItem(
                                    title = "剧集加速专属模式",
                                    summary = "针对番剧、电影、纪录片等 PGC 内容启用专属多源并发与兼容模式调度",
                                    checked = config.enableEpisodeAcceleration,
                                    onCheckedChange = { onConfigChange(config.copy(enableEpisodeAcceleration = it)) },
                                    enabled = config.enabled && config.enableBtr
                                )

                                PreferenceDivider()

                                PreferenceSwitchItem(
                                    title = "直播流多源并发拉取 (实验性)",
                                    summary = "基于 BTR 直播内核 (live-core)，对直播分片与流地址执行多源并发拉取与竞速优选",
                                    checked = config.enableLiveAcceleration,
                                    onCheckedChange = { onConfigChange(config.copy(enableLiveAcceleration = it)) },
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
                        Spacer(Modifier.width(4.dp))
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
        shape = RoundedCornerShape(LocalUiStyle.current.dialogCornerRadius())
    )
}

@Composable
private fun CustomCdnPoolDialog(
    customPool: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var textValue by rememberSaveable {
        mutableStateOf(customPool.joinToString("\n"))
    }
    var validationError by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 CDN 节点池") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "每行填写一个 B 站官方 CDN 域名（.bilivideo.com/.cn/.net）。留空则自动使用官方推荐的默认骨干 CDN 节点池。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        validationError = null
                    },
                    isError = validationError != null,
                    supportingText = validationError?.let { err -> { Text(err) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = { Text("例:\nupos-sz-mirrorcos.bilivideo.com\nupos-sz-mirrorali.bilivideo.com", fontSize = 12.sp) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = textValue.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { raw -> raw to BiliAcceleratorCore.cleanHost(raw).trim().lowercase().substringBefore(':') }
                val invalid = parsed.filter { (_, host) -> !BiliAcceleratorCore.isValidCustomCdnHost(host) }
                if (invalid.isNotEmpty()) {
                    val preview = invalid.take(2).joinToString(", ") { it.first }
                    val suffix = if (invalid.size > 2) " 等 ${invalid.size} 项" else ""
                    validationError = "包含无效或非官方 CDN 域名：$preview$suffix"
                    return@TextButton
                }
                val hosts = parsed.map { it.second }.distinct()
                onSave(hosts)
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (customPool.isNotEmpty()) {
                    TextButton(onClick = { onSave(emptyList()) }) {
                        Text("恢复默认")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
        shape = RoundedCornerShape(LocalUiStyle.current.dialogCornerRadius())
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
        mutableIntStateOf(if (isPreset) presetIndex else presets.size)
    }
    var customHost by rememberSaveable {
        mutableStateOf(if (isPreset) "" else currentHost)
    }
    var validationError by rememberSaveable {
        mutableStateOf<String?>(null)
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
                                onClick = {
                                    selectedPresetIndex = index
                                    validationError = null
                                }
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedPresetIndex == index),
                            onClick = {
                                selectedPresetIndex = index
                                validationError = null
                            }
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
                        onValueChange = {
                            customHost = it
                            validationError = null
                        },
                        isError = validationError != null,
                        supportingText = validationError?.let { err -> { Text(err) } },
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
                    val cleaned = BiliAcceleratorCore.cleanHost(customHost).trim().lowercase().substringBefore(':')
                    if (!BiliAcceleratorCore.isValidCustomCdnHost(cleaned)) {
                        validationError = "请输入有效的 B 站官方中继域名（如 proxy-tf-all-ws.bilivideo.com）"
                        return@TextButton
                    }
                    cleaned
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
        shape = RoundedCornerShape(LocalUiStyle.current.dialogCornerRadius())
    )
}

@Composable
private fun McdnStrategyDialog(
    currentStrategy: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val normalizedStrategy = BiliAcceleratorCore.normalizeMcdnStrategy(currentStrategy)
    val strategies = listOf(
        "proxy-all" to ("中继代理全部 (默认推荐)" to "通过云厂商官方 CDN 中继所有 MCDN 节点"),
        "proxy-v1" to ("仅中继资源接口 (/v1/resource/)" to "仅对资源接口走官方中继代理，其余中继节点原样直连避免 403")
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
                    val isSelected = key.equals(normalizedStrategy, ignoreCase = true)
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
        shape = RoundedCornerShape(LocalUiStyle.current.dialogCornerRadius())
    )
}
