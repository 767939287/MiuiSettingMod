package com.darknight.miuisettingmod.hook.systemserver

import android.os.Handler
import android.os.Looper
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * system_server（包名 `android`）的主题相关 hook 入口。
 *
 * 由 [com.darknight.miuisettingmod.hook.xposed.XposedEntry.onSystemServerStarting] 分发。
 */
class SystemServerThemeLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            ThemeDrmAlwaysBypassHook(),
            HookPrefs.getBoolean(KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "android"
        const val KEY = "theme_crack_system_server_enabled"
    }
}

/**
 * 常驻放行 MIUI 内容 DRM 授权检查（`DrmManager#isLegal`）。
 *
 * 早期实现只在 `ThemeReceiver#validateTheme` 执行期间临时挂钩，锁屏解锁 / 开机后
 * 系统会从别的入口（`ThemeManagerService`、异步复检、缓存复检）再次调用 `isLegal`，
 * 落在临时窗口外就会被判非法，表现为「主题校验偶尔失效」。
 * 改为在 system_server 里常驻返回 `DRM_SUCCESS`。
 *
 * `isLegal` 可能定义在父类上，因此沿继承链收集重载；`DrmManager` 在 system_server
 * 启动早期尚未加载时按 [RETRY_INTERVAL_MS] 重试。
 */
class ThemeDrmAlwaysBypassHook : BaseHook() {

    override val key: String = SystemServerThemeLoad.KEY

    @Volatile
    private var installed = false

    private var attempts = 0

    private var retryHandler: Handler? = null

    override fun init() {
        tryInstall()
    }

    private fun tryInstall() {
        if (installed) return
        val loader = target.classLoader
        val manager = Reflect.findClassIfExists(DRM_MANAGER_CLASS, loader)
        if (manager == null) {
            scheduleRetry("$DRM_MANAGER_CLASS not loaded yet")
            return
        }
        val success = resolveDrmSuccess(loader)
        if (success == null) {
            scheduleRetry("DRM_SUCCESS not found")
            return
        }
        val methods = collectIsLegalMethods(manager)
        if (methods.isEmpty()) {
            scheduleRetry("no isLegal overload on ${manager.name}")
            return
        }
        var count = 0
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                HookHelper.hookReplace(method) { success }
                count++
            }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
        }
        if (count == 0) {
            scheduleRetry("hook installation failed")
            return
        }
        installed = true
        HookHelper.log("$tag: ${manager.name} $count isLegal overload(s) -> DRM_SUCCESS (always)")
    }

    private fun scheduleRetry(reason: String) {
        if (installed) return
        if (attempts >= MAX_ATTEMPTS) {
            HookHelper.log("$tag: giving up after $attempts attempt(s): $reason")
            return
        }
        attempts++
        if (attempts == 1) HookHelper.log("$tag: waiting for DRM class: $reason")
        val handler = retryHandler ?: Handler(Looper.getMainLooper()).also { retryHandler = it }
        handler.postDelayed({ tryInstall() }, RETRY_INTERVAL_MS)
    }

    /** 沿继承链收集 `isLegal` 重载（`declaredMethods` 不含父类）。 */
    private fun collectIsLegalMethods(clazz: Class<*>): List<Method> {
        val collected = LinkedHashMap<String, Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods
                .filter { it.name == IS_LEGAL }
                .forEach { method ->
                    val signature = method.parameterTypes.joinToString(",") { it.name }
                    collected.putIfAbsent(signature, method)
                }
            current = current.superclass
        }
        return collected.values.toList()
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
        private const val DRM_MANAGER_CLASS = "miui.drm.DrmManager"
        private const val IS_LEGAL = "isLegal"
        private val DRM_RESULT_CLASSES = listOf(
            "miui.drm.DrmManager\$DrmResult",
            "miui.drm.DrmResult",
        )

        /** 类/枚举暂时不可用时的重试间隔。 */
        private const val RETRY_INTERVAL_MS = 2_000L

        /** 最多重试次数（2 秒 × 30 ≈ 1 分钟）。 */
        private const val MAX_ATTEMPTS = 30
    }
}
