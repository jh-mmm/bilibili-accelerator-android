package io.github.jh_mmm.biliaccelerator.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.os.Process
import androidx.compose.ui.platform.LocalContext
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import io.github.jh_mmm.biliaccelerator.core.StatsSnapshot
import io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import io.github.jh_mmm.biliaccelerator.ui.MainActivity
import io.github.jh_mmm.biliaccelerator.ui.MainActivity.ActivationState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun StatusDetailDialog(
    state: ActivationState,
    snapshot: StatsSnapshot,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hookReport = HookStatusTracker.formatReport(
        snapshot.hookStatus.ifEmpty { HookStatusTracker.getSnapshot() }
    )
    val isServiceBound = XposedServiceProvider.isServiceBound
    val isSelfInjected = MainActivity.isModuleActive() ||
            StatsManager.isFrameworkSelfInjected ||
            snapshot.isFrameworkActivated

    val now = System.currentTimeMillis()
    val hasRecentHeartbeat = snapshot.lastHeartbeatTimestamp > 0L &&
            (now - snapshot.lastHeartbeatTimestamp) in 0..MainActivity.DEFAULT_HEARTBEAT_THRESHOLD_MS

    val lastHbDesc = if (snapshot.lastHeartbeatTimestamp > 0L) {
        val formatted = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(snapshot.lastHeartbeatTimestamp),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        if (hasRecentHeartbeat) "$formatted (活跃)" else "$formatted (已超时离线)"
    } else {
        "暂无记录"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "模块状态与诊断",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Section 1: Activation status
                Text(
                    text = when (state) {
                        ActivationState.ACTIVE_EFFECTIVE -> "● 运行状态：已生效 (运行正常)"
                        ActivationState.ACTIVE_HEARTBEAT -> "● 运行状态：等待拦截 (等待流量)"
                        ActivationState.INACTIVE -> "● 运行状态：未激活"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = when (state) {
                        ActivationState.ACTIVE_EFFECTIVE -> MaterialTheme.colorScheme.primary
                        ActivationState.ACTIVE_HEARTBEAT -> MaterialTheme.colorScheme.tertiary
                        ActivationState.INACTIVE -> MaterialTheme.colorScheme.error
                    }
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (state) {
                        ActivationState.ACTIVE_EFFECTIVE ->
                            "已成功拦截并加速视频播放，累计完成 ${snapshot.totalRewrites} 次视频流重定向。"
                        ActivationState.ACTIVE_HEARTBEAT -> {
                            val clientName = if (!snapshot.clientPackage.isNullOrEmpty()) {
                                "${snapshot.clientPackage} (v${snapshot.clientVersion ?: "就绪"})"
                            } else if (snapshot.hasTargetScope) {
                                snapshot.frameworkScope.filter { io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isTargetPackage(it) }.joinToString(", ")
                            } else {
                                "哔哩哔哩客户端"
                            }
                            "监控目标：$clientName\n模块已就绪并处于等待拦截状态，播放任意视频即可开始加速。"
                        }
                        ActivationState.INACTIVE -> {
                            if (isServiceBound && snapshot.hasQueriedScope && !snapshot.hasTargetScope) {
                                val scopeListDesc = if (snapshot.frameworkScope.isEmpty()) {
                                    "（当前未勾选任何应用）"
                                } else {
                                    "（当前已勾选: ${snapshot.frameworkScope.joinToString(", ")}）"
                                }
                                "模块已成功连接框架服务，但作用域未勾选哔哩哔哩。\n\n排查指引：\n1. 打开 LSPosed 管理器，进入本模块的作用域设置；\n2. 勾选“哔哩哔哩”（支持国内版、国际版、概念版等）；\n3. 启动哔哩哔哩播放任意视频即可开始加速。\n\n$scopeListDesc"
                            } else {
                                "未检测到活跃框架服务连接。\n\n排查指引：\n1. 打开 LSPosed 管理器，确认本模块开关已开启；\n2. 在模块作用域中勾选“哔哩哔哩”（无需勾选本模块自身）；\n3. 若刚在 LSPosed 中开启模块，请完全重启本应用（强制停止后重开，或点击下方“重启连接”按钮），以接收框架服务推送；\n4. 启动哔哩哔哩播放任意视频即可建立实时握手与加速重定向。"
                            }
                        }
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))

                // Section 2: Framework & Runtime
                Text(
                    text = "【框架与运行时状态】",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                val frameworkDesc = when {
                    isSelfInjected -> "已挂载 (LSPosed 作用域生效)"
                    isServiceBound -> "已连接 (现代系统服务)"
                    hasRecentHeartbeat && !snapshot.clientPackage.isNullOrEmpty() -> "已建立通信 (来自 ${snapshot.clientPackage})"
                    snapshot.lastHeartbeatTimestamp > 0L -> "通信离线 (最近会话: $lastHbDesc)"
                    else -> "未连接"
                }
                val scopeDesc = if (snapshot.hasQueriedScope) {
                    if (snapshot.frameworkScope.isEmpty()) {
                        "未勾选任何应用"
                    } else {
                        snapshot.frameworkScope.joinToString(", ")
                    }
                } else {
                    "未获取 (等待框架同步)"
                }
                Text(
                    text = "框架运行状态: $frameworkDesc\n框架 Service 绑定: ${if (isServiceBound) "已连接" else "未连接"}\n已勾选作用域: $scopeDesc\n最近心跳回执: $lastHbDesc",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))

                // Section 3: Hook Status Report
                Text(
                    text = "【Hook 挂载状态】",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hookReport,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        },
        dismissButton = if (state == ActivationState.INACTIVE && !isServiceBound) {
            {
                TextButton(
                    onClick = {
                        (context as? Activity)?.finishAffinity()
                        Process.killProcess(Process.myPid())
                    }
                ) {
                    Text("重启应用连接框架")
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(20.dp)
    )
}
