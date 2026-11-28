package com.custom.parentguard

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 离线清缓存诊断日志 v7.1（零联网权限、零主线程磁盘 I/O）：
 * - [P1-1] log() 在主线程仅做极轻量内存列表更新，所有磁盘追加/裁剪/Download 写入全部交由独立守护单线程 psg-diag 串行执行；
 * - [P3-2] 导出到公共 Download 目录的文件首行自动附带隐私提示（注明含本机应用包名，排查完请删除）；
 * - [P3-3] ensureLoaded() 在后台线程读取磁盘并与内存已有记录合并，仅在读取无异常后置位 loadedFromDisk。
 */
object CleanDiagLogger {

    private const val MAX_LINES = 260
    private const val MAX_BYTES = 64 * 1024L
    private const val PRIVACY_HEADER = "# ParentSuperGuard 诊断日志（仅供排查，含本机应用包名与名称，排查完毕请删除）"

    private val memLock = Any()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "psg-diag").apply { isDaemon = true }
    }

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile
    private var loadedFromDisk = false

    @Volatile
    private var loadInFlight = false

    fun ensureLoaded(context: Context) {
        if (loadedFromDisk || loadInFlight) return
        val appCtx = context.applicationContext ?: context
        loadInFlight = true
        io.execute {
            try {
                val f = privateLogFile(appCtx)
                if (f.exists() && f.isFile) {
                    val diskLines = f.readLines(Charsets.UTF_8)
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .takeLast(MAX_LINES)
                    synchronized(memLock) {
                        val existing = _lines.value
                        val merged = if (existing.isEmpty()) {
                            diskLines
                        } else {
                            (diskLines + existing).takeLast(MAX_LINES)
                        }
                        _lines.value = merged
                    }
                }
                loadedFromDisk = true
            } catch (_: Exception) {
            } finally {
                loadInFlight = false
            }
        }
    }

    fun log(context: Context?, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
        val entry = "[$ts] $msg"
        val snapshotForCompact: List<String>
        synchronized(memLock) {
            val updated = (_lines.value + entry).takeLast(MAX_LINES)
            _lines.value = updated
            snapshotForCompact = updated
        }

        if (context == null) return
        val appCtx = context.applicationContext ?: context
        val exportToDownload = GuardStore.config.value.exportDiagToDownload
        io.execute {
            try {
                val f = privateLogFile(appCtx)
                f.parentFile?.mkdirs()
                if (f.exists() && f.length() > MAX_BYTES) {
                    f.writeText(snapshotForCompact.joinToString("\n") + "\n", Charsets.UTF_8)
                } else {
                    f.appendText(entry + "\n", Charsets.UTF_8)
                }
                if (exportToDownload) {
                    appendLineToDownloadIfWritable(entry)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun clear(context: Context) {
        synchronized(memLock) {
            _lines.value = emptyList()
        }
        val appCtx = context.applicationContext ?: context
        io.execute {
            try {
                val f = privateLogFile(appCtx)
                if (f.exists()) f.delete()
            } catch (_: Exception) {
            }
        }
    }

    fun getFullText(): String {
        val current = synchronized(memLock) { _lines.value }
        return if (current.isEmpty()) {
            "暂无诊断日志（执行一次清理后会自动记录每一步点击与判定结果）"
        } else {
            "$PRIVACY_HEADER\n" + current.joinToString("\n")
        }
    }

    fun exportNow(context: Context): Pair<Boolean, String> {
        val text = getFullText()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val fileName = "ParentSuperGuard-diag-$dateStr.txt"

        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs()) && downloadDir.canWrite()) {
                val outFile = File(downloadDir, fileName)
                outFile.writeText(text + "\n", Charsets.UTF_8)
                return Pair(true, outFile.absolutePath)
            }
        } catch (_: Exception) {
        }

        return try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            fallbackDir.mkdirs()
            val outFile = File(fallbackDir, fileName)
            outFile.writeText(text + "\n", Charsets.UTF_8)
            Pair(true, outFile.absolutePath)
        } catch (e: Exception) {
            Pair(false, e.message ?: "写入失败")
        }
    }

    private fun appendLineToDownloadIfWritable(line: String) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) ?: return
            if (!downloadDir.exists() || !downloadDir.canWrite()) return
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            val outFile = File(downloadDir, "ParentSuperGuard-diag-$dateStr.txt")
            if (!outFile.exists() || outFile.length() == 0L) {
                outFile.writeText("$PRIVACY_HEADER\n$line\n", Charsets.UTF_8)
            } else {
                outFile.appendText(line + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }
    }

    private fun privateLogFile(context: Context): File {
        return File(File(context.filesDir, "diag"), "clean-diag.log")
    }
}
