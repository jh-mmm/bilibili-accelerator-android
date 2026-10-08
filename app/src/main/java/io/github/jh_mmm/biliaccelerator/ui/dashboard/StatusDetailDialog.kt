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
import io.github.jh_mmm.biliaccelerator.core.StatsSnapshot
import io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
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
    val hookReport = HookStatusTracker.formatReport(
        snapshot.hookStatus.ifEmpty { HookStatusTracker.getSnapshot() }
    )
    val isServiceBound = XposedServiceProvider.isServiceBound

    val lastHbDesc = if (snapshot.lastHeartbeatTimestamp > 0L) {
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(snapshot.lastHeartbeatTimestamp),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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
                        ActivationState.ACTIVE_HEARTBEAT -> "● 运行状态：已激活 (等待流量)"
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
                        ActivationState.ACTIVE_HEARTBEAT ->
                            if (isServiceBound) "LSPosed 框架服务已成功连接，播放任意 B 站视频即可开始加速。"
                            else "目标应用（哔哩哔哩）已挂载并建立通信，播放视频即可开始加速。"
                        ActivationState.INACTIVE ->
                            "排查指引：\n1. 打开 LSPosed 管理器确认模块开关已开启\n2. 确认推荐作用域已勾选“哔哩哔哩”\n3. 强行停止哔哩哔哩后重新打开测试。"
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
                Text(
                    text = "框架 Service 绑定: ${if (isServiceBound) "已连接" else "未连接"}\n最近心跳回执: $lastHbDesc",
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
        shape = RoundedCornerShape(20.dp)
    )
}
