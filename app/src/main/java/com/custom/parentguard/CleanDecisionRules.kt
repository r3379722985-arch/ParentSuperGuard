package com.custom.parentguard

enum class DialogDecision {
    /** 当前窗口不是确认弹窗（例如后台的应用信息页，含“卸载”但无“取消”按钮），忽略不误退 */
    NOT_A_DIALOG,
    /** 小米双选项底部菜单仍在过渡动画中，稍等不误退 */
    WAIT_TRANSITION,
    /** 危险确认弹窗（提到全部数据/删除数据/卸载/账号等），立刻按返回取消 */
    ABORT_DANGEROUS,
    /** 明确安全的「清除缓存」确认弹窗，允许点击确定按钮 */
    CONFIRM_CACHE
}

/**
 * 纯函数安全判定规则（无 Android 框架依赖，全部由 JUnit 单元测试直接验证）：
 * 1. 核对当前「应用信息」页属于本次目标 App，防止慢加载/残余页误操作；
 * 2. 修复小米底部菜单 `isDangerOnlyMenu` 误判底层应用信息页（含“卸载”无“取消”）的大坑；
 * 3. 修复防流氓守护误拦「卸载流氓软件」系统确认弹窗的大坑（shouldBlockInstallerWindow）。
 */
object CleanDecisionRules {

    val ABSOLUTE_FORBIDDEN = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage",
        "卸载", "uninstall", "停用", "disable", "恢复出厂"
    )

    // 仅用于「清除数据」底部菜单的危险数据清除词（刻意不包含“卸载”，杜绝误判底层应用信息页）
    private val DATA_WIPE_MENU_KEYWORDS = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage"
    )

    val NON_MIUI_FORBIDDEN = listOf(
        "清除数据", "clear data", "清空数据"
    )

    fun matchesTargetAppWindow(
        rawWindowText: String,
        targetAppName: String,
        targetPackageName: String
    ): Boolean {
        val win = rawWindowText.lowercase()
        val pkg = targetPackageName.trim().lowercase()
        if (pkg.isNotEmpty() && win.contains(pkg)) return true

        val name = targetAppName.trim().lowercase()
        if (name.isEmpty()) return false
        if (win.contains(name)) return true

        val compactWin = win.replace(" ", "")
        val compactName = name.replace(" ", "")
        if (compactName.length >= 2 && compactWin.contains(compactName)) return true

        return false
    }

    /**
     * 判断弹出菜单是否只含“清除全部数据”等危险项、而无“清除缓存”项（如缓存为 0B 时的小米底部菜单）：
     * 必须同时包含弹窗/菜单特征（「取消 / cancel」）与数据清除危险词，绝不误判底层应用信息页！
     */
    fun isDangerOnlyMenu(rawWindowText: String, clearCacheLabels: List<String>): Boolean {
        val txt = rawWindowText.lowercase()
        val hasCancel = txt.contains("取消") || txt.contains("cancel")
        if (!hasCancel) return false
        val hasClearCache = clearCacheLabels.any { txt.contains(it.lowercase()) }
        if (hasClearCache) return false
        return DATA_WIPE_MENU_KEYWORDS.any { txt.contains(it) }
    }

    fun evaluateCacheConfirmWindow(rawWindowText: String): DialogDecision {
        val txt = rawWindowText.lowercase()
        val looksLikeDialog = txt.contains("取消") || txt.contains("cancel")
        if (!looksLikeDialog) {
            return DialogDecision.NOT_A_DIALOG
        }
        if (txt.contains("清除全部数据") && txt.contains("清除缓存")) {
            return DialogDecision.WAIT_TRANSITION
        }
        if (ABSOLUTE_FORBIDDEN.any { txt.contains(it) } || txt.contains("账号")) {
            return DialogDecision.ABORT_DANGEROUS
        }
        if (txt.contains("缓存") || txt.contains("cache")) {
            return DialogDecision.CONFIRM_CACHE
        }
        return DialogDecision.NOT_A_DIALOG
    }

    fun isForceStopConfirmWindow(rawWindowText: String): Boolean {
        val txt = rawWindowText.lowercase()
        if (ABSOLUTE_FORBIDDEN.any { txt.contains(it) }) return false
        val hasCancel = txt.contains("取消") || txt.contains("cancel")
        val hasStop = txt.contains("强行停止") || txt.contains("强制停止") ||
            txt.contains("结束运行") || txt.contains("force stop")
        return hasCancel && hasStop
    }

    /**
     * 判定系统安装器弹窗是否属于高风险安装包弹窗（需要拦截）：
     * 关键修复：Android 卸载确认框也由 packageinstaller 弹出！只要包含“卸载 / uninstall”，绝对放行，
     * 绝不把家长/子女「卸载流氓软件」的确认框给拦掉！
     */
    /** 自带兜底风险词：与调用方词表取并集，保证判定不因调用方漏传参数而失效 */
    private val BUILTIN_RISK_KEYWORDS = listOf(
        "未知来源", "高风险", "未经官方", "恶意", "风险应用", "安全风险", "报毒"
    )

    fun shouldBlockInstallerWindow(rawWindowText: String, highRiskKeywords: List<String>): Boolean {
        val txt = rawWindowText.lowercase()
        if (txt.contains("卸载") || txt.contains("uninstall")) {
            return false
        }
        val looksLikeInstall = txt.contains("安装") || txt.contains("install") ||
            txt.contains("未知来源") || txt.contains("风险")
        if (!looksLikeInstall) return false
        return (highRiskKeywords + BUILTIN_RISK_KEYWORDS).any { txt.contains(it.lowercase()) }
    }
}
