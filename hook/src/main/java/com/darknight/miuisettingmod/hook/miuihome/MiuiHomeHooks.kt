package com.darknight.miuisettingmod.hook.miuihome

import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
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
        initHook(HomeAutoDownloadHook(), enabled)
        // 平板专属：Dock 隐藏 / 网格扩展 / Dock 无动画（非平板由 BaseLoad 按 deviceScope 跳过）。
        initHook(HomeDockHiddenHook(), HookPrefs.getBoolean(KEY_HIDE_DOCK, false))
        initHook(HomeGridExpandHook(), HookPrefs.getBoolean(KEY_EXPAND_GRID, false))
        initHook(HomeDockNoAnimationHook(), HookPrefs.getBoolean(KEY_NO_DOCK_ANIMATION, false))
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
        val method = Reflect.findMethodIfExists(clazz, "themeManagerSupportPaidWidget")
        if (method == null) {
            HookHelper.log("$tag: themeManagerSupportPaidWidget not found, skip")
            return
        }
        HookHelper.hookReplace(method) { false }
        HookHelper.log("$tag: themeManagerSupportPaidWidget -> false installed")
    }

    companion object {
        private const val MAML_UTIL_CLASS = "com.miui.maml.widget.edit.MamlutilKt"
    }
}

/**
 * 允许桌面直接自动开始下载小组件。
 *
 * 目标：`com.miui.home.launcher.gadget.MaMlPendingHostView#isCanAutoStartDownload`
 */
class HomeAutoDownloadHook : BaseHook() {

    override val key: String = MiuiHomeLoad.KEY

    override fun init() {
        val clazz = Reflect.findClassIfExists(PENDING_HOST_CLASS, target.classLoader)
        if (clazz == null) {
            HookHelper.log("$tag: $PENDING_HOST_CLASS not found, skip")
            return
        }
        val method = Reflect.findMethodIfExists(clazz, "isCanAutoStartDownload")
        if (method == null) {
            HookHelper.log("$tag: isCanAutoStartDownload not found, skip")
            return
        }
        HookHelper.hookReplace(method) { true }
        HookHelper.log("$tag: isCanAutoStartDownload -> true installed")
    }

    companion object {
        private const val PENDING_HOST_CLASS =
            "com.miui.home.launcher.gadget.MaMlPendingHostView"
    }
}
