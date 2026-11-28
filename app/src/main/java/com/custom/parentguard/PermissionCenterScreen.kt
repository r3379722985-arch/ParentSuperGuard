package com.custom.parentguard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionCenterScreen(
    rom: RomFamily,
    permState: PermissionSnapshot,
    onRequestExternalFiles: () -> Unit,
    onMarkRestrictedDone: () -> Unit,
    onFinishAndEnterHome: () -> Unit
) {
    val context = LocalContext.current
    val nextStep = PermissionStepPlanner.nextRecommendedStep(permState)

    BackHandler(enabled = true) {
        onFinishAndEnterHome()
    }

    fun openUsageStats() {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    fun openAppDetailsForRestricted() {
        onMarkRestrictedDone()
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        } catch (_: Exception) {}
    }

    fun openAccessibilitySettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    fun openUnknownAppSources() {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        } catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7FB))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF1D4ED8), Color(0xFF0F766E))),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "一站式权限配置中心",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onFinishAndEnterHome) {
                            Text("进入主页", color = Color(0xFFFEF08A), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "当前设备：${rom.displayName}",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFEF08A)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "全部权限集中在此处。因免 Root 系统安全机制限制，点底部按钮即可按顺序逐项跳转，开完按返回会自动打勾推进。",
                        fontSize = 13.sp,
                        color = Color(0xFFDBEAFE)
                    )
                    Spacer(Modifier.height(14.dp))

                    val progress = (permState.readyCount.toFloat() / 4f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(9.dp),
                        color = Color(0xFFFDE047),
                        trackColor = Color(0x44000000)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "权限配置进度：已就绪 ${permState.readyCount} / 4 项",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            PermissionChecklistCard(
                stepLabel = "1",
                tagText = "必选 · 只需1次",
                tagColor = Color(0xFFDC2626),
                title = "使用情况访问权限",
                desc = "用于精准识别缓存大于 5MB 的高缓存软件（如抖音、拼多多），避免盲目翻页。",
                statusText = if (permState.hasUsageStats) "✓ 已开启" else "待开启",
                isReady = permState.hasUsageStats,
                isHighlighted = nextStep == PermissionActionStep.USAGE_STATS,
                actionLabel = if (permState.hasUsageStats) "查看" else "去开启",
                onAction = { openUsageStats() }
            )

            Spacer(Modifier.height(10.dp))

            if (permState.isAndroid13Plus) {
                val restrictedDone = permState.cleanServiceRunning || permState.restrictedConfirmed
                PermissionChecklistCard(
                    stepLabel = "2",
                    tagText = "小米/vivo 新系统必看",
                    tagColor = Color(0xFF2563EB),
                    title = "解锁「受限设置」（防止无障碍变灰）",
                    desc = "若无障碍开关变灰：打开本软件「应用信息」→ 右上角「⋮ 三个点」（或底部菜单）→ 允许受限设置。",
                    statusText = if (restrictedDone) "✓ 已确认" else "建议先做",
                    isReady = restrictedDone,
                    isHighlighted = nextStep == PermissionActionStep.RESTRICTED_SETTINGS,
                    actionLabel = if (restrictedDone) "重新查看" else "去解锁",
                    onAction = { openAppDetailsForRestricted() }
                )
                Spacer(Modifier.height(10.dp))
            }

            val cleanStepNo = if (permState.isAndroid13Plus) "3" else "2"
            val cleanStatus = when {
                permState.cleanServiceRunning -> "✓ 运行中"
                permState.cleanServiceZombie -> "⚠ 已休眠(需重开)"
                else -> "待开启"
            }
            val cleanDesc = if (permState.cleanServiceZombie) {
                "系统休眠了该服务：点右侧进入无障碍，把「安心卫士 · 自动清缓存」先关再开一次即可恢复。"
            } else {
                "自动点击系统「清除缓存」，清完默认自动关闭自身，不触发银行 App 风控。\n路径：${rom.accessibilityPathHint}"
            }
            PermissionChecklistCard(
                stepLabel = cleanStepNo,
                tagText = "核心 · 清完即关",
                tagColor = Color(0xFF15803D),
                title = "自动清缓存无障碍服务",
                desc = cleanDesc,
                statusText = cleanStatus,
                isReady = permState.cleanServiceRunning,
                isHighlighted = nextStep == PermissionActionStep.CLEAN_ACCESSIBILITY,
                actionLabel = if (permState.cleanServiceRunning) "已就绪" else "去开启",
                onAction = { openAccessibilitySettings() }
            )

            Spacer(Modifier.height(10.dp))

            val filesStepNo = if (permState.isAndroid13Plus) "4" else "3"
            PermissionChecklistCard(
                stepLabel = filesStepNo,
                tagText = "可选 · 只需1次",
                tagColor = Color(0xFF0F766E),
                title = "缩略图与系统日志清理权限",
                desc = "开启后顺手清理相册旧预览缩略图（.thumbnails）与系统调试日志，绝不碰照片和用户文件夹。",
                statusText = if (permState.hasExternalFiles) "✓ 已授权" else "未开启",
                isReady = permState.hasExternalFiles,
                isHighlighted = nextStep == PermissionActionStep.EXTERNAL_FILES,
                actionLabel = if (permState.hasExternalFiles) "已授权" else "去授权",
                onAction = onRequestExternalFiles
            )

            Spacer(Modifier.height(10.dp))

            val guardStepNo = if (permState.isAndroid13Plus) "5" else "4"
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StepBadge(guardStepNo, permState.rogueServiceRunning)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("防流氓误装与弹窗守护（可选）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(
                                text = "推荐先用系统「安装未知应用」关掉浏览器/短视频装软件权限（零银行风控）；如需拦快应用广告再开常驻守护。",
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openUnknownAppSources() },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("系统防误装(推荐)", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D4ED8))
                        }
                        OutlinedButton(
                            onClick = { openAccessibilitySettings() },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (permState.rogueServiceRunning) "✓ 常驻已开" else "开启常驻守护",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (permState.rogueServiceRunning) Color(0xFF15803D) else Color(0xFF475569)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        Surface(shadowElevation = 8.dp, color = Color.White) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val (btnLabel, btnColor, btnAction) = when (nextStep) {
                    PermissionActionStep.USAGE_STATS -> Triple(
                        "下一步：去开启「使用情况权限」（必选）",
                        Color(0xFFEA580C),
                        { openUsageStats() }
                    )
                    PermissionActionStep.RESTRICTED_SETTINGS -> Triple(
                        "下一步：去解锁小米/vivo「受限设置」",
                        Color(0xFF2563EB),
                        { openAppDetailsForRestricted() }
                    )
                    PermissionActionStep.CLEAN_ACCESSIBILITY -> Triple(
                        "下一步：去开启「自动清缓存无障碍」",
                        Color(0xFF16A34A),
                        { openAccessibilitySettings() }
                    )
                    PermissionActionStep.EXTERNAL_FILES -> Triple(
                        "下一步（可选）：授权缩略图与日志清理",
                        Color(0xFF0F766E),
                        onRequestExternalFiles
                    )
                    PermissionActionStep.ALL_DONE -> Triple(
                        "核心权限全部就绪，立即进入主页！",
                        Color(0xFF15803D),
                        onFinishAndEnterHome
                    )
                }

                Button(
                    onClick = btnAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = btnColor)
                ) {
                    Text(btnLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                if (nextStep != PermissionActionStep.ALL_DONE && permState.essentialReady) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = onFinishAndEnterHome,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "必选权限已开好，跳过可选项直接进入主页 →",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBadge(stepLabel: String, ready: Boolean) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(
                color = if (ready) Color(0xFF16A34A) else Color(0xFF2563EB),
                shape = RoundedCornerShape(999.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (ready) "✓" else stepLabel,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PermissionChecklistCard(
    stepLabel: String,
    tagText: String,
    tagColor: Color,
    title: String,
    desc: String,
    statusText: String,
    isReady: Boolean,
    isHighlighted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val bgColor = when {
        isReady -> Color(0xFFF0FDF4)
        isHighlighted -> Color(0xFFFFF7ED)
        else -> Color.White
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepBadge(stepLabel, isReady)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tagText, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = tagColor)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isReady) Color(0xFF15803D) else Color(0xFFEA580C)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReady) Color(0xFF15803D) else Color(0xFF2563EB)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(actionLabel, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 13.sp, color = Color(0xFF475569))
        }
    }
}
