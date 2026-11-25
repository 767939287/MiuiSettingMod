package com.darknight.miuisettingmod.hook.systemui

import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.Canvas
import android.graphics.Insets
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.status.HookStatusReporter
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * com.android.systemui 进程的 hook 入口。
 */
class SystemUiLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        // 两条 hook 都常驻安装：真正的开关判断放在 hook 内部，
        // 这样 App 里改开关可以热重载，不必重启 SystemUI。
        // （开关全关时内部不会挂任何方法 hook，也不会有探测开销。）
        initHook(HideGestureLineHook(), true)
        initHook(AodGlowHook(), true)
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
 * 桌面（`com.miui.home` / `com.miui.fliphome` / `com.mi.android.globallauncher`）
 * 时隐藏，切到其它应用后 100ms 恢复绘制。
 *
 * ## 热重载
 * `init()` 会注册配置监听：开关 / 方案变化后立即补齐或生效，无需重启 SystemUI。
 * 唯一例外是「不占位」：`navigationBars` inset 只在导航栏窗口重建时读取，
 * 手动隐藏开关需要 SystemUI 重启后才会归零。
 *
 * 目标：`com.android.systemui`
 */
class HideGestureLineHook : BaseHook() {

    override val key: String = KEY_HIDE

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 手动开关（常驻隐藏）。 */
    @Volatile
    private var manualEnabled = false

    /** 自动隐藏开关。 */
    @Volatile
    private var autoEnabled = false

    /** 自动隐藏：当前处于桌面时置位。 */
    @Volatile
    private var hiddenByAuto = false

    /**
     * 最近一次绘制手势条的 View。
     *
     * 只在实例变化时写一次（[rememberHandle]），每帧只有一次 volatile 读 + 身份比较，
     * 用于状态切换后主动 `invalidate()`——`NavigationHandle#setAlpha` 在 alpha<=0 时
     * 不会自己 invalidate，不主动重绘会导致手势条残留/不恢复。
     */
    @Volatile
    private var handleRef: WeakReference<View>? = null

    private val installLock = Any()
    private var classLoader: ClassLoader? = null

    /** 已挂 onDraw 的方案（0 新 / 1 旧），-1 表示未挂。 */
    private var drawMethodHooked = -1

    /** 成功挂上的 onDraw 类数量，用于失败上报。 */
    private var drawHookCount = 0

    private var topActivityHooked = false
    private var insetHooked = false

    private val restoreRunnable = Runnable { setHiddenByAuto(false) }

    private val shouldSkipDraw: Boolean
        get() = manualEnabled || hiddenByAuto

    override fun init() {
        classLoader = target.classLoader
        manualEnabled = HookPrefs.getBoolean(KEY_HIDE, false)
        autoEnabled = HookPrefs.getBoolean(KEY_AUTO_HIDE, false)
        syncInstall()
        // 热重载：开关或方案变化后立即生效（inset 归零除外，见类注释）。
        HookPrefs.registerListener { onPrefsChanged() }
    }

    /** 配置变化回调：重读开关，补齐需要的 hook 并触发一次重绘。 */
    private fun onPrefsChanged() {
        val manual = HookPrefs.getBoolean(KEY_HIDE, false)
        val auto = HookPrefs.getBoolean(KEY_AUTO_HIDE, false)
        val method = readHideMethod()
        if (manual == manualEnabled && auto == autoEnabled &&
            (drawMethodHooked == -1 || method == drawMethodHooked)
        ) {
            return
        }
        val manualTurnedOn = manual && !manualEnabled
        manualEnabled = manual
        autoEnabled = auto
        HookHelper.log("$tag: prefs changed manual=$manual auto=$auto method=$method")
        syncInstall()
        invalidateHandle()
        if (manualTurnedOn) {
            HookHelper.log("$tag: inset 归零需重启 SystemUI 后生效")
        }
    }

    /** 按当前开关补齐缺失的 hook；可重复调用，已挂的部分不会重复挂。 */
    private fun syncInstall() {
        synchronized(installLock) {
            val loader = classLoader ?: return
            val anyEnabled = manualEnabled || autoEnabled
            if (!anyEnabled) return

            val method = readHideMethod()
            if (method != drawMethodHooked) {
                when (method) {
                    METHOD_OLD -> hookOldMethod(loader)
                    else -> hookNewMethod(loader)
                }
                drawMethodHooked = method
            }
            if (autoEnabled && !topActivityHooked) {
                topActivityHooked = hookTopActivity(loader)
            }
            // 只有「手动隐藏」才把 inset 写死为 0：自动隐藏是随桌面/应用动态切换的，
            // 写死会让应用内内容永久压在导航栏下。
            if (manualEnabled && !insetHooked) {
                insetHooked = hookNavBarInset(loader)
            }
            if (drawHookCount == 0) {
                HookStatusReporter.markFailed(KEY_HIDE, overrideInstalled = true)
            }
        }
    }

    /** 下拉框存的是字符串，兼容 int 写入。 */
    private fun readHideMethod(): Int {
        val raw = HookPrefs.getString(KEY_METHOD, null)
        if (raw != null) return raw.toIntOrNull() ?: METHOD_NEW
        return HookPrefs.getInt(KEY_METHOD, METHOD_NEW)
    }

    private fun hookNewMethod(loader: ClassLoader?) {
        hookOnDraw(loader, NAV_HANDLE_CLASS)
        hookOnDraw(loader, QUICKSWITCH_HANDLE_CLASS)
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
        // onDraw 是每帧调用：不走 hookBefore（每次都会 new HookParam + 复制参数列表），
        // 只用一个不分配对象的回调，需要隐藏时短路，其余直接放行。
        HookHelper.intercept(method) { chain ->
            val view = chain.thisObject as? View
            if (view != null) rememberHandle(view)
            if (shouldSkipDraw) null else chain.proceed()
        }
        drawHookCount++
        HookHelper.log("$tag: ${clazz.name}#onDraw hooked")
    }

    /** 记录手势条 View：引用没变时零分配（每帧只做一次 volatile 读 + 身份比较）。 */
    private fun rememberHandle(view: View) {
        val ref = handleRef
        if (ref != null && ref.get() === view) return
        handleRef = WeakReference(view)
    }

    /** 状态变化后主动重绘，否则手势条会停留在上一帧（隐藏不生效 / 恢复了还在）。 */
    private fun invalidateHandle() {
        val view = handleRef?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) view.invalidate() else view.postInvalidate()
    }

    private fun setHiddenByAuto(value: Boolean) {
        if (hiddenByAuto == value) return
        hiddenByAuto = value
        invalidateHandle()
    }

    /** 自动隐藏：判断顶层 Activity 是否为桌面；返回是否成功挂上。 */
    private fun hookTopActivity(loader: ClassLoader?): Boolean {
        val clazz = Reflect.findClassIfExists(TOP_ACTIVITY_OBSERVER, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $TOP_ACTIVITY_OBSERVER not found, auto hide unavailable")
            HookStatusReporter.markFailed(KEY_AUTO_HIDE)
            return false
        }
        val method = Reflect.findMethodIfExists(
            clazz,
            "updateTopActivity",
            ActivityManager.RunningTaskInfo::class.java,
        )
        if (method == null) {
            HookHelper.log("$tag: updateTopActivity(RunningTaskInfo) not found, auto hide unavailable")
            HookStatusReporter.markFailed(KEY_AUTO_HIDE)
            return false
        }
        // 字段只解析一次：updateTopActivity 在每次任务切换都会触发，别再每次反射查找。
        val topActivityField = runCatching { Reflect.findField(clazz, "mTopActivity") }.getOrNull()
        if (topActivityField == null) {
            HookHelper.log("$tag: mTopActivity field not found, auto hide unavailable")
            HookStatusReporter.markFailed(KEY_AUTO_HIDE)
            return false
        }
        HookHelper.hookAfter(method) { param ->
            val self = param.thisObject ?: return@hookAfter
            val top = runCatching { topActivityField.get(self) }.getOrNull() as? ComponentName
            val pkg = top?.packageName
            mainHandler.removeCallbacks(restoreRunnable)
            if (pkg != null && pkg in HOME_PACKAGES) {
                setHiddenByAuto(true)
            } else {
                mainHandler.postDelayed(restoreRunnable, RESTORE_DELAY_MS)
            }
        }
        HookHelper.log("$tag: ${clazz.name}#updateTopActivity hooked (auto hide)")
        // 自动隐藏没有独立的 BaseHook（挂在 HideGestureLineHook 里），
        // 这里单独上报它的生效状态，否则功能已生效也会显示「未生效」。
        HookStatusReporter.markInstalled(KEY_AUTO_HIDE)
        return true
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
     *
     * 返回是否成功挂上。
     */
    private fun hookNavBarInset(loader: ClassLoader?): Boolean {
        val clazz = Reflect.findClassIfExists(NAV_BAR_CLASS, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $NAV_BAR_CLASS not found, inset hiding unavailable")
            return false
        }
        val method = Reflect.findMethodIfExists(
            clazz,
            "getBarLayoutParamsForRotation",
            Int::class.javaPrimitiveType!!,
        )
        if (method == null) {
            HookHelper.log("$tag: getBarLayoutParamsForRotation not found, inset hiding unavailable")
            return false
        }
        HookHelper.hookAfter(method) { param ->
            // 只用「手动隐藏」判断：自动隐藏随桌面/应用切换，不能写死 inset。
            if (!manualEnabled) return@hookAfter
            val layoutParams = param.result as? WindowManager.LayoutParams ?: return@hookAfter
            zeroNavigationBarInset(layoutParams)
        }
        HookHelper.log("$tag: ${clazz.name}#getBarLayoutParamsForRotation hooked (zero inset)")
        return true
    }

    private fun zeroNavigationBarInset(layoutParams: WindowManager.LayoutParams) {
        // `providedInsets` 与 `InsetsFrameProvider` 在 Android SDK 中是 @hide，只能反射访问。
        val providers = NavBarInsets.providedInsets(layoutParams) ?: return
        val navBarsType = WindowInsets.Type.navigationBars()
        val zeroInsets = Insets.of(0, 0, 0, 0)
        providers.forEach { provider ->
            if (provider == null) return@forEach
            if (NavBarInsets.typeOf(provider) != navBarsType) return@forEach
            NavBarInsets.setInsetsSize(provider, zeroInsets)
        }
    }

    /**
     * `navigationBars` inset 的反射缓存。
     *
     * 这些成员全部是 @hide，此前每次调用都重新 `getDeclaredField` / `getDeclaredMethod`，
     * 且字段取不到时**静默 return**（隐藏失效也查不到原因）。现在只解析一次，
     * 失败会打日志，方便定位是被 hidden API 拦了还是 ROM 改了实现。
     */
    private object NavBarInsets {

        private val providedInsetsField: Field? by lazy {
            runCatching {
                WindowManager.LayoutParams::class.java
                    .getDeclaredField("providedInsets")
                    .apply { isAccessible = true }
            }.onFailure {
                HookHelper.log("HideGestureLineHook: LayoutParams#providedInsets 取不到，彻底隐藏失效", it)
            }.getOrNull()
        }

        @Volatile
        private var providerClass: Class<*>? = null

        @Volatile
        private var getTypeMethod: Method? = null

        @Volatile
        private var setInsetsSizeMethod: Method? = null

        @Volatile
        private var resolvedFailureLogged = false

        fun providedInsets(layoutParams: WindowManager.LayoutParams): Array<*>? {
            val field = providedInsetsField ?: return null
            return runCatching { field.get(layoutParams) as? Array<*> }.getOrNull()
        }

        fun typeOf(provider: Any): Int? {
            ensureProviderMethods(provider.javaClass)
            return runCatching { getTypeMethod?.invoke(provider) as? Int }.getOrNull()
        }

        fun setInsetsSize(provider: Any, insets: Insets) {
            ensureProviderMethods(provider.javaClass)
            val method = setInsetsSizeMethod ?: return
            runCatching { method.invoke(provider, insets) }.onFailure {
                HookHelper.log("HideGestureLineHook: set navigationBars inset failed", it)
            }
        }

        private fun ensureProviderMethods(clazz: Class<*>) {
            if (providerClass == clazz) return
            synchronized(this) {
                if (providerClass == clazz) return
                getTypeMethod = runCatching {
                    clazz.getDeclaredMethod("getType").apply { isAccessible = true }
                }.getOrNull()
                setInsetsSizeMethod = runCatching {
                    clazz.getDeclaredMethod("setInsetsSize", Insets::class.java)
                        .apply { isAccessible = true }
                }.getOrNull()
                providerClass = clazz
                if ((getTypeMethod == null || setInsetsSizeMethod == null) && !resolvedFailureLogged) {
                    resolvedFailureLogged = true
                    HookHelper.log("HideGestureLineHook: InsetsFrameProvider 反射失败，彻底隐藏失效")
                }
            }
        }
    }

    companion object {
        const val KEY_HIDE = "hide_navigation_bar_enabled"
        const val KEY_AUTO_HIDE = "auto_hide_navigation_bar_enabled"
        const val KEY_METHOD = "gesture_line_hide_method"

        const val METHOD_NEW = 0
        const val METHOD_OLD = 1

        /** 桌面包名：与 SystemUI 自己的 `MiuiTopActivityObserver` 判断保持一致。 */
        private val HOME_PACKAGES = setOf("com.miui.home", "com.miui.fliphome", "com.mi.android.globallauncher")
        private const val RESTORE_DELAY_MS = 100L
        private const val NAV_BAR_CLASS =
            "com.android.systemui.navigationbar.views.NavigationBar"
        private const val NAV_HANDLE_CLASS =
            "com.android.systemui.navigationbar.gestural.NavigationHandle"
        private const val QUICKSWITCH_HANDLE_CLASS =
            "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle"
        private const val TOP_ACTIVITY_OBSERVER =
            "com.miui.systemui.functions.MiuiTopActivityObserver"
        private const val OLD_DECOR_CLASS =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
    }
}
