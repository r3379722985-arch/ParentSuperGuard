package com.custom.parentguard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppCacheCandidate(
    val packageName: String,
    val appName: String,
    val cacheBytes: Long
)

data class InstalledAppAuditItem(
    val packageName: String,
    val appName: String,
    val isBuiltInStrict: Boolean,
    val isCustomLocked: Boolean,
    val isTrustedRegular: Boolean,
    val isSuspectRogue: Boolean
) {
    val isStrictProtected: Boolean get() = isBuiltInStrict || isCustomLocked
}

data class GuardConfig(
    val blockHighRiskInstall: Boolean = true,
    val blockQuickAppPopup: Boolean = true,
    val blockRogueAppJump: Boolean = true,
    val alsoForceStopOnClean: Boolean = false,
    val autoDisableCleanAccessibility: Boolean = true
)

data class CleanRunState(
    val running: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentAppName: String = "",
    val cleanedApps: Int = 0
)

data class GuardSnapshot(
    val totalStorageBytes: Long = 0L,
    val availStorageBytes: Long = 0L,
    val hasUsagePermission: Boolean = false,
    val cacheCandidates: List<AppCacheCandidate> = emptyList(),
    val totalAppCacheBytes: Long = 0L,
    val safeThumbFiles: List<File> = emptyList(),
    val safeThumbBytes: Long = 0L,
    val safeLogFiles: List<File> = emptyList(),
    val safeLogBytes: Long = 0L,
    val safeEmptyDirs: List<File> = emptyList(),
    val auditedApps: List<InstalledAppAuditItem> = emptyList()
) {
    val usedStorageBytes: Long get() = (totalStorageBytes - availStorageBytes).coerceAtLeast(0L)
    val totalCleanableBytes: Long get() = totalAppCacheBytes + safeThumbBytes + safeLogBytes
    val suspectRogueCount: Int get() = auditedApps.count { it.isSuspectRogue }
}

object GuardStore {
    private const val PREFS = "parent_super_guard_v54_prefs"
    private const val K_INSTALL = "block_install"
    private const val K_QUICK = "block_quick"
    private const val K_JUMP = "block_jump"
    private const val K_STOP = "force_stop"
    private const val K_AUTO_OFF = "auto_off_acc"
    private const val K_BLOCKED = "blocked_cnt"
    private const val K_LOGS = "blocked_logs_v1"
    private const val K_CUSTOM_PROTECTED = "custom_protected_pkgs"

    private val _config = MutableStateFlow(GuardConfig())
    val config: StateFlow<GuardConfig> = _config.asStateFlow()

    private val _customProtected = MutableStateFlow<Set<String>>(emptySet())
    val customProtected: StateFlow<Set<String>> = _customProtected.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private val _blockLogs = MutableStateFlow<List<String>>(emptyList())
    val blockLogs: StateFlow<List<String>> = _blockLogs.asStateFlow()

    private val _cleanState = MutableStateFlow(CleanRunState())
    val cleanState: StateFlow<CleanRunState> = _cleanState.asStateFlow()

    // 单一横幅通知源，修复旧横幅盖住新提示的问题
    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    // 实时反映两个无障碍服务的连接状态（disableSelf 后界面秒刷新）
    private val _cleanServiceConnected = MutableStateFlow(false)
    val cleanServiceConnected: StateFlow<Boolean> = _cleanServiceConnected.asStateFlow()

    private val _rogueServiceConnected = MutableStateFlow(false)
    val rogueServiceConnected: StateFlow<Boolean> = _rogueServiceConnected.asStateFlow()

    private val _guardPausedUntilMs = MutableStateFlow(0L)
    val guardPausedUntilMs: StateFlow<Long> = _guardPausedUntilMs.asStateFlow()

    @Volatile var queue: List<AppCacheCandidate> = emptyList(); private set
    @Volatile var queueIndex: Int = 0; private set
    @Volatile var cleanedInSession: Int = 0; private set
    @Volatile var sessionActive: Boolean = false; private set
    @Volatile var availBytesBeforeClean: Long = 0L; private set
    @Volatile var extFreedBeforeQueue: Long = 0L; private set

    fun setCleanServiceConnected(connected: Boolean) {
        _cleanServiceConnected.value = connected
    }

    fun setRogueServiceConnected(connected: Boolean) {
        _rogueServiceConnected.value = connected
    }

    fun postBanner(msg: String?) {
        _bannerMessage.value = msg
    }

    fun isGuardTemporarilyPaused(): Boolean = System.currentTimeMillis() < _guardPausedUntilMs.value

    fun pauseGuardForMinutes(minutes: Int) {
        _guardPausedUntilMs.value = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
    }

    fun load(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _config.value = GuardConfig(
                blockHighRiskInstall = sp.getBoolean(K_INSTALL, true),
                blockQuickAppPopup = sp.getBoolean(K_QUICK, true),
                blockRogueAppJump = sp.getBoolean(K_JUMP, true),
                alsoForceStopOnClean = sp.getBoolean(K_STOP, false),
                autoDisableCleanAccessibility = sp.getBoolean(K_AUTO_OFF, true)
            )
            _blockedCount.value = sp.getInt(K_BLOCKED, 0)
            val savedLogs = sp.getString(K_LOGS, "") ?: ""
            _blockLogs.value = savedLogs.split("\n").filter { it.isNotBlank() }.take(15)
            _customProtected.value = sp.getStringSet(K_CUSTOM_PROTECTED, emptySet())?.toSet() ?: emptySet()
        } catch (_: Exception) {
        }
    }

    fun saveConfig(context: Context, cfg: GuardConfig) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(K_INSTALL, cfg.blockHighRiskInstall)
                .putBoolean(K_QUICK, cfg.blockQuickAppPopup)
                .putBoolean(K_JUMP, cfg.blockRogueAppJump)
                .putBoolean(K_STOP, cfg.alsoForceStopOnClean)
                .putBoolean(K_AUTO_OFF, cfg.autoDisableCleanAccessibility)
                .apply()
            _config.value = cfg
        } catch (_: Exception) {
        }
    }

    fun toggleCustomProtected(context: Context, packageName: String): Boolean {
        val next = _customProtected.value.toMutableSet()
        val nowProtected = if (packageName in next) {
            next.remove(packageName)
            false
        } else {
            next.add(packageName)
            true
        }
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putStringSet(K_CUSTOM_PROTECTED, HashSet(next)).apply()
            _customProtected.value = next.toSet()
        } catch (_: Exception) {
        }
        return nowProtected
    }

    fun recordBlocked(context: Context, reason: String) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = _blockedCount.value + 1
            val ts = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
            val updatedLogs = (listOf("$ts · $reason") + _blockLogs.value).take(15)
            sp.edit()
                .putInt(K_BLOCKED, next)
                .putString(K_LOGS, updatedLogs.joinToString("\n"))
                .apply()
            _blockedCount.value = next
            _blockLogs.value = updatedLogs
        } catch (_: Exception) {
        }
    }

    fun startCleanQueue(apps: List<AppCacheCandidate>, availBeforeBytes: Long, extFreedBytes: Long) {
        queue = apps
        queueIndex = 0
        cleanedInSession = 0
        availBytesBeforeClean = availBeforeBytes
        extFreedBeforeQueue = extFreedBytes
        sessionActive = apps.isNotEmpty()
        _bannerMessage.value = null
        _cleanState.value = CleanRunState(
            running = sessionActive,
            currentIndex = if (sessionActive) 1 else 0,
            totalCount = apps.size,
            currentAppName = apps.firstOrNull()?.appName ?: "",
            cleanedApps = 0
        )
    }

    fun currentApp(): AppCacheCandidate? = queue.getOrNull(queueIndex)

    fun advanceCleanQueue(didClean: Boolean, currentAvailBytes: Long): AppCacheCandidate? {
        if (didClean) cleanedInSession++
        queueIndex++
        val next = queue.getOrNull(queueIndex)
        if (next == null) {
            val delta = if (availBytesBeforeClean > 0L && currentAvailBytes > availBytesBeforeClean) {
                currentAvailBytes - availBytesBeforeClean
            } else {
                extFreedBeforeQueue
            }
            val spaceNote = if (delta >= 1024L * 1024L) {
                "，手机腾出空间约 ${SystemScanner.formatBytes(delta)}"
            } else ""
            finishCleanQueue("全部安全清理完成！共清理 $cleanedInSession 个软件缓存$spaceNote（微信、银行与相册均已绝对保护）。")
            return null
        }
        _cleanState.value = CleanRunState(
            running = true,
            currentIndex = queueIndex + 1,
            totalCount = queue.size,
            currentAppName = next.appName,
            cleanedApps = cleanedInSession
        )
        return next
    }

    fun finishCleanQueue(msg: String) {
        sessionActive = false
        queue = emptyList()
        _cleanState.value = CleanRunState(
            running = false,
            currentIndex = 0,
            totalCount = 0,
            currentAppName = "",
            cleanedApps = cleanedInSession
        )
        _bannerMessage.value = msg
    }

    fun cancelCleanQueue() {
        if (sessionActive) {
            finishCleanQueue("已停止清理（本次已安全清理 $cleanedInSession 个软件的缓存）。")
        }
    }

    fun resetIfServiceNotEnabled() {
        if (sessionActive) {
            finishCleanQueue("您尚未开启清缓存开关，已自动取消本次等待，随时可重新点击。")
        }
    }
}
