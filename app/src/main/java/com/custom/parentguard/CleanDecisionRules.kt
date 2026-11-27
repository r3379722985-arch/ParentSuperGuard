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
 * 纯函数安全判定规则（无 Android 框架依赖，由 JUnit 单元测试直接验证）：
 * 解决背景“应用信息”页包含「卸载」二字时被误判成危险弹窗、导致误按返回或误判未清理的问题。
 */
object CleanDecisionRules {

    val ABSOLUTE_FORBIDDEN = listOf(
        "清除全部数据", "清除所有数据", "删除全部数据", "删除应用数据", "删除数据", "清除存储",
        "全部数据", "所有数据", "clear all data", "clear storage",
        "卸载", "uninstall", "停用", "disable", "恢复出厂"
    )

    val NON_MIUI_FORBIDDEN = listOf(
        "清除数据", "clear data", "清空数据"
    )

    fun evaluateCacheConfirmWindow(rawWindowText: String): DialogDecision {
        val txt = rawWindowText.lowercase()
        // 1. 必须是弹窗（包含取消按钮）；普通应用信息页/存储页没有“取消”，绝不误触发返回
        val looksLikeDialog = txt.contains("取消") || txt.contains("cancel")
        if (!looksLikeDialog) {
            return DialogDecision.NOT_A_DIALOG
        }
        // 2. 小米双选项底部菜单（同时列出「清除全部数据」与「清除缓存」），退场动画期间等待而非误判取消
        if (txt.contains("清除全部数据") && txt.contains("清除缓存")) {
            return DialogDecision.WAIT_TRANSITION
        }
        // 3. 任何确认弹窗若出现危险词或“账号”，立即安全中止
        if (ABSOLUTE_FORBIDDEN.any { txt.contains(it) } || txt.contains("账号")) {
            return DialogDecision.ABORT_DANGEROUS
        }
        // 4. 明确提及“缓存 / cache”且无危险词，安全放行点击确认
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
}
