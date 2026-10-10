package io.github.jh_mmm.biliaccelerator.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.RewriteLogEntry
import io.github.jh_mmm.biliaccelerator.core.StatsSnapshot
import io.github.jh_mmm.biliaccelerator.ui.MainActivity.ActivationState
import io.github.jh_mmm.biliaccelerator.ui.components.AcceleratorPage
import io.github.jh_mmm.biliaccelerator.ui.components.StatCard
import io.github.jh_mmm.biliaccelerator.ui.theme.BiliBlue
import io.github.jh_mmm.biliaccelerator.ui.theme.LocalUiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.TagMcdn
import io.github.jh_mmm.biliaccelerator.ui.theme.TagPcdn
import io.github.jh_mmm.biliaccelerator.ui.theme.TagSched
import io.github.jh_mmm.biliaccelerator.ui.theme.TagUpos
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.isAcceleratorDarkTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 生成稳定且不依赖列表下标的 LazyColumn 条目 key，避免新日志插入头部时导致全部条目 key 变化与列表抖动。
 */
internal fun logItemKey(log: RewriteLogEntry): String {
    return log.id.takeIf { !it.isNullOrBlank() }
        ?: "${log.timestamp}_${log.originalHost}_${log.targetHost}_${log.reason}"
}

internal fun resolveUposDisplayName(
    targetHost: String,
    uposEntries: List<String> = emptyList(),
    uposValues: List<String> = emptyList()
): String {
    val index = uposValues.indexOf(targetHost)
    if (index in uposEntries.indices) {
        return uposEntries[index]
            .substringBefore(" - ")
            .replace(Regex("""\s*[()（）]\s*"""), "")
            .trim()
    }
    return when (targetHost.lowercase().substringBefore(".")) {
        "upos-sz-mirrorcos" -> "腾讯云国内"
        "upos-sz-mirrorali" -> "阿里云国内"
        "upos-sz-mirrorhw" -> "华为云国内"
        "upos-tf-all-hw" -> "华为云全国混流"
        "upos-tf-all-tx" -> "腾讯云全国混流"
        "upos-sz-mirrorcosov" -> "腾讯云海外"
        "upos-sz-mirroraliov" -> "阿里云海外"
        "upos-sz-mirrorhwov" -> "华为云海外"
        else -> targetHost.substringBefore(".").removePrefix("upos-")
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: ActivationState,
    snapshot: StatsSnapshot,
    config: AcceleratorConfig,
    onRefresh: () -> Unit,
    onLogClick: (RewriteLogEntry) -> Unit = {},
    uposEntries: List<String> = emptyList(),
    uposValues: List<String> = emptyList(),
    bottomContentPadding: Dp = 80.dp
) {
    var showStatusDialog by rememberSaveable { mutableStateOf(false) }

    if (showStatusDialog) {
        StatusDetailDialog(
            state = state,
            snapshot = snapshot,
            onDismiss = { showStatusDialog = false }
        )
    }

    AcceleratorPage(
        title = "看板",
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary
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
                top = 10.dp,
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Runtime Status Card (Signature HyperEars Design)
            item(key = "runtime-status") {
                RuntimeStatusCard(
                    state = state,
                    snapshot = snapshot,
                    onClick = { showStatusDialog = true }
                )
            }

            // 2. Metrics Grid (2x2)
            item(key = "stats-grid") {
                RuntimeStatsGrid(snapshot = snapshot)
            }

            // 3. Process Progress & Summary Card
            item(key = "runtime-details") {
                RuntimeDetailsCard(
                    snapshot = snapshot,
                    config = config,
                    uposEntries = uposEntries,
                    uposValues = uposValues,
                    onRefresh = onRefresh
                )
            }

            // 4. Section Header for Logs
            item(key = "logs-header") {
                SectionHeader(
                    title = "最近拦截与重定向",
                    count = snapshot.recentLogs.size
                )
            }

            // 5. Logs Stream or Empty Card
            if (snapshot.recentLogs.isEmpty()) {
                item(key = "empty-logs") {
                    EmptyLogsCard()
                }
            } else {
                items(
                    items = snapshot.recentLogs,
                    key = { log -> logItemKey(log) }
                ) { logItem ->
                    RewriteLogCard(
                        log = logItem,
                        onClick = { onLogClick(logItem) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    state: ActivationState,
    snapshot: StatsSnapshot,
    onClick: () -> Unit
) {
    val dark = isAcceleratorDarkTheme()
    val uiStyle = LocalUiStyle.current
    val cornerRadius = if (uiStyle == UiStyle.MIUIX) 20.dp else 16.dp

    val containerColor = when (state) {
        ActivationState.ACTIVE_EFFECTIVE -> if (dark) Color(0xFF143320) else Color(0xFFE2F8E7)
        ActivationState.ACTIVE_HEARTBEAT -> if (dark) Color(0xFF112938) else Color(0xFFE1F4FD)
        ActivationState.INACTIVE -> if (dark) Color(0xFF381818) else Color(0xFFFAECEC)
    }

    val accentColor = when (state) {
        ActivationState.ACTIVE_EFFECTIVE -> Color(0xFF2ECC71)
        ActivationState.ACTIVE_HEARTBEAT -> Color(0xFF00AEEC)
        ActivationState.INACTIVE -> Color(0xFFF72727)
    }

    val title = when (state) {
        ActivationState.ACTIVE_EFFECTIVE -> "运行正常"
        ActivationState.ACTIVE_HEARTBEAT -> "等待流量"
        ActivationState.INACTIVE -> "模块未激活"
    }

    val summary = when (state) {
        ActivationState.ACTIVE_EFFECTIVE -> "视频流原生加速与 PCDN 过滤已生效"
        ActivationState.ACTIVE_HEARTBEAT -> "LSPosed 服务已挂载 · 播放视频即可加速"
        ActivationState.INACTIVE -> "未检测到框架绑定或心跳，点击查看排查指引"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius))
        ) {
            // Background huge watermark icon
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 35.dp, y = 30.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    imageVector = when (state) {
                        ActivationState.ACTIVE_EFFECTIVE -> Icons.Rounded.CheckCircleOutline
                        ActivationState.ACTIVE_HEARTBEAT -> Icons.Outlined.Check
                        ActivationState.INACTIVE -> Icons.Default.Warning
                    },
                    contentDescription = null,
                    modifier = Modifier.size(150.dp),
                    tint = accentColor.copy(alpha = if (dark) 0.18f else 0.15f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dark) Color(0xFFF5F5F5) else Color(0xFF191919)
                    )

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (state) {
                                ActivationState.ACTIVE_EFFECTIVE -> "已生效"
                                ActivationState.ACTIVE_HEARTBEAT -> "待命中"
                                ActivationState.INACTIVE -> "未激活"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = (if (dark) Color(0xFFE0E0E0) else Color(0xFF404040)).copy(alpha = 0.85f),
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(28.dp))
                Text(
                    text = "已拦截 ${snapshot.pcdnBlocked} 次 PCDN · 已规避 ${snapshot.avoidedHosts.size} 个慢速节点",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = (if (dark) Color(0xFFE0E0E0) else Color(0xFF333333)).copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatsGrid(snapshot: StatsSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                value = snapshot.totalRewrites.toString(),
                label = "总重定向次数",
                valueColor = BiliBlue,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = snapshot.pcdnBlocked.toString(),
                label = "PCDN 拦截次数",
                valueColor = TagPcdn,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                value = snapshot.mcdnProxied.toString(),
                label = "MCDN 代理次数",
                valueColor = TagMcdn,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = snapshot.avoidedHosts.size.toString(),
                label = "已规避慢速节点",
                valueColor = TagUpos,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RuntimeDetailsCard(
    snapshot: StatsSnapshot,
    config: AcceleratorConfig,
    uposEntries: List<String>,
    uposValues: List<String>,
    onRefresh: () -> Unit
) {
    val uiStyle = LocalUiStyle.current
    val cornerRadius = if (uiStyle == UiStyle.MIUIX) 16.dp else 12.dp

    val lastHeartbeatFormatted = if (snapshot.lastHeartbeatTimestamp > 0L) {
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(snapshot.lastHeartbeatTimestamp),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    } else {
        "等待建立"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "调度处理概览",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "心跳: $lastHeartbeatFormatted",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricColumn(
                    label = "总请求数",
                    value = snapshot.totalRequests.toString(),
                    modifier = Modifier.weight(0.9f)
                )
                MetricColumn(
                    label = "首选 UPOS",
                    value = resolveUposDisplayName(config.targetHost, uposEntries, uposValues),
                    modifier = Modifier.weight(1.3f)
                )
                MetricColumn(
                    label = "PCDN 拦截",
                    value = if (config.blockPcdn) "已开启" else "已关闭",
                    modifier = Modifier.weight(0.9f)
                )
                MetricColumn(
                    label = "MCDN 代理",
                    value = if (config.proxyMcdn) "已开启" else "已关闭",
                    modifier = Modifier.weight(0.9f)
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = if (value.length > 5) 13.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
        )
        Text(
            text = "$count 条",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun EmptyLogsCard() {
    val uiStyle = LocalUiStyle.current
    val cornerRadius = if (uiStyle == UiStyle.MIUIX) 16.dp else 12.dp

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "暂无加速拦截日志",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "在哔哩哔哩中播放视频后将实时在此处展示重写记录",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun RewriteLogCard(
    log: RewriteLogEntry,
    onClick: () -> Unit
) {
    val uiStyle = LocalUiStyle.current
    val cornerRadius = if (uiStyle == UiStyle.MIUIX) 14.dp else 10.dp

    val (tagLabel, tagColor) = when {
        log.isPcdn -> "PCDN 阻断" to TagPcdn
        log.isMcdn -> "MCDN 代理" to TagMcdn
        log.reason.contains("source") -> "线路调度" to TagSched
        else -> "UPOS 重定向" to TagUpos
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tagColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = tagLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tagColor
                    )
                }

                Text(
                    text = log.timeFormatted,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(2.dp))

            // Origin -> Target
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "原域名: ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = log.originalHost,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "重定向: ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = log.targetHost,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "触发策略: ${log.reason}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
