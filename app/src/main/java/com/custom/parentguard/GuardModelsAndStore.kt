package com.custom.parentguard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppNotifState {
    ENABLED,
    DISABLED,
    UNKNOWN
}

enum class AppCleanOutcome {
    CLEANED,
    ALREADY_ZERO,
    FAILED,
    SKIPPED
}

sealed class PinVerifyResult {
    object Success : PinVerifyResult()
    data class Wrong(val failedAttempts: Int, val lockoutMs: Long) : PinVerifyResult()
    data class LockedOut(val remainingMs: Long) : PinVerifyResult()
}

sealed class PinSaveResult {
    data class Saved(val recoveryCode: String) : PinSaveResult()
    object Cleared : PinSaveResult()
    data class Failed(val reason: String) : PinSaveResult()
}

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

data class NotificationMuteCandidate(
    val packageName: String,
    val appName: String,
    val isHighSpam: Boolean,
    val systemNotifState: AppNotifState = AppNotifState.UNKNOWN
)

data class GuardConfig(
    val blockHighRiskInstall: Boolean = true,
    val blockQuickAppPopup: Boolean = true,
    val blockRogueAppJump: Boolean = true,
    val alsoForceStopOnClean: Boolean = false,
    val autoDisableCleanAccessibility: Boolean = true,
    val exportDiagToDownload: Boolean = false
)

data class CleanRunState(
    val running: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val currentAppName: String = "",
    val cleanedApps: Int = 0,
    val zeroApps: Int = 0,
    val failedApps: Int = 0
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
    val auditedApps: List<InstalledAppAuditItem> = emptyList(),
    val notificationCandidates: List<NotificationMuteCandidate> = emptyList(),
    val scannedAtMs: Long = 0L
) {
    val usedStorageBytes: Long get() = (totalStorageBytes - availStorageBytes).coerceAtLeast(0L)
    val totalCleanableBytes: Long get() = totalAppCacheBytes + safeThumbBytes + safeLogBytes
    val suspectRogueCount: Int get() = auditedApps.count { it.isSuspectRogue }
}

object GuardStore {
    private const val PREFS = "parent_super_guard_prefs"
    private val LEGACY_PREF_NAMES = listOf(
        "parent_super_guard_v54_prefs",
        "parent_super_guard_v53_prefs",
        "parent_super_guard_v52_prefs",
        "parent_super_guard_v51_prefs",
        "parent_super_guard_v5_prefs"
    )

    private const val K_INSTALL = "block_install"
    private const val K_QUICK = "block_quick"
    private const val K_JUMP = "block_jump"
    private const val K_STOP = "force_stop"
    private const val K_AUTO_OFF = "auto_off_acc"
    private const val K_DIAG_DOWNLOAD = "export_diag_download_v1"
    private const val K_BLOCKED = "blocked_cnt"
    private const val K_LOGS = "blocked_logs_v1"
    private const val K_CLEAN_HISTORY = "clean_history_v1"
    private const val K_CUSTOM_PROTECTED = "custom_protected_pkgs"
    private const val K_PAUSED_UNTIL = "guard_paused_until_ms"
    private const val K_ONBOARDING_DONE = "onboarding_completed_v1"
    private const val K_RESTRICTED_OK = "restricted_confirmed_v1"
    private const val K_CHECKLIST_DONE = "child_checklist_done_v1"
    private const val K_ADMIN_PIN_HASH = "admin_pin_hash_v1"
    private const val K_RECOVERY_HASH = "admin_recovery_hash_v1"
    private const val K_PIN_FAILED_COUNT = "admin_pin_failed_count_v1"
    private const val K_PIN_LOCKOUT_UNTIL = "admin_pin_lockout_until_ms_v1"
    private const val K_NOTIF_HANDLED = "notif_handled_pkgs_v1"
    private const val K_MIGRATED_LEGACY = "migrated_legacy_prefs_v1"
    private const val K_RECENTLY_CLEANED = "recently_cleaned_pkgs_v1"

    const val MAX_APPS_PER_BATCH = 20
    const val RECENTLY_CLEANED_TTL_MS = 10L * 60_000L

    private val _config = MutableStateFlow(GuardConfig())
    val config: StateFlow<GuardConfig> = _config.asStateFlow()

    private val _customProtected = MutableStateFlow<Set<String>>(emptySet())
    val customProtected: StateFlow<Set<String>> = _customProtected.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private val _blockLogs = MutableStateFlow<List<String>>(emptyList())
    val blockLogs: StateFlow<List<String>> = _blockLogs.asStateFlow()

    private val _cleanHistory = MutableStateFlow<List<String>>(emptyList())
    val cleanHistory: StateFlow<List<String>> = _cleanHistory.asStateFlow()

    private val _checklistDoneIds = MutableStateFlow<Set<String>>(emptySet())
    val checklistDoneIds: StateFlow<Set<String>> = _checklistDoneIds.asStateFlow()

    private val _notifHandledPkgs = MutableStateFlow<Set<String>>(emptySet())
    val notifHandledPkgs: StateFlow<Set<String>> = _notifHandledPkgs.asStateFlow()

    private val _adminPinHash = MutableStateFlow<String?>(null)
    val adminPinHash: StateFlow<String?> = _adminPinHash.asStateFlow()

    private val _recoveryCodeHash = MutableStateFlow<String?>(null)
    val recoveryCodeHash: StateFlow<String?> = _recoveryCodeHash.asStateFlow()

    private val _pinFailedAttempts = MutableStateFlow(0)
    val pinFailedAttempts: StateFlow<Int> = _pinFailedAttempts.asStateFlow()

    private val _pinLockoutUntilMs = MutableStateFlow(0L)
    val pinLockoutUntilMs: StateFlow<Long> = _pinLockoutUntilMs.asStateFlow()

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

    private val _onboardingCompleted = MutableStateFlow(false)
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    private val _restrictedConfirmed = MutableStateFlow(false)
    val restrictedConfirmed: StateFlow<Boolean> = _restrictedConfirmed.asStateFlow()

    @Volatile private var hasLoadedOnce: Boolean = false
    @Volatile var queue: List<AppCacheCandidate> = emptyList(); private set
    @Volatile var queueIndex: Int = 0; private set
    @Volatile var cleanedInSession: Int = 0; private set
    @Volatile var zeroInSession: Int = 0; private set
    @Volatile var failedInSession: Int = 0; private set
    @Volatile var skippedInSession: Int = 0; private set
    @Volatile var remainingOverflowCount: Int = 0; private set
    @Volatile var sessionActive: Boolean = false; private set
    @Volatile var availBytesBeforeClean: Long = 0L; private set
    @Volatile var extFreedBeforeQueue: Long = 0L; private set
    @Volatile var sessionStartedAtMs: Long = 0L; private set

    private val recentLock = Any()
    @Volatile private var recentlyCleanedPkgs: Map<String, Long> = emptyMap()

    fun setCleanServiceConnected(connected: Boolean) {
        _cleanServiceConnected.value = connected
    }

    fun setRogueServiceConnected(connected: Boolean) {
        _rogueServiceConnected.value = connected
    }

    fun postBanner(msg: String?) {
        _bannerMessage.value = msg
    }

    fun getActiveRecentlyCleaned(nowMs: Long = System.currentTimeMillis()): Map<String, Long> {
        synchronized(recentLock) {
            val active = recentlyCleanedPkgs.filterValues { (nowMs - it) < RECENTLY_CLEANED_TTL_MS }
            if (active.size != recentlyCleanedPkgs.size) {
                recentlyCleanedPkgs = active
            }
            return active
        }
    }

    fun markPackageRecentlyCleaned(context: Context, packageName: String) {
        val snapshotCopy: Map<String, Long>
        synchronized(recentLock) {
            val now = System.currentTimeMillis()
            val updated = recentlyCleanedPkgs.filterValues { (now - it) < RECENTLY_CLEANED_TTL_MS }.toMutableMap()
            updated[packageName] = now
            snapshotCopy = updated.toMap()
            recentlyCleanedPkgs = snapshotCopy
        }
        persistRecentlyCleaned(context, snapshotCopy)
    }

    private fun persistRecentlyCleaned(context: Context, map: Map<String, Long>) {
        try {
            val encoded = map.entries.joinToString("\n") { "${it.key}=${it.value}" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(K_RECENTLY_CLEANED, encoded)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun markOnboardingCompleted(context: Context, done: Boolean = true) {
        _onboardingCompleted.value = done
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putBoolean(K_ONBOARDING_DONE, done).apply()
        } catch (_: Exception) {
        }
    }

    fun markRestrictedConfirmed(context: Context, confirmed: Boolean = true) {
        _restrictedConfirmed.value = confirmed
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putBoolean(K_RESTRICTED_OK, confirmed).apply()
        } catch (_: Exception) {
        }
    }

    fun toggleChecklistItem(context: Context, itemId: String) {
        val next = _checklistDoneIds.value.toMutableSet()
        if (itemId in next) next.remove(itemId) else next.add(itemId)
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putStringSet(K_CHECKLIST_DONE, HashSet(next)).apply()
            _checklistDoneIds.value = next.toSet()
        } catch (_: Exception) {
        }
    }

    fun markNotificationHandled(context: Context, packageName: String) {
        if (packageName in _notifHandledPkgs.value) return
        val next = _notifHandledPkgs.value + packageName
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putStringSet(K_NOTIF_HANDLED, HashSet(next)).apply()
            _notifHandledPkgs.value = next
        } catch (_: Exception) {
        }
    }

    fun toggleNotificationHandled(context: Context, packageName: String) {
        val next = _notifHandledPkgs.value.toMutableSet()
        if (packageName in next) next.remove(packageName) else next.add(packageName)
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putStringSet(K_NOTIF_HANDLED, HashSet(next)).apply()
            _notifHandledPkgs.value = next.toSet()
        } catch (_: Exception) {
        }
    }

    fun setAdminPin(context: Context, pin: String?): PinSaveResult {
        return try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (pin.isNullOrBlank()) {
                sp.edit()
                    .remove(K_ADMIN_PIN_HASH)
                    .remove(K_RECOVERY_HASH)
                    .putInt(K_PIN_FAILED_COUNT, 0)
                    .putLong(K_PIN_LOCKOUT_UNTIL, 0L)
                    .apply()
                _adminPinHash.value = null
                _recoveryCodeHash.value = null
                _pinFailedAttempts.value = 0
                _pinLockoutUntilMs.value = 0L
                PinSaveResult.Cleared
            } else {
                val pinHash = CleanDecisionRules.hashPin(pin)
                val recoveryCode = CleanDecisionRules.generateRecoveryCode()
                val recHash = CleanDecisionRules.hashRecoveryCode(recoveryCode)
                val ok = sp.edit()
                    .putString(K_ADMIN_PIN_HASH, pinHash)
                    .putString(K_RECOVERY_HASH, recHash)
                    .putInt(K_PIN_FAILED_COUNT, 0)
                    .putLong(K_PIN_LOCKOUT_UNTIL, 0L)
                    .commit()
                if (!ok) return PinSaveResult.Failed("加密密码写入失败，请检查手机剩余空间")
                _adminPinHash.value = pinHash
                _recoveryCodeHash.value = recHash
                _pinFailedAttempts.value = 0
                _pinLockoutUntilMs.value = 0L
                PinSaveResult.Saved(recoveryCode)
            }
        } catch (e: Exception) {
            PinSaveResult.Failed("密码保存异常：${e.message ?: "未知错误"}")
        }
    }

    fun attemptVerifyAdminPin(context: Context, pin: String): PinVerifyResult {
        val stored = _adminPinHash.value ?: return PinVerifyResult.Success
        val now = System.currentTimeMillis()
        val lockoutUntil = _pinLockoutUntilMs.value
        if (now < lockoutUntil) {
            return PinVerifyResult.LockedOut(lockoutUntil - now)
        }

        val ok = CleanDecisionRules.verifyPin(pin, stored)
        val sp = try { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        if (ok) {
            _pinFailedAttempts.value = 0
            _pinLockoutUntilMs.value = 0L
            val editor = sp?.edit()
                ?.putInt(K_PIN_FAILED_COUNT, 0)
                ?.putLong(K_PIN_LOCKOUT_UNTIL, 0L)
            if (CleanDecisionRules.isLegacyPinHash(stored)) {
                val upgraded = CleanDecisionRules.hashPin(pin)
                _adminPinHash.value = upgraded
                editor?.putString(K_ADMIN_PIN_HASH, upgraded)
            }
            editor?.apply()
            return PinVerifyResult.Success
        } else {
            val nextAttempts = _pinFailedAttempts.value + 1
            val lockoutMs = CleanDecisionRules.lockoutDurationMsForFailures(nextAttempts)
            val nextUntil = if (lockoutMs > 0L) now + lockoutMs else 0L
            _pinFailedAttempts.value = nextAttempts
            _pinLockoutUntilMs.value = nextUntil
            sp?.edit()
                ?.putInt(K_PIN_FAILED_COUNT, nextAttempts)
                ?.putLong(K_PIN_LOCKOUT_UNTIL, nextUntil)
                ?.apply()
            return PinVerifyResult.Wrong(nextAttempts, lockoutMs)
        }
    }

    fun resetAdminPinBySystemAuth(context: Context) {
        setAdminPin(context, null)
        postBanner("已通过手机锁屏验证关闭管理锁，您的白名单与设置均已完整保留。")
    }

    fun attemptResetWithRecoveryCode(context: Context, code: String): PinVerifyResult {
        val storedRec = _recoveryCodeHash.value ?: return PinVerifyResult.Wrong(_pinFailedAttempts.value + 1, 0L)
        val now = System.currentTimeMillis()
        val lockoutUntil = _pinLockoutUntilMs.value
        if (now < lockoutUntil) {
            return PinVerifyResult.LockedOut(lockoutUntil - now)
        }
        if (CleanDecisionRules.verifyRecoveryCode(code, storedRec)) {
            setAdminPin(context, null)
            postBanner("已通过恢复码关闭管理锁，您的白名单与设置均已完整保留。")
            return PinVerifyResult.Success
        }
        val nextAttempts = _pinFailedAttempts.value + 1
        val lockoutMs = CleanDecisionRules.lockoutDurationMsForFailures(nextAttempts)
        val nextUntil = if (lockoutMs > 0L) now + lockoutMs else 0L
        _pinFailedAttempts.value = nextAttempts
        _pinLockoutUntilMs.value = nextUntil
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(K_PIN_FAILED_COUNT, nextAttempts)
                .putLong(K_PIN_LOCKOUT_UNTIL, nextUntil)
                .apply()
        } catch (_: Exception) {
        }
        return PinVerifyResult.Wrong(nextAttempts, lockoutMs)
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

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        try {
            val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (current.getBoolean(K_MIGRATED_LEGACY, false)) return
            val hasExistingData = current.contains(K_CUSTOM_PROTECTED) || current.contains(K_BLOCKED) || current.contains(K_INSTALL)
            if (!hasExistingData) {
                for (oldName in LEGACY_PREF_NAMES) {
                    val oldSp = context.getSharedPreferences(oldName, Context.MODE_PRIVATE)
                    if (oldSp.all.isNotEmpty()) {
                        val ed = current.edit()
                        if (oldSp.contains(K_INSTALL)) ed.putBoolean(K_INSTALL, oldSp.getBoolean(K_INSTALL, true))
                        if (oldSp.contains(K_QUICK)) ed.putBoolean(K_QUICK, oldSp.getBoolean(K_QUICK, true))
                        if (oldSp.contains(K_JUMP)) ed.putBoolean(K_JUMP, oldSp.getBoolean(K_JUMP, true))
                        if (oldSp.contains(K_STOP)) ed.putBoolean(K_STOP, oldSp.getBoolean(K_STOP, false))
                        if (oldSp.contains(K_AUTO_OFF)) ed.putBoolean(K_AUTO_OFF, oldSp.getBoolean(K_AUTO_OFF, true))
                        if (oldSp.contains(K_BLOCKED)) ed.putInt(K_BLOCKED, oldSp.getInt(K_BLOCKED, 0))
                        if (oldSp.contains(K_LOGS)) ed.putString(K_LOGS, oldSp.getString(K_LOGS, ""))
                        val oldCustom = oldSp.getStringSet(K_CUSTOM_PROTECTED, null)
                        if (!oldCustom.isNullOrEmpty()) {
                            ed.putStringSet(K_CUSTOM_PROTECTED, HashSet(oldCustom))
                        }
                        ed.putBoolean(K_MIGRATED_LEGACY, true).apply()
                        return
                    }
                }
            }
            current.edit().putBoolean(K_MIGRATED_LEGACY, true).apply()
        } catch (_: Exception) {
        }
    }

    fun ensureLoaded(context: Context) {
        if (!hasLoadedOnce) {
            load(context)
        }
        CleanDiagLogger.ensureLoaded(context)
    }

    @Synchronized
    fun load(context: Context) {
        try {
            migrateLegacyPrefsIfNeeded(context)
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _config.value = GuardConfig(
                blockHighRiskInstall = sp.getBoolean(K_INSTALL, true),
                blockQuickAppPopup = sp.getBoolean(K_QUICK, true),
                blockRogueAppJump = sp.getBoolean(K_JUMP, true),
                alsoForceStopOnClean = sp.getBoolean(K_STOP, false),
                autoDisableCleanAccessibility = sp.getBoolean(K_AUTO_OFF, true),
                exportDiagToDownload = sp.getBoolean(K_DIAG_DOWNLOAD, false)
            )
            _blockedCount.value = sp.getInt(K_BLOCKED, 0)
            val savedLogs = sp.getString(K_LOGS, "") ?: ""
            _blockLogs.value = savedLogs.split("\n").filter { it.isNotBlank() }.take(15)
            val savedHist = sp.getString(K_CLEAN_HISTORY, "") ?: ""
            _cleanHistory.value = savedHist.split("\n").filter { it.isNotBlank() }.take(20)
            _customProtected.value = sp.getStringSet(K_CUSTOM_PROTECTED, emptySet())?.toSet() ?: emptySet()
            _checklistDoneIds.value = sp.getStringSet(K_CHECKLIST_DONE, emptySet())?.toSet() ?: emptySet()
            _notifHandledPkgs.value = sp.getStringSet(K_NOTIF_HANDLED, emptySet())?.toSet() ?: emptySet()
            _adminPinHash.value = sp.getString(K_ADMIN_PIN_HASH, null)?.takeIf { it.isNotBlank() }
            _recoveryCodeHash.value = sp.getString(K_RECOVERY_HASH, null)?.takeIf { it.isNotBlank() }
            _pinFailedAttempts.value = sp.getInt(K_PIN_FAILED_COUNT, 0)
            val savedLockout = sp.getLong(K_PIN_LOCKOUT_UNTIL, 0L)
            _pinLockoutUntilMs.value = if (savedLockout > System.currentTimeMillis()) savedLockout else 0L
            val savedPaused = sp.getLong(K_PAUSED_UNTIL, 0L)
            _guardPausedUntilMs.value = if (savedPaused > System.currentTimeMillis()) savedPaused else 0L
            _onboardingCompleted.value = sp.getBoolean(K_ONBOARDING_DONE, false)
            _restrictedConfirmed.value = sp.getBoolean(K_RESTRICTED_OK, false)
            synchronized(recentLock) {
                val now = System.currentTimeMillis()
                val rawRecent = sp.getString(K_RECENTLY_CLEANED, "") ?: ""
                val parsedRecent = mutableMapOf<String, Long>()
                rawRecent.split("\n").forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val pkg = line.substring(0, idx).trim()
                        val ts = line.substring(idx + 1).trim().toLongOrNull() ?: 0L
                        if (pkg.isNotEmpty() && (now - ts) < RECENTLY_CLEANED_TTL_MS) {
                            parsedRecent[pkg] = ts
                        }
                    }
                }
                recentlyCleanedPkgs = parsedRecent.toMap()
            }
            hasLoadedOnce = true
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
                .putBoolean(K_DIAG_DOWNLOAD, cfg.exportDiagToDownload)
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

    fun recordCleanHistory(context: Context, appsCleaned: Int, freedBytes: Long) {
        try {
            val ts = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
            val sizePart = if (freedBytes >= 1024L * 1024L) {
                "，释放约 ${SystemScanner.formatBytes(freedBytes)}"
            } else ""
            val entry = "$ts · 安全清理 $appsCleaned 个软件缓存$sizePart"
            val updated = (listOf(entry) + _cleanHistory.value).take(20)
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putString(K_CLEAN_HISTORY, updated.joinToString("\n")).apply()
            _cleanHistory.value = updated
        } catch (_: Exception) {
        }
    }

    fun startCleanQueue(allApps: List<AppCacheCandidate>, availBeforeBytes: Long, extFreedBytes: Long): Int {
        val now = System.currentTimeMillis()
        val uncleaned = CleanDecisionRules.filterRecentlyCleaned(
            items = allApps,
            recentlyCleanedTimestamps = getActiveRecentlyCleaned(now),
            nowMs = now,
            ttlMs = RECENTLY_CLEANED_TTL_MS,
            packageSelector = { it.packageName }
        )
        val batch = uncleaned.take(MAX_APPS_PER_BATCH)
        remainingOverflowCount = (uncleaned.size - batch.size).coerceAtLeast(0)
        queue = batch
        queueIndex = 0
        cleanedInSession = 0
        zeroInSession = 0
        failedInSession = 0
        skippedInSession = 0
        availBytesBeforeClean = availBeforeBytes
        extFreedBeforeQueue = extFreedBytes
        sessionStartedAtMs = now
        sessionActive = batch.isNotEmpty()
        _bannerMessage.value = null
        _cleanState.value = CleanRunState(
            running = sessionActive,
            currentIndex = if (sessionActive) 1 else 0,
            totalCount = batch.size,
            currentAppName = batch.firstOrNull()?.appName ?: "",
            cleanedApps = 0,
            zeroApps = 0,
            failedApps = 0
        )
        return batch.size
    }

    fun currentApp(): AppCacheCandidate? = queue.getOrNull(queueIndex)

    fun addExternalFreedBytes(bytes: Long) {
        if (bytes > 0L) {
            extFreedBeforeQueue += bytes
        }
    }

    fun advanceCleanQueue(context: Context, outcome: AppCleanOutcome, currentAvailBytes: Long): AppCacheCandidate? {
        if (!sessionActive) return null
        val finishedApp = queue.getOrNull(queueIndex)
        when (outcome) {
            AppCleanOutcome.CLEANED -> {
                cleanedInSession++
                if (finishedApp != null) markPackageRecentlyCleaned(context, finishedApp.packageName)
            }
            AppCleanOutcome.ALREADY_ZERO -> {
                zeroInSession++
                if (finishedApp != null) markPackageRecentlyCleaned(context, finishedApp.packageName)
            }
            AppCleanOutcome.FAILED -> failedInSession++
            AppCleanOutcome.SKIPPED -> skippedInSession++
        }

        val total = queue.size
        queueIndex++
        val next = queue.getOrNull(queueIndex)
        CleanDiagLogger.log(
            context,
            "advance pkg=${finishedApp?.packageName ?: "?"} outcome=$outcome progress=$queueIndex/$total next=${next?.packageName ?: "DONE"}"
        )

        if (next == null) {
            val elapsedSec = ((System.currentTimeMillis() - sessionStartedAtMs) / 1000L).coerceAtLeast(1L)
            val delta = if (availBytesBeforeClean > 0L && currentAvailBytes > availBytesBeforeClean) {
                currentAvailBytes - availBytesBeforeClean
            } else {
                extFreedBeforeQueue
            }
            CleanDiagLogger.log(
                context,
                "done cleaned=$cleanedInSession zero=$zeroInSession failed=$failedInSession skipped=$skippedInSession freed=${SystemScanner.formatBytes(delta)} elapsed=${elapsedSec}s"
            )
            if (cleanedInSession > 0 || delta >= 1024L * 1024L) {
                recordCleanHistory(context, cleanedInSession, delta)
            }

            val msg = buildHonestFinishBanner(delta)
            finishCleanQueue(msg)
            return null
        }

        _cleanState.value = CleanRunState(
            running = true,
            currentIndex = queueIndex + 1,
            totalCount = queue.size,
            currentAppName = next.appName,
            cleanedApps = cleanedInSession,
            zeroApps = zeroInSession,
            failedApps = failedInSession
        )
        return next
    }

    private fun buildHonestFinishBanner(freedBytes: Long): String {
        val spaceNote = if (freedBytes >= 1024L * 1024L) {
            "，腾出空间约 ${SystemScanner.formatBytes(freedBytes)}"
        } else ""
        val moreNote = if (remainingOverflowCount > 0) {
            "（其余 $remainingOverflowCount 个可按需再清）"
        } else ""

        return when {
            cleanedInSession > 0 -> {
                val extraParts = mutableListOf<String>()
                if (zeroInSession > 0) extraParts.add("${zeroInSession} 个已为空")
                if (skippedInSession > 0) extraParts.add("${skippedInSession} 个已保护跳过")
                if (failedInSession > 0) extraParts.add("${failedInSession} 个未命中按钮")
                val extraText = if (extraParts.isNotEmpty()) "（${extraParts.joinToString("、")}）" else ""
                "安全清理完成！成功清理 $cleanedInSession 个软件缓存$spaceNote$extraText$moreNote。微信、银行与相册均已保护。"
            }
            zeroInSession > 0 && failedInSession == 0 -> {
                "检查完成，这 $zeroInSession 个软件缓存已经很干净了$spaceNote，不用重复清理。"
            }
            else -> {
                "本次未清出缓存（${zeroInSession} 个已为空、${skippedInSession} 个已保护跳过、${failedInSession} 个未命中按钮），可请孩子在「子女设置 → 诊断日志」查看原因。"
            }
        }
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
            cleanedApps = cleanedInSession,
            zeroApps = zeroInSession,
            failedApps = failedInSession
        )
        _bannerMessage.value = msg
    }

    fun cancelCleanQueue(context: Context? = null, includeCurrentCleaned: Boolean = false) {
        if (sessionActive) {
            val cur = queue.getOrNull(queueIndex)
            if (includeCurrentCleaned && cur != null) {
                cleanedInSession++
                if (context != null) {
                    markPackageRecentlyCleaned(context, cur.packageName)
                }
            }
            val elapsedSec = ((System.currentTimeMillis() - sessionStartedAtMs) / 1000L).coerceAtLeast(1L)
            CleanDiagLogger.log(
                context,
                "cancelled cleaned=$cleanedInSession zero=$zeroInSession failed=$failedInSession elapsed=${elapsedSec}s"
            )
            if (context != null && cleanedInSession > 0) {
                val nowAvail = SystemScanner.getCurrentAvailBytes()
                val delta = if (availBytesBeforeClean > 0L && nowAvail > availBytesBeforeClean) {
                    nowAvail - availBytesBeforeClean
                } else {
                    extFreedBeforeQueue
                }
                recordCleanHistory(context, cleanedInSession, delta)
            }
            finishCleanQueue("已停止清理（本次已安全清理 $cleanedInSession 个软件；下次点清理会自动跳过刚清完的，接着清理剩下的）。")
        }
    }

    fun resetIfServiceNotEnabled() {
        if (sessionActive) {
            finishCleanQueue("您尚未开启清缓存开关（或系统刚休眠了该服务），已自动解除等待，随时可重新点击。")
        }
    }
}
