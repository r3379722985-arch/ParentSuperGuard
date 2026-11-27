package com.custom.parentguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 独立服务 1：专职多品牌安全清缓存引擎 v5.4
 * 本轮精修：
 * 1. 修复最隐蔽的大坑：确认弹窗检测改用 CleanDecisionRules，背景应用信息页含“卸载”字样绝不再误触发返回/取消；
 * 2. 窗口排序改为顶层弹窗 layer 最高优先，确保先识别弹窗与底部菜单再看底层页面；
 * 3. 存储入口放宽合并文案长度上限（适配「存储占用 1.24 GB」「存储空间 342 MB」），存储子页同步支持大字号向下滚动查找；
 * 4. 校验 safeClick 返回值，并在 onServiceConnected / onUnbind / onDestroy 实时同步连接状态，disableSelf 后界面秒级刷新。
 */
class CacheCleanAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CacheCleanAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.contains(context.packageName) &&
                    enabled.contains(CacheCleanAccessibilityService::class.java.simpleName)
            } catch (_: Exception) {
                false
            }
        }
    }

    private enum class CleanStep {
        IDLE,
        APP_INFO,
        MIUI_MENU,
        STORAGE_PAGE,
        CACHE_CONFIRM,
        FORCE_STOP
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rom: RomFamily by lazy { BrandDetector.detect() }

    private var cleanStep = CleanStep.IDLE
    private var didCleanCurrentApp = false
    private var scrollAttemptsAppInfo = 0
    private var scrollAttemptsStorage = 0
    private var ignoreEventsUntilMs = 0L
    private var lastActionMs = 0L
    private var appTimeoutRunnable: Runnable? = null
    private var confirmFallbackRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null

    private val EXACT_CONFIRM_OK = setOf("确定", "清除", "清空", "确认", "ok", "清除缓存", "清空缓存")
    private val EXACT_CONFIRM_STOP = setOf("确定", "强行停止", "强制停止", "结束运行", "ok", "force stop")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GuardStore.setCleanServiceConnected(true)
        GuardStore.load(this)
        if (GuardStore.sessionActive) {
            openNextCacheTarget()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        GuardStore.setCleanServiceConnected(false)
        clearTimers()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        GuardStore.setCleanServiceConnected(false)
        clearTimers()
        super.onDestroy()
    }

    override fun onInterrupt() = clearTimers()

    fun beginCacheQueue() {
        if (!GuardStore.sessionActive) return
        openNextCacheTarget()
    }

    private fun openNextCacheTarget() {
        clearTimers()
        val target = GuardStore.currentApp()
        if (target == null) {
            finishQueueAndReturn()
            return
        }
        val customLocked = GuardStore.customProtected.value
        if (IroncladWhitelist.isStrictlyProtectedFromClean(target.packageName, packageName, customLocked)) {
            advanceApp(false)
            return
        }

        cleanStep = CleanStep.APP_INFO
        didCleanCurrentApp = false
        scrollAttemptsAppInfo = 0
        scrollAttemptsStorage = 0
        lastActionMs = 0L
        ignoreEventsUntilMs = System.currentTimeMillis() + 350L

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${target.packageName}")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(intent)
            scheduleTimeout(4800L)
            schedulePoll(450L)
        } catch (_: Exception) {
            advanceApp(false)
        }
    }

    private fun scheduleTimeout(ms: Long) {
        appTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (GuardStore.sessionActive) {
                advanceApp(didCleanCurrentApp)
            }
        }
        appTimeoutRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun schedulePoll(delayMs: Long) {
        pollRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (GuardStore.sessionActive) {
                processCurrentWindows()
            }
        }
        pollRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun scheduleConfirmFallback() {
        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (GuardStore.sessionActive && cleanStep == CleanStep.CACHE_CONFIRM) {
                afterCacheStep()
            }
        }
        confirmFallbackRunnable = r
        handler.postDelayed(r, 950L)
    }

    private fun clearTimers() {
        appTimeoutRunnable?.let { handler.removeCallbacks(it) }
        appTimeoutRunnable = null
        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
        confirmFallbackRunnable = null
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun isCoolingDown(gapMs: Long = 220L): Boolean {
        val now = System.currentTimeMillis()
        if (now < ignoreEventsUntilMs) return true
        return (now - lastActionMs) < gapMs
    }

    private fun markActionTaken(extraIgnoreMs: Long = 0L) {
        val now = System.currentTimeMillis()
        lastActionMs = now
        if (extraIgnoreMs > 0L) {
            ignoreEventsUntilMs = maxOf(ignoreEventsUntilMs, now + extraIgnoreMs)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !GuardStore.sessionActive) return
        if (System.currentTimeMillis() < ignoreEventsUntilMs) return
        processCurrentWindows()
    }

    /** 顶层弹窗 / 底部菜单（layer 最高）严格排在最前，确保先处理弹窗再看底层窗口 */
    private fun getCandidateRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val allWins = windows
            if (!allWins.isNullOrEmpty()) {
                for (w in allWins.sortedByDescending { it.layer }) {
                    val r = w.root ?: continue
                    if (roots.none { it == r }) {
                        roots.add(r)
                    }
                }
            }
            val active = rootInActiveWindow
            if (active != null && roots.none { it == active }) {
                roots.add(active)
            }
        } catch (_: Exception) {
        }
        return roots
    }

    private fun processCurrentWindows() {
        if (!GuardStore.sessionActive || isCoolingDown(200L)) return
        try {
            val roots = getCandidateRoots()
            if (roots.isEmpty()) return

            when (cleanStep) {
                CleanStep.APP_INFO -> {
                    for (r in roots) {
                        if (onCleanAppInfo(r, r.packageName?.toString() ?: "")) return
                    }
                }
                CleanStep.MIUI_MENU -> {
                    for (r in roots) {
                        if (onCleanMiuiMenu(r)) return
                    }
                }
                CleanStep.STORAGE_PAGE -> {
                    for (r in roots) {
                        if (onCleanStoragePage(r, r.packageName?.toString() ?: "")) return
                    }
                }
                CleanStep.CACHE_CONFIRM -> {
                    for (r in roots) {
                        if (onCleanConfirmDialog(r)) return
                    }
                }
                CleanStep.FORCE_STOP -> {
                    for (r in roots) {
                        if (onCleanForceStop(r)) return
                    }
                }
                CleanStep.IDLE -> Unit
            }
        } catch (_: Exception) {
        }
    }

    private fun onCleanAppInfo(root: AccessibilityNodeInfo, winPkg: String): Boolean {
        val directCache = findNode(root, rom.clearCacheLabels, allowMiuiMenu = false, maxExtraChars = 6)
        if (directCache != null) {
            markActionTaken()
            if (isEffectivelyEnabled(directCache)) {
                if (safeClick(directCache)) {
                    didCleanCurrentApp = true
                    cleanStep = CleanStep.CACHE_CONFIRM
                    scheduleConfirmFallback()
                    schedulePoll(360L)
                }
            } else {
                afterCacheStep()
            }
            return true
        }

        // 小米/红米 MIUI & 澎湃OS 底部「清除数据」唤出底部菜单（仅选清除缓存）
        if (rom == RomFamily.XIAOMI && winPkg == "com.miui.securitycenter") {
            if (!windowText(root).contains("清除全部数据")) {
                val menuBtn = findNode(root, listOf("清除数据"), allowMiuiMenu = true, maxExtraChars = 2)
                if (menuBtn != null && isEffectivelyEnabled(menuBtn)) {
                    markActionTaken(240L)
                    cleanStep = CleanStep.MIUI_MENU
                    safeClick(menuBtn)
                    scheduleTimeout(3500L)
                    schedulePoll(360L)
                    return true
                }
            }
        }

        // 存储入口允许较长合并文案（如「存储占用 1.24 GB」「存储空间 342 MB」）
        val storageBtn = findNode(root, rom.storageEntryLabels, allowMiuiMenu = false, maxExtraChars = 18)
        if (storageBtn != null) {
            markActionTaken(260L)
            cleanStep = CleanStep.STORAGE_PAGE
            safeClick(storageBtn)
            scheduleTimeout(4000L)
            schedulePoll(420L)
            return true
        }

        if (scrollAttemptsAppInfo < 2) {
            if (scrollForward(root)) {
                scrollAttemptsAppInfo++
                markActionTaken(260L)
                schedulePoll(420L)
                return true
            }
        }
        return false
    }

    private fun onCleanMiuiMenu(root: AccessibilityNodeInfo): Boolean {
        val cacheOpt = findNode(root, rom.clearCacheLabels, allowMiuiMenu = false, maxExtraChars = 6)
        if (cacheOpt != null && isEffectivelyEnabled(cacheOpt)) {
            markActionTaken(320L)
            if (safeClick(cacheOpt)) {
                didCleanCurrentApp = true
                cleanStep = CleanStep.CACHE_CONFIRM
                scheduleTimeout(3200L)
                scheduleConfirmFallback()
                schedulePoll(380L)
            }
            return true
        }
        val txt = windowText(root)
        if (txt.contains("清除全部数据")) {
            markActionTaken(250L)
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ afterCacheStep() }, 300L)
            return true
        }
        return false
    }

    private fun onCleanStoragePage(root: AccessibilityNodeInfo, winPkg: String): Boolean {
        val cacheBtn = findNode(root, rom.clearCacheLabels, allowMiuiMenu = false, maxExtraChars = 6)
        if (cacheBtn != null) {
            markActionTaken()
            if (isEffectivelyEnabled(cacheBtn)) {
                if (safeClick(cacheBtn)) {
                    didCleanCurrentApp = true
                    cleanStep = CleanStep.CACHE_CONFIRM
                    scheduleConfirmFallback()
                    schedulePoll(360L)
                }
            } else {
                afterCacheStep()
            }
            return true
        }

        if (rom == RomFamily.XIAOMI && winPkg == "com.miui.securitycenter") {
            if (!windowText(root).contains("清除全部数据")) {
                val menuBtn = findNode(root, listOf("清除数据"), allowMiuiMenu = true, maxExtraChars = 2)
                if (menuBtn != null && isEffectivelyEnabled(menuBtn)) {
                    markActionTaken(240L)
                    cleanStep = CleanStep.MIUI_MENU
                    safeClick(menuBtn)
                    scheduleTimeout(3500L)
                    schedulePoll(360L)
                    return true
                }
            }
        }

        // 大字号模式下存储页按钮可能位于屏幕下方，允许安全向下滑一次查找
        if (scrollAttemptsStorage < 1) {
            if (scrollForward(root)) {
                scrollAttemptsStorage++
                markActionTaken(260L)
                schedulePoll(420L)
                return true
            }
        }
        return false
    }

    private fun onCleanConfirmDialog(root: AccessibilityNodeInfo): Boolean {
        val txt = windowText(root)
        return when (CleanDecisionRules.evaluateCacheConfirmWindow(txt)) {
            DialogDecision.NOT_A_DIALOG, DialogDecision.WAIT_TRANSITION -> false
            DialogDecision.ABORT_DANGEROUS -> {
                markActionTaken(250L)
                performGlobalAction(GLOBAL_ACTION_BACK)
                advanceApp(false)
                true
            }
            DialogDecision.CONFIRM_CACHE -> {
                val positiveBtn = findDialogPositiveButton(root, EXACT_CONFIRM_OK)
                if (positiveBtn != null && isEffectivelyEnabled(positiveBtn)) {
                    confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
                    markActionTaken(280L)
                    safeClick(positiveBtn)
                    didCleanCurrentApp = true
                    handler.postDelayed({ afterCacheStep() }, 360L)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun afterCacheStep() {
        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
        if (!GuardStore.config.value.alsoForceStopOnClean) {
            advanceApp(didCleanCurrentApp)
            return
        }
        if (cleanStep == CleanStep.STORAGE_PAGE || cleanStep == CleanStep.CACHE_CONFIRM) {
            val roots = getCandidateRoots()
            val stopHere = roots.any { findNode(it, rom.forceStopLabels, allowMiuiMenu = false, maxExtraChars = 4) != null }
            cleanStep = CleanStep.FORCE_STOP
            if (!stopHere) {
                markActionTaken(240L)
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            scheduleTimeout(3200L)
            schedulePoll(400L)
        } else {
            cleanStep = CleanStep.FORCE_STOP
            scheduleTimeout(3200L)
            schedulePoll(360L)
        }
    }

    private fun onCleanForceStop(root: AccessibilityNodeInfo): Boolean {
        val txt = windowText(root)
        if (CleanDecisionRules.isForceStopConfirmWindow(txt)) {
            val ok = findDialogPositiveButton(root, EXACT_CONFIRM_STOP)
            if (ok != null && isEffectivelyEnabled(ok)) {
                markActionTaken(260L)
                safeClick(ok)
                handler.postDelayed({ advanceApp(didCleanCurrentApp) }, 360L)
                return true
            }
        }

        val stopBtn = findNode(root, rom.forceStopLabels, allowMiuiMenu = false, maxExtraChars = 4)
        if (stopBtn != null) {
            markActionTaken(240L)
            if (isEffectivelyEnabled(stopBtn)) {
                safeClick(stopBtn)
                scheduleTimeout(2800L)
                schedulePoll(360L)
            } else {
                advanceApp(didCleanCurrentApp)
            }
            return true
        }
        return false
    }

    private fun advanceApp(didClean: Boolean) {
        clearTimers()
        val availNow = SystemScanner.getCurrentAvailBytes()
        val next = GuardStore.advanceCleanQueue(didClean, availNow)
        if (next == null) {
            finishQueueAndReturn()
        } else {
            openNextCacheTarget()
        }
    }

    private fun finishQueueAndReturn() {
        clearTimers()
        cleanStep = CleanStep.IDLE

        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }

        if (GuardStore.config.value.autoDisableCleanAccessibility) {
            handler.postDelayed({
                try { disableSelf() } catch (_: Exception) {}
            }, 650L)
        }
    }

    private fun findDialogPositiveButton(root: AccessibilityNodeInfo, exactLabels: Set<String>): AccessibilityNodeInfo? {
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (!byId.isNullOrEmpty()) {
            val cand = byId.first()
            val label = (cand.text?.toString() ?: "").trim().lowercase()
            if (label.isNotEmpty() &&
                !hasAny(label, CleanDecisionRules.ABSOLUTE_FORBIDDEN) &&
                !hasAny(label, CleanDecisionRules.NON_MIUI_FORBIDDEN)
            ) {
                return cand
            }
        }

        fun walkButton(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val raw = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
            if (raw.isNotEmpty() && raw in exactLabels) {
                val cls = n.className?.toString()?.lowercase() ?: ""
                val isBtnLike = n.isClickable || cls.contains("button") || (n.parent?.isClickable == true)
                if (isBtnLike &&
                    !hasAny(raw, CleanDecisionRules.ABSOLUTE_FORBIDDEN) &&
                    !hasAny(raw, CleanDecisionRules.NON_MIUI_FORBIDDEN)
                ) {
                    return n
                }
            }
            for (i in 0 until n.childCount) {
                val r = walkButton(n.getChild(i))
                if (r != null) return r
            }
            return null
        }
        return walkButton(root)
    }

    private fun isEffectivelyEnabled(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        var cur: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) {
                return cur.isEnabled
            }
            if (!cur.isEnabled) return false
            cur = cur.parent
            depth++
        }
        return true
    }

    private fun hasAny(text: String, list: List<String>): Boolean = list.any { text.contains(it) }

    private fun windowText(root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        allowMiuiMenu: Boolean,
        maxExtraChars: Int
    ): AccessibilityNodeInfo? {
        var prefixFallback: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val raw = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (raw.isNotEmpty()) {
                val lower = raw.lowercase()
                val forbidden = hasAny(lower, CleanDecisionRules.ABSOLUTE_FORBIDDEN) ||
                    (!allowMiuiMenu && hasAny(lower, CleanDecisionRules.NON_MIUI_FORBIDDEN))
                if (!forbidden) {
                    if (labels.any { lower == it }) {
                        return n
                    }
                    if (prefixFallback == null && labels.any { lower.startsWith(it) && lower.length <= it.length + maxExtraChars }) {
                        prefixFallback = n
                    }
                }
            }
            for (i in 0 until n.childCount) {
                val res = walk(n.getChild(i))
                if (res != null) return res
            }
            return null
        }
        return walk(root) ?: prefixFallback
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        fun findScroll(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                val r = findScroll(n.getChild(i))
                if (r != null) return r
            }
            return null
        }
        return findScroll(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    private fun safeClick(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            cur = cur.parent
            depth++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            return dispatchGesture(g, null, null)
        }
        return false
    }
}
