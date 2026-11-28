package com.custom.parentguard

import android.os.Build

enum class RomFamily(
    val displayName: String,
    val accessibilityPathHint: String,
    val lockDesktopHint: String,
    val shakeSensorHint: String,
    val storageEntryLabels: List<String>,
    val clearCacheLabels: List<String>,
    val forceStopLabels: List<String>
) {
    XIAOMI(
        displayName = "小米 / 红米 (MIUI / 澎湃OS)",
        accessibilityPathHint = "设置 → 更多设置 → 无障碍 → 已下载的应用 → 开启「安心卫士 · 自动清缓存」",
        lockDesktopHint = "双指捏合桌面 → 桌面设置 → 打开「锁定桌面布局」",
        shakeSensorHint = "应用信息 → 权限管理 → 关闭「获取设备动作与方向」",
        storageEntryLabels = listOf("存储", "存储占用", "存储空间", "存储和缓存", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("结束运行", "强行停止", "强制停止", "force stop")
    ),
    VIVO(
        displayName = "vivo / iQOO (OriginOS / FuntouchOS)",
        accessibilityPathHint = "设置 → 快捷与辅助 → 无障碍 → 已下载的服务 → 开启「安心卫士 · 自动清缓存」",
        lockDesktopHint = "设置 → 桌面、锁屏与壁纸 → 桌面设置 → 打开「锁定布局」",
        shakeSensorHint = "应用信息 → 权限 → 关闭「传感器 / 摇一摇动作」权限",
        storageEntryLabels = listOf("内存储存", "存储", "存储与缓存", "存储和缓存", "存储占用", "存储空间", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "强制停止", "结束运行", "force stop")
    ),
    OPPO(
        displayName = "OPPO / 一加 / 真我 (ColorOS)",
        accessibilityPathHint = "设置 → 其他设置/系统设置 → 无障碍 → 已下载的应用 → 开启「安心卫士 · 自动清缓存」",
        lockDesktopHint = "设置 → 桌面与锁屏 → 打开「锁定桌面布局」",
        shakeSensorHint = "应用信息 → 权限管理 → 关闭「设备动作与方向」",
        storageEntryLabels = listOf("存储占用", "存储空间", "存储和缓存", "存储", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "结束运行", "强制停止", "force stop")
    ),
    HUAWEI_HONOR(
        displayName = "华为 / 荣耀 (HarmonyOS / EMUI / MagicOS)",
        accessibilityPathHint = "设置 → 辅助功能 → 无障碍 → 已安装的服务 → 开启「安心卫士 · 自动清缓存」",
        lockDesktopHint = "双指捏合桌面 → 桌面设置 → 打开「锁定布局」",
        shakeSensorHint = "应用信息 → 权限 → 关闭「获取应用内陀螺仪/动作」",
        storageEntryLabels = listOf("存储", "存储空间", "存储和缓存", "storage"),
        clearCacheLabels = listOf("清空缓存", "清除缓存", "清理缓存", "clear cache"),
        forceStopLabels = listOf("强行停止", "强制停止", "结束运行", "force stop")
    ),
    SAMSUNG(
        displayName = "三星 (One UI)",
        accessibilityPathHint = "设置 → 辅助功能 → 已安装的应用程序 → 开启「安心卫士 · 自动清缓存」",
        lockDesktopHint = "设置 → 主屏幕 → 打开「锁定主屏幕布局」",
        shakeSensorHint = "应用信息 → 权限 → 关闭不必要的传感器权限",
        storageEntryLabels = listOf("存储", "存储空间", "存储和缓存", "storage"),
        clearCacheLabels = listOf("清除缓存", "清空缓存", "clear cache"),
        forceStopLabels = listOf("强制停止", "强行停止", "force stop")
    ),
    GENERIC(
        displayName = "通用安卓品牌",
        accessibilityPathHint = "设置 → 无障碍 → 已下载的应用 → 开启「安心卫士 · 自动清缓存」",
        lockDesktopHint = "长按桌面空白处 → 桌面设置 → 打开「锁定桌面布局」",
        shakeSensorHint = "应用信息 → 权限管理 → 关闭设备动作与传感器权限",
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
