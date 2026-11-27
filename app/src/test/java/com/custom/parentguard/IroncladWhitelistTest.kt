package com.custom.parentguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(
            CleanDecisionRules.matchesTargetAppWindow(
                "App info com.xunmeng.pinduoduo Storage 840 MB Force stop",
                "拼多多",
                "com.xunmeng.pinduoduo"
            )
        )
        assertFalse(
            CleanDecisionRules.matchesTargetAppWindow(
                "应用信息 淘宝 版本 10.38.0 存储占用 520 MB 结束运行 卸载 清除数据",
                "拼多多",
                "com.xunmeng.pinduoduo"
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
        // 关键回归测试：底层「应用信息」页包含“卸载”但无“取消”，绝不能被误判成危险菜单按返回！
        assertFalse(
            CleanDecisionRules.isDangerOnlyMenu(
                "应用信息 抖音 存储占用 1.2 GB 结束运行 卸载 清除数据",
                clearLabels
            )
        )
        // 真正的小米 0B 缓存底部菜单（只有“清除全部数据”和“取消”）-> 立即识别关闭
        assertTrue(CleanDecisionRules.isDangerOnlyMenu("清除全部数据 取消", clearLabels))
        assertTrue(CleanDecisionRules.isDangerOnlyMenu("删除应用数据 取消", clearLabels))
        // 正常双选项菜单（有“清除缓存”）-> 不关闭
        assertFalse(CleanDecisionRules.isDangerOnlyMenu("清除全部数据 清除缓存 取消", clearLabels))
    }

    @Test
    fun installerBlockerNeverBlocksUninstallingRogueApps() {
        val riskWords = listOf("未知来源", "高风险", "报毒") + IroncladWhitelist.ROGUE_NAME_HINTS
        // 关键回归测试：卸载流氓软件时 packageinstaller 弹窗含“极速清理”+“卸载”，绝对放行！
        assertFalse(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "要卸载「极速清理大师」吗？ 取消 卸载",
                riskWords
            )
        )
        assertFalse(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "Do you want to uninstall 走路赚钱领红包? Cancel OK",
                riskWords
            )
        )
        // 真正安装流氓/未知来源软件 -> 拦截！
        assertTrue(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "是否安装「极速清理大师」？来自未知来源 取消 安装",
                riskWords
            )
        )
        assertTrue(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "安全风险提示：该应用未经官方审核 取消 继续安装",
                riskWords
            )
        )
        // 普通官方更新（无高风险词与马甲词）-> 放行！
        assertFalse(
            CleanDecisionRules.shouldBlockInstallerWindow(
                "是否安装更新「高德地图」？ 取消 安装",
                riskWords
            )
        )
    }

    @Test
    fun dialogSafetyRulesNeverMisreadAppInfoPageOrDangerousPrompt() {
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
    fun rogueHintsDetectedWithoutFalsePositives() {
        assertTrue(IroncladWhitelist.isSuspectRogueLabel("极速清理大师"))
        assertTrue(IroncladWhitelist.isSuspectRogueLabel("走路赚钱领红包"))
        assertFalse(IroncladWhitelist.isSuspectRogueLabel("微信"))
        assertFalse(IroncladWhitelist.isSuspectRogueLabel("高德地图"))
        assertFalse(IroncladWhitelist.isSuspectRogueLabel("桌面小组件"))
    }
}
