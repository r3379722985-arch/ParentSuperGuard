package com.custom.parentguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IroncladWhitelistTest {

    private val selfPkg = "com.custom.parentguard"

    @Test
    fun strictProtectionCoversWeChatBanksGalleriesAndAntiFraud() {
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.tencent.mm", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.tencent.wework", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.alibaba.android.rimet", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.eg.android.AlipayGphone", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("cn.gov.pbc.dcep", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.hicorenational.antifraud", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.icbc", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.local.rural.ebank", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.prov.rcb.app", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.miui.gallery", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.vivo.gallery", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.sohu.inputmethod.sogou", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.custom.ime.pinyin", selfPkg))
    }

    @Test
    fun notificationCleanerProtectsLifeServicesAndSurfacesAdSpamApps() {
        // 金融/反诈/通讯
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.tencent.mm", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.icbc", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.hicorenational.antifraud", selfPkg))
        // P2-3 新增保护：外卖、快递、导航、医院挂号
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.sankuai.meituan", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("me.ele", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.cainiao.wireless", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.sf.activity", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.jingdong.app.mall", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.autonavi.minimap", selfPkg))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.city.hospital.guahao", selfPkg))
        // 广告推送大户仍允许关闭通知
        assertFalse(IroncladWhitelist.shouldKeepNotifications("com.xunmeng.pinduoduo", selfPkg))
        assertFalse(IroncladWhitelist.shouldKeepNotifications("com.ss.android.article.news", selfPkg))
        assertFalse("com.jingdong.app.mall" in IroncladWhitelist.HIGH_SPAM_NOTIFICATION_PKGS)
    }

    @Test
    fun shortTokensDoNotFalseMatchUnrelatedPackages() {
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.goodtime.cleaner", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.primetime.video", selfPkg))
    }

    @Test
    fun trustedRegularAppsAllowSafeCacheCleaning() {
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.ss.android.ugc.aweme", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.xunmeng.pinduoduo", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.taobao.taobao", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.xingin.xhs", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.ct.client", selfPkg))
        assertTrue(IroncladWhitelist.isTrustedFamilyApp("com.ss.android.ugc.aweme", selfPkg))
        assertTrue(IroncladWhitelist.isTrustedFamilyApp("com.ct.client", selfPkg))
    }

    @Test
    fun customUserProtectionWorks() {
        val custom = setOf("com.hospital.reg.app")
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.hospital.reg.app", selfPkg, custom))
        assertTrue(IroncladWhitelist.isTrustedFamilyApp("com.hospital.reg.app", selfPkg, custom))
        assertTrue(IroncladWhitelist.shouldKeepNotifications("com.hospital.reg.app", selfPkg, custom))
    }

    @Test
    fun targetAppWindowVerificationPreventsStalePageClicks() {
        assertTrue(
            CleanDecisionRules.matchesTargetAppWindow(
                "应用信息 抖音 版本 31.2.0 存储占用 1.2 GB 结束运行 卸载 清除数据",
                "抖音",
                "com.ss.android.ugc.aweme"
            )
        )
        assertFalse(
            CleanDecisionRules.matchesTargetAppWindow(
                "应用信息 微信 版本 8.0.50 存储占用 12 GB 结束运行 清除数据",
                "今日头条",
                "com.ss.android.article.news"
            )
        )
    }

    @Test
    fun dangerOnlyMenuNeverMisclassifiesBackgroundAppInfoPage() {
        val clearLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache")
        assertFalse(
            CleanDecisionRules.isDangerOnlyMenu(
                "应用信息 抖音 存储占用 1.2 GB 结束运行 卸载 清除数据",
                clearLabels
            )
        )
        assertTrue(CleanDecisionRules.isDangerOnlyMenu("清除全部数据 取消", clearLabels))
        assertFalse(CleanDecisionRules.isDangerOnlyMenu("清除全部数据 清除缓存 取消", clearLabels))
    }

    @Test
    fun installerBlockerUsesBuiltinRiskKeywordsAndAllowsUninstall() {
        val riskWords = IroncladWhitelist.ROGUE_NAME_HINTS
        // 卸载流氓软件无条件放行
        assertFalse(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "要卸载「极速清理大师」吗？ 取消 卸载",
                riskWords
            )
        )
        // 恢复回归测试：即便调用方传入空词表，内置风险词仍拦截高风险未审核安装弹窗！
        assertTrue(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "安全风险提示：该应用未经官方审核 取消 继续安装",
                emptyList()
            )
        )
        assertTrue(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "是否安装「极速清理大师」？来自未知来源 取消 安装",
                riskWords
            )
        )
        assertFalse(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "是否安装更新「高德地图」？ 取消 安装",
                riskWords
            )
        )
    }

    @Test
    fun dialogSafetyRulesWorkAccurately() {
        assertEquals(
            DialogDecision.NOT_A_DIALOG,
            CleanDecisionRules.evaluateCacheConfirmWindow("抖音 存储 256MB 结束运行 卸载 清除数据")
        )
        assertEquals(
            DialogDecision.WAIT_TRANSITION,
            CleanDecisionRules.evaluateCacheConfirmWindow("清除全部数据 清除缓存 取消")
        )
        assertEquals(
            DialogDecision.ABORT_DANGEROUS,
            CleanDecisionRules.evaluateCacheConfirmWindow("清除全部数据？所有文件、设置和账号都将永久删除 取消 确定")
        )
        assertEquals(
            DialogDecision.CONFIRM_CACHE,
            CleanDecisionRules.evaluateCacheConfirmWindow("清除缓存？ 取消 确定")
        )
    }

    @Test
    fun pbkdf2PinHashingLegacyUpgradeAndRecoveryCodeWork() {
        assertTrue(CleanDecisionRules.isValidPinFormat("2580"))
        assertFalse(CleanDecisionRules.isValidPinFormat("123"))
        assertFalse(CleanDecisionRules.isValidPinFormat("12ab"))

        // P2-2: 同一密码两次哈希因随机盐必须不同，但 verifyPin 均通过
        val h1 = CleanDecisionRules.hashPin("2580")
        val h2 = CleanDecisionRules.hashPin("2580")
        assertTrue(h1.startsWith("pbkdf2_sha256:100000:"))
        assertNotEquals(h1, h2)
        assertTrue(CleanDecisionRules.verifyPin("2580", h1))
        assertTrue(CleanDecisionRules.verifyPin("2580", h2))
        assertFalse(CleanDecisionRules.verifyPin("0852", h1))
        assertFalse(CleanDecisionRules.isLegacyPinHash(h1))

        // 兼容旧版 v6.1 单次 SHA-256 哈希
        val legacy = CleanDecisionRules.legacySha256Hash("2580")
        assertTrue(CleanDecisionRules.isLegacyPinHash(legacy))
        assertTrue(CleanDecisionRules.verifyPin("2580", legacy))
        assertFalse(CleanDecisionRules.verifyPin("9999", legacy))

        // P1-1: 8 位恢复码生成与校验（忽略大小写与短横线）
        val code = CleanDecisionRules.generateRecoveryCode()
        assertEquals(9, code.length)
        assertEquals('-', code[4])
        val recHash = CleanDecisionRules.hashRecoveryCode(code)
        assertTrue(CleanDecisionRules.verifyRecoveryCode(code, recHash))
        assertTrue(CleanDecisionRules.verifyRecoveryCode(code.lowercase().replace("-", " "), recHash))
        assertFalse(CleanDecisionRules.verifyRecoveryCode("AAAA-BBBB", recHash))
    }

    @Test
    fun pinLockoutBackoffScheduleIsAccurate() {
        assertEquals(0L, CleanDecisionRules.lockoutDurationMsForFailures(0))
        assertEquals(0L, CleanDecisionRules.lockoutDurationMsForFailures(2))
        assertEquals(30_000L, CleanDecisionRules.lockoutDurationMsForFailures(3))
        assertEquals(30_000L, CleanDecisionRules.lockoutDurationMsForFailures(5))
        assertEquals(300_000L, CleanDecisionRules.lockoutDurationMsForFailures(6))
        assertEquals(300_000L, CleanDecisionRules.lockoutDurationMsForFailures(9))
        assertEquals(1_800_000L, CleanDecisionRules.lockoutDurationMsForFailures(10))
        assertEquals(1_800_000L, CleanDecisionRules.lockoutDurationMsForFailures(15))
    }

    @Test
    fun permissionWizardPlansSequentialStepsProperly() {
        val s0 = PermissionSnapshot(
            hasUsageStats = false,
            isAndroid13Plus = true,
            restrictedConfirmed = false,
            cleanServiceRunning = false,
            cleanServiceZombie = false,
            hasExternalFiles = false,
            rogueServiceRunning = false
        )
        assertEquals(PermissionActionStep.USAGE_STATS, PermissionStepPlanner.nextRecommendedStep(s0))
        assertTrue(PermissionStepPlanner.shouldAutoOpenCenterOnLaunch(false, false))
        assertEquals(PermissionActionStep.RESTRICTED_SETTINGS, PermissionStepPlanner.nextRecommendedStep(s0.copy(hasUsageStats = true)))
        assertEquals(PermissionActionStep.CLEAN_ACCESSIBILITY, PermissionStepPlanner.nextRecommendedStep(s0.copy(hasUsageStats = true, restrictedConfirmed = true)))
        assertEquals(PermissionActionStep.EXTERNAL_FILES, PermissionStepPlanner.nextRecommendedStep(s0.copy(hasUsageStats = true, restrictedConfirmed = true, cleanServiceRunning = true)))
        assertEquals(PermissionActionStep.ALL_DONE, PermissionStepPlanner.nextRecommendedStep(s0.copy(hasUsageStats = true, restrictedConfirmed = true, cleanServiceRunning = true, hasExternalFiles = true)))
        assertFalse(PermissionStepPlanner.shouldAutoOpenCenterOnLaunch(true, true))
    }

    @Test
    fun recentlyCleanedFilterSkipsAlreadyCleanedAppsAfterMidwayCancel() {
        val apps = listOf("com.dragon.read", "com.ss.android.ugc.aweme", "com.xunmeng.pinduoduo")
        val now = 1_000_000L
        val ttl = 10L * 60_000L
        val cleaned = mapOf(
            "com.dragon.read" to (now - 60_000L),          // 1 分钟前刚清完 -> 必须跳过
            "com.ss.android.ugc.aweme" to (now - 700_000L) // 11 分钟前清的 -> 已过冷却，可重新清
        )
        val remaining = CleanDecisionRules.filterRecentlyCleaned(apps, cleaned, now, ttl) { it }
        assertEquals(listOf("com.ss.android.ugc.aweme", "com.xunmeng.pinduoduo"), remaining)
    }

    @Test
    fun userInterruptionDetectsHomeAndOtherAppsWithoutFalseTriggeringSettings() {
        val allowed = setOf("com.android.settings", "com.miui.securitycenter", "com.oplus.appdetail")
        assertFalse(CleanDecisionRules.isUserInterruptedForeground("com.android.settings", allowed))
        assertFalse(CleanDecisionRules.isUserInterruptedForeground("com.oplus.appdetail", allowed))
        assertFalse(CleanDecisionRules.isUserInterruptedForeground("com.android.systemui", allowed))
        assertFalse(CleanDecisionRules.isUserInterruptedForeground(null, allowed))
        // 按 Home 回到桌面或切去微信 -> 判定为用户主动中断！
        assertTrue(CleanDecisionRules.isUserInterruptedForeground("com.oppo.launcher", allowed))
        assertTrue(CleanDecisionRules.isUserInterruptedForeground("com.miui.home", allowed))
        assertTrue(CleanDecisionRules.isUserInterruptedForeground("com.tencent.mm", allowed))
    }

    @Test
    fun watchdogRequiresTwoConsecutiveNonAllowedHits() {
        val allowed = setOf("com.android.settings", "com.miui.securitycenter")
        var hits = 0
        hits = CleanDecisionRules.nextInterruptHits(hits, "com.miui.home", allowed)
        assertEquals(1, hits)
        hits = CleanDecisionRules.nextInterruptHits(hits, null, allowed)
        assertEquals(0, hits)
        hits = CleanDecisionRules.nextInterruptHits(hits, "com.miui.home", allowed)
        hits = CleanDecisionRules.nextInterruptHits(hits, "com.miui.home", allowed)
        assertEquals(2, hits)
        hits = CleanDecisionRules.nextInterruptHits(hits, "com.miui.securitycenter", allowed)
        assertEquals(0, hits)
    }

    @Test
    fun benignAccountNoticeInCacheDialogIsNotFalseAborted() {
        assertEquals(
            DialogDecision.CONFIRM_CACHE,
            CleanDecisionRules.evaluateCacheConfirmWindow("确定清除应用缓存？清除缓存不会影响您的账号登录 取消 确定")
        )
        assertEquals(
            DialogDecision.ABORT_DANGEROUS,
            CleanDecisionRules.evaluateCacheConfirmWindow("确定删除账号及所有本地记录？ 取消 确定")
        )
    }

    @Test
    fun sizeTokenExtractorDetectsStorageChangeAndIgnoresMobileTraffic() {
        val before = CleanDecisionRules.extractSizeTokens("应用信息 拼多多 存储占用 607MB 流量消耗 881.3MB 数据流量 1.5GB 缓存 120.5 MB")
        val after = CleanDecisionRules.extractSizeTokens("应用信息 拼多多 存储占用 486.5MB 流量消耗 882.0MB 数据流量 1.6GB 缓存 0 B")
        assertTrue("607mb" in before)
        assertTrue("120.5mb" in before)
        assertFalse("881.3mb" in before)
        assertFalse("1.5gb" in before)
        assertNotEquals(before, after)
        val onlyTrafficChanged = CleanDecisionRules.extractSizeTokens("应用信息 拼多多 存储占用 607MB 流量消耗 999.9MB 缓存 120.5 MB")
        assertEquals(before, onlyTrafficChanged)
    }
}
