package com.custom.parentguard

enum class PermissionActionStep {
    USAGE_STATS,
    RESTRICTED_SETTINGS,
    CLEAN_ACCESSIBILITY,
    EXTERNAL_FILES,
    ALL_DONE
}

data class PermissionSnapshot(
    val hasUsageStats: Boolean,
    val isAndroid13Plus: Boolean,
    val restrictedConfirmed: Boolean,
    val cleanServiceRunning: Boolean,
    val cleanServiceZombie: Boolean,
    val hasExternalFiles: Boolean,
    val rogueServiceRunning: Boolean
) {
    val readyCount: Int
        get() = listOf(
            hasUsageStats,
            cleanServiceRunning,
            hasExternalFiles,
            rogueServiceRunning
        ).count { it }

    val essentialReady: Boolean
        get() = hasUsageStats
}

object PermissionStepPlanner {

    fun nextRecommendedStep(state: PermissionSnapshot): PermissionActionStep {
        if (!state.hasUsageStats) return PermissionActionStep.USAGE_STATS
        if (state.isAndroid13Plus && !state.cleanServiceRunning && !state.restrictedConfirmed) {
            return PermissionActionStep.RESTRICTED_SETTINGS
        }
        if (!state.cleanServiceRunning) return PermissionActionStep.CLEAN_ACCESSIBILITY
        if (!state.hasExternalFiles) return PermissionActionStep.EXTERNAL_FILES
        return PermissionActionStep.ALL_DONE
    }

    fun shouldAutoOpenCenterOnLaunch(
        onboardingCompleted: Boolean,
        hasUsageStats: Boolean
    ): Boolean {
        return !onboardingCompleted || !hasUsageStats
    }
}
