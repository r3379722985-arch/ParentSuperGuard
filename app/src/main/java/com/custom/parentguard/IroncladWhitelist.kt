package com.custom.parentguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 双层白名单 + 生活通知保护名单：
 * - 第一层（绝对禁区）：微信、QQ、企业微信、钉钉、支付宝、云闪付、数字人民币、国家反诈中心、全部银行与农商行、医保、政务、相册、闹钟、输入法、以及用户手动加锁的软件。
 * - 生活通知额外保护（P2-3 修复）：外卖（美团/饿了么）、快递（菜鸟/顺丰/快递100/京东）、导航打车（滴滴/高德/百度地图）及医院挂号类不建议关通知，防止漏接外卖电话、取件码与叫号提醒。
 * - 高发广告推送大户：拼多多、头条、短视频、浏览器等，在「通知栏大扫除」默认展示供一键静音。
 */
object IroncladWhitelist {

    val STRICT_NEVER_TOUCH_PACKAGES = setOf(
        "com.tencent.mm",                  // 微信
        "com.tencent.wework",              // 企业微信
        "com.tencent.mobileqq",            // QQ
        "com.alibaba.android.rimet",       // 钉钉
        "com.eg.android.AlipayGphone",     // 支付宝
        "com.unionpay",                    // 云闪付
        "cn.gov.pbc.dcep",                 // 数字人民币
        "com.hicorenational.antifraud",    // 国家反诈中心

        // 中国主流银行
        "com.icbc",
        "com.android.bankabc",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "com.bankcomm.Bankcomm",
        "com.yitong.mbank.psbc",
        "cmb.pb",
        "com.pingan.paces.ccms",
        "com.ecitic.bank.mobile",
        "cn.com.spdb.mobilebank.per",
        "com.cib.cibmb",
        "cn.com.cmbc.newmbank",
        "com.cebbank.mobile.cemb",
        "com.cgbchina.xpt",
        "com.hxb.mobile.client",
        "com.rytong.bankps",
        "com.bosc.mobilebank",

        // 政务、医保、出行刚需
        "cn.hsa.app",
        "com.tmri.app.main",
        "com.MobileTicket",
        "cn.gov.tax.its",

        // 常见输入法与各品牌相册、闹钟
        "com.sohu.inputmethod.sogou",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.tencent.wetype",
        "com.miui.gallery",
        "com.vivo.gallery",
        "com.coloros.gallery3d",
        "com.oneplus.gallery",
        "com.huawei.photos",
        "com.hihonor.photos",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.google.android.apps.photos",
        "com.android.deskclock",
        "com.miui.clock",
        "com.android.BBKClock"
    )

    private val STRICT_SUBSTRING_KEYWORDS = listOf(
        "bank", "ebank", "mbank", "abchina", "unionpay", "alipay", "wallet",
        "gallery", "photos", "camera", "contacts", "dialer", "alarm",
        "inputmethod", "pinyin", "sogou", "baidu_input", "iflytek"
    )

    private val STRICT_SEGMENT_TOKENS = setOf(
        "ccb", "icbc", "boc", "psbc", "cmb", "rcb", "nxbank", "rural",
        "pay", "mms", "clock", "ime", "wetype"
    )

    // P2-3 修复：生活刚需通知保护（外卖、快递物流、出行导航、常见医疗挂号）
    val KEEP_NOTIFICATION_EXTRA = setOf(
        "com.sdu.didi.psnger",             // 滴滴出行
        "com.autonavi.minimap",            // 高德地图
        "com.baidu.BaiduMap",              // 百度地图
        "com.sankuai.meituan",             // 美团
        "com.sankuai.meituan.takeoutnew",  // 美团外卖
        "me.ele",                          // 饿了么
        "com.cainiao.wireless",            // 菜鸟驿站
        "com.sf.activity",                 // 顺丰速运
        "com.Kingdee.Express",             // 快递100
        "com.jingdong.app.mall",           // 京东（保留快递配送通知）
        "com.haodf.android",               // 好大夫在线
        "com.greenline.guahao",            // 微医挂号
        "com.alihealth.app",               // 阿里健康
        "com.jd.jdh.patient"               // 京东健康
    )

    // 医疗挂号与快递物流包名关键词兜底，防止地方医院/快递 App 被误标建议关通知
    private val KEEP_NOTIFICATION_KEYWORDS = listOf(
        "hospital", "clinic", "guahao", "medical", "medicare", "yibao",
        "cainiao", "kuaidi", "express", "delivery", "takeout"
    )

    // 高发广告推送大户（京东已移出，避免漏掉物流派件通知）
    val HIGH_SPAM_NOTIFICATION_PKGS = setOf(
        "com.xunmeng.pinduoduo",
        "com.ss.android.article.news",
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.baidu.searchbox",
        "com.UCMobile",
        "com.quark.browser",
        "com.taobao.taobao",
        "com.ss.android.article.video",
        "com.qiyi.video",
        "com.tencent.qqlive",
        "com.youku.phone"
    )

    val TRUSTED_REGULAR_APPS = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.taobao.taobao",
        "com.xunmeng.pinduoduo",
        "com.jingdong.app.mall",
        "com.xingin.xhs",
        "tv.danmaku.bili",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.ss.android.article.news",
        "com.ss.android.article.video",
        "com.sankuai.meituan",
        "com.sankuai.meituan.takeoutnew",
        "me.ele",
        "com.cainiao.wireless",
        "com.sf.activity",
        "com.tencent.qqlive",
        "com.qiyi.video",
        "com.youku.phone",
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "com.ximalaya.ting.android",
        "com.baidu.searchbox",
        "com.quark.browser",
        "com.UCMobile",
        "cn.xuexi.android",
        "com.sdu.didi.psnger",
        "com.greenpoint.android.mc10086.activity",
        "com.sinovatech.unicom.ui",
        "com.ct.client",
        "com.dragon.read",
        "com.kmxs.reader"
    )

    val ROGUE_NAME_HINTS = listOf(
        "极速清理", "强力清理", "一键清理", "手机清理大师", "内存清理",
        "手机加速", "内存优化", "省电大师", "领现金", "领红包",
        "看视频赚钱", "走路赚钱", "免费看剧赚钱", "极速版福利"
    )

    fun isStrictlyProtectedFromClean(
        packageName: String,
        selfPackage: String,
        customProtected: Set<String> = emptySet()
    ): Boolean {
        if (packageName == selfPackage) return true
        if (packageName in STRICT_NEVER_TOUCH_PACKAGES) return true
        if (packageName in customProtected) return true
        val lower = packageName.lowercase()
        if (STRICT_SUBSTRING_KEYWORDS.any { lower.contains(it) }) return true
        val segments = lower.split('.', '_', '-')
        return segments.any { it in STRICT_SEGMENT_TOKENS }
    }

    /** 是否属于重要生活/金融/医疗通知软件（绝不放进「关垃圾推送」建议列表） */
    fun shouldKeepNotifications(
        packageName: String,
        selfPackage: String,
        customProtected: Set<String> = emptySet()
    ): Boolean {
        if (isStrictlyProtectedFromClean(packageName, selfPackage, customProtected)) return true
        if (packageName in KEEP_NOTIFICATION_EXTRA) return true
        val lower = packageName.lowercase()
        return KEEP_NOTIFICATION_KEYWORDS.any { lower.contains(it) }
    }

    fun isTrustedFamilyApp(
        packageName: String,
        selfPackage: String,
        customProtected: Set<String> = emptySet()
    ): Boolean {
        if (isStrictlyProtectedFromClean(packageName, selfPackage, customProtected)) return true
        return packageName in TRUSTED_REGULAR_APPS
    }

    fun isSuspectRogueLabel(label: String): Boolean {
        return ROGUE_NAME_HINTS.any { label.contains(it, ignoreCase = true) }
    }

    fun isSuspectRogue(
        context: Context,
        packageName: String,
        selfPackage: String,
        customProtected: Set<String> = emptySet()
    ): Boolean {
        if (isTrustedFamilyApp(packageName, selfPackage, customProtected)) return false
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return false
            val label = pm.getApplicationLabel(info).toString()
            isSuspectRogueLabel(label)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
