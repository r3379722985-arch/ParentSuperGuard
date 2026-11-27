package com.custom.parentguard

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SystemScanner {

    private val PROTECTED_TOP_DIRS = setOf(
        "android", "dcim", "pictures", "movies", "music",
        "download", "documents", "notifications", "ringtones",
        "alarms", "podcasts", "recordings", "audiobooks",
        "tencent", "weixin", "wechat", "miui", "huawei",
        "honor", "coloros", "vivo", "oppo", "samsung"
    )

    private val THUMB_DIRS = listOf(
        "DCIM/.thumbnails",
        "Pictures/.thumbnails",
        "Movies/.thumbnails"
    )

    private val SAFE_LOG_DIRS = listOf(
        "MIUI/debug_log",
        "BugReport",
        "ramdump",
        "diag_logs",
        "vivo_log"
    )

    fun getCurrentAvailBytes(): Long {
        return try {
            StatFs(Environment.getDataDirectory().path).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun hasUsageStats(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** 严格只读扫描，绝不在打开界面时删除任何文件，且彻底屏蔽所有系统组件进入卸载列表 */
    suspend fun scanSnapshot(context: Context): GuardSnapshot = withContext(Dispatchers.IO) {
        GuardStore.load(context)
        val customLocked = GuardStore.customProtected.value

        val (totalSpace, availSpace) = try {
            val s = StatFs(Environment.getDataDirectory().path)
            Pair(s.totalBytes, s.availableBytes)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }

        val hasUsage = hasUsageStats(context)
        val pm = context.packageManager
        val ssm = try {
            context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        } catch (_: Exception) {
            null
        }
        val userHandle = Process.myUserHandle()
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        val candidates = mutableListOf<AppCacheCandidate>()
        val audits = mutableListOf<InstalledAppAuditItem>()

        for (info in installed) {
            if (info.packageName == context.packageName) continue
            val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            if (isSys) {
                if (info.packageName in IroncladWhitelist.TRUSTED_REGULAR_APPS &&
                    !IroncladWhitelist.isStrictlyProtectedFromClean(info.packageName, context.packageName, customLocked)
                ) {
                    val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
                    var cacheBytes = 0L
                    if (hasUsage && ssm != null) {
                        try {
                            cacheBytes = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, userHandle)
                                .cacheBytes.coerceAtLeast(0L)
                        } catch (_: Exception) {
                        }
                    }
                    if (!hasUsage || cacheBytes >= 5L * 1024L * 1024L) {
                        candidates.add(AppCacheCandidate(info.packageName, label, cacheBytes))
                    }
                }
                continue
            }

            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            val builtInStrict = IroncladWhitelist.isStrictlyProtectedFromClean(info.packageName, context.packageName)
            val isCustomLocked = !builtInStrict && (info.packageName in customLocked)
            val strictProtected = builtInStrict || isCustomLocked
            val trustedRegular = !strictProtected && IroncladWhitelist.isTrustedFamilyApp(info.packageName, context.packageName, customLocked)
            val suspectRogue = !strictProtected && !trustedRegular && IroncladWhitelist.isSuspectRogueLabel(label)

            audits.add(
                InstalledAppAuditItem(
                    packageName = info.packageName,
                    appName = label,
                    isBuiltInStrict = builtInStrict,
                    isCustomLocked = isCustomLocked,
                    isTrustedRegular = trustedRegular,
                    isSuspectRogue = suspectRogue
                )
            )

            if (!strictProtected) {
                var cacheBytes = 0L
                if (hasUsage && ssm != null) {
                    try {
                        cacheBytes = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, userHandle)
                            .cacheBytes.coerceAtLeast(0L)
                    } catch (_: Exception) {
                    }
                }
                if (!hasUsage || cacheBytes >= 5L * 1024L * 1024L) {
                    candidates.add(AppCacheCandidate(info.packageName, label, cacheBytes))
                }
            }
        }

        val thumbFiles = mutableListOf<File>()
        var thumbBytes = 0L
        val logFiles = mutableListOf<File>()
        var logBytes = 0L
        val emptyDirs = mutableListOf<File>()

        try {
            val root = Environment.getExternalStorageDirectory()
            if (root.exists() && root.canRead()) {
                for (rel in THUMB_DIRS) {
                    val d = File(root, rel)
                    if (d.exists() && d.isDirectory) {
                        collectFiles(d, 2) { f ->
                            thumbFiles.add(f)
                            thumbBytes += f.length().coerceAtLeast(0L)
                        }
                    }
                }
                for (rel in SAFE_LOG_DIRS) {
                    val d = File(root, rel)
                    if (d.exists() && d.isDirectory) {
                        collectFiles(d, 4) { f ->
                            logFiles.add(f)
                            logBytes += f.length().coerceAtLeast(0L)
                        }
                    }
                }
                root.listFiles()?.forEach { entry ->
                    if (entry.isDirectory) {
                        val lower = entry.name.lowercase()
                        if (lower !in PROTECTED_TOP_DIRS && !lower.startsWith(".")) {
                            collectEmptyDirs(entry, 1, 3, emptyDirs)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val sortedCandidates = candidates.sortedByDescending { it.cacheBytes }
        GuardSnapshot(
            totalStorageBytes = totalSpace,
            availStorageBytes = availSpace,
            hasUsagePermission = hasUsage,
            cacheCandidates = sortedCandidates,
            totalAppCacheBytes = sortedCandidates.sumOf { it.cacheBytes },
            safeThumbFiles = thumbFiles,
            safeThumbBytes = thumbBytes,
            safeLogFiles = logFiles,
            safeLogBytes = logBytes,
            safeEmptyDirs = emptyDirs,
            auditedApps = audits.sortedWith(
                compareByDescending<InstalledAppAuditItem> { it.isSuspectRogue }
                    .thenByDescending { it.isCustomLocked }
                    .thenBy { it.isBuiltInStrict }
            )
        )
    }

    suspend fun cleanExternalSafeFiles(snapshot: GuardSnapshot): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (f in snapshot.safeThumbFiles + snapshot.safeLogFiles) {
            try {
                if (f.exists() && f.isFile) {
                    val len = f.length().coerceAtLeast(0L)
                    if (f.delete()) freed += len
                }
            } catch (_: Exception) {
            }
        }
        snapshot.safeEmptyDirs.sortedByDescending { it.absolutePath.length }.forEach { d ->
            try {
                if (d.exists() && d.isDirectory && (d.listFiles()?.isEmpty() == true)) {
                    d.delete()
                }
            } catch (_: Exception) {
            }
        }
        freed
    }

    private fun collectFiles(dir: File, maxDepth: Int, depth: Int = 0, onFile: (File) -> Unit) {
        if (depth > maxDepth) return
        dir.listFiles()?.forEach { child ->
            if (child.isFile) onFile(child)
            else if (child.isDirectory && depth < maxDepth) collectFiles(child, maxDepth, depth + 1, onFile)
        }
    }

    private fun collectEmptyDirs(dir: File, depth: Int, maxDepth: Int, out: MutableList<File>) {
        if (depth > maxDepth) return
        val list = dir.listFiles() ?: return
        if (list.isEmpty()) {
            out.add(dir)
            return
        }
        list.forEach { child ->
            if (child.isDirectory && !child.name.startsWith(".")) {
                collectEmptyDirs(child, depth + 1, maxDepth, out)
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1073741824L -> String.format("%.2f GB", bytes / 1073741824.0)
            bytes >= 1048576L -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
