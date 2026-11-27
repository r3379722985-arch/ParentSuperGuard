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
 * 独立服务 1：专职多品牌安全清缓存引擎 v5.6
 * 本轮深度修复：
 * 1. MIUI_MENU 阶段改为先扫描所有窗口查找「清除缓存」，全未找到时再检查危险菜单，配合 CleanDecisionRules.isDangerOnlyMenu 彻底消除底层页面含“卸载”导致的误返回；
 * 2. 严格区分服务真实在线（isServiceRunning）与系统设置残留（isEnabledInSettings），彻底消除小米/vivo 僵尸无障碍导致的主按钮假死；
 * 3. 新增 stopImmediately() 立即清空全部延迟回调，点击「停止自动清理」时不会再被残余延时回调覆盖；
 * 4. android:id/button1 同步强制校验 exactLabels，绝不误点取消或未知文案；
 * 5. 看到二次确认弹窗后，真正点下确定按钮才计入已清理成功数，统计更诚实。
 */
class CacheCleanAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CacheCleanAccessibilityService? = null
            private set

        /** 服务当前进程是否真实在线运行 */
        fun isServiceRunning(): Boolean = instance != null

        /** 系统无障碍设置里开关是否处于勾选态（注：部分机型后台休眠后可能处于勾选但未运行的僵尸态） */
        fun isEnabledInSettings(context: Context): Boolean {
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
    private var targetIdentityVerified = false
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
        cleanStep = CleanStep.IDLE
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        GuardStore.setCleanServiceConnected(false)
        clearTimers()
        cleanStep = CleanStep.IDLE
        super.onDestroy()
    }

    override fun onInterrupt() = clearTimers()

    fun beginCacheQueue() {
        if (!GuardStore.sessionActive) return
        openNextCacheTarget()
    }

    /** 点击「停止自动清理」时立刻终止所有挂起的延迟回调与状态机 */
    fun stopImmediately() {
        clearTimers()
        cleanStep = CleanStep.IDLE
        didCleanCurrentApp = false
    }

    private fun openNextCacheTarget() {
        clearTimers()
        if (!GuardStore.sessionActive) {
            cleanStep = CleanStep.IDLE
            return
        }
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
        targetIdentityVerified = false
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
                    // 先在所有窗口中寻找「清除缓存」选项，确保不会因为窗口顺序先误判其他窗口
                    for (r in roots) {
                        if (tryClickMiuiClearCacheOption(r)) return
                    }
                    for (r in roots) {
                        if (tryDismissDangerOnlyMenu(r)) return
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
        val target = GuardStore.currentApp() ?: return false
        val winTxt = windowText(root)

        if (!targetIdentityVerified) {
            if (!CleanDecisionRules.matchesTargetAppWindow(winTxt, target.appName, target.packageName)) {
                return false
            }
            targetIdentityVerified = true
        }

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

        if (rom == RomFamily.XIAOMI && winPkg == "com.miui.securitycenter") {
            if (!winTxt.contains("清除全部数据")) {
                val menuBtn = findNode(root, listOf("清除数据"), allowMiuiMenu = true, maxExtraChars = 2)
                if (menuBtn != null && isEffectivelyEnabled(menuBtn)) {
                    markActionTaken(260L)
                    cleanStep = CleanStep.MIUI_MENU
                    safeClick(menuBtn)
                    scheduleTimeout(3500L)
                    schedulePoll(380L)
                    return true
                }
            }
        }

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

    private fun tryClickMiuiClearCacheOption(root: AccessibilityNodeInfo): Boolean {
        val cacheOpt = findNode(root, rom.clearCacheLabels, allowMiuiMenu = false, maxExtraChars = 6)
        if (cacheOpt != null && isEffectivelyEnabled(cacheOpt)) {
            markActionTaken(320L)
            if (safeClick(cacheOpt)) {
                // 小米点完菜单里的清除缓存后通常会弹二次确认框，先标 true 供无弹窗机型兜底，若看到弹窗则在点确定时最终确认
                didCleanCurrentApp = true
                cleanStep = CleanStep.CACHE_CONFIRM
                scheduleTimeout(3200L)
                scheduleConfirmFallback()
                schedulePoll(380L)
            }
            return true
        }
        return false
    }

    private fun tryDismissDangerOnlyMenu(root: AccessibilityNodeInfo): Boolean {
        val txt = windowText(root)
        if (CleanDecisionRules.isDangerOnlyMenu(txt, rom.clearCacheLabels)) {
            markActionTaken(250L)
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                if (GuardStore.sessionActive) afterCacheStep()
            }, 300L)
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
                    markActionTaken(260L)
                    cleanStep = CleanStep.MIUI_MENU
                    safeClick(menuBtn)
                    scheduleTimeout(3500L)
                    schedulePoll(380L)
                    return true
                }
            }
        }

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
                didCleanCurrentApp = false
                performGlobalAction(GLOBAL_ACTION_BACK)
                advanceApp(false)
                true
            }
            DialogDecision.CONFIRM_CACHE -> {
                // 既然真正出现了二次确认弹窗，就必须等到点下确定按钮才算清理成功
                didCleanCurrentApp = false
                val positiveBtn = findDialogPositiveButton(root, EXACT_CONFIRM_OK)
                if (positiveBtn != null && isEffectivelyEnabled(positiveBtn)) {
                    confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
                    markActionTaken(280L)
                    val clicked = safeClick(positiveBtn)
                    didCleanCurrentApp = clicked
                    handler.postDelayed({
                        if (GuardStore.sessionActive) afterCacheStep()
                    }, 360L)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun afterCacheStep() {
        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
        if (!GuardStore.sessionActive) return
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
                handler.postDelayed({
                    if (GuardStore.sessionActive) advanceApp(didCleanCurrentApp)
                }, 360L)
                return true
            }
        }

        val target = GuardStore.currentApp() ?: return false
        if (!CleanDecisionRules.matchesTargetAppWindow(txt, target.appName, target.packageName)) {
            return false
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
        if (!GuardStore.sessionActive) {
            cleanStep = CleanStep.IDLE
            return
        }
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
        // 即使命中 android:id/button1，也必须强制校验文案在安全确定词集合内，绝不误点取消或其他未知按钮
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (!byId.isNullOrEmpty()) {
            val cand = byId.first()
            val label = (cand.text?.toString() ?: "").trim().lowercase()
            if (label in exactLabels &&
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
