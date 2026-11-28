package com.custom.parentguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 独立服务 1：类女仆清理（SD Maid SE）架构的多品牌安全清缓存引擎 v7.0
 */
class CacheCleanAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CacheCleanAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

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

        fun requestUserStop(context: Context, bringAppToFront: Boolean) {
            val svc = instance
            if (svc != null) {
                svc.cancelByUserAndReturn(bringAppToFront = bringAppToFront)
            } else {
                GuardStore.cancelCleanQueue(context, includeCurrentCleaned = false)
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

    private enum class ClickSource {
        NONE,
        DIRECT_OR_STORAGE,
        MIUI_BOTTOM_MENU
    }

    private val ALLOWED_CLEAN_WINDOW_PKGS = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.xiaomi.misettings",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.coloros.safecenter",
        "com.coloros.appdetail",
        "com.oplus.safecenter",
        "com.oplus.appdetail",
        "com.iqoo.secure",
        "com.vivo.abe",
        "com.vivo.appdetail",
        "com.vivo.permissionmanager",
        "com.vivo.appfilter",
        "com.meizu.safe",
        "com.samsung.android.lool"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val rom: RomFamily by lazy { BrandDetector.detect() }

    private var cleanStep = CleanStep.IDLE
    private var currentOutcome = AppCleanOutcome.FAILED
    private var clickSource = ClickSource.NONE
    private var preClickSizeTokens: Set<String> = emptySet()
    private var targetIdentityVerified = false
    private var triedMiuiMenuForCurrent = false
    private var triedStoragePageForCurrent = false
    private var confirmClickRetries = 0
    private var pendingTransition = false
    private var scrollAttemptsAppInfo = 0
    private var scrollAttemptsStorage = 0
    private var interruptHits = 0
    private var ignoreEventsUntilMs = 0L
    private var userInterruptCheckAfterMs = 0L
    private var lastActionMs = 0L
    private var appTimeoutRunnable: Runnable? = null
    private var confirmFallbackRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null
    private var transitionRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null

    private var overlayView: View? = null
    private var overlayStatusText: TextView? = null

    private val EXACT_CONFIRM_OK = setOf("确定", "清除", "清空", "确认", "ok", "清除缓存", "清空缓存")
    private val EXACT_CONFIRM_STOP = setOf("确定", "强行停止", "强制停止", "结束运行", "ok", "force stop")
    private val KNOWN_CLEAR_CACHE_VIEW_IDS = listOf(
        "com.android.settings:id/clear_cache_button",
        "com.android.settings:id/button2",
        "com.oplus.appdetail:id/clear_cache_button",
        "com.vivo.abe:id/clear_cache_button"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GuardStore.setCleanServiceConnected(true)
        GuardStore.ensureLoaded(this)
        CleanDiagLogger.log(this, "service_connected rom=${rom.name} sessionActive=${GuardStore.sessionActive}")
        if (GuardStore.sessionActive) {
            Thread {
                val freed = SystemScanner.cleanExternalSafeFilesSync()
                if (freed > 0L) GuardStore.addExternalFreedBytes(freed)
            }.start()
            openNextCacheTarget()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CleanDiagLogger.log(this, "service_unbind sessionActive=${GuardStore.sessionActive}")
        instance = null
        GuardStore.setCleanServiceConnected(false)
        clearTimers()
        hideProgressOverlay()
        cleanStep = CleanStep.IDLE
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        GuardStore.setCleanServiceConnected(false)
        clearTimers()
        hideProgressOverlay()
        cleanStep = CleanStep.IDLE
        super.onDestroy()
    }

    override fun onInterrupt() {
        CleanDiagLogger.log(this, "service_onInterrupt sessionActive=${GuardStore.sessionActive}")
        if (GuardStore.sessionActive) {
            cancelByUserAndReturn(bringAppToFront = false)
        } else {
            clearTimers()
            hideProgressOverlay()
        }
    }

    fun beginCacheQueue() {
        if (!GuardStore.sessionActive) return
        CleanDiagLogger.log(this, "begin_queue total=${GuardStore.queue.size} rom=${rom.name}")
        openNextCacheTarget()
    }

    fun stopImmediately() {
        clearTimers()
        hideProgressOverlay()
        cleanStep = CleanStep.IDLE
        currentOutcome = AppCleanOutcome.FAILED
        interruptHits = 0
    }

    private fun cancelByUserAndReturn(bringAppToFront: Boolean) {
        val didCleanCurrent = (currentOutcome == AppCleanOutcome.CLEANED)
        if (bringAppToFront) {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
        stopImmediately()
        GuardStore.cancelCleanQueue(this, includeCurrentCleaned = didCleanCurrent)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(38)
    }

    private fun showOrUpdateProgressOverlay(index: Int, total: Int, appName: String) {
        handler.post {
            try {
                val text = "清理中 ($index/$total)：$appName"
                val existingText = overlayStatusText
                if (overlayView != null && existingText != null) {
                    existingText.text = text
                    return@post
                }
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                overlayView?.let { stale ->
                    try { wm.removeViewImmediate(stale) } catch (_: Throwable) {}
                    overlayView = null
                    overlayStatusText = null
                }

                val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(dp(320))
                val barWidth = minOf(screenWidth - dp(24), dp(380))

                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#EB14532D"))
                        cornerRadius = dp(24).toFloat()
                    }
                    elevation = dp(6).toFloat()
                }

                val statusTv = TextView(this).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val stopBtn = TextView(this).apply {
                    this.text = "停止"
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#DC2626"))
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener {
                        CleanDiagLogger.log(this@CacheCleanAccessibilityService, "user_clicked_overlay_stop")
                        cancelByUserAndReturn(bringAppToFront = true)
                    }
                }

                val textLp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = dp(10)
                }
                val btnLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                container.addView(statusTv, textLp)
                container.addView(stopBtn, btnLp)

                val params = WindowManager.LayoutParams(
                    barWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = getStatusBarHeightPx() + dp(8)
                }

                wm.addView(container, params)
                overlayView = container
                overlayStatusText = statusTv
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideProgressOverlay() {
        handler.post {
            try {
                val v = overlayView
                if (v != null) {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    wm?.removeViewImmediate(v)
                }
            } catch (_: Throwable) {
            } finally {
                overlayView = null
                overlayStatusText = null
            }
        }
    }

    private fun startForegroundWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!GuardStore.sessionActive) {
                    watchdogRunnable = null
                    return
                }
                if (checkUserInterruptedManually()) {
                    watchdogRunnable = null
                    return
                }
                handler.postDelayed(this, 400L)
            }
        }
        watchdogRunnable = r
        handler.postDelayed(r, 400L)
    }

    private fun openNextCacheTarget() {
        clearTimers()
        if (!GuardStore.sessionActive) {
            hideProgressOverlay()
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
            CleanDiagLogger.log(this, "skip_protected pkg=${target.packageName}")
            advanceApp(AppCleanOutcome.SKIPPED)
            return
        }

        val runState = GuardStore.cleanState.value
        showOrUpdateProgressOverlay(
            index = runState.currentIndex.coerceAtLeast(1),
            total = runState.totalCount.coerceAtLeast(1),
            appName = target.appName
        )

        cleanStep = CleanStep.APP_INFO
        currentOutcome = AppCleanOutcome.FAILED
        clickSource = ClickSource.NONE
        preClickSizeTokens = emptySet()
        targetIdentityVerified = false
        triedMiuiMenuForCurrent = false
        triedStoragePageForCurrent = false
        confirmClickRetries = 0
        pendingTransition = false
        scrollAttemptsAppInfo = 0
        scrollAttemptsStorage = 0
        interruptHits = 0
        lastActionMs = 0L

        val now = System.currentTimeMillis()
        ignoreEventsUntilMs = now + 260L
        userInterruptCheckAfterMs = now + 2600L

        CleanDiagLogger.log(
            this,
            "open step=APP_INFO idx=${runState.currentIndex}/${runState.totalCount} pkg=${target.packageName} name=${target.appName}"
        )

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
            startForegroundWatchdog()
            scheduleTimeout(5200L)
            schedulePoll(380L)
        } catch (e: Exception) {
            CleanDiagLogger.log(this, "open_failed pkg=${target.packageName} err=${e.message}")
            advanceApp(AppCleanOutcome.FAILED)
        }
    }

    private fun checkUserInterruptedManually(): Boolean {
        if (!GuardStore.sessionActive) return true
        val now = System.currentTimeMillis()
        if (now < userInterruptCheckAfterMs) return false
        val activePkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val nextHits = CleanDecisionRules.nextInterruptHits(interruptHits, activePkg, ALLOWED_CLEAN_WINDOW_PKGS)
        if (nextHits != interruptHits) {
            interruptHits = nextHits
            if (interruptHits > 0) {
                CleanDiagLogger.log(this, "interrupt? pkg=$activePkg hits=$interruptHits/2")
            }
        }
        if (interruptHits >= 2) {
            CleanDiagLogger.log(this, "interrupt_confirmed pkg=$activePkg -> cancel")
            cancelByUserAndReturn(bringAppToFront = false)
            return true
        }
        return false
    }

    private fun scheduleTransition(delayMs: Long, action: () -> Unit) {
        transitionRunnable?.let { handler.removeCallbacks(it) }
        pendingTransition = true
        val r = Runnable {
            pendingTransition = false
            transitionRunnable = null
            if (GuardStore.sessionActive && !checkUserInterruptedManually()) {
                action()
            }
        }
        transitionRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun scheduleTimeout(ms: Long) {
        appTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (GuardStore.sessionActive && !checkUserInterruptedManually()) {
                // P2-1 修复：小米「清除数据」弹层超时未出现时，若还没试过「存储占用」页，自动降级尝试而不是直接 FAILED
                if (cleanStep == CleanStep.MIUI_MENU && !triedStoragePageForCurrent) {
                    CleanDiagLogger.log(this, "TIMEOUT_MIUI_MENU -> fallback to APP_INFO/storage_entry")
                    cleanStep = CleanStep.APP_INFO
                    scheduleTimeout(3800L)
                    schedulePoll(220L)
                    return@Runnable
                }
                CleanDiagLogger.log(this, "TIMEOUT step=$cleanStep outcome=$currentOutcome")
                advanceApp(currentOutcome)
            }
        }
        appTimeoutRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun schedulePoll(delayMs: Long) {
        pollRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (GuardStore.sessionActive && !checkUserInterruptedManually()) {
                processCurrentWindows()
            }
        }
        pollRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun scheduleConfirmFallback(delayMs: Long = 1150L) {
        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (GuardStore.sessionActive && !pendingTransition && cleanStep == CleanStep.CACHE_CONFIRM) {
                if (checkUserInterruptedManually()) return@Runnable
                val roots = getCandidateRoots()
                val dialogStillVisible = roots.any {
                    CleanDecisionRules.evaluateCacheConfirmWindow(windowText(it)) == DialogDecision.CONFIRM_CACHE
                }
                if (dialogStillVisible) {
                    CleanDiagLogger.log(this, "FALLBACK_DEFERRED confirm_dialog_still_visible -> poll")
                    processCurrentWindows()
                    scheduleConfirmFallback(900L)
                    return@Runnable
                }
                val sizeChanged = preClickSizeTokens.isNotEmpty() && roots.any { root ->
                    val nowTokens = CleanDecisionRules.extractSizeTokens(windowText(root))
                    nowTokens.isNotEmpty() && nowTokens != preClickSizeTokens
                }
                if (clickSource == ClickSource.MIUI_BOTTOM_MENU) {
                    if (sizeChanged) {
                        currentOutcome = AppCleanOutcome.CLEANED
                        CleanDiagLogger.log(this, "FALLBACK_MIUI size_changed -> CLEANED")
                    } else {
                        currentOutcome = AppCleanOutcome.FAILED
                        CleanDiagLogger.log(this, "FALLBACK_MIUI no_confirm_dialog_and_no_size_change -> FAILED")
                    }
                } else {
                    val buttonNowDisabled = roots.any { root ->
                        val btn = findClearCacheNode(root)
                        btn != null && !isEffectivelyEnabled(btn)
                    }
                    if (sizeChanged || buttonNowDisabled || preClickSizeTokens.isEmpty()) {
                        currentOutcome = AppCleanOutcome.CLEANED
                        CleanDiagLogger.log(
                            this,
                            "FALLBACK_DIRECT silent_clear sizeChanged=$sizeChanged btnDisabled=$buttonNowDisabled noBaseline=${preClickSizeTokens.isEmpty()} -> CLEANED"
                        )
                    } else {
                        currentOutcome = AppCleanOutcome.FAILED
                        CleanDiagLogger.log(this, "FALLBACK_DIRECT no_size_change_and_btn_enabled -> FAILED")
                    }
                }
                afterCacheStep()
            }
        }
        confirmFallbackRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun clearTimers() {
        pendingTransition = false
        appTimeoutRunnable?.let { handler.removeCallbacks(it) }
        appTimeoutRunnable = null
        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
        confirmFallbackRunnable = null
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        transitionRunnable?.let { handler.removeCallbacks(it) }
        transitionRunnable = null
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun isCoolingDown(gapMs: Long = 180L): Boolean {
        if (pendingTransition) return true
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
        if (event == null || !GuardStore.sessionActive || pendingTransition) return
        if (System.currentTimeMillis() < ignoreEventsUntilMs) return
        if (checkUserInterruptedManually()) return
        processCurrentWindows()
    }

    private fun getCandidateRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val allWins = windows
            if (!allWins.isNullOrEmpty()) {
                for (w in allWins.sortedByDescending { it.layer }) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == packageName) continue
                    if (roots.none { it == r }) {
                        roots.add(r)
                    }
                }
            }
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() != packageName && roots.none { it == active }) {
                roots.add(active)
            }
        } catch (_: Exception) {
        }
        return roots
    }

    private fun processCurrentWindows() {
        if (!GuardStore.sessionActive || isCoolingDown(160L)) return
        if (checkUserInterruptedManually()) return
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

    private fun findClearCacheNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (vid in KNOWN_CLEAR_CACHE_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(vid)
            if (!nodes.isNullOrEmpty()) {
                for (cand in nodes) {
                    val txt = (cand.text?.toString() ?: cand.contentDescription?.toString() ?: "").trim().lowercase()
                    if (txt.isNotEmpty() &&
                        !hasAny(txt, CleanDecisionRules.ABSOLUTE_FORBIDDEN) &&
                        !hasAny(txt, CleanDecisionRules.NON_MIUI_FORBIDDEN) &&
                        rom.clearCacheLabels.any { txt == it || txt.startsWith(it) }
                    ) {
                        return cand
                    }
                }
            }
        }
        return findNode(root, rom.clearCacheLabels, allowMiuiMenu = false, maxExtraChars = 6)
    }

    private fun onCleanAppInfo(root: AccessibilityNodeInfo, winPkg: String): Boolean {
        val target = GuardStore.currentApp() ?: return false
        val winTxt = windowText(root)

        if (!targetIdentityVerified) {
            if (!CleanDecisionRules.matchesTargetAppWindow(winTxt, target.appName, target.packageName)) {
                return false
            }
            targetIdentityVerified = true
            preClickSizeTokens = CleanDecisionRules.extractSizeTokens(winTxt)
            CleanDiagLogger.log(this, "verified_app_info pkg=${target.packageName} winPkg=$winPkg sizes=$preClickSizeTokens")
        }

        val directCache = findClearCacheNode(root)
        if (directCache != null) {
            markActionTaken()
            if (isEffectivelyEnabled(directCache)) {
                val ok = safeClick(directCache)
                CleanDiagLogger.log(this, "click_direct_cache ok=$ok")
                if (ok) {
                    clickSource = ClickSource.DIRECT_OR_STORAGE
                    cleanStep = CleanStep.CACHE_CONFIRM
                    scheduleConfirmFallback(1100L)
                    schedulePoll(240L)
                } else {
                    schedulePoll(260L)
                }
            } else {
                CleanDiagLogger.log(this, "direct_cache_disabled -> ALREADY_ZERO")
                currentOutcome = AppCleanOutcome.ALREADY_ZERO
                afterCacheStep()
            }
            return true
        }

        if (rom == RomFamily.XIAOMI && winPkg == "com.miui.securitycenter" && !triedMiuiMenuForCurrent) {
            if (!winTxt.contains("清除全部数据")) {
                val menuBtn = findNode(root, listOf("清除数据"), allowMiuiMenu = true, maxExtraChars = 2)
                if (menuBtn != null && isEffectivelyEnabled(menuBtn)) {
                    markActionTaken(240L)
                    triedMiuiMenuForCurrent = true
                    cleanStep = CleanStep.MIUI_MENU
                    val ok = safeClick(menuBtn)
                    CleanDiagLogger.log(this, "click_miui_menu ok=$ok")
                    scheduleTimeout(3800L)
                    schedulePoll(320L)
                    return true
                }
            }
        }

        if (!triedStoragePageForCurrent) {
            val storageBtn = findNode(root, rom.storageEntryLabels, allowMiuiMenu = false, maxExtraChars = 18)
            if (storageBtn != null) {
                markActionTaken(220L)
                triedStoragePageForCurrent = true
                cleanStep = CleanStep.STORAGE_PAGE
                val ok = safeClick(storageBtn)
                CleanDiagLogger.log(this, "click_storage_entry ok=$ok")
                scheduleTimeout(4000L)
                schedulePoll(340L)
                return true
            }
        }

        if (scrollAttemptsAppInfo < 2) {
            if (scrollForward(root)) {
                scrollAttemptsAppInfo++
                markActionTaken(240L)
                CleanDiagLogger.log(this, "scroll_app_info attempt=$scrollAttemptsAppInfo")
                schedulePoll(360L)
                return true
            }
        }
        return false
    }

    private fun tryClickMiuiClearCacheOption(root: AccessibilityNodeInfo): Boolean {
        val cacheOpt = findClearCacheNode(root)
        if (cacheOpt != null && isEffectivelyEnabled(cacheOpt)) {
            markActionTaken(280L)
            val ok = safeClick(cacheOpt)
            CleanDiagLogger.log(this, "click_miui_clear_cache ok=$ok")
            if (ok) {
                clickSource = ClickSource.MIUI_BOTTOM_MENU
                confirmClickRetries = 0
                cleanStep = CleanStep.CACHE_CONFIRM
                scheduleTimeout(3600L)
                scheduleConfirmFallback(1350L)
                schedulePoll(320L)
            } else {
                schedulePoll(260L)
            }
            return true
        }
        return false
    }

    private fun tryDismissDangerOnlyMenu(root: AccessibilityNodeInfo): Boolean {
        val txt = windowText(root)
        if (CleanDecisionRules.isDangerOnlyMenu(txt, rom.clearCacheLabels)) {
            markActionTaken(300L)
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (!triedStoragePageForCurrent) {
                CleanDiagLogger.log(this, "danger_only_menu -> back & fallback to storage_entry")
                scheduleTransition(280L) {
                    cleanStep = CleanStep.APP_INFO
                    schedulePoll(220L)
                }
            } else {
                CleanDiagLogger.log(this, "danger_only_menu after storage tried -> ALREADY_ZERO")
                currentOutcome = AppCleanOutcome.ALREADY_ZERO
                scheduleTransition(260L) { afterCacheStep() }
            }
            return true
        }
        return false
    }

    private fun onCleanStoragePage(root: AccessibilityNodeInfo, winPkg: String): Boolean {
        val winTxt = windowText(root)
        val cacheBtn = findClearCacheNode(root)
        if (cacheBtn != null) {
            markActionTaken()
            if (isEffectivelyEnabled(cacheBtn)) {
                preClickSizeTokens = CleanDecisionRules.extractSizeTokens(winTxt)
                val ok = safeClick(cacheBtn)
                CleanDiagLogger.log(this, "click_storage_clear_cache ok=$ok")
                if (ok) {
                    clickSource = ClickSource.DIRECT_OR_STORAGE
                    confirmClickRetries = 0
                    cleanStep = CleanStep.CACHE_CONFIRM
                    scheduleConfirmFallback(1100L)
                    schedulePoll(240L)
                } else {
                    schedulePoll(260L)
                }
            } else {
                CleanDiagLogger.log(this, "storage_cache_disabled -> ALREADY_ZERO")
                currentOutcome = AppCleanOutcome.ALREADY_ZERO
                afterCacheStep()
            }
            return true
        }

        if (rom == RomFamily.XIAOMI && winPkg == "com.miui.securitycenter" && !triedMiuiMenuForCurrent) {
            if (!winTxt.contains("清除全部数据")) {
                val menuBtn = findNode(root, listOf("清除数据"), allowMiuiMenu = true, maxExtraChars = 2)
                if (menuBtn != null && isEffectivelyEnabled(menuBtn)) {
                    markActionTaken(240L)
                    triedMiuiMenuForCurrent = true
                    cleanStep = CleanStep.MIUI_MENU
                    val ok = safeClick(menuBtn)
                    CleanDiagLogger.log(this, "click_miui_menu_in_storage ok=$ok")
                    scheduleTimeout(3800L)
                    schedulePoll(320L)
                    return true
                }
            }
        }

        if (scrollAttemptsStorage < 1) {
            if (scrollForward(root)) {
                scrollAttemptsStorage++
                markActionTaken(240L)
                schedulePoll(360L)
                return true
            }
        }
        return false
    }

    private fun onCleanConfirmDialog(root: AccessibilityNodeInfo): Boolean {
        val txt = windowText(root)
        val decision = CleanDecisionRules.evaluateCacheConfirmWindow(txt)
        return when (decision) {
            DialogDecision.NOT_A_DIALOG -> {
                val cacheBtn = findClearCacheNode(root)
                if (clickSource == ClickSource.DIRECT_OR_STORAGE && cacheBtn != null && !isEffectivelyEnabled(cacheBtn)) {
                    confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
                    currentOutcome = AppCleanOutcome.CLEANED
                    CleanDiagLogger.log(this, "fast_path button_disabled -> CLEANED")
                    scheduleTransition(120L) { afterCacheStep() }
                    return true
                }
                if (clickSource == ClickSource.DIRECT_OR_STORAGE && preClickSizeTokens.isNotEmpty()) {
                    val nowTokens = CleanDecisionRules.extractSizeTokens(txt)
                    if (nowTokens.isNotEmpty() && nowTokens != preClickSizeTokens) {
                        confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
                        currentOutcome = AppCleanOutcome.CLEANED
                        CleanDiagLogger.log(this, "fast_path size_changed $preClickSizeTokens->$nowTokens -> CLEANED")
                        scheduleTransition(140L) { afterCacheStep() }
                        return true
                    }
                }
                false
            }
            DialogDecision.WAIT_TRANSITION -> false
            DialogDecision.ABORT_DANGEROUS -> {
                CleanDiagLogger.log(this, "dialog ABORT_DANGEROUS -> back & FAILED")
                markActionTaken(300L)
                currentOutcome = AppCleanOutcome.FAILED
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleTransition(240L) { advanceApp(AppCleanOutcome.FAILED) }
                true
            }
            DialogDecision.CONFIRM_CACHE -> {
                val positiveBtn = findDialogPositiveButton(root, EXACT_CONFIRM_OK)
                if (positiveBtn != null && isEffectivelyEnabled(positiveBtn)) {
                    confirmFallbackRunnable?.let { handler.removeCallbacks(it) }
                    markActionTaken(320L)
                    val clicked = safeClick(positiveBtn)
                    CleanDiagLogger.log(
                        this,
                        "dialog CONFIRM_CACHE btn='${positiveBtn.text}' ok=$clicked retry=$confirmClickRetries"
                    )
                    if (clicked) {
                        currentOutcome = AppCleanOutcome.CLEANED
                        scheduleTransition(240L) { afterCacheStep() }
                    } else {
                        confirmClickRetries++
                        if (confirmClickRetries <= 2) {
                            schedulePoll(240L)
                            scheduleConfirmFallback(900L)
                        } else {
                            currentOutcome = AppCleanOutcome.FAILED
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            scheduleTransition(240L) { advanceApp(AppCleanOutcome.FAILED) }
                        }
                    }
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
            advanceApp(currentOutcome)
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
            schedulePoll(340L)
        } else {
            cleanStep = CleanStep.FORCE_STOP
            scheduleTimeout(3200L)
            schedulePoll(320L)
        }
    }

    private fun onCleanForceStop(root: AccessibilityNodeInfo): Boolean {
        val txt = windowText(root)
        if (CleanDecisionRules.isForceStopConfirmWindow(txt)) {
            val ok = findDialogPositiveButton(root, EXACT_CONFIRM_STOP)
            if (ok != null && isEffectivelyEnabled(ok)) {
                markActionTaken(340L)
                val clicked = safeClick(ok)
                CleanDiagLogger.log(this, "click_force_stop_confirm ok=$clicked")
                scheduleTransition(260L) { advanceApp(currentOutcome) }
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
                val clicked = safeClick(stopBtn)
                CleanDiagLogger.log(this, "click_force_stop ok=$clicked")
                scheduleTimeout(2800L)
                schedulePoll(320L)
            } else {
                advanceApp(currentOutcome)
            }
            return true
        }
        return false
    }

    private fun advanceApp(outcome: AppCleanOutcome) {
        clearTimers()
        if (!GuardStore.sessionActive) {
            hideProgressOverlay()
            cleanStep = CleanStep.IDLE
            return
        }
        val availNow = SystemScanner.getCurrentAvailBytes()
        val next = GuardStore.advanceCleanQueue(this, outcome, availNow)
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
        hideProgressOverlay()

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
