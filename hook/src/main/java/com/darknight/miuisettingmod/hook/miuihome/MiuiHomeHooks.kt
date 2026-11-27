package com.darknight.miuisettingmod.hook.miuihome

import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.conflict.HyperCeilerCompat
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect

/**
 * com.miui.home（桌面）进程的 hook 入口。
 */
class MiuiHomeLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        val enabled = HookPrefs.getBoolean(KEY, false)
        initHook(HomePaidWidgetHook(), enabled)
        // 「允许桌面自动下载小组件」（MaMlPendingHostView#isCanAutoStartDownload）
        // 已从主题相关开关里移除：打开主题相关开关不应顺带放开 MAML 小组件的后台自动下载。
        val hideDock = HookPrefs.getBoolean(KEY_HIDE_DOCK, false)
        val expandGrid = HookPrefs.getBoolean(KEY_EXPAND_GRID, false)
        val desktopOnly = HookPrefs.getBoolean(KEY_DOCK_DESKTOP_ONLY, false)
        // 平板专属：Dock 隐藏 / 网格扩展 / Dock 无动画（非平板由 BaseLoad 按 deviceScope 跳过）。
        initHook(HomeDockHiddenHook(), hideDock)
        initHook(HomeGridExpandHook(), expandGrid)
        initHook(HomeDockNoAnimationHook(), HookPrefs.getBoolean(KEY_NO_DOCK_ANIMATION, false))
        initHook(HomeDockDesktopOnlyHook(), desktopOnly)
        // 三个 Dock 开关共用 DeviceConfig / GridController / Dock 视图与动画方法；
        // desktopOnly 虽然只改 applyAlpha，但与 HyperCeiler 的 Dock 显隐共用同一套
        // 视图状态，同样可能互相覆盖，因此一并纳入检测。
        if (hideDock || expandGrid || desktopOnly) {
            HyperCeilerCompat.warnIfAnyEnabled(
                "MiuiHomeLoad",
                "HyperCeiler 的「隐藏 Dock / 底栏高度 / 平板网格」会 hook 同一批 " +
                    "DeviceConfig、GridController 与 Dock 视图方法，建议只开一侧",
                HyperCeilerCompat.KEY_HIDE_DOCK,
                HyperCeilerCompat.KEY_HOTSEATS_HEIGHT,
                HyperCeilerCompat.KEY_PAD_GRID,
            )
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.miui.home"
        const val KEY = "theme_crack_home_enabled"

        /** 隐藏平板桌面 Dock 栏。 */
        const val KEY_HIDE_DOCK = "hide_home_dock_enabled"

        /** 网格向下扩展一行（依赖 Dock 隐藏）。 */
        const val KEY_EXPAND_GRID = "expand_home_grid_enabled"

        /** 返回桌面时 Dock 不播放弹出动画（Dock 保留）。 */
        const val KEY_NO_DOCK_ANIMATION = "home_dock_no_animation_enabled"

        /** Dock 仅在桌面显示（应用 / 最近任务隐藏）。 */
        const val KEY_DOCK_DESKTOP_ONLY = "home_dock_desktop_only_enabled"
    }
}

/**
 * 关闭桌面侧付费小组件支持判定。
 *
 * 目标：`com.miui.maml.widget.edit.MamlutilKt#themeManagerSupportPaidWidget`
 */
class HomePaidWidgetHook : BaseHook() {

    override val key: String = MiuiHomeLoad.KEY

    override fun init() {
        val clazz = Reflect.findClassIfExists(MAML_UTIL_CLASS, target.classLoader)
        if (clazz == null) {
            HookHelper.log("$tag: $MAML_UTIL_CLASS not found, skip")
            return
        }
        // 同名重载可能在平板分支上带参数，按名字匹配全部重载。
        val methods = Reflect.findMethodsByName(clazz, "themeManagerSupportPaidWidget")
        if (methods.isEmpty()) {
            HookHelper.log("$tag: themeManagerSupportPaidWidget not found, skip")
            return
        }
        methods.forEach { method -> HookHelper.hookReplace(method) { false } }
        HookHelper.log("$tag: ${methods.size} overload(s) -> false installed")
    }

    companion object {
        private const val MAML_UTIL_CLASS = "com.miui.maml.widget.edit.MamlutilKt"
    }
}


