package com.custom.parentguard

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardStore.ensureLoaded(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FB)) {
                    ParentSuperGuardApp()
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
fun ParentSuperGuardApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val rom = remember { BrandDetector.detect() }

    val cfg by GuardStore.config.collectAsState()
    val cleanState by GuardStore.cleanState.collectAsState()
    val bannerMessage by GuardStore.bannerMessage.collectAsState()
    val blockLogs by GuardStore.blockLogs.collectAsState()
    val cleanHistory by GuardStore.cleanHistory.collectAsState()
    val checklistDoneIds by GuardStore.checklistDoneIds.collectAsState()
    val notifHandledPkgs by GuardStore.notifHandledPkgs.collectAsState()
    val adminPinHash by GuardStore.adminPinHash.collectAsState()
    val recoveryCodeHash by GuardStore.recoveryCodeHash.collectAsState()
    val pinFailedAttempts by GuardStore.pinFailedAttempts.collectAsState()
    val pinLockoutUntilMs by GuardStore.pinLockoutUntilMs.collectAsState()
    val cleanConnected by GuardStore.cleanServiceConnected.collectAsState()
    val rogueConnected by GuardStore.rogueServiceConnected.collectAsState()
    val pausedUntil by GuardStore.guardPausedUntilMs.collectAsState()
    val onboardingCompleted by GuardStore.onboardingCompleted.collectAsState()
    val restrictedConfirmed by GuardStore.restrictedConfirmed.collectAsState()

    var snapshot by remember { mutableStateOf(GuardSnapshot()) }
    var scanning by remember { mutableStateOf(false) }
    var hasUsageLive by remember { mutableStateOf(SystemScanner.hasUsageStats(context)) }
    var hasFilesPerm by remember { mutableStateOf(hasExternalStorageAccess(context)) }
    var cleanInSettings by remember { mutableStateOf(CacheCleanAccessibilityService.isEnabledInSettings(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // P3-3 修复：使用 rememberSaveable，旋转屏幕 / 分屏 / 折叠屏展开不丢失页签和解锁状态
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var adminUnlocked by rememberSaveable { mutableStateOf(false) }

    var showUnlockDialog by remember { mutableStateOf(false) }
    var unlockUsingRecoveryMode by remember { mutableStateOf(false) }
    var unlockPinInput by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var isVerifyingPin by remember { mutableStateOf(false) }

    var showNeedSwitchDialog by remember { mutableStateOf(false) }
    var needSwitchIsZombie by remember { mutableStateOf(false) }

    var showPermissionCenter by rememberSaveable {
        mutableStateOf(
            PermissionStepPlanner.shouldAutoOpenCenterOnLaunch(
                onboardingCompleted = onboardingCompleted,
                hasUsageStats = SystemScanner.hasUsageStats(context)
            )
        )
    }

    val cleanSvcRunning = cleanConnected || CacheCleanAccessibilityService.isServiceRunning()
    val rogueSvcRunning = rogueConnected || RogueGuardAccessibilityService.isServiceRunning()
    val isGuardPaused = nowMs < pausedUntil
    val hasAdminPin = !adminPinHash.isNullOrBlank()
    val hasRecoveryCode = !recoveryCodeHash.isNullOrBlank()
    val pinLockoutRemainingMs = (pinLockoutUntilMs - nowMs).coerceAtLeast(0L)
    val isPinLockedOut = pinLockoutRemainingMs > 0L

    val permSnapshot = PermissionSnapshot(
        hasUsageStats = hasUsageLive,
        isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        restrictedConfirmed = restrictedConfirmed,
        cleanServiceRunning = cleanSvcRunning,
        cleanServiceZombie = !cleanSvcRunning && cleanInSettings,
        hasExternalFiles = hasFilesPerm,
        rogueServiceRunning = rogueSvcRunning
    )

    // P2-9 修复：自动关闭无障碍（disableSelf）后延迟刷新系统设置态，消除回主页状态灯短暂不一致
    LaunchedEffect(cleanConnected) {
        if (!cleanConnected) {
            delay(800L)
            cleanInSettings = CacheCleanAccessibilityService.isEnabledInSettings(context)
        }
    }

    // 暂停防流氓倒计时刷新
    LaunchedEffect(pausedUntil) {
        nowMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < pausedUntil) {
            delay(15_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    // P2-1 修复：管理锁冷却倒计时每秒刷新
    LaunchedEffect(pinLockoutUntilMs, showUnlockDialog) {
        nowMs = System.currentTimeMillis()
        while (showUnlockDialog && System.currentTimeMillis() < pinLockoutUntilMs) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    // P1-1 修复：系统锁屏密码 / 指纹认证回调（验证通过立即无损重置管理锁）
    val deviceCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    GuardStore.resetAdminPinBySystemAuth(context)
                }
                adminUnlocked = true
                selectedTab = 1
                showUnlockDialog = false
                unlockUsingRecoveryMode = false
                unlockPinInput = ""
                unlockError = null
            }
        } else {
            unlockError = "未完成手机锁屏验证，请重试或使用 8 位恢复码"
        }
    }

    fun launchSystemLockScreenRecovery() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null || !km.isDeviceSecure) {
            if (hasRecoveryCode) {
                unlockUsingRecoveryMode = true
                unlockPinInput = ""
                unlockError = "此手机未设锁屏密码，请输入设置管理锁时保存的 8 位恢复码。"
            } else {
                unlockError = "此手机未设锁屏密码。可在系统「设置 → 应用 → 安心超级卫士 → 清除数据」重置（会清空个人保护名单）。"
            }
            return
        }
        try {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(
                "验证手机身份以重置管理锁",
                "验证手机锁屏密码或指纹后，将关闭子女管理锁并完整保留所有白名单"
            )
            if (intent != null) {
                deviceCredentialLauncher.launch(intent)
            } else {
                unlockError = "当前系统不支持锁屏认证，请改用 8 位恢复码。"
            }
        } catch (_: Exception) {
            unlockError = "无法唤起系统锁屏验证，请改用 8 位恢复码。"
        }
    }

    fun refreshAll() {
        if (scanning) return
        scope.launch {
            scanning = true
            nowMs = System.currentTimeMillis()
            hasUsageLive = SystemScanner.hasUsageStats(context)
            hasFilesPerm = hasExternalStorageAccess(context)
            cleanInSettings = CacheCleanAccessibilityService.isEnabledInSettings(context)

            if (CacheCleanAccessibilityService.isServiceRunning() && !restrictedConfirmed) {
                GuardStore.markRestrictedConfirmed(context, true)
            }
            // P1-3 修复：不再在 ON_RESUME 一刀切取消（防止锁屏认证/授权/转屏误杀任务），
            // 仅在服务已停止时复位；若用户主动切回主页，由服务 2-hit 看门狗或主页「停止」按钮裁决
            if (GuardStore.sessionActive && !CacheCleanAccessibilityService.isServiceRunning()) {
                GuardStore.resetIfServiceNotEnabled()
            }
            snapshot = SystemScanner.scanSnapshot(context)
            hasUsageLive = snapshot.hasUsagePermission
            scanning = false
        }
    }

    /** P2-8 & P3-4：共享 45 秒快照新鲜度检查，主页大按钮与「现在去打开」弹窗统一复用 */
    suspend fun prepareFreshSnapshot(): GuardSnapshot {
        return if (snapshot.scannedAtMs == 0L || System.currentTimeMillis() - snapshot.scannedAtMs > 45_000L) {
            scanning = true
            val fresh = try { SystemScanner.scanSnapshot(context) } finally { scanning = false }
            snapshot = fresh
            hasUsageLive = fresh.hasUsagePermission
            fresh
        } else {
            snapshot
        }
    }

    fun launchCleanOrPrompt() {
        if (!hasUsageLive) {
            if (hasAdminPin && !adminUnlocked) {
                GuardStore.postBanner("首次使用还差一步设置，请把手机交给孩子在下方「子女设置」里帮点一下。")
            } else {
                showPermissionCenter = true
            }
            return
        }

        GuardStore.postBanner(null)
        scope.launch {
            val cur = prepareFreshSnapshot()

            val targets = cur.cacheCandidates
            val currentSvc = CacheCleanAccessibilityService.instance
            if (targets.isNotEmpty() && currentSvc == null) {
                needSwitchIsZombie = CacheCleanAccessibilityService.isEnabledInSettings(context)
                showNeedSwitchDialog = true
                return@launch
            }

            val availBefore = SystemScanner.getCurrentAvailBytes()
            val extFreed = SystemScanner.cleanExternalSafeFiles(cur)
            if (targets.isEmpty()) {
                val msg = if (extFreed > 0L) {
                    GuardStore.recordCleanHistory(context, 0, extFreed)
                    "已经清理好了！释放了 ${SystemScanner.formatBytes(extFreed)} 空间，手机现在很干净。"
                } else {
                    "手机现在很干净，不需要清理啦！"
                }
                GuardStore.postBanner(msg)
                refreshAll()
                return@launch
            }

            val queuedCount = GuardStore.startCleanQueue(targets, availBefore, extFreed)
            if (queuedCount == 0) {
                GuardStore.postBanner("刚刚已经全部清理过啦，手机现在很干净！")
                refreshAll()
                return@launch
            }
            // P1-6 修复：启动前再次实时取 instance，避免点击瞬间服务销毁导致主页永久卡死
            val liveNow = CacheCleanAccessibilityService.instance
            if (liveNow != null) {
                liveNow.beginCacheQueue()
            } else {
                GuardStore.resetIfServiceNotEnabled()
            }
        }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFilesPerm = granted
        if (granted) refreshAll()
    }

    fun requestExternalFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            } catch (_: Exception) {
                try { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
            }
        } else {
            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
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

    if (showPermissionCenter) {
        PermissionCenterScreen(
            rom = rom,
            permState = permSnapshot,
            onRequestExternalFiles = { requestExternalFilesPermission() },
            onMarkRestrictedDone = { GuardStore.markRestrictedConfirmed(context, true) },
            onFinishAndEnterHome = {
                if (permSnapshot.essentialReady) {
                    GuardStore.markOnboardingCompleted(context, true)
                }
                showPermissionCenter = false
                refreshAll()
            }
        )
        return
    }

    if (showNeedSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showNeedSwitchDialog = false },
            title = {
                Text(
                    text = if (needSwitchIsZombie) "清理开关休息了，重新点开即可" else "需要打开一下清理开关",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (needSwitchIsZombie) {
                        "手机刚刚把清理开关休眠了。\n\n点下面绿色按钮后，找到「安心卫士 · 自动清缓存」，先关一下再打开，就会自动帮您清完手机垃圾！"
                    } else {
                        "为了安全不惹银行软件弹窗，上次清完已自动关掉开关。\n\n点下面绿色按钮后，在「已下载的应用 / 服务」里找到「安心卫士 · 自动清缓存」打开开关，就会自动开始清理。\n\n（如果不想自己找，也可以拿给孩子帮忙点一下，或让孩子在「子女设置」里改成永久免开模式。）"
                    },
                    fontSize = 15.sp,
                    color = Color(0xFF334155)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNeedSwitchDialog = false
                        scope.launch {
                            val cur = prepareFreshSnapshot()
                            val availBefore = SystemScanner.getCurrentAvailBytes()
                            // 此时无障碍尚未开启，先不提前删外置文件，待用户真正打开开关后由服务启动时统一安全清理
                            val queuedCount = GuardStore.startCleanQueue(cur.cacheCandidates, availBefore, 0L)
                            if (queuedCount == 0) {
                                GuardStore.postBanner("刚刚已经全部清理过啦，手机现在很干净！")
                                refreshAll()
                                return@launch
                            }
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Exception) {
                                GuardStore.resetIfServiceNotEnabled()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                ) {
                    Text("现在去打开", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNeedSwitchDialog = false }) {
                    Text("先不弄了", fontSize = 15.sp, color = Color(0xFF64748B))
                }
            }
        )
    }

    // P1-1 & P2-1 升级版解锁弹窗（带防穷举冷却倒计时 + 手机锁屏密码找回 + 8 位恢复码兜底）
    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = {
                showUnlockDialog = false
                unlockUsingRecoveryMode = false
                unlockPinInput = ""
                unlockError = null
                isVerifyingPin = false
            },
            title = {
                Text(
                    text = if (unlockUsingRecoveryMode) "输入 8 位备用恢复码" else "请输入 4 位子女管理密码",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (unlockUsingRecoveryMode) {
                            "输入设置管理锁时保存的 8 位恢复码（不区分大小写与短横线），验证通过后将关闭管理锁。"
                        } else {
                            "用于防止家长误触修改高级开关，主页一键清理永远免密。"
                        },
                        fontSize = 13.5.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = unlockPinInput,
                        onValueChange = { v ->
                            if (unlockUsingRecoveryMode) {
                                if (v.length <= 10) {
                                    unlockPinInput = v
                                    unlockError = null
                                }
                            } else {
                                if (v.length <= 4 && v.all { it.isDigit() }) {
                                    unlockPinInput = v
                                    unlockError = null
                                }
                            }
                        },
                        enabled = !isPinLockedOut,
                        label = { Text(if (unlockUsingRecoveryMode) "例如：A3K9-7B2M" else "4 位数字密码") },
                        singleLine = true,
                        visualTransformation = if (unlockUsingRecoveryMode) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (unlockUsingRecoveryMode) KeyboardType.Ascii else KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isPinLockedOut) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "输错次数过多，请在 ${CleanDecisionRules.formatLockoutRemaining(pinLockoutRemainingMs)} 后重试（也可直接用手机锁屏密码找回）。",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDC2626)
                        )
                    } else if (unlockError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(unlockError ?: "", fontSize = 12.5.sp, color = Color(0xFFDC2626))
                    }

                    Spacer(Modifier.height(10.dp))
                    // P1-1 核心找回入口：手机锁屏密码/指纹验证重置（即使在冷却中也可使用系统锁屏验证证明机主身份）
                    OutlinedButton(
                        onClick = { launchSystemLockScreenRecovery() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("忘记密码？用手机锁屏密码/指纹解锁", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                    }

                    if (hasRecoveryCode) {
                        TextButton(
                            onClick = {
                                unlockUsingRecoveryMode = !unlockUsingRecoveryMode
                                unlockPinInput = ""
                                unlockError = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (unlockUsingRecoveryMode) "返回输入 4 位数字密码" else "没设锁屏密码？改用 8 位恢复码解锁",
                                fontSize = 12.5.sp,
                                color = Color(0xFF475569)
                            )
                        }
                    }

                    if (pinFailedAttempts >= 5) {
                        Text(
                            text = "极端兜底：如既无锁屏密码又遗失恢复码，可在系统「设置 → 应用 → 安心超级卫士 → 清除数据」重置（会清空个人保护名单）。",
                            fontSize = 11.5.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isPinLockedOut && !isVerifyingPin,
                    onClick = {
                        val inputCopy = unlockPinInput
                        val useRecovery = unlockUsingRecoveryMode
                        isVerifyingPin = true
                        unlockError = null
                        scope.launch {
                            val res = try {
                                withContext(Dispatchers.Default) {
                                    if (useRecovery) {
                                        GuardStore.attemptResetWithRecoveryCode(context, inputCopy)
                                    } else {
                                        GuardStore.attemptVerifyAdminPin(context, inputCopy)
                                    }
                                }
                            } finally {
                                isVerifyingPin = false
                            }
                            when (res) {
                                is PinVerifyResult.Success -> {
                                    adminUnlocked = true
                                    selectedTab = 1
                                    showUnlockDialog = false
                                    unlockUsingRecoveryMode = false
                                    unlockPinInput = ""
                                    unlockError = null
                                }
                                is PinVerifyResult.LockedOut -> {
                                    nowMs = System.currentTimeMillis()
                                    unlockError = "请等待 ${CleanDecisionRules.formatLockoutRemaining(res.remainingMs)} 后重试"
                                }
                                is PinVerifyResult.Wrong -> {
                                    nowMs = System.currentTimeMillis()
                                    unlockError = if (res.lockoutMs > 0L) {
                                        "密码错误（已错 ${res.failedAttempts} 次），请等待 ${CleanDecisionRules.formatLockoutRemaining(res.lockoutMs)} 后再试"
                                    } else {
                                        if (useRecovery) "恢复码不正确，请检查后重试" else "密码不正确（已错 ${res.failedAttempts} 次）"
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        text = when {
                            isVerifyingPin -> "验证中..."
                            isPinLockedOut -> "冷却 ${CleanDecisionRules.formatLockoutRemaining(pinLockoutRemainingMs)}"
                            unlockUsingRecoveryMode -> "验证恢复码"
                            else -> "解锁进入"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnlockDialog = false
                        unlockUsingRecoveryMode = false
                        unlockPinInput = ""
                        unlockError = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        containerColor = Color(0xFFF4F7FB),
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        if (hasAdminPin) adminUnlocked = false
                    },
                    icon = { Text("🏠", fontSize = 20.sp) },
                    label = { Text("一键清理", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        if (hasAdminPin && !adminUnlocked) {
                            nowMs = System.currentTimeMillis()
                            showUnlockDialog = true
                        } else {
                            selectedTab = 1
                        }
                    },
                    icon = { Text(if (hasAdminPin && !adminUnlocked) "🔒" else "🔧", fontSize = 18.sp) },
                    label = { Text("子女设置", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                ElderHomeScreen(
                    snapshot = snapshot,
                    cleanState = cleanState,
                    bannerMessage = bannerMessage,
                    latestCleanRecord = cleanHistory.firstOrNull(),
                    hasUsageLive = hasUsageLive,
                    scanning = scanning,
                    onStartClean = { launchCleanOrPrompt() },
                    onRefreshAfterCancel = { refreshAll() }
                )
            } else {
                ChildToolsScreen(
                    rom = rom,
                    snapshot = snapshot,
                    permSnapshot = permSnapshot,
                    cfg = cfg,
                    isGuardPaused = isGuardPaused,
                    blockLogs = blockLogs,
                    checklistDoneIds = checklistDoneIds,
                    notifHandledPkgs = notifHandledPkgs,
                    hasAdminPin = hasAdminPin,
                    scanning = scanning,
                    onOpenPermissionCenter = { showPermissionCenter = true },
                    onRefresh = { refreshAll() }
                )
            }
        }
    }
}

@Composable
fun ElderHomeScreen(
    snapshot: GuardSnapshot,
    cleanState: CleanRunState,
    bannerMessage: String?,
    latestCleanRecord: String?,
    hasUsageLive: Boolean,
    scanning: Boolean,
    onStartClean: () -> Unit,
    onRefreshAfterCancel: () -> Unit
) {
    val context = LocalContext.current
    var showWechatGuide by remember { mutableStateOf(false) }
    var showAutoPayGuide by remember { mutableStateOf(false) }

    val usedRatio = if (snapshot.totalStorageBytes > 0) {
        (snapshot.usedStorageBytes.toFloat() / snapshot.totalStorageBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val storageHeadline = when {
        scanning -> "正在检查手机空间..."
        snapshot.totalStorageBytes == 0L -> "手机安全守护中"
        usedRatio >= 0.85f -> "手机有点满了，建议点下面清理"
        else -> "手机空间很充足"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF15803D), Color(0xFF0F766E))),
                    RoundedCornerShape(24.dp)
                )
                .padding(22.dp)
        ) {
            Column {
                Text(
                    text = "手机安心清理",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = storageHeadline,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFEF08A)
                )

                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = if (usedRatio >= 0.85f) Color(0xFFFCA5A5) else Color(0xFFFDE047),
                    trackColor = Color(0x44000000)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "剩余可用 ${SystemScanner.formatBytes(snapshot.availStorageBytes)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (hasUsageLive && !scanning) {
                        Text(
                            text = if (snapshot.totalCleanableBytes > 0L) {
                                "可清 ${SystemScanner.formatBytes(snapshot.totalCleanableBytes)}"
                            } else "很干净",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFEF08A)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "放心点：绝不删照片、微信聊天，也不动手机银行",
                    fontSize = 14.sp,
                    color = Color(0xFFDCFCE7)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!bannerMessage.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFDCFCE7))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = bannerMessage,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF14532D)
                    )
                    TextButton(onClick = { GuardStore.postBanner(null) }) {
                        Text("知道了", fontSize = 14.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Button(
            onClick = onStartClean,
            enabled = !scanning && !cleanState.running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 74.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!hasUsageLive) Color(0xFFEA580C) else Color(0xFF16A34A)
            )
        ) {
            Text(
                text = when {
                    !hasUsageLive -> "请孩子帮忙完成首次设置"
                    cleanState.running -> "正在清理中 (${cleanState.currentIndex}/${cleanState.totalCount})，请稍等..."
                    else -> "点这里 · 一键清理手机"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        if (cleanState.running) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    CacheCleanAccessibilityService.requestUserStop(context, bringAppToFront = false)
                    onRefreshAfterCancel()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("停止清理", fontSize = 18.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
            }
        }

        if (!latestCleanRecord.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "上次清理：$latestCleanRecord",
                fontSize = 13.5.sp,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("💬 清理微信垃圾（不丢照片和聊天）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showWechatGuide = !showWechatGuide },
                        modifier = Modifier.weight(1.1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text(if (showWechatGuide) "收起步骤" else "看 3 步怎么点", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let {
                                try { context.startActivity(it) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.weight(0.9f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("打开微信", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                    }
                }
                if (showWechatGuide) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "1. 打开微信 → 点右下角「我」→ 点「设置」\n2. 点「通用」→ 点「存储空间」\n3. 只点「缓存」旁边的绿色【清理】按钮！\n（放心，照片和聊天记录一条都不会少）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF064E3B)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("💸 查查有没有偷偷扣费（关自动续费）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showAutoPayGuide = !showAutoPayGuide },
                        modifier = Modifier.weight(1.1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))
                    ) {
                        Text(if (showAutoPayGuide) "收起步骤" else "看 3 步怎么查", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            context.packageManager.getLaunchIntentForPackage("com.eg.android.AlipayGphone")?.let {
                                try { context.startActivity(it) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.weight(0.9f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("打开支付宝", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412))
                    }
                }
                if (showAutoPayGuide) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "【微信查扣费】\n" +
                               "1. 微信 →「我」→「服务」→「钱包」\n" +
                               "2. 点下方「支付设置」→「自动续费 / 扣费服务」\n" +
                               "3. 不认识的保险、会员、短剧扣费点进去关闭！\n\n" +
                               "【支付宝查扣费】\n" +
                               "1. 支付宝 → 右下角「我的」→ 右上角「设置」\n" +
                               "2. 点「支付设置」→「免密支付 / 自动扣款」\n" +
                               "3. 不认识的代扣项目逐个关闭！",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF7C2D12)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
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
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 13.sp, color = Color(0xFF475569))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
