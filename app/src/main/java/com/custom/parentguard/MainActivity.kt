package com.custom.parentguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardStore.load(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FB)) {
                    ParentSuperGuardScreen()
                }
            }
        }
    }
}

private fun hasExternalStorageAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun ParentSuperGuardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val rom = remember { BrandDetector.detect() }

    val cfg by GuardStore.config.collectAsState()
    val cleanState by GuardStore.cleanState.collectAsState()
    val bannerMessage by GuardStore.bannerMessage.collectAsState()
    val blockedCount by GuardStore.blockedCount.collectAsState()
    val blockLogs by GuardStore.blockLogs.collectAsState()
    val cleanConnected by GuardStore.cleanServiceConnected.collectAsState()
    val rogueConnected by GuardStore.rogueServiceConnected.collectAsState()
    val pausedUntil by GuardStore.guardPausedUntilMs.collectAsState()

    var snapshot by remember { mutableStateOf(GuardSnapshot()) }
    var scanning by remember { mutableStateOf(false) }
    var hasFilesPerm by remember { mutableStateOf(hasExternalStorageAccess(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showWechatGuide by remember { mutableStateOf(false) }
    var showRestrictedGuide by remember { mutableStateOf(false) }
    var showAppAudit by remember { mutableStateOf(false) }

    // 严格以服务真实运行态为准，避免小米/vivo 僵尸无障碍导致“假就绪”或按钮卡死
    val cleanSvcRunning = cleanConnected || CacheCleanAccessibilityService.isServiceRunning()
    val rogueSvcRunning = rogueConnected || RogueGuardAccessibilityService.isServiceRunning()
    val isGuardPaused = nowMs < pausedUntil

    LaunchedEffect(pausedUntil) {
        nowMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < pausedUntil) {
            delay(15_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    fun refreshAll() {
        if (scanning) return
        scope.launch {
            scanning = true
            nowMs = System.currentTimeMillis()
            hasFilesPerm = hasExternalStorageAccess(context)
            // 如果之前点了清理去开无障碍，但回来时服务仍未真实运行，自动解除按钮等待态
            if (GuardStore.sessionActive && !CacheCleanAccessibilityService.isServiceRunning()) {
                GuardStore.resetIfServiceNotEnabled()
            }
            snapshot = SystemScanner.scanSnapshot(context)
            scanning = false
        }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFilesPerm = granted
        if (granted) refreshAll()
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 顶部超级安心看板
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF15803D), Color(0xFF1D4ED8))),
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
                        text = "安心超级卫士 v5.6",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { refreshAll() },
                        enabled = !scanning && !cleanState.running
                    ) {
                        Text(if (scanning) "扫描中" else "重新扫描", color = Color(0xFFFEF08A), fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "已适配：${rom.displayName} · 零联网无广告",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFEF08A)
                )
                Spacer(Modifier.height(14.dp))

                val ratio = if (snapshot.totalStorageBytes > 0) {
                    (snapshot.usedStorageBytes.toFloat() / snapshot.totalStorageBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = Color(0xFFFDE047),
                    trackColor = Color(0x44000000)
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已用 ${SystemScanner.formatBytes(snapshot.usedStorageBytes)}", fontSize = 14.sp, color = Color.White)
                    Text("剩余 ${SystemScanner.formatBytes(snapshot.availStorageBytes)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFEF08A))
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("可安全清理缓存", fontSize = 14.sp, color = Color(0xFFDCFCE7))
                        Text(
                            text = when {
                                scanning -> "扫描中..."
                                !snapshot.hasUsagePermission -> "需先开统计权限"
                                else -> SystemScanner.formatBytes(snapshot.totalCleanableBytes)
                            },
                            fontSize = if (!snapshot.hasUsagePermission) 22.sp else 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("已挡流氓弹窗", fontSize = 14.sp, color = Color(0xFFDBEAFE))
                        Text("$blockedCount 次", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFDE047))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "绝对保护：微信、QQ、支付宝、云闪付、银行、反诈、医保、相册（可安全清抖音/快手/拼多多/淘宝缓存）",
                    fontSize = 12.5.sp, color = Color(0xFFBBF7D0)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 子女配置状态速览条
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusChip("统计权限", snapshot.hasUsagePermission)
                StatusChip("清缓存", cleanSvcRunning)
                StatusChip("文件清理", hasFilesPerm)
                StatusChip("防流氓", rogueSvcRunning)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!bannerMessage.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFDCFCE7))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = bannerMessage ?: "",
                        modifier = Modifier.weight(1f),
                        fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14532D)
                    )
                    TextButton(onClick = { GuardStore.postBanner(null) }) {
                        Text("知道了", fontSize = 13.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 第 1 步（强制前置）：使用情况访问权限
        if (!snapshot.hasUsagePermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("第 1 步（只需子女帮开一次）：查看缓存大小", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412))
                    Spacer(Modifier.height(4.dp))
                    Text("开启后软件才能精准挑出缓存大于 5MB 的软件来清（避免盲目翻遍手机里几十个软件吓到家长）。", fontSize = 14.sp, color = Color(0xFF7C2D12))
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))
                    ) {
                        Text("去开启「使用情况权限」", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 核心大按钮：一键安全清理（兼容真实在线态与僵尸服务休眠恢复）
        Button(
            onClick = {
                GuardStore.postBanner(null)
                val cur = snapshot
                scope.launch {
                    val availBefore = SystemScanner.getCurrentAvailBytes()
                    val extFreed = SystemScanner.cleanExternalSafeFiles(cur)
                    val targets = cur.cacheCandidates
                    if (targets.isEmpty()) {
                        val msg = if (extFreed > 0L) {
                            "非常干净！已清理旧缩略图与系统日志 ${SystemScanner.formatBytes(extFreed)}，目前没有大于 5MB 的待清软件。"
                        } else {
                            "非常干净！目前没有大于 5MB 的待清软件，手机状态良好。"
                        }
                        GuardStore.postBanner(msg)
                        refreshAll()
                        return@launch
                    }

                    GuardStore.startCleanQueue(targets, availBefore, extFreed)
                    val liveSvc = CacheCleanAccessibilityService.instance
                    if (liveSvc != null) {
                        liveSvc.beginCacheQueue()
                    } else {
                        val isZombie = CacheCleanAccessibilityService.isEnabledInSettings(context)
                        val tip = if (isZombie) {
                            "系统暂时休眠了清缓存服务：请在无障碍中把「安心卫士 · 自动清缓存」先关再开一次，打开后立刻自动开始清理！"
                        } else {
                            "请开启「安心卫士 · 自动清缓存（清完即关）」（${rom.accessibilityPathHint}），开启后自动开始清理！"
                        }
                        GuardStore.postBanner(tip)
                        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                    }
                }
            },
            enabled = !scanning && !cleanState.running && snapshot.hasUsagePermission,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
        ) {
            Text(
                text = when {
                    !snapshot.hasUsagePermission -> "请先完成第 1 步（开启使用情况权限）"
                    cleanState.running -> "正在自动清理 (${cleanState.currentIndex}/${cleanState.totalCount})..."
                    !cleanSvcRunning -> "一键安全清理（去开清缓存开关）"
                    else -> "一键安全清理高缓存软件（最多前 ${GuardStore.MAX_APPS_PER_BATCH} 个）"
                },
                fontSize = 17.5.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
        }

        if (cleanState.running) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    CacheCleanAccessibilityService.instance?.stopImmediately()
                    GuardStore.cancelCleanQueue()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("停止自动清理", fontSize = 16.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
            }
        }

        // Android 13/14/15+ 受限设置 2 步解锁引导
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (!cleanSvcRunning || !rogueSvcRunning)) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "小米/vivo 无障碍开关变灰「受限设置」？",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showRestrictedGuide = !showRestrictedGuide }) {
                            Text(if (showRestrictedGuide) "收起" else "2步解锁", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (showRestrictedGuide) {
                        Text(
                            text = "新系统默认锁住手动安装软件的无障碍开关，子女帮解锁一次即可：\n1. 点下面按钮打开本软件「应用信息」\n2. 点右上角「⋮ 三个点」（或底部菜单）→ 点【允许受限设置】并验证锁屏密码，回来就能开无障碍了！",
                            fontSize = 13.5.sp, color = Color(0xFF1E40AF)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    })
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("打开本软件「应用信息」允许受限设置", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 清理与防银行风控设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("清理与防银行风控设置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Spacer(Modifier.height(8.dp))
                GuardRow(
                    "清完自动关闭「清缓存无障碍」（强烈推荐）",
                    "清理结束立刻自动关闭，彻底规避银行 App / 云闪付风控弹窗",
                    cfg.autoDisableCleanAccessibility
                ) { GuardStore.saveConfig(context, cfg.copy(autoDisableCleanAccessibility = it)) }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                GuardRow(
                    "清理缓存时顺便强停多余后台",
                    "绝不强停微信、QQ、支付宝、银行与闹钟（且二次核对页面应用名）",
                    cfg.alsoForceStopOnClean
                ) { GuardStore.saveConfig(context, cfg.copy(alsoForceStopOnClean = it)) }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 独立可选防流氓弹窗守护 + 系统级防误装直达入口
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
                    Text("防流氓误装与弹窗守护", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), modifier = Modifier.weight(1f))
                    Text(
                        text = if (rogueSvcRunning) "常驻运行中" else "未开常驻",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (rogueSvcRunning) Color(0xFF15803D) else Color(0xFF64748B)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "最稳防流氓妙招（零银行风控）：点下方按钮进入系统「安装未知应用」，把浏览器/抖音/头条的安装权限全部关掉，比常驻拦截还省心！",
                    fontSize = 13.sp, color = Color(0xFF475569)
                )
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                        } catch (_: Exception) {
                            try { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("直达系统「安装未知应用」关掉广告误装权限", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D4ED8))
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFE2E8F0))

                GuardRow(
                    "拦截高风险/未知来源安装包弹窗（需开常驻）",
                    "仅挡高风险与流氓马甲安装提示，绝不拦截卸载或官方商店更新",
                    cfg.blockHighRiskInstall
                ) { GuardStore.saveConfig(context, cfg.copy(blockHighRiskInstall = it)) }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                GuardRow(
                    "拦截快应用广告与流氓马甲乱跳（需开常驻）",
                    "自动退回快应用弹窗与疑似领红包/极速清理马甲",
                    cfg.blockQuickAppPopup && cfg.blockRogueAppJump
                ) { GuardStore.saveConfig(context, cfg.copy(blockQuickAppPopup = it, blockRogueAppJump = it)) }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (rogueSvcRunning) "管理常驻开关" else "开启常驻守护", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (isGuardPaused) {
                                GuardStore.pauseGuardForMinutes(context, 0)
                                GuardStore.postBanner("已恢复防流氓拦截守护。")
                            } else {
                                GuardStore.pauseGuardForMinutes(context, 10)
                                GuardStore.postBanner("已暂停防流氓拦截 10 分钟（已保存，后台重启不失效），子女可放心装软件。")
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isGuardPaused) "恢复拦截" else "暂停10分钟", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEA580C))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 微信安全清理大字向导
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("微信安全清缓存（绝不丢照片和聊天）", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                Spacer(Modifier.height(6.dp))
                Text("本软件绝不私自删微信目录。推荐走微信官方自带「存储空间 → 清理缓存」。", fontSize = 14.sp, color = Color(0xFF047857))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showWechatGuide = !showWechatGuide },
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text(if (showWechatGuide) "收起步骤" else "看 3 步清微信", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let {
                                try { context.startActivity(it) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.weight(0.8f).heightIn(min = 46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("打开微信", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                    }
                }
                if (showWechatGuide) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "1. 打开微信 → 点右下角「我」→ 点「设置」\n2. 点「通用」→ 点「存储空间」\n3. 只点「缓存」旁边的绿色【清理】按钮！（照片和聊天记录一条都不会少）",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF064E3B)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 零无障碍备选：高缓存软件排行
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("高缓存软件排行（不开无障碍也能手动点）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Spacer(Modifier.height(4.dp))
                Text("已剔除微信、银行、相册；不想动某个软件也可以点「加保护」锁进白名单：", fontSize = 13.sp, color = Color(0xFF64748B))
                Spacer(Modifier.height(8.dp))
                if (!snapshot.hasUsagePermission) {
                    Text(
                        text = "开启上方第 1 步「使用情况权限」后，这里会自动按缓存从大到小列出需要清理的软件。",
                        fontSize = 14.5.sp, color = Color(0xFF9A3412), fontWeight = FontWeight.SemiBold
                    )
                } else if (snapshot.cacheCandidates.isEmpty()) {
                    Text(
                        text = if (scanning) "扫描中..." else "目前非常干净，没有大于 5MB 缓存的软件！",
                        fontSize = 15.sp, color = Color(0xFF15803D), fontWeight = FontWeight.SemiBold
                    )
                } else {
                    snapshot.cacheCandidates.take(12).forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                Text(
                                    text = "缓存约 ${SystemScanner.formatBytes(app.cacheBytes)}",
                                    fontSize = 13.sp, color = Color(0xFFEA580C), fontWeight = FontWeight.SemiBold
                                )
                            }
                            TextButton(
                                onClick = {
                                    GuardStore.toggleCustomProtected(context, app.packageName)
                                    GuardStore.postBanner("已将「${app.appName}」加入个人保护名单，今后清理将自动跳过。")
                                    refreshAll()
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
                                Text("去清理", fontSize = 14.sp, color = Color.White)
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 已装软件排查
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
                        Text("仅排查第三方软件，绝不误列系统桌面与安全中心", fontSize = 13.sp, color = Color(0xFF64748B))
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
                                Text(app.appName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                Text(
                                    text = when {
                                        app.isBuiltInStrict -> "铁壁保护（微信/银行/相册）"
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
                                        refreshAll()
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
                                    Text("卸载", fontSize = 14.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
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

        if (!hasFilesPerm) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
                        }
                    } else {
                        legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("（可选）授权清理相册旧缩略图与系统日志", fontSize = 14.sp, color = Color(0xFF475569))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun StatusChip(label: String, ok: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = Color(0xFF64748B))
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (ok) "● 就绪" else "○ 未开",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (ok) Color(0xFF15803D) else Color(0xFF94A3B8)
        )
    }
}

@Composable
fun GuardRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 13.sp, color = Color(0xFF475569))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
