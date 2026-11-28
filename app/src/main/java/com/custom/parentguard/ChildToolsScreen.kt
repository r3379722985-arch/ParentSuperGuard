package com.custom.parentguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「子女管理与防坑中心」页面（v6.2）：
 * - P1-1 配套：设锁时生成并展示 8 位一次性恢复码（同时支持用手机锁屏密码随时忘记重置）；
 * - P2-3 修复：通知大扫除默认只展示高骚扰广告推送大户，普通第三方软件折叠在「其它普通软件（不推荐关）」中，
 *   外卖/快递/医疗挂号类自动剔除保护；
 * - P3-1 & P3-2 修复：支持「展开全部」不再截断前 20，且每行清晰显示「✓ 已关闭 / ✓ 已处理 / 待关闭」状态。
 */
@Composable
fun ChildToolsScreen(
    rom: RomFamily,
    snapshot: GuardSnapshot,
    permSnapshot: PermissionSnapshot,
    cfg: GuardConfig,
    isGuardPaused: Boolean,
    blockLogs: List<String>,
    checklistDoneIds: Set<String>,
    notifHandledPkgs: Set<String>,
    hasAdminPin: Boolean,
    scanning: Boolean,
    onOpenPermissionCenter: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showChecklist by remember { mutableStateOf(true) }
    var showNotificationCleaner by remember { mutableStateOf(false) }
    var expandAllHighSpamNotifs by remember { mutableStateOf(false) }
    var showOtherNormalNotifs by remember { mutableStateOf(false) }
    var expandAllOtherNotifs by remember { mutableStateOf(false) }
    var showHighCacheList by remember { mutableStateOf(false) }
    var expandAllHighCache by remember { mutableStateOf(false) }
    var showAppAudit by remember { mutableStateOf(false) }

    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var newlyGeneratedRecoveryCode by remember { mutableStateOf<String?>(null) }
    var isSavingPin by remember { mutableStateOf(false) }

    fun openNotificationSettingsForApp(pkg: String) {
        GuardStore.markNotificationHandled(context, pkg)
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                })
            } catch (_: Exception) {}
        }
    }

    // 设锁成功后的一次性 8 位恢复码展示弹窗
    if (newlyGeneratedRecoveryCode != null) {
        AlertDialog(
            onDismissRequest = { newlyGeneratedRecoveryCode = null },
            title = { Text("管理锁已开启 · 请记住找回方式", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "以后如果忘记 4 位密码，可以直接在解锁框点「用手机锁屏密码解锁」一键关闭锁。",
                        fontSize = 14.sp,
                        color = Color(0xFF334155)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "如果家长手机没设锁屏密码，请截图或抄下这组 8 位备用恢复码：",
                        fontSize = 13.5.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4))
                    ) {
                        Text(
                            text = newlyGeneratedRecoveryCode ?: "",
                            modifier = Modifier.padding(14.dp),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF15803D)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { newlyGeneratedRecoveryCode = null }) {
                    Text("我记下了")
                }
            }
        )
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                pinInput = ""
                pinError = null
                isSavingPin = false
            },
            title = {
                Text(
                    text = if (hasAdminPin) "修改或关闭 4 位管理锁" else "设置 4 位子女管理锁",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "设置后进入「子女设置」需输 4 位数字密码（PBKDF2 加密 + 防穷举冷却），长辈主页一键清理永远免密。忘记时可用手机锁屏密码或恢复码找回。",
                        fontSize = 13.5.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { v ->
                            if (v.length <= 4 && v.all { it.isDigit() }) {
                                pinInput = v
                                pinError = null
                            }
                        },
                        label = { Text("输入 4 位数字密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(pinError ?: "", fontSize = 12.5.sp, color = Color(0xFFDC2626))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isSavingPin,
                    onClick = {
                        if (!CleanDecisionRules.isValidPinFormat(pinInput)) {
                            pinError = "请输入 4 位纯数字密码"
                        } else {
                            val pinCopy = pinInput
                            isSavingPin = true
                            scope.launch {
                                val saveRes = try {
                                    withContext(Dispatchers.Default) {
                                        GuardStore.setAdminPin(context, pinCopy)
                                    }
                                } finally {
                                    isSavingPin = false
                                }
                                when (saveRes) {
                                    is PinSaveResult.Saved -> {
                                        showPinDialog = false
                                        pinInput = ""
                                        pinError = null
                                        newlyGeneratedRecoveryCode = saveRes.recoveryCode
                                    }
                                    is PinSaveResult.Cleared -> {
                                        showPinDialog = false
                                        pinInput = ""
                                        pinError = null
                                    }
                                    is PinSaveResult.Failed -> {
                                        pinError = saveRes.reason
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isSavingPin) "加密保存中..." else "保存密码")
                }
            },
            dismissButton = {
                Row {
                    if (hasAdminPin) {
                        TextButton(
                            enabled = !isSavingPin,
                            onClick = {
                                isSavingPin = true
                                scope.launch {
                                    val res = try {
                                        withContext(Dispatchers.IO) {
                                            GuardStore.setAdminPin(context, null)
                                        }
                                    } finally {
                                        isSavingPin = false
                                    }
                                    when (res) {
                                        is PinSaveResult.Failed -> pinError = res.reason
                                        else -> {
                                            GuardStore.postBanner("已关闭子女管理锁。")
                                            showPinDialog = false
                                            pinInput = ""
                                            pinError = null
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("关闭锁", color = Color(0xFFDC2626))
                        }
                    }
                    TextButton(
                        onClick = {
                            showPinDialog = false
                            pinInput = ""
                            pinError = null
                        }
                    ) {
                        Text("取消")
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. 统一权限配置中心入口卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPermissionCenter() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "一站式权限配置中心 (${permSnapshot.readyCount}/4)",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.weight(1f)
                    )
                    Text("去配置 ›", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusChip("统计权限", permSnapshot.hasUsageStats)
                    StatusChip("清缓存", permSnapshot.cleanServiceRunning)
                    StatusChip("文件清理", permSnapshot.hasExternalFiles)
                    StatusChip("防流氓", permSnapshot.rogueServiceRunning)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. 子女 3 分钟防坑体检清单
        val checklistTotal = 5
        val checklistDoneCount = checklistDoneIds.size.coerceAtMost(checklistTotal)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("子女 3 分钟防坑体检清单 ($checklistDoneCount/$checklistTotal)", fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text("零耗电、零银行风控，设完打勾一劳永逸", fontSize = 13.sp, color = Color(0xFF64748B))
                    }
                    TextButton(onClick = { showChecklist = !showChecklist }) {
                        Text(if (showChecklist) "收起" else "展开", fontWeight = FontWeight.Bold)
                    }
                }
                if (showChecklist) {
                    Spacer(Modifier.height(8.dp))

                    AntiPitfallCheckItem(
                        checked = "unknown_apps" in checklistDoneIds,
                        title = "1. 关闭浏览器/短视频「安装未知应用」权限",
                        desc = "从源头杜绝 90% 广告误点装流氓软件。",
                        actionText = "直达设置",
                        onToggle = { GuardStore.toggleChecklistItem(context, "unknown_apps") },
                        onAction = {
                            try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
                            catch (_: Exception) {
                                try { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    AntiPitfallCheckItem(
                        checked = "screen_timeout" in checklistDoneIds,
                        title = "2. 把自动息屏时间调成 2 分钟",
                        desc = "长辈打字看验证码慢，默认 30 秒总黑屏很急人。",
                        actionText = "显示设置",
                        onToggle = { GuardStore.toggleChecklistItem(context, "screen_timeout") },
                        onAction = {
                            try { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } catch (_: Exception) {}
                        }
                    )
                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    AntiPitfallCheckItem(
                        checked = "lock_desktop" in checklistDoneIds,
                        title = "3. 开启「锁定桌面布局」防图标拖丢",
                        desc = rom.lockDesktopHint,
                        actionText = null,
                        onToggle = { GuardStore.toggleChecklistItem(context, "lock_desktop") },
                        onAction = {}
                    )
                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    AntiPitfallCheckItem(
                        checked = "shake_ads" in checklistDoneIds,
                        title = "4. 关闭常用软件「摇一摇动作」权限",
                        desc = "针对淘宝/拼多多/头条/百度：${rom.shakeSensorHint}",
                        actionText = "应用管理",
                        onToggle = { GuardStore.toggleChecklistItem(context, "shake_ads") },
                        onAction = {
                            try { context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) } catch (_: Exception) {}
                        }
                    )
                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    AntiPitfallCheckItem(
                        checked = "overseas_calls" in checklistDoneIds,
                        title = "5. 开启运营商免费「拦截国际/境外来电」",
                        desc = "在微信搜：移动高频骚扰防护 / 联通来电管家 / 天翼防骚扰，免费开启最防电诈。",
                        actionText = "打开微信",
                        onToggle = { GuardStore.toggleChecklistItem(context, "overseas_calls") },
                        onAction = {
                            context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let {
                                try { context.startActivity(it) } catch (_: Exception) {}
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. 通知栏垃圾大扫除（P2-3 分组保护 + P3-1 完整展开 + P3-2 状态回显）
        val highSpamNotifs = remember(snapshot.notificationCandidates) {
            snapshot.notificationCandidates.filter { it.isHighSpam }
        }
        val otherNormalNotifs = remember(snapshot.notificationCandidates) {
            snapshot.notificationCandidates.filter { !it.isHighSpam }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("通知栏垃圾大扫除（建议关 ${highSpamNotifs.size} 个）", fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text("已自动保护微信/银行/反诈/外卖/快递/医院挂号", fontSize = 13.sp, color = Color(0xFF64748B))
                    }
                    TextButton(onClick = { showNotificationCleaner = !showNotificationCleaner }) {
                        Text(if (showNotificationCleaner) "收起" else "去关", fontWeight = FontWeight.Bold)
                    }
                }
                if (showNotificationCleaner) {
                    Spacer(Modifier.height(8.dp))
                    if (highSpamNotifs.isEmpty()) {
                        Text("未发现高发广告推送大户，通知栏很清爽。", fontSize = 14.sp, color = Color(0xFF15803D))
                    } else {
                        val visibleHigh = if (expandAllHighSpamNotifs) highSpamNotifs else highSpamNotifs.take(10)
                        visibleHigh.forEach { item ->
                            NotificationCleanerRow(
                                item = item,
                                handledLocally = item.packageName in notifHandledPkgs,
                                emphasizeAction = true,
                                onToggleHandled = { GuardStore.toggleNotificationHandled(context, item.packageName) },
                                onOpenSettings = { openNotificationSettingsForApp(item.packageName) }
                            )
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                        if (highSpamNotifs.size > 10) {
                            TextButton(
                                onClick = { expandAllHighSpamNotifs = !expandAllHighSpamNotifs },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (expandAllHighSpamNotifs) "收起高发广告列表" else "展开全部高发广告软件（共 ${highSpamNotifs.size} 个）",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 其余普通软件默认折叠，不主动诱导关通知
                    if (otherNormalNotifs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showOtherNormalNotifs = !showOtherNormalNotifs },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (showOtherNormalNotifs) "收起其它普通软件" else "查看其它普通软件（不推荐关，共 ${otherNormalNotifs.size} 个）",
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                        }
                        if (showOtherNormalNotifs) {
                            Spacer(Modifier.height(6.dp))
                            val visibleOther = if (expandAllOtherNotifs) otherNormalNotifs else otherNormalNotifs.take(15)
                            visibleOther.forEach { item ->
                                NotificationCleanerRow(
                                    item = item,
                                    handledLocally = item.packageName in notifHandledPkgs,
                                    emphasizeAction = false,
                                    onToggleHandled = { GuardStore.toggleNotificationHandled(context, item.packageName) },
                                    onOpenSettings = { openNotificationSettingsForApp(item.packageName) }
                                )
                                HorizontalDivider(color = Color(0xFFF1F5F9))
                            }
                            if (otherNormalNotifs.size > 15) {
                                TextButton(
                                    onClick = { expandAllOtherNotifs = !expandAllOtherNotifs },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (expandAllOtherNotifs) "收起列表" else "展开全部（共 ${otherNormalNotifs.size} 个）",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 4. 清理策略与防流氓规则开关
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("清理与防流氓规则开关", fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Spacer(Modifier.height(8.dp))

                GuardRow(
                    "清完自动关闭无障碍（银行防拦截模式）",
                    "开启=清完自动关，不惹银行弹窗；若实测家长手机银行不弹窗，关闭此项即可让家长每次真正【点一下直接清】，彻底不用再进系统设置！",
                    cfg.autoDisableCleanAccessibility
                ) { GuardStore.saveConfig(context, cfg.copy(autoDisableCleanAccessibility = it)) }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                GuardRow(
                    "清理缓存时顺便强停多余后台",
                    "绝不强停微信、QQ、支付宝、银行与闹钟（且核对目标页应用名）",
                    cfg.alsoForceStopOnClean
                ) { GuardStore.saveConfig(context, cfg.copy(alsoForceStopOnClean = it)) }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                GuardRow(
                    "拦截高风险/未知来源安装包弹窗（需开常驻）",
                    "仅挡高风险与流氓马甲安装，绝不拦截卸载或官方商店更新",
                    cfg.blockHighRiskInstall
                ) { GuardStore.saveConfig(context, cfg.copy(blockHighRiskInstall = it)) }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                GuardRow(
                    "拦截快应用广告与流氓马甲乱跳（需开常驻）",
                    "自动退回快应用弹窗与疑似领红包/极速清理马甲",
                    cfg.blockQuickAppPopup && cfg.blockRogueAppJump
                ) { GuardStore.saveConfig(context, cfg.copy(blockQuickAppPopup = it, blockRogueAppJump = it)) }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (isGuardPaused) {
                            GuardStore.pauseGuardForMinutes(context, 0)
                            GuardStore.postBanner("已恢复防流氓拦截守护。")
                        } else {
                            GuardStore.pauseGuardForMinutes(context, 10)
                            GuardStore.postBanner("已暂停防流氓拦截 10 分钟（已持久化，杀后台不失效），子女可放心装软件。")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (isGuardPaused) "当前已暂停拦截 · 点此立即恢复" else "暂停防流氓拦截 10 分钟（方便子女装软件）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEA580C)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 5. 高缓存软件手动去清理 + 一键加保护
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("高缓存软件排行（手动清理 / 加保护）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text("不想清理某个软件可点「加保护」锁进个人白名单", fontSize = 13.sp, color = Color(0xFF64748B))
                    }
                    TextButton(onClick = { showHighCacheList = !showHighCacheList }) {
                        Text(if (showHighCacheList) "收起" else "查看(${snapshot.cacheCandidates.size})", fontWeight = FontWeight.Bold)
                    }
                }
                if (showHighCacheList) {
                    Spacer(Modifier.height(8.dp))
                    if (!snapshot.hasUsagePermission) {
                        Text("请先在顶部「一站式权限配置中心」开启使用情况权限。", fontSize = 14.sp, color = Color(0xFF9A3412))
                    } else if (snapshot.cacheCandidates.isEmpty()) {
                        Text(
                            text = if (scanning) "扫描中..." else "目前非常干净，没有大于 5MB 缓存的软件！",
                            fontSize = 14.5.sp, color = Color(0xFF15803D), fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        val visibleCaches = if (expandAllHighCache) snapshot.cacheCandidates else snapshot.cacheCandidates.take(15)
                        visibleCaches.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.appName, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(
                                        text = "缓存约 ${SystemScanner.formatBytes(app.cacheBytes)}",
                                        fontSize = 13.sp, color = Color(0xFFEA580C), fontWeight = FontWeight.SemiBold
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        GuardStore.toggleCustomProtected(context, app.packageName)
                                        GuardStore.postBanner("已将「${app.appName}」加入个人保护名单。")
                                        onRefresh()
                                    }
                                ) {
                                    Text("加保护", fontSize = 13.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${app.packageName}")
                                            })
                                        } catch (_: Exception) {}
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                ) {
                                    Text("去清理", fontSize = 13.5.sp, color = Color.White)
                                }
                            }
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                        if (snapshot.cacheCandidates.size > 15) {
                            TextButton(
                                onClick = { expandAllHighCache = !expandAllHighCache },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (expandAllHighCache) "收起列表" else "展开全部（共 ${snapshot.cacheCandidates.size} 个）",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 6. 已装软件排查与流氓卸载
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("已装软件排查（疑似流氓 ${snapshot.suspectRogueCount} 个）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text("可核对银行保护状态、解除个人保护或卸载流氓马甲", fontSize = 13.sp, color = Color(0xFF64748B))
                    }
                    TextButton(onClick = { showAppAudit = !showAppAudit }) {
                        Text(if (showAppAudit) "收起" else "排查", fontWeight = FontWeight.Bold)
                    }
                }
                if (showAppAudit) {
                    Spacer(Modifier.height(8.dp))
                    snapshot.auditedApps.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                Text(
                                    text = when {
                                        app.isBuiltInStrict -> "铁壁保护（微信/银行/反诈/相册）"
                                        app.isCustomLocked -> "已手动加保护（不自动清理）"
                                        app.isTrustedRegular -> "正规常用软件（安全，可清缓存）"
                                        app.isSuspectRogue -> "疑似流氓清理/领红包马甲（建议卸载）"
                                        else -> "普通第三方软件"
                                    },
                                    fontSize = 13.sp,
                                    color = when {
                                        app.isSuspectRogue -> Color(0xFFDC2626)
                                        app.isStrictProtected || app.isTrustedRegular -> Color(0xFF15803D)
                                        else -> Color(0xFF475569)
                                    }
                                )
                            }
                            if (app.isCustomLocked) {
                                TextButton(
                                    onClick = {
                                        GuardStore.toggleCustomProtected(context, app.packageName)
                                        GuardStore.postBanner("已将「${app.appName}」移出个人保护名单。")
                                        onRefresh()
                                    }
                                ) {
                                    Text("解除保护", fontSize = 13.sp, color = Color(0xFF2563EB))
                                }
                            } else if (!app.isBuiltInStrict && !app.isTrustedRegular) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_DELETE).apply {
                                                data = Uri.parse("package:${app.packageName}")
                                            })
                                        } catch (_: Exception) {}
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("卸载", fontSize = 13.5.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 7. 可选 4 位子女管理锁 + 拦截日志
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("子女管理锁（4位密码，支持锁屏找回）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Text(
                        text = if (hasAdminPin) "已开启：切回主页自动上锁；忘记密码可用手机锁屏密码或恢复码找回。" else "未开启：可设 4 位密码防家长手滑误改设置。",
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )
                }
                OutlinedButton(
                    onClick = { showPinDialog = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (hasAdminPin) "修改/关闭" else "设置锁", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (blockLogs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近拦截记录（已持久化保存）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Spacer(Modifier.height(6.dp))
                    blockLogs.forEach { item ->
                        Text("• $item", fontSize = 14.sp, color = Color(0xFF334155), modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 8. P0-0 & P2-4 独立隔离的离线诊断日志卡（折叠时不订阅 StateFlow，清理过程零整页重组）
        DiagLogCard(cfg = cfg)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NotificationCleanerRow(
    item: NotificationMuteCandidate,
    handledLocally: Boolean,
    emphasizeAction: Boolean,
    onToggleHandled: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isSysDisabled = item.systemNotifState == AppNotifState.DISABLED
    val isDone = isSysDisabled || handledLocally
    val statusText = when {
        isSysDisabled -> "✓ 系统已关闭通知"
        handledLocally -> "✓ 已去设置过（点此可取消标记）"
        item.systemNotifState == AppNotifState.ENABLED -> "● 通知开启中（高发广告，建议关闭）"
        item.isHighSpam -> "高发广告推送软件（建议关闭）"
        else -> "普通第三方软件（按需处理）"
    }
    val statusColor = when {
        isDone -> Color(0xFF15803D)
        item.isHighSpam -> Color(0xFFEA580C)
        else -> Color(0xFF64748B)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !isSysDisabled) { onToggleHandled() }
        ) {
            Text(item.appName, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(text = statusText, fontSize = 12.5.sp, color = statusColor, fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal)
        }
        Spacer(Modifier.width(8.dp))
        if (isDone) {
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("复查", fontSize = 13.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (emphasizeAction) Color(0xFFEA580C) else Color(0xFF64748B)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (emphasizeAction) "关通知" else "去设置", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AntiPitfallCheckItem(
    checked: Boolean,
    title: String,
    desc: String,
    actionText: String?,
    onToggle: () -> Unit,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (checked) Color(0xFF15803D) else Color(0xFF0F172A)
            )
            Text(desc, fontSize = 12.5.sp, color = Color(0xFF475569))
        }
        if (actionText != null) {
            TextButton(onClick = onAction) {
                Text(actionText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
            }
        }
    }
}


@Composable
private fun DiagLogCard(cfg: GuardConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("清缓存诊断日志（离线排错）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Text("纯本地记录每步点击与耗时，出问题导出发出来一眼定位", fontSize = 13.sp, color = Color(0xFF64748B))
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "查看", fontWeight = FontWeight.Bold)
                }
            }
            if (expanded) {
                DiagLogExpandedBody(
                    cfg = cfg,
                    onExport = {
                        scope.launch {
                            val (ok, pathOrErr) = withContext(Dispatchers.IO) {
                                CleanDiagLogger.exportNow(context)
                            }
                            GuardStore.postBanner(
                                if (ok) "诊断日志已导出至：$pathOrErr" else "导出失败：$pathOrErr"
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DiagLogExpandedBody(
    cfg: GuardConfig,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val diagLines by CleanDiagLogger.lines.collectAsState()

    Spacer(Modifier.height(8.dp))
    GuardRow(
        "自动追加日志到 Download 文件夹",
        "文件名为 ParentSuperGuard-diag-日期.txt（含本机应用名，排查完请删除）",
        cfg.exportDiagToDownload
    ) { GuardStore.saveConfig(context, cfg.copy(exportDiagToDownload = it)) }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("ParentSuperGuardDiag", CleanDiagLogger.getFullText()))
                GuardStore.postBanner("已复制全部诊断日志（共 ${diagLines.size} 条）到剪贴板。")
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("复制日志", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("导出文件", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
        }
        TextButton(
            onClick = {
                CleanDiagLogger.clear(context)
                GuardStore.postBanner("已清空诊断日志。")
            }
        ) {
            Text("清空", fontSize = 13.sp, color = Color(0xFFDC2626))
        }
    }

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
    ) {
        val preview = if (diagLines.isEmpty()) {
            "暂无日志，执行一次清理后会记录每一步状态。"
        } else {
            "共 ${diagLines.size} 条（仅显示最近 25 条）：\n" + diagLines.takeLast(25).joinToString("\n")
        }
        Text(
            text = preview,
            modifier = Modifier.padding(12.dp),
            fontSize = 11.5.sp,
            color = Color(0xFF334155)
        )
    }
}
