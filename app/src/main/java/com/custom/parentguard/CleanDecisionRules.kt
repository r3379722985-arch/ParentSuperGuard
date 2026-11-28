package com.custom.parentguard

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class DialogDecision {
    NOT_A_DIALOG,
    WAIT_TRANSITION,
    ABORT_DANGEROUS,
    CONFIRM_CACHE
}

/**
 * 纯函数安全判定规则、PBKDF2 强哈希、恢复码、阶梯冷却、看门狗防误判与存储大小提取（全部由 JUnit 单元测试覆盖）。
 */
object CleanDecisionRules {

    const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_PREFIX = "pbkdf2_sha256"
    private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    // P2-3 修复：只提取“存储/缓存/占用/数据(排除流量与网络)/storage/cache”附近的容量数字，排除“流量消耗 881.3MB”干扰
    private val STORAGE_SIZE_CONTEXT_REGEX = Regex(
        "(?:存储|缓存|占用|数据(?!流量|网络|使用)|storage|cache)[^0-9]{0,12}(\\d+(?:\\.\\d+)?\\s*(?:kb|mb|gb|b))\\b",
        RegexOption.IGNORE_CASE
    )

    val ABSOLUTE_FORBIDDEN = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage",
        "卸载", "uninstall", "停用", "disable", "恢复出厂"
    )

    private val DATA_WIPE_MENU_KEYWORDS = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage"
    )

    private val ACCOUNT_WIPE_PHRASES = listOf(
        "删除账号", "清除账号", "清空账号", "账号都将", "永久删除"
    )

    val NON_MIUI_FORBIDDEN = listOf(
        "清除数据", "clear data", "清空数据"
    )

    val BUILTIN_INSTALL_RISK_KEYWORDS = listOf(
        "未知来源", "高风险", "未经官方", "恶意", "风险应用", "安全风险", "报毒"
    )

    fun matchesTargetAppWindow(
        rawWindowText: String,
        targetAppName: String,
        targetPackageName: String
    ): Boolean {
        val win = rawWindowText.lowercase()
        val pkg = targetPackageName.trim().lowercase()
        if (pkg.isNotEmpty() && win.contains(pkg)) return true

        val name = targetAppName.trim().lowercase()
        if (name.isEmpty()) return false
        if (win.contains(name)) return true

        val compactWin = win.replace(" ", "")
        val compactName = name.replace(" ", "")
        if (compactName.length >= 2 && compactWin.contains(compactName)) return true

        return false
    }

    fun isDangerOnlyMenu(rawWindowText: String, clearCacheLabels: List<String>): Boolean {
        val txt = rawWindowText.lowercase()
        val hasCancel = txt.contains("取消") || txt.contains("cancel")
        if (!hasCancel) return false
        val hasClearCache = clearCacheLabels.any { txt.contains(it.lowercase()) }
        if (hasClearCache) return false
        return DATA_WIPE_MENU_KEYWORDS.any { txt.contains(it) }
    }

    fun evaluateCacheConfirmWindow(rawWindowText: String): DialogDecision {
        val txt = rawWindowText.lowercase()
        val looksLikeDialog = txt.contains("取消") || txt.contains("cancel")
        if (!looksLikeDialog) {
            return DialogDecision.NOT_A_DIALOG
        }
        if (txt.contains("清除全部数据") && txt.contains("清除缓存")) {
            return DialogDecision.WAIT_TRANSITION
        }
        if (ABSOLUTE_FORBIDDEN.any { txt.contains(it) } || ACCOUNT_WIPE_PHRASES.any { txt.contains(it) }) {
            return DialogDecision.ABORT_DANGEROUS
        }
        if (txt.contains("缓存") || txt.contains("cache")) {
            return DialogDecision.CONFIRM_CACHE
        }
        if (txt.contains("账号") || txt.contains("密码")) {
            return DialogDecision.WAIT_TRANSITION
        }
        return DialogDecision.NOT_A_DIALOG
    }

    fun isForceStopConfirmWindow(rawWindowText: String): Boolean {
        val txt = rawWindowText.lowercase()
        if (ABSOLUTE_FORBIDDEN.any { txt.contains(it) }) return false
        val hasCancel = txt.contains("取消") || txt.contains("cancel")
        val hasStop = txt.contains("强行停止") || txt.contains("强制停止") ||
            txt.contains("结束运行") || txt.contains("force stop")
        return hasCancel && hasStop
    }

    fun shouldBlockInstallerWindow(rawWindowText: String, highRiskKeywords: List<String>): Boolean {
        val txt = rawWindowText.lowercase()
        if (txt.contains("卸载") || txt.contains("uninstall")) {
            return false
        }
        val looksLikeInstall = txt.contains("安装") || txt.contains("install") ||
            txt.contains("未知来源") || txt.contains("风险")
        if (!looksLikeInstall) return false
        return (highRiskKeywords + BUILTIN_INSTALL_RISK_KEYWORDS).any { txt.contains(it.lowercase()) }
    }

    fun extractSizeTokens(rawWindowText: String): Set<String> {
        return STORAGE_SIZE_CONTEXT_REGEX.findAll(rawWindowText)
            .mapNotNull { m -> m.groups[1]?.value?.lowercase()?.replace(" ", "") }
            .toSet()
    }

    fun isValidPinFormat(pin: String): Boolean {
        return pin.length == 4 && pin.all { it.isDigit() }
    }

    fun hashPin(pin: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val derived = derivePbkdf2(pin, salt, PBKDF2_ITERATIONS)
        return "$PBKDF2_PREFIX:$PBKDF2_ITERATIONS:${toHex(salt)}:${toHex(derived)}"
    }

    fun isLegacyPinHash(storedHash: String): Boolean {
        return storedHash.length == 64 && !storedHash.startsWith("$PBKDF2_PREFIX:")
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return false
        if (storedHash.startsWith("$PBKDF2_PREFIX:")) {
            val parts = storedHash.split(":")
            if (parts.size != 4) return false
            val iterations = parts[1].toIntOrNull() ?: return false
            if (iterations < 10_000) return false
            val salt = fromHex(parts[2]) ?: return false
            val expected = fromHex(parts[3]) ?: return false
            val actual = derivePbkdf2(pin, salt, iterations)
            return MessageDigest.isEqual(actual, expected)
        }
        val legacyExpected = fromHex(storedHash) ?: return false
        val legacyActual = legacySha256Bytes(pin)
        return MessageDigest.isEqual(legacyActual, legacyExpected)
    }

    fun legacySha256Hash(pin: String): String = toHex(legacySha256Bytes(pin))

    private fun legacySha256Bytes(pin: String): ByteArray {
        val salted = "psg_v6_pin_salt::$pin"
        return MessageDigest.getInstance("SHA-256").digest(salted.toByteArray(Charsets.UTF_8))
    }

    private fun derivePbkdf2(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun generateRecoveryCode(): String {
        val rng = SecureRandom()
        val raw = buildString(8) {
            repeat(8) {
                append(RECOVERY_ALPHABET[rng.nextInt(RECOVERY_ALPHABET.length)])
            }
        }
        return "${raw.substring(0, 4)}-${raw.substring(4, 8)}"
    }

    fun normalizeRecoveryCode(raw: String): String {
        return raw.uppercase().filter { it.isLetterOrDigit() }
    }

    fun hashRecoveryCode(code: String): String {
        val norm = normalizeRecoveryCode(code)
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val derived = derivePbkdf2("rc::$norm", salt, PBKDF2_ITERATIONS)
        return "$PBKDF2_PREFIX:$PBKDF2_ITERATIONS:${toHex(salt)}:${toHex(derived)}"
    }

    fun verifyRecoveryCode(input: String, storedHash: String): Boolean {
        val norm = normalizeRecoveryCode(input)
        if (norm.length != 8 || !storedHash.startsWith("$PBKDF2_PREFIX:")) return false
        val parts = storedHash.split(":")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = fromHex(parts[2]) ?: return false
        val expected = fromHex(parts[3]) ?: return false
        val actual = derivePbkdf2("rc::$norm", salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }

    fun lockoutDurationMsForFailures(failedAttempts: Int): Long {
        return when {
            failedAttempts >= 10 -> 30L * 60_000L
            failedAttempts >= 6 -> 5L * 60_000L
            failedAttempts >= 3 -> 30_000L
            else -> 0L
        }
    }

    fun formatLockoutRemaining(remainingMs: Long): String {
        val totalSec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return if (min > 0L) "${min} 分 ${sec} 秒" else "${sec} 秒"
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun <T> filterRecentlyCleaned(
        items: List<T>,
        recentlyCleanedTimestamps: Map<String, Long>,
        nowMs: Long,
        ttlMs: Long,
        packageSelector: (T) -> String
    ): List<T> {
        if (recentlyCleanedTimestamps.isEmpty()) return items
        return items.filter { item ->
            val cleanedAt = recentlyCleanedTimestamps[packageSelector(item)] ?: return@filter true
            (nowMs - cleanedAt) >= ttlMs
        }
    }

    fun isUserInterruptedForeground(
        activeWindowPkg: String?,
        allowedSettingsPkgs: Set<String>
    ): Boolean {
        if (activeWindowPkg.isNullOrBlank()) return false
        if (activeWindowPkg == "com.android.systemui") return false
        return activeWindowPkg !in allowedSettingsPkgs
    }

    fun nextInterruptHits(
        currentHits: Int,
        activeWindowPkg: String?,
        allowedSettingsPkgs: Set<String>
    ): Int {
        return if (isUserInterruptedForeground(activeWindowPkg, allowedSettingsPkgs)) {
            currentHits + 1
        } else {
            0
        }
    }
}
