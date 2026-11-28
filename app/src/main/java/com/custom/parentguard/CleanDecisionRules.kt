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
 * 纯函数安全判定规则、PBKDF2 强哈希、恢复码与阶梯冷却计算（无 Android 框架依赖，全部由 JUnit 单元测试直接验证）。
 */
object CleanDecisionRules {

    const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_PREFIX = "pbkdf2_sha256"
    private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    val ABSOLUTE_FORBIDDEN = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage",
        "卸载", "uninstall", "停用", "disable", "恢复出厂"
    )

    private val DATA_WIPE_MENU_KEYWORDS = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage"
    )

    val NON_MIUI_FORBIDDEN = listOf(
        "清除数据", "clear data", "清空数据"
    )

    // 恢复内置兜底安装风险词：即使调用方未传全词表，纯函数自身也能守住安全底线
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
        if (ABSOLUTE_FORBIDDEN.any { txt.contains(it) } || txt.contains("账号")) {
            return DialogDecision.ABORT_DANGEROUS
        }
        if (txt.contains("缓存") || txt.contains("cache")) {
            return DialogDecision.CONFIRM_CACHE
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

    fun isValidPinFormat(pin: String): Boolean {
        return pin.length == 4 && pin.all { it.isDigit() }
    }

    /** P2-2 修复：PBKDF2WithHmacSHA256 + 10万次迭代 + 16字节随机盐 */
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
        // 平滑兼容 v6.0 / v6.1 旧版单次 SHA-256 哈希，登录成功后由 GuardStore 自动升级存盘
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

    /** P1-1 兜底：生成 8 位易读一次性恢复码（格式 XXXX-XXXX，不含 0/O/1/I/L） */
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

    /** P2-1 修复：防穷举阶梯式冷却策略（3次=30秒，6次=5分钟，10次及以上=30分钟） */
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
}
