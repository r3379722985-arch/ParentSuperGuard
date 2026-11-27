package com.custom.parentguard

import android.os.Build

enum class RomFamily(
    val displayName: String,
    val accessibilityPathHint: String,
    val storageEntryLabels: List<String>,
    val clearCacheLabels: List<String>,
    val forceStopLabels: List<String>
) {
    XIAOMI(
        displayName = "小米 / 红米 (MIUI / 澎湃OS)",
        accessibilityPathHint = "设置 → 更多设置 → 无障碍 → 已下载的应用 → 开启「安心卫士 · 自动清缓存」",
        storageEntryLabels = listOf("存储", "存储占用", "存储空间", "存储和缓存", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("结束运行", "强行停止", "强制停止", "force stop")
    ),
    VIVO(
        displayName = "vivo / iQOO (OriginOS / FuntouchOS)",
        accessibilityPathHint = "设置 → 快捷与辅助 → 无障碍 → 已下载的服务 → 开启「安心卫士 · 自动清缓存」",
        storageEntryLabels = listOf("内存储存", "存储", "存储与缓存", "存储和缓存", "存储占用", "存储空间", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "强制停止", "结束运行", "force stop")
    ),
    OPPO(
        displayName = "OPPO / 一加 / 真我 (ColorOS)",
        accessibilityPathHint = "设置 → 其他设置/系统设置 → 无障碍 → 已下载的应用 → 开启「安心卫士 · 自动清缓存」",
        storageEntryLabels = listOf("存储占用", "存储空间", "存储和缓存", "存储", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "结束运行", "强制停止", "force stop")
    ),
    HUAWEI_HONOR(
        displayName = "华为 / 荣耀 (HarmonyOS / EMUI / MagicOS)",
        accessibilityPathHint = "设置 → 辅助功能 → 无障碍 → 已安装的服务 → 开启「安心卫士 · 自动清缓存」",
        storageEntryLabels = listOf("存储", "存储空间", "存储和缓存", "storage"),
        clearCacheLabels = listOf("清空缓存", "清除缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "强制停止", "结束运行", "force stop")
    ),
    SAMSUNG(
        displayName = "三星 (One UI)",
        accessibilityPathHint = "设置 → 辅助功能 → 已安装的应用程序 → 开启「安心卫士 · 自动清缓存」",
        storageEntryLabels = listOf("存储", "存储空间", "存储和缓存", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "clear cache"),
        forceStopLabels = listOf("强制停止", "强行停止", "force stop")
    ),
    GENERIC(
        displayName = "通用安卓品牌",
        accessibilityPathHint = "设置 → 无障碍 → 已下载的应用 → 开启「安心卫士 · 自动清缓存」",
        storageEntryLabels = listOf(
            "存储和缓存", "存储空间和缓存", "存储与缓存", "存储占用",
            "内存储存", "存储空间", "存储", "占用空间",
            "storage & cache", "storage and cache", "storage"
        ),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "强制停止", "结束运行", "force stop")
    )
}

object BrandDetector {
    fun detect(): RomFamily {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        val combined = "$m $b"
        return when {
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") || combined.contains("blackshark") ->
                RomFamily.XIAOMI
            combined.contains("vivo") || combined.contains("iqoo") ->
                RomFamily.VIVO
            combined.contains("oppo") || combined.contains("oneplus") || combined.contains("realme") ->
                RomFamily.OPPO
            combined.contains("huawei") || combined.contains("honor") ->
                RomFamily.HUAWEI_HONOR
            combined.contains("samsung") ->
                RomFamily.SAMSUNG
            else -> RomFamily.GENERIC
        }
    }
}
