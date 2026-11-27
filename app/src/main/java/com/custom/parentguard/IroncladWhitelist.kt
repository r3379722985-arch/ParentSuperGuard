package com.custom.parentguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 双层白名单 + 用户自定义保护名单：
 * - 第一层（绝对禁区）：微信、QQ、企业微信、钉钉、支付宝、云闪付、数字人民币、国家反诈中心、全部银行与农商行、医保、政务、相册、闹钟、输入法、以及用户手动加锁的软件 -> 绝不清缓存、绝不强停、绝不拦截、绝不提供卸载。
 * - 第二层（正规常用大厂）：抖音、快手、淘宝、拼多多、京东、小红书、B站、地图、运营商营业厅、主流影音等 -> 绝不当流氓软件拦截或建议卸载，但【允许安全清理缓存】！
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
        "com.icbc",                        // 工商银行
        "com.android.bankabc",             // 农业银行
        "com.chinamworld.main",            // 建设银行
        "com.chinamworld.bocmbci",         // 中国银行
        "com.bankcomm.Bankcomm",           // 交通银行
        "com.yitong.mbank.psbc",           // 邮储银行
        "cmb.pb",                          // 招商银行
        "com.pingan.paces.ccms",           // 平安口袋银行
        "com.ecitic.bank.mobile",          // 中信银行
        "cn.com.spdb.mobilebank.per",      // 浦发银行
        "com.cib.cibmb",                   // 兴业银行
        "cn.com.cmbc.newmbank",            // 民生银行
        "com.cebbank.mobile.cemb",         // 光大银行
        "com.cgbchina.xpt",                // 广发银行
        "com.hxb.mobile.client",           // 华夏银行
        "com.rytong.bankps",               // 北京银行
        "com.bosc.mobilebank",             // 上海银行

        // 政务、医保、出行刚需
        "cn.hsa.app",                      // 国家医保服务平台
        "com.tmri.app.main",               // 交管12123
        "com.MobileTicket",                // 铁路12306
        "cn.gov.tax.its",                  // 个人所得税

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

    val TRUSTED_REGULAR_APPS = setOf(
        "com.ss.android.ugc.aweme",        // 抖音
        "com.ss.android.ugc.aweme.lite",   // 抖音极速版
        "com.smile.gifmaker",              // 快手
        "com.kuaishou.nebula",             // 快手极速版
        "com.taobao.taobao",               // 淘宝
        "com.xunmeng.pinduoduo",           // 拼多多
        "com.jingdong.app.mall",           // 京东
        "com.xingin.xhs",                  // 小红书
        "tv.danmaku.bili",                 // 哔哩哔哩
        "com.autonavi.minimap",            // 高德地图
        "com.baidu.BaiduMap",              // 百度地图
        "com.ss.android.article.news",     // 今日头条
        "com.ss.android.article.video",    // 西瓜视频
        "com.sankuai.meituan",             // 美团
        "com.tencent.qqlive",              // 腾讯视频
        "com.qiyi.video",                  // 爱奇艺
        "com.youku.phone",                 // 优酷
        "com.netease.cloudmusic",          // 网易云音乐
        "com.tencent.qqmusic",             // QQ音乐
        "com.kugou.android",               // 酷狗音乐
        "com.ximalaya.ting.android",       // 喜马拉雅
        "com.baidu.searchbox",             // 百度
        "com.quark.browser",               // 夸克
        "com.UCMobile",                    // UC浏览器
        "cn.xuexi.android",                // 学习强国
        "com.sdu.didi.psnger",             // 滴滴出行
        "com.greenpoint.android.mc10086.activity", // 中国移动
        "com.sinovatech.unicom.ui",        // 中国联通
        "com.ct.client"                    // 中国电信
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
