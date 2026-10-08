package io.github.jh_mmm.biliaccelerator.ui.about

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.jh_mmm.biliaccelerator.R
import io.github.jh_mmm.biliaccelerator.ui.components.AcceleratorPage
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceActionItem
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceDivider
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceGroupCard
import io.github.jh_mmm.biliaccelerator.ui.components.PreferenceGroupTitle
import io.github.jh_mmm.biliaccelerator.ui.theme.LocalUiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Long,
    bottomContentPadding: Dp = 80.dp
) {
    val context = LocalContext.current
    val uiStyle = LocalUiStyle.current
    val cornerRadius = if (uiStyle == UiStyle.MIUIX) 18.dp else 12.dp

    val appIcon = remember(context) {
        runCatching {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: context.packageManager.getApplicationIcon(context.packageName)
            drawable.toBitmap(width = 144, height = 144, config = Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }

    fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        }
    }

    AcceleratorPage(title = "关于") { pagePadding, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Card: App Info
            item(key = "app-header") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(cornerRadius),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = "Logo",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.RocketLaunch,
                                contentDescription = "Logo",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = "Bili Accelerator",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "v$versionName ($versionCode) · GPL-3.0-only",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "面向哔哩哔哩客户端的原生视频流加速与 PCDN 过滤模块，提供智能 UPOS 选线与抗劣化防卡顿优化。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Group: 项目与开源链接
            item(key = "header-links") {
                PreferenceGroupTitle("项目与开源")
            }
            item(key = "card-links") {
                PreferenceGroupCard {
                    PreferenceActionItem(
                        title = "GitHub 源代码仓库",
                        summary = "查看项目最新源码与开发进展",
                        icon = Icons.Outlined.Code,
                        onClick = { openUrl("https://github.com/jh-mmm/bilibili-accelerator-android") }
                    )

                    PreferenceDivider()

                    PreferenceActionItem(
                        title = "Releases 版本发布",
                        summary = "检查最新正式发布构建与更新日志",
                        icon = Icons.Outlined.OpenInNew,
                        onClick = { openUrl("https://github.com/jh-mmm/bilibili-accelerator-android/releases") }
                    )

                    PreferenceDivider()

                    PreferenceActionItem(
                        title = "问题反馈与建议",
                        summary = "提交 Issue 报告错误或提出建议",
                        icon = Icons.Outlined.HelpOutline,
                        onClick = { openUrl("https://github.com/jh-mmm/bilibili-accelerator-android/issues") }
                    )
                }
            }
        }
    }
}

