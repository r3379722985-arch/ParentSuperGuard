package com.custom.parentguard

import android.content.Context
import android.content.SharedPreferences
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
    val auditedApps: List<InstalledAppAuditItem> = emptyList()
) {
    val usedStorageBytes: Long get() = (totalStorageBytes - availStorageBytes).coerceAtLeast(0L)
    val totalCleanableBytes: Long get() = totalAppCacheBytes + safeThumbBytes + safeLogBytes
    val suspectRogueCount: Int get() = auditedApps.count { it.isSuspectRogue }
}

object GuardStore {
    private const val PREFS = "parent_super_guard_prefs"
    /** v5.4 及更早版本使用的旧文件名；首次加载时自动迁移，避免升级把家长的自定义保护名单清空 */
    private const val LEGACY_PREFS = "parent_super_guard_v54_prefs"
    private const val K_INSTALL = "block_install"
    private const val K_QUICK = "block_quick"
    private const val K_JUMP = "block_jump"
    private const val K_STOP = "force_stop"
    private const val K_AUTO_OFF = "auto_off_acc"
    private const val K_BLOCKED = "blocked_cnt"
    private const val K_LOGS = "blocked_logs_v1"
    private const val K_CUSTOM_PROTECTED = "custom_protected_pkgs"
    private const val K_PAUSED_UNTIL = "guard_paused_until_ms"

    const val MAX_APPS_PER_BATCH = 20

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

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _cleanServiceConnected = MutableStateFlow(false)
    val cleanServiceConnected: StateFlow<Boolean> = _cleanServiceConnected.asStateFlow()

    private val _rogueServiceConnected = MutableStateFlow(false)
    val rogueServiceConnected: StateFlow<Boolean> = _rogueServiceConnected.asStateFlow()

    private val _guardPausedUntilMs = MutableStateFlow(0L)
    val guardPausedUntilMs: StateFlow<Long> = _guardPausedUntilMs.asStateFlow()

    @Volatile var queue: List<AppCacheCandidate> = emptyList(); private set
    @Volatile var queueIndex: Int = 0; private set
    @Volatile var cleanedInSession: Int = 0; private set
    @Volatile var remainingOverflowCount: Int = 0; private set
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

    fun pauseGuardForMinutes(context: Context, minutes: Int) {
        val until = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        _guardPausedUntilMs.value = until
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putLong(K_PAUSED_UNTIL, until).apply()
        } catch (_: Exception) {
        }
    }

    fun load(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            migrateLegacyPrefs(context, sp)
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
            val savedPaused = sp.getLong(K_PAUSED_UNTIL, 0L)
            _guardPausedUntilMs.value = if (savedPaused > System.currentTimeMillis()) savedPaused else 0L
        } catch (_: Exception) {
        }
    }

    /**
     * 一次性迁移：仅当新配置文件为空、且旧文件存在时复制全部键值，
     * 迁移成功后清空旧文件，避免下次启动重复迁移。
     */
    private fun migrateLegacyPrefs(context: Context, sp: SharedPreferences) {
        try {
            if (sp.all.isNotEmpty()) return
            val old = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val all = old.all
            if (all.isEmpty()) return
            val e = sp.edit()
            all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            e.apply()
            old.edit().clear().apply()
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

    fun startCleanQueue(allApps: List<AppCacheCandidate>, availBeforeBytes: Long, extFreedBytes: Long) {
        val batch = allApps.take(MAX_APPS_PER_BATCH)
        remainingOverflowCount = (allApps.size - batch.size).coerceAtLeast(0)
        queue = batch
        queueIndex = 0
        cleanedInSession = 0
        availBytesBeforeClean = availBeforeBytes
        extFreedBeforeQueue = extFreedBytes
        sessionActive = batch.isNotEmpty()
        _bannerMessage.value = null
        _cleanState.value = CleanRunState(
            running = sessionActive,
            currentIndex = if (sessionActive) 1 else 0,
            totalCount = batch.size,
            currentAppName = batch.firstOrNull()?.appName ?: "",
            cleanedApps = 0
        )
    }

    fun currentApp(): AppCacheCandidate? = queue.getOrNull(queueIndex)

    fun advanceCleanQueue(didClean: Boolean, currentAvailBytes: Long): AppCacheCandidate? {
        if (!sessionActive) return null
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
            val moreNote = if (remainingOverflowCount > 0) {
                "（已优先清完缓存最大的前 $MAX_APPS_PER_BATCH 个，其余 $remainingOverflowCount 个可按需再清）"
            } else ""
            finishCleanQueue("全部安全清理完成！共清理 $cleanedInSession 个软件缓存$spaceNote$moreNote。微信、银行与相册均已绝对保护。")
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
        remainingOverflowCount = 0
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
            finishCleanQueue("您尚未开启清缓存开关（或系统刚休眠了该服务），已自动解除等待，随时可重新点击。")
        }
    }
}
