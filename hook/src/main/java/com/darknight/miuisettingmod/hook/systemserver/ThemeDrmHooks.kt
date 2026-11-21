package com.darknight.miuisettingmod.hook.systemserver

import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface.HookHandle

/**
 * system_server（包名 `android`）的主题相关 hook 入口。
 *
 * 由 [com.darknight.miuisettingmod.hook.xposed.XposedEntry.onSystemServerStarting] 分发。
 */
class SystemServerThemeLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            ThemeValidateDrmBypassHook(),
            HookPrefs.getBoolean(KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "android"
        const val KEY = "theme_crack_system_server_enabled"
    }
}

/**
 * 主题校验期间临时放行 DRM 授权检查。
 *
 * MIUI 在 `miui.drm.ThemeReceiver#validateTheme` 里逐个调用
 * `DrmManager#isLegal` 校验主题合法性。这里只在 `validateTheme` 执行的这段时间内
 * 把所有 `isLegal` 重载替换成返回 `DrmResult.DRM_SUCCESS`，执行结束立即卸载，
 * 避免常驻修改系统 DRM 造成其它副作用。
 */
class ThemeValidateDrmBypassHook : BaseHook() {

    override val key: String = SystemServerThemeLoad.KEY

    private val lock = Any()

    /** 嵌套调用计数（`validateTheme` 可能并发/重入）。 */
    private var depth = 0

    private var activeHooks: List<HookHandle> = emptyList()

    override fun init() {
        val loader = target.classLoader
        val receiver = Reflect.findClassIfExists(THEME_RECEIVER_CLASS, loader)
        if (receiver == null) {
            HookHelper.log("$tag: $THEME_RECEIVER_CLASS not found, skip")
            return
        }
        val method = Reflect.findMethodIfExists(receiver, "validateTheme")
        if (method == null) {
            HookHelper.log("$tag: validateTheme not found, skip")
            return
        }
        HookHelper.intercept(method) { chain ->
            installDrmHooks(loader)
            try {
                chain.proceed()
            } finally {
                releaseDrmHooks()
            }
        }
        HookHelper.log("$tag: ${receiver.name}#validateTheme hooked")
    }

    private fun installDrmHooks(loader: ClassLoader?) {
        synchronized(lock) {
            if (depth++ > 0) return
            val manager = Reflect.findClassIfExists(DRM_MANAGER_CLASS, loader)
            if (manager == null) {
                HookHelper.log("$tag: $DRM_MANAGER_CLASS not found")
                return
            }
            val success = resolveDrmSuccess(loader)
            if (success == null) {
                HookHelper.log("$tag: DRM_SUCCESS not found")
                return
            }
            activeHooks = manager.declaredMethods
                .filter { it.name == "isLegal" }
                .map { method ->
                    method.isAccessible = true
                    HookHelper.hookReplace(method) { success }
                }
            if (activeHooks.isEmpty()) {
                HookHelper.log("$tag: no isLegal overload found on ${manager.name}")
            }
        }
    }

    private fun releaseDrmHooks() {
        synchronized(lock) {
            if (depth <= 0) return
            depth--
            if (depth > 0) return
            activeHooks.forEach { runCatching { it.unhook() } }
            activeHooks = emptyList()
        }
    }

    /** `DrmResult.DRM_SUCCESS` 枚举常量（兼容独立类与内部类两种声明方式）。 */
    private fun resolveDrmSuccess(loader: ClassLoader?): Any? {
        for (className in DRM_RESULT_CLASSES) {
            val clazz = Reflect.findClassIfExists(className, loader) ?: continue
            val success = clazz.enumConstants
                ?.firstOrNull { (it as? Enum<*>)?.name == "DRM_SUCCESS" }
            if (success != null) return success
        }
        return null
    }

    companion object {
        private const val THEME_RECEIVER_CLASS = "miui.drm.ThemeReceiver"
        private const val DRM_MANAGER_CLASS = "miui.drm.DrmManager"
        private val DRM_RESULT_CLASSES = listOf(
            "miui.drm.DrmManager\$DrmResult",
            "miui.drm.DrmResult",
        )
    }
}
