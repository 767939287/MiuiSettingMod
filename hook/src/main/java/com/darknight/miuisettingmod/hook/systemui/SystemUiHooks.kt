package com.darknight.miuisettingmod.hook.systemui

import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
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
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
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
            return
        }
        val method = Reflect.findMethodIfExists(
            clazz,
            "updateTopActivity",
            ActivityManager.RunningTaskInfo::class.java,
        )
        if (method == null) {
            HookHelper.log("$tag: updateTopActivity(RunningTaskInfo) not found, auto hide unavailable")
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
    }

    companion object {
        const val KEY_HIDE = "hide_navigation_bar_enabled"
        const val KEY_AUTO_HIDE = "auto_hide_navigation_bar_enabled"
        const val KEY_METHOD = "gesture_line_hide_method"

        const val METHOD_NEW = 0
        const val METHOD_OLD = 1

        private const val MIUI_HOME_PACKAGE = "com.miui.home"
        private const val RESTORE_DELAY_MS = 100L
        private const val TOP_ACTIVITY_OBSERVER =
            "com.miui.systemui.functions.MiuiTopActivityObserver"
        private const val OLD_DECOR_CLASS =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
    }
}
