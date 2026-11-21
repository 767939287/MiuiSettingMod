package com.darknight.miuisettingmod.ui.screen.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darknight.miuisettingmod.R
import com.darknight.miuisettingmod.prefs.OptionSpec
import com.darknight.miuisettingmod.prefs.OptionType
import com.darknight.miuisettingmod.ui.component.pref.HookOptionsPage
import com.darknight.miuisettingmod.ui.component.pref.HookSection

/**
 * 功能页：模块全部功能入口。
 *
 * 页面布局由通用组件 [HookOptionsPage] 提供；配置项全量注册在 [featureSpecs]，
 * 供全局搜索与作用域申请使用。
 *
 * @param pendingSearchKey 全局搜索命中的配置键：本页据此滚动到对应分区或打开对应子页面。
 * @param onSearchKeyHandled [pendingSearchKey] 处理完毕后的回调。
 */
@Composable
fun FeaturesPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
    pendingSearchKey: String? = null,
    onSearchKeyHandled: () -> Unit = {},
) {
    val specs = remember { featureSpecs() }
    val sections = remember(specs) { featureSections(specs) }

    HookOptionsPage(
        title = stringResource(R.string.tab_features),
        sections = sections,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        externalSearchKey = pendingSearchKey,
        onExternalSearchHandled = onSearchKeyHandled,
    )
}

private fun specByKey(specs: List<OptionSpec>, key: String): OptionSpec =
    specs.first { it.key == key }

private fun featureSections(specs: List<OptionSpec>): List<HookSection> = listOf(
    HookSection(
        titleRes = R.string.feature_section_display,
        specs = listOf(
            specByKey(specs, "force_enable_advanced_color_style_mode"),
        ),
    ),
    HookSection(
        titleRes = R.string.feature_section_systemui,
        specs = listOf(
            specByKey(specs, "hide_navigation_bar_enabled"),
            specByKey(specs, "auto_hide_navigation_bar_enabled"),
            specByKey(specs, "gesture_line_hide_method"),
        ),
    ),
    HookSection(
        titleRes = R.string.feature_section_aod,
        specs = listOf(
            specByKey(specs, "aod_disable_notification_glow"),
        ),
    ),
    HookSection(
        titleRes = R.string.feature_section_theme,
        specs = listOf(
            specByKey(specs, "theme_crack_theme_manager_enabled"),
            specByKey(specs, "theme_crack_personal_assistant_enabled"),
            specByKey(specs, "theme_crack_home_enabled"),
            specByKey(specs, "theme_crack_system_server_enabled"),
        ),
    ),
)

/** 功能页的全部配置项（App 启动时注册，供全局搜索与作用域申请使用）。 */
internal fun featureSpecs(): List<OptionSpec> = listOf(
    OptionSpec(
        key = "force_enable_advanced_color_style_mode",
        type = OptionType.SWITCH,
        titleRes = R.string.force_enable_advanced_color_style_mode,
        summaryRes = R.string.force_enable_advanced_color_style_mode_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.android.settings"),
        showStatus = true,
    ),
    OptionSpec(
        key = "hide_navigation_bar_enabled",
        type = OptionType.SWITCH,
        titleRes = R.string.hide_navigation_bar_enabled,
        summaryRes = R.string.hide_navigation_bar_enabled_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.android.systemui"),
        showStatus = true,
    ),
    OptionSpec(
        key = "auto_hide_navigation_bar_enabled",
        type = OptionType.SWITCH,
        titleRes = R.string.auto_hide_navigation_bar_enabled,
        summaryRes = R.string.auto_hide_navigation_bar_enabled_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.android.systemui"),
        showStatus = true,
    ),
    OptionSpec(
        key = "gesture_line_hide_method",
        type = OptionType.DROPDOWN,
        titleRes = R.string.gesture_line_hide_method,
        summaryRes = R.string.gesture_line_hide_method_summary,
        defaultString = "0",
        entryResIds = listOf(
            R.string.gesture_line_hide_method_new,
            R.string.gesture_line_hide_method_old,
        ),
        entryValues = listOf("0", "1"),
        targetPackages = listOf("com.android.systemui"),
    ),
    OptionSpec(
        key = "aod_disable_notification_glow",
        type = OptionType.SWITCH,
        titleRes = R.string.aod_disable_notification_glow,
        summaryRes = R.string.aod_disable_notification_glow_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.android.systemui"),
        showStatus = true,
    ),
    OptionSpec(
        key = "theme_crack_theme_manager_enabled",
        type = OptionType.SWITCH,
        titleRes = R.string.theme_crack_theme_manager_enabled,
        summaryRes = R.string.theme_crack_theme_manager_enabled_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.android.thememanager"),
        showStatus = true,
    ),
    OptionSpec(
        key = "theme_crack_personal_assistant_enabled",
        type = OptionType.SWITCH,
        titleRes = R.string.theme_crack_personal_assistant_enabled,
        summaryRes = R.string.theme_crack_personal_assistant_enabled_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.miui.personalassistant"),
        showStatus = true,
    ),
    OptionSpec(
        key = "theme_crack_home_enabled",
        type = OptionType.SWITCH,
        titleRes = R.string.theme_crack_home_enabled,
        summaryRes = R.string.theme_crack_home_enabled_summary,
        defaultBoolean = false,
        targetPackages = listOf("com.miui.home"),
        showStatus = true,
    ),
    OptionSpec(
        key = "theme_crack_system_server_enabled",
        type = OptionType.SWITCH,
        titleRes = R.string.theme_crack_system_server_enabled,
        summaryRes = R.string.theme_crack_system_server_enabled_summary,
        defaultBoolean = false,
        targetPackages = listOf("system"),
        showStatus = true,
    ),
)
