package com.darknight.miuisettingmod.hook.systemui

import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.Canvas
import android.graphics.Insets
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.status.HookStatusReporter
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect

/**
 * com.android.systemui 进程的 hook 入口。
 */
class SystemUiLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        val manual = HookPrefs.getBoolean(HideGestureLineHook.KEY_HIDE, false)
        val auto = HookPrefs.getBoolean(HideGestureLineHook.KEY_AUTO_HIDE, false)
        initHook(HideGestureLineHook(), manual || auto)
        initHook(AodGlowHook(), HookPrefs.getBoolean(KEY_AOD_GLOW, false))
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
        const val KEY_AOD_GLOW = "aod_disable_notification_glow"
    }
}

/**
 * 隐藏系统界面的手势线（导航条）。
 *
 * 两套方案由 `gesture_line_hide_method` 选择：
 * - **新方案（0，默认）**：拦截 `NavigationHandle#onDraw` /
 *   `QuickswitchOrientedNavHandle#onDraw`，命中条件时不绘制。
 * - **旧方案（1）**：拦截 `MiuiDecorationBottomView#onDraw`（有状态栏跟随问题的版本用）。
 *
 * 自动隐藏（`auto_hide_navigation_bar_enabled`）：通过
 * `MiuiTopActivityObserver#updateTopActivity` 判断顶层 Activity，
 * 桌面（com.miui.home）时隐藏，切到其它应用后 100ms 恢复绘制。
 *
 * 目标：`com.android.systemui`
 */
class HideGestureLineHook : BaseHook() {

    override val key: String = KEY_HIDE

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 手动开关：常驻隐藏。 */
    @Volatile
    private var hiddenByUser = false

    /** 自动隐藏：当前处于桌面时置位。 */
    @Volatile
    private var hiddenByAuto = false

    private val restoreRunnable = Runnable { hiddenByAuto = false }

    private val shouldSkipDraw: Boolean
        get() = hiddenByUser || hiddenByAuto

    override fun init() {
        val loader = target.classLoader
        hiddenByUser = HookPrefs.getBoolean(KEY_HIDE, false)
        val autoHide = HookPrefs.getBoolean(KEY_AUTO_HIDE, false)
        val useOldMethod = readHideMethod() == METHOD_OLD

        if (useOldMethod) {
            if (autoHide) hookTopActivity(loader)
            hookOldMethod(loader)
        } else {
            if (autoHide) hookTopActivity(loader)
            hookNewMethod(loader)
        }
        // 隐藏手势线时统一把导航栏 inset 高度归零，内容直接延伸到底。
        hookNavBarInset(loader)
    }

    /** 下拉框存的是字符串，兼容 int 写入。 */
    private fun readHideMethod(): Int {
        val raw = HookPrefs.getString(KEY_METHOD, null)
        if (raw != null) return raw.toIntOrNull() ?: METHOD_NEW
        return HookPrefs.getInt(KEY_METHOD, METHOD_NEW)
    }

    private fun hookNewMethod(loader: ClassLoader?) {
        hookOnDraw(loader, "com.android.systemui.navigationbar.gestural.NavigationHandle")
        hookOnDraw(loader, "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle")
    }

    private fun hookOldMethod(loader: ClassLoader?) {
        val clazz = Reflect.findClassIfExists(OLD_DECOR_CLASS, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $OLD_DECOR_CLASS not found, skip old method")
            return
        }
        hookOnDrawOn(clazz)
    }

    private fun hookOnDraw(loader: ClassLoader?, className: String) {
        val clazz = Reflect.findClassIfExists(className, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $className not found, skip")
            return
        }
        hookOnDrawOn(clazz)
    }

    private fun hookOnDrawOn(clazz: Class<*>) {
        val method = Reflect.findMethodIfExists(clazz, "onDraw", Canvas::class.java)
        if (method == null) {
            HookHelper.log("$tag: onDraw(Canvas) not found on ${clazz.name}")
            return
        }
        HookHelper.hookBefore(method) { param ->
            if (shouldSkipDraw) param.setResultValue(null)
        }
        HookHelper.log("$tag: ${clazz.name}#onDraw hooked")
    }

    private fun hookTopActivity(loader: ClassLoader?) {
        val clazz = Reflect.findClassIfExists(TOP_ACTIVITY_OBSERVER, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $TOP_ACTIVITY_OBSERVER not found, auto hide unavailable")
            HookStatusReporter.markFailed(KEY_AUTO_HIDE)
            return
        }
        val method = Reflect.findMethodIfExists(
            clazz,
            "updateTopActivity",
            ActivityManager.RunningTaskInfo::class.java,
        )
        if (method == null) {
            HookHelper.log("$tag: updateTopActivity(RunningTaskInfo) not found, auto hide unavailable")
            HookStatusReporter.markFailed(KEY_AUTO_HIDE)
            return
        }
        HookHelper.hookAfter(method) { param ->
            val top = runCatching {
                Reflect.getObjectField(param.thisObject ?: return@runCatching null, "mTopActivity")
            }.getOrNull() as? ComponentName
            val isHome = top?.packageName == MIUI_HOME_PACKAGE
            mainHandler.removeCallbacks(restoreRunnable)
            if (isHome) {
                hiddenByAuto = true
            } else {
                mainHandler.postDelayed(restoreRunnable, RESTORE_DELAY_MS)
            }
        }
        HookHelper.log("$tag: ${clazz.name}#updateTopActivity hooked (auto hide)")
        // 自动隐藏没有独立的 BaseHook（挂在 HideGestureLineHook 里），
        // 这里单独上报它的生效状态，否则功能已生效也会显示「未生效」。
        HookStatusReporter.markInstalled(KEY_AUTO_HIDE)
    }

    /**
     * 彻底隐藏：把手势导航栏窗口提供给系统的 `navigationBars` insets 高度置 0。
     *
     * 仅拦截 `onDraw` 只是不画手势条，窗口仍向系统贡献一个导航栏 insets，
     * 内容会被「顶起」一段。MIUI 在 `NavigationBar#getBarLayoutParamsForRotation(int)`
     * 里给 `InsetsFrameProvider` 写入了固定高度，这里在该方法返回后把
     * `WindowInsets.Type.navigationBars()` 的 provider 改成 0。
     *
     * 只改 navigationBars：`systemGestures` / `mandatorySystemGestures`
     * 属于另外的 provider，底部返回手势不受影响。
     */
    private fun hookNavBarInset(loader: ClassLoader?) {
        val clazz = Reflect.findClassIfExists(NAV_BAR_CLASS, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $NAV_BAR_CLASS not found, inset hiding unavailable")
            return
        }
        val method = Reflect.findMethodIfExists(
            clazz,
            "getBarLayoutParamsForRotation",
            Int::class.javaPrimitiveType!!,
        )
        if (method == null) {
            HookHelper.log("$tag: getBarLayoutParamsForRotation not found, inset hiding unavailable")
            return
        }
        HookHelper.hookAfter(method) { param ->
            if (!hiddenByUser) return@hookAfter
            val layoutParams = param.result as? WindowManager.LayoutParams ?: return@hookAfter
            zeroNavigationBarInset(layoutParams)
        }
        HookHelper.log("$tag: ${clazz.name}#getBarLayoutParamsForRotation hooked (zero inset)")
    }

    private fun zeroNavigationBarInset(layoutParams: WindowManager.LayoutParams) {
        // `providedInsets` 与 `InsetsFrameProvider` 在 Android SDK 中是 @hide，只能反射访问。
        val providers = runCatching {
            WindowManager.LayoutParams::class.java
                .getDeclaredField("providedInsets")
                .apply { isAccessible = true }
                .get(layoutParams) as? Array<*>
        }.getOrNull() ?: return

        val navBarsType = WindowInsets.Type.navigationBars()
        val zeroInsets = Insets.of(0, 0, 0, 0)
        providers.forEach { provider ->
            if (provider == null) return@forEach
            val type = runCatching {
                provider.javaClass.getDeclaredMethod("getType")
                    .apply { isAccessible = true }
                    .invoke(provider)
            }.getOrNull()
            if (type != navBarsType) return@forEach
            runCatching {
                provider.javaClass
                    .getDeclaredMethod("setInsetsSize", Insets::class.java)
                    .apply { isAccessible = true }
                    .invoke(provider, zeroInsets)
            }.onFailure { HookHelper.log("$tag: set navigationBars inset failed", it) }
        }
    }

    companion object {
        const val KEY_HIDE = "hide_navigation_bar_enabled"
        const val KEY_AUTO_HIDE = "auto_hide_navigation_bar_enabled"
        const val KEY_METHOD = "gesture_line_hide_method"

        const val METHOD_NEW = 0
        const val METHOD_OLD = 1

        private const val MIUI_HOME_PACKAGE = "com.miui.home"
        private const val RESTORE_DELAY_MS = 100L
        private const val NAV_BAR_CLASS =
            "com.android.systemui.navigationbar.views.NavigationBar"
        private const val TOP_ACTIVITY_OBSERVER =
            "com.miui.systemui.functions.MiuiTopActivityObserver"
        private const val OLD_DECOR_CLASS =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
    }
}
