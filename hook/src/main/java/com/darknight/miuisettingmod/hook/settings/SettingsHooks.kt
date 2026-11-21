package com.darknight.miuisettingmod.hook.settings

import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect

/**
 * com.android.settings 进程的 hook 入口。
 */
class SettingsLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            ForceAdvancedColorStyleHook(),
            HookPrefs.getBoolean(ForceAdvancedColorStyleHook.KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.settings"
    }
}

/**
 * 强制开启色彩风格高级模式。
 *
 * 依据 ColorOS / HyperLight 的实现：系统用
 * `ScreenEffectFragment#isSupportSimplifiedColormode()` 判断「是否只提供简化色彩模式」，
 * 直接返回 false 即可让「高级模式（专业模式）」入口常驻。
 *
 * 目标：`com.android.settings`（`com.android.settings.display.ScreenEffectFragment`）
 */
class ForceAdvancedColorStyleHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val fragment = Reflect.findClassIfExists(FRAGMENT_CLASS, target.classLoader)
        if (fragment == null) {
            HookHelper.log("$tag: $FRAGMENT_CLASS not found, skip")
            return
        }
        val method = Reflect.findMethodIfExists(fragment, "isSupportSimplifiedColormode")
        if (method == null) {
            HookHelper.log("$tag: isSupportSimplifiedColormode not found, skip")
            return
        }
        HookHelper.hookReplace(method) { false }
        HookHelper.log("$tag: isSupportSimplifiedColormode -> false installed")
    }

    companion object {
        const val KEY = "force_enable_advanced_color_style_mode"

        private const val FRAGMENT_CLASS =
            "com.android.settings.display.ScreenEffectFragment"
    }
}
