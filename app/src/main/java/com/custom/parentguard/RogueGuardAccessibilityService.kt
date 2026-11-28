package com.custom.parentguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 独立服务 2（可选）：日常防流氓弹窗守护 v6.0
 */
class RogueGuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RogueGuardAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.contains(context.packageName) &&
                    enabled.contains(RogueGuardAccessibilityService::class.java.simpleName)
            } catch (_: Exception) {
                false
            }
        }
    }

    private var lastBlockMs = 0L
    private var lastSeenPkg = ""

    private val INSTALL_PKGS = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.vivo.packageinstaller",
        "com.oplus.packageinstaller",
        "com.coloros.packageinstaller",
        "com.huawei.appmarket.packageinstaller",
        "com.hihonor.appmarket.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    private val QUICK_APP_PKGS = setOf(
        "com.miui.hybrid",
        "com.miui.hybrid.accessory",
        "com.vivo.hybrid",
        "com.vivo.quickapp",
        "com.oplus.quickapp",
        "com.nearme.instant.platform",
        "com.heytap.instant.platform",
        "com.huawei.fastapp",
        "com.hihonor.fastapp",
        "org.hapjs.mockup"
    )

    private val HIGH_RISK_INSTALL_KEYWORDS = listOf(
        "未知来源", "高风险", "未经官方", "恶意", "风险应用", "安全风险", "报毒"
    ) + IroncladWhitelist.ROGUE_NAME_HINTS

    private val CANCEL_LABELS = setOf("取消", "否", "拒绝", "不安装", "放弃安装", "cancel", "no", "deny")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GuardStore.setRogueServiceConnected(true)
        GuardStore.load(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        GuardStore.setRogueServiceConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        GuardStore.setRogueServiceConnected(false)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    private fun canBlock(gapMs: Long = 1500L): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBlockMs < gapMs) return false
        lastBlockMs = now
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (GuardStore.sessionActive || GuardStore.isGuardTemporarilyPaused()) return

        try {
            val root = rootInActiveWindow ?: return
            val winPkg = root.packageName?.toString() ?: (event.packageName?.toString() ?: "")
            if (winPkg.isEmpty() || winPkg == packageName) return

            val cfg = GuardStore.config.value

            if (cfg.blockHighRiskInstall && winPkg in INSTALL_PKGS) {
                val txt = collectText(root)
                if (CleanDecisionRules.shouldBlockInstallerWindow(txt, HIGH_RISK_INSTALL_KEYWORDS) && canBlock()) {
                    val cancel = findCancelButton(root)
                    if (cancel == null || !clickSafe(cancel)) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    GuardStore.recordBlocked(this, "挡住高风险/未知来源安装包弹窗")
                }
                return
            }

            if (cfg.blockQuickAppPopup && winPkg in QUICK_APP_PKGS && winPkg != lastSeenPkg) {
                if (canBlock()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    GuardStore.recordBlocked(this, "退回快应用广告弹窗")
                    lastSeenPkg = winPkg
                }
                return
            }

            if (cfg.blockRogueAppJump && winPkg != lastSeenPkg) {
                val customLocked = GuardStore.customProtected.value
                if (IroncladWhitelist.isSuspectRogue(this, winPkg, packageName, customLocked)) {
                    if (canBlock(2000L)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        GuardStore.recordBlocked(this, "拦住疑似流氓马甲软件乱跳")
                        lastSeenPkg = winPkg
                    }
                    return
                }
            }

            lastSeenPkg = winPkg
        } catch (_: Exception) {
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return sb.toString()
    }

    private fun findCancelButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/button2")
        if (!byId.isNullOrEmpty()) {
            val cand = byId.first()
            val t = (cand.text?.toString() ?: "").trim().lowercase()
            if (t in CANCEL_LABELS) return cand
        }
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
            if (t.isNotEmpty() && t in CANCEL_LABELS) return n
            for (i in 0 until n.childCount) {
                val r = walk(n.getChild(i))
                if (r != null) return r
            }
            return null
        }
        return walk(root)
    }

    private fun clickSafe(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }
}
