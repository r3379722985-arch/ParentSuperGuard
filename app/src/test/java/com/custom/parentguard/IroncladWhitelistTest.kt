package com.custom.parentguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IroncladWhitelistTest {

    private val selfPkg = "com.custom.parentguard"

    @Test
    fun strictProtectionCoversWeChatBanksGalleriesAndInputMethods() {
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.tencent.mm", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.tencent.wework", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.eg.android.AlipayGphone", selfPkg))
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("cn.gov.pbc.dcep", selfPkg))
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
        // 含 "ime" 子串的普通/流氓包名不应被误当成输入法锁进铁壁白名单
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.goodtime.cleaner", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.primetime.video", selfPkg))
    }

    @Test
    fun trustedRegularAppsAllowSafeCacheCleaning() {
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.ss.android.ugc.aweme", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.xunmeng.pinduoduo", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.taobao.taobao", selfPkg))
        assertFalse(IroncladWhitelist.isStrictlyProtectedFromClean("com.xingin.xhs", selfPkg))
        assertTrue(IroncladWhitelist.isTrustedFamilyApp("com.ss.android.ugc.aweme", selfPkg))
        assertTrue(IroncladWhitelist.isTrustedFamilyApp("com.xingin.xhs", selfPkg))
    }

    @Test
    fun customUserProtectionWorks() {
        val custom = setOf("com.hospital.reg.app")
        assertTrue(IroncladWhitelist.isStrictlyProtectedFromClean("com.hospital.reg.app", selfPkg, custom))
        assertTrue(IroncladWhitelist.isTrustedFamilyApp("com.hospital.reg.app", selfPkg, custom))
    }

    @Test
    fun dialogSafetyRulesNeverMisreadAppInfoPageOrDangerousPrompt() {
        // 1. 后台的应用信息页（含“卸载”但无“取消”）绝不能触发 ABORT
        assertEquals(
            DialogDecision.NOT_A_DIALOG,
            CleanDecisionRules.evaluateCacheConfirmWindow("抖音 存储 256MB 结束运行 卸载 清除数据")
        )
        // 2. 小米双选项底部菜单过渡期间等待，不误取消
        assertEquals(
            DialogDecision.WAIT_TRANSITION,
            CleanDecisionRules.evaluateCacheConfirmWindow("清除全部数据 清除缓存 取消")
        )
        // 3. 真正的危险弹窗（含全部数据 + 取消）必须立刻中止
        assertEquals(
            DialogDecision.ABORT_DANGEROUS,
            CleanDecisionRules.evaluateCacheConfirmWindow("清除全部数据？所有文件、设置和账号都将永久删除 取消 确定")
        )
        // 4. 真正的清缓存确认弹窗（含缓存 + 取消，无危险词）允许点击确定
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
