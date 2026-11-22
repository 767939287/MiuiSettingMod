package com.darknight.miuisettingmod.hook.miuihome

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.device.DeviceType
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.concurrent.atomic.AtomicBoolean

/** 只在平板形态生效（手机 / 折叠屏没有平板 Dock 这套布局）。 */
private val PAD_ONLY: Set<DeviceType> = setOf(DeviceType.PAD)

/** 平板 Dock 的动画执行器：`DockViewManager` 的每次事务都经由它执行。 */
private const val DOCK_ANIMATION_RUNNER =
    "com.miui.home.launcher.dock.v3.view.DockAnimationRunner"

/**
 * 隐藏平板桌面底部的 Dock 悬浮栏。
 *
 * 移植自 MiuiHomeMod（`/root/project/MiuiHomeMod`，作者桜咲，Apache-2.0），
 * 思路与 HyperCeiler 的 HideDock / HotSeatsHeight 相同。
 *
 * 关键点：**不能跳过 `DockControllerImpl.initialize()`**（会让桌面直接崩），
 * 所以完整保留 Dock 控制器生命周期，只做四件事：
 * 1. `DeviceConfig#getHotSeatsHeight()` / `getDockWindowHeight()` 压成 1px，其它按 Dock 高度布局的地方不再占位；
 * 2. `GestureTouchEventTracker#isTouchCountAndHotSeatSupport()` 返回 false，上滑手势跳过 Dock；
 * 3. `DockWindowManager#createWindow()` 之后立刻 `removeViewImmediate`，并拦掉后续 `updateWindowHeight` / `destroyWindow`；
 * 4. 兜底：`DockContainerView#onAttachedToWindow()`、`DockWindowManager#reparentToWindow()` /
 *    `reparentToLauncher()`（最近任务里 Dock 会被挂回 launcher 容器）之后强制 `GONE` + 全透明；
 * 5. `DockAnimationRunner#applyAlpha()` 的目标 alpha 强制为 0，避免动画收尾把视图刷回 VISIBLE。
 *
 * 目标：`com.miui.home`
 */
class HomeDockHiddenHook : BaseHook() {

    override val key: String = MiuiHomeLoad.KEY_HIDE_DOCK

    override val deviceScope: Set<DeviceType> = PAD_ONLY

    /** 是否已经记录过「最近任务里 Dock 被挂回 launcher 容器」的日志（只记一次，避免刷屏）。 */
    private var launcherReparentLogged = false

    override fun init() {
        val loader = target.classLoader
        installAfterApplicationReady(tag, loader) { installDockHooks(loader) }
    }

    private fun installDockHooks(loader: ClassLoader?) {
        val deviceConfig = Reflect.findClassIfExists(DEVICE_CONFIG, loader)
        if (deviceConfig != null) {
            hookDockSize(deviceConfig)
        } else {
            HookHelper.log("$tag: $DEVICE_CONFIG not found, skip dock size")
        }
        hookDockGesture(loader)
        hookDockWindowRemoval(loader)
        hookDockViewHide(loader)
    }

    /** Dock 高度压成 1px（用 1 而不是 0，避免内部算出负尺寸）。 */
    private fun hookDockSize(clazz: Class<*>) {
        val hotSeats = Reflect.findMethodIfExists(clazz, "getHotSeatsHeight")
        val dockHeight = Reflect.findMethodIfExists(clazz, "getDockWindowHeight")
        if (hotSeats == null && dockHeight == null) {
            HookHelper.log("$tag: dock height methods not found in $DEVICE_CONFIG")
            return
        }
        hotSeats?.let { HookHelper.hookReplace(it) { HIDDEN_DOCK_HEIGHT } }
        dockHeight?.let { HookHelper.hookReplace(it) { HIDDEN_DOCK_HEIGHT } }
        HookHelper.log("$tag: getHotSeatsHeight/getDockWindowHeight -> $HIDDEN_DOCK_HEIGHT")
    }

    /** 上滑手势不再把 Dock 算进去。 */
    private fun hookDockGesture(loader: ClassLoader?) {
        val clazz = Reflect.findClassIfExists(GESTURE_TRACKER, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $GESTURE_TRACKER not found, skip dock gesture")
            return
        }
        val method = Reflect.findMethodIfExists(clazz, "isTouchCountAndHotSeatSupport")
        if (method == null) {
            HookHelper.log("$tag: isTouchCountAndHotSeatSupport not found, skip")
            return
        }
        HookHelper.hookReplace(method) { false }
        HookHelper.log("$tag: isTouchCountAndHotSeatSupport -> false")
    }

    /** 窗口创建后立刻从 WindowManager 移除，并拦掉后续窗口操作。 */
    private fun hookDockWindowRemoval(loader: ClassLoader?) {
        val clazz = Reflect.findClassIfExists(DOCK_WINDOW_MANAGER, loader)
        if (clazz == null) {
            HookHelper.log("$tag: $DOCK_WINDOW_MANAGER not found, skip window removal")
            return
        }
        val dockRootField = runCatching { Reflect.findField(clazz, "dockRootView") }.getOrNull()
        val windowManagerField = runCatching { Reflect.findField(clazz, "windowManager") }.getOrNull()
        val createWindow = Reflect.findMethodIfExists(clazz, "createWindow")
        val updateWindowHeight = Reflect.findMethodIfExists(
            clazz,
            "updateWindowHeight",
            Int::class.javaPrimitiveType!!,
        )
        val destroyWindow = Reflect.findMethodIfExists(clazz, "destroyWindow")

        if (createWindow != null && dockRootField != null && windowManagerField != null) {
            HookHelper.intercept(createWindow, mode = ExceptionMode.PASSTHROUGH) { chain ->
                chain.proceed()
                runCatching {
                    val view = dockRootField.get(chain.thisObject) as View
                    val windowManager = windowManagerField.get(chain.thisObject) as WindowManager
                    windowManager.removeViewImmediate(view)
                    HookHelper.log("$tag: dock window removed from WindowManager")
                }.onFailure { HookHelper.log("$tag: remove dock window failed", it) }
                null
            }
        }

        updateWindowHeight?.let { method ->
            HookHelper.intercept(method, mode = ExceptionMode.PASSTHROUGH) { chain ->
                // 窗口已移除时不再 updateViewLayout，否则会对未附加的 View 操作而报错。
                val view = runCatching { dockRootField?.get(chain.thisObject) as? View }.getOrNull()
                if (view != null && view.parent == null) null else chain.proceed()
            }
        }

        destroyWindow?.let { method -> HookHelper.hookReplace(method) { null } }
        HookHelper.log("$tag: DockWindowManager window hooks installed")
    }

    /** 兜底：attach / reparent 之后强制隐藏 Dock 视图。 */
    private fun hookDockViewHide(loader: ClassLoader?) {
        val container = Reflect.findClassIfExists(DOCK_CONTAINER_VIEW, loader)
        val onAttached = container?.let { Reflect.findMethodIfExists(it, "onAttachedToWindow") }
        onAttached?.let { method ->
            HookHelper.intercept(method, mode = ExceptionMode.PASSTHROUGH) { chain ->
                chain.proceed()
                hideView(chain.thisObject)
                null
            }
        }

        val windowManagerClass = Reflect.findClassIfExists(DOCK_WINDOW_MANAGER, loader)
        val dockRootField = windowManagerClass
            ?.let { runCatching { Reflect.findField(it, "dockRootView") }.getOrNull() }
        val reparent = windowManagerClass?.let {
            Reflect.findMethodIfExists(it, "reparentToWindow")
        }
        if (dockRootField != null && reparent != null) {
            HookHelper.intercept(reparent, mode = ExceptionMode.PASSTHROUGH) { chain ->
                chain.proceed()
                runCatching { hideView(dockRootField.get(chain.thisObject)) }
                    .onFailure { HookHelper.log("$tag: hide dock after reparent failed", it) }
                null
            }
        }

        // 最近任务里 Dock 不走悬浮窗：`reparentToLauncher()` 会把 HotSeats 挂进 launcher
        // 容器（此时 dockRootView 反而是 GONE），所以需要单独兜底。
        val dockViewField = windowManagerClass
            ?.let { runCatching { Reflect.findField(it, "dockView") }.getOrNull() }
        val reparentToLauncher = windowManagerClass?.let {
            Reflect.findMethodIfExists(it, "reparentToLauncher")
        }
        if (dockViewField != null && reparentToLauncher != null) {
            HookHelper.intercept(reparentToLauncher, mode = ExceptionMode.PASSTHROUGH) { chain ->
                // 不执行原逻辑：原实现会把 HotSeats 挂进 launcher 窗口，而返回桌面手势的
                // 过渡期间它就已经可见（快滑时表现为「闪一下」、慢滑时落在桌面就一直显示），
                // 事后再隐藏已经来不及。这里直接阻止挂载，并兜底把已挂在容器里的移除。
                runCatching {
                    val dockView = dockViewField.get(chain.thisObject) as? View
                    if (dockView != null) {
                        (dockView.parent as? ViewGroup)?.removeView(dockView)
                    }
                    if (!launcherReparentLogged) {
                        launcherReparentLogged = true
                        HookHelper.log(
                            "$tag: reparentToLauncher skipped, dock kept out of launcher window"
                        )
                    }
                }.onFailure {
                    HookHelper.log("$tag: detach dock from launcher failed", it)
                }
                null
            }
        }

        // Dock 位移动画收尾时会用 `applyAlpha(目标值)` 把视图重新设回 VISIBLE，
        // 上面几处 GONE 会被它覆盖；这里把动画的 alpha 目标强制成 0，
        // 最近任务场景也能保持隐藏。
        val runner = Reflect.findClassIfExists(DOCK_ANIMATION_RUNNER, loader)
        val applyAlpha = runner?.let { clazz ->
            Reflect.findMethodIfExists(clazz, "applyAlpha", Float::class.javaPrimitiveType!!)
        }
        applyAlpha?.let { method ->
            HookHelper.hookBefore(method) { param -> param.setArg(0, 0f) }
        }

        HookHelper.log("$tag: DockContainerView / reparent(ToWindow|ToLauncher) hooks installed")
    }

    private fun hideView(target: Any?) {
        val view = target as? View ?: return
        if (view.visibility != View.GONE) view.visibility = View.GONE
        if (view.alpha != 0f) view.alpha = 0f
    }

    companion object {
        /** Dock 被压到的高度，用 1px 而不是 0，避免内部算出负尺寸。 */
        private const val HIDDEN_DOCK_HEIGHT = 1

        private const val DEVICE_CONFIG = "com.miui.home.launcher.DeviceConfig"
        private const val GESTURE_TRACKER = "com.miui.home.recents.GestureTouchEventTracker"
        private const val DOCK_CONTAINER_VIEW = "com.miui.home.launcher.dock.DockContainerView"
        private const val DOCK_WINDOW_MANAGER = "com.miui.home.launcher.dock.v3.view.DockWindowManager"
    }
}

/**
 * 桌面网格向下扩展一行，填补 Dock 隐藏后空出来的底部空间。
 *
 * 移植自 MiuiHomeMod（`/root/project/MiuiHomeMod`，作者桜咲，Apache-2.0）。
 *
 * 在 `GridController#notifyGridChange()` 下发前，把 `mActiveGridConfigInWorkspace` /
 * `mSuggestFullScreenGridConfig` 换成「行数 +1、`dockBarHeight = 0`、指示条贴底」的副本。
 * `CellLayout`、翻页指示条、图标放置读的都是同一个 `GridConfig`，因此三者保持一致。
 *
 * 目标：`com.miui.home`
 */
class HomeGridExpandHook : BaseHook() {

    override val key: String = MiuiHomeLoad.KEY_EXPAND_GRID

    override val deviceScope: Set<DeviceType> = PAD_ONLY

    override fun init() {
        val loader = target.classLoader
        installAfterApplicationReady(tag, loader) { installGridHooks(loader) }
    }

    private fun installGridHooks(loader: ClassLoader?) {
        val controller = Reflect.findClassIfExists(GRID_CONTROLLER, loader)
        if (controller == null) {
            HookHelper.log("$tag: $GRID_CONTROLLER not found, skip")
            return
        }
        val notifyGridChange = Reflect.findMethodIfExists(controller, "notifyGridChange")
        if (notifyGridChange == null) {
            HookHelper.log("$tag: notifyGridChange not found, skip")
            return
        }
        val activeField = runCatching {
            Reflect.findField(controller, "mActiveGridConfigInWorkspace")
        }.getOrNull()
        val suggestField = runCatching {
            Reflect.findField(controller, "mSuggestFullScreenGridConfig")
        }.getOrNull()
        if (activeField == null) {
            HookHelper.log("$tag: mActiveGridConfigInWorkspace not found, skip")
            return
        }

        HookHelper.intercept(notifyGridChange, mode = ExceptionMode.PASSTHROUGH) { chain ->
            runCatching {
                val controllerInstance = chain.thisObject
                val active = activeField.get(controllerInstance)
                if (active != null) {
                    val expanded = expandConfig(active, loader)
                    if (expanded != null) {
                        activeField.set(controllerInstance, expanded)
                        suggestField?.set(controllerInstance, expanded)
                    }
                }
            }.onFailure { HookHelper.log("$tag: expand workspace grid failed", it) }
            chain.proceed()
        }

        // Application 阶段网格配置已经算好，这里主动刷一次（会走上面的 hook）。
        runCatching {
            val instance = Reflect.callStaticMethod(controller, "getInstance")
            if (instance != null && activeField.get(instance) != null) {
                notifyGridChange.invoke(instance)
                HookHelper.log("$tag: initial grid refresh done -> ${activeField.get(instance)}")
            }
        }.onFailure { HookHelper.log("$tag: initial grid refresh failed", it) }

        HookHelper.log("$tag: $GRID_CONTROLLER#notifyGridChange hooked")
    }

    /**
     * 复制一份 `GridConfig`：行数 +1、`dockBarHeight` 归零、指示条贴底；
     * `cellSize` 在原值放不下时才等比缩小，最多缩 [MAX_SHRINK_PERCENT]%，否则放弃扩展。
     */
    private fun expandConfig(config: Any, loader: ClassLoader?): Any? {
        val clazz = config.javaClass
        val countY = intOf(clazz, config, "CountY")
        val cellSize = intOf(clazz, config, "CellSize")
        val height = intOf(clazz, config, "Height")
        val top = intOf(clazz, config, "Top")
        val bottom = intOf(clazz, config, "Bottom")
        val dockBarHeight = intOf(clazz, config, "DockBarHeight")
        val indicatorBarHeight = intOf(clazz, config, "IndicatorBarHeight")

        // 已经扩展过（或本来就是无 dock 配置）。
        if (dockBarHeight <= 0) return null
        if (countY <= 0 || cellSize <= 0 || height <= 0) return null

        val available = height - top - bottom - indicatorBarHeight
        if (available <= 0) {
            HookHelper.log("$tag: available height invalid: $available")
            return null
        }

        val newCountY = countY + 1
        val newCellSize = minOf(cellSize, available / newCountY)
        if (newCellSize * 100 < cellSize * (100 - MAX_SHRINK_PERCENT)) {
            HookHelper.log(
                "$tag: cannot fit one more row (cellSize $cellSize -> $newCellSize), skip"
            )
            return null
        }

        val builderClass = Reflect.findClass(GRID_CONFIG_BUILDER, loader)
        val builder = builderClass.getDeclaredConstructor().newInstance()
        fun setInt(name: String, value: Int) {
            builderClass
                .getMethod("set$name", Int::class.javaPrimitiveType!!)
                .invoke(builder, value)
        }

        setInt("Width", intOf(clazz, config, "Width"))
        setInt("Height", height)
        setInt("CountX", intOf(clazz, config, "CountX"))
        setInt("CountY", newCountY)
        setInt("Top", top)
        setInt("Left", intOf(clazz, config, "Left"))
        setInt("Bottom", bottom)
        setInt("CellSize", newCellSize)
        setInt("IndicatorBarHeight", indicatorBarHeight)
        setInt("DockBarHeight", 0)
        setInt("IconSize", intOf(clazz, config, "IconSize"))
        setInt("IndicatorMarginBottom", bottom)

        val expanded = builderClass.getMethod("build").invoke(builder) ?: return null
        val expandedClass = expanded.javaClass

        runCatching {
            val name = clazz.getMethod("getName").invoke(config) as String?
            expandedClass.getMethod("setName", String::class.java).invoke(expanded, name)
        }.onFailure { HookHelper.log("$tag: copy grid name failed", it) }

        runCatching {
            val customField = clazz.getDeclaredField("customConfig").apply { isAccessible = true }
            val custom = customField.get(config)
            if (custom != null) {
                expandedClass.getMethod("setCustomConfig", customField.type).invoke(expanded, custom)
            }
        }.onFailure { HookHelper.log("$tag: copy customConfig failed", it) }

        return expanded
    }

    private fun intOf(clazz: Class<*>, instance: Any, name: String): Int =
        clazz.getMethod("get$name").invoke(instance) as Int

    companion object {
        /** 允许为了塞下新的一行而缩小的最大比例，超过就放弃扩展。 */
        private const val MAX_SHRINK_PERCENT = 12

        private const val GRID_CONTROLLER = "com.miui.home.launcher.grid.GridController"
        private const val GRID_CONFIG_BUILDER =
            "com.miui.home.launcher.grid.GridConfig\$GridConfigBuilder"
    }
}

/**
 * 返回桌面时 Dock 不播放弹出动画（Dock 照常显示，只是瞬间到位）。
 *
 * 平板 Dock 的每次状态变化都会包成 `Transaction` 交给
 * `DockAnimationRunner#runAnimation`；该方法在 `!transaction.getAnimated()` 时直接
 * `applyTranslation(1.0f)` + `applyAlpha(toAlpha)` 跳到终态（原生自带的「无动画」分支），
 * 所以把 `animated` 改成 false 即可：Dock 保留、没有从底部弹出 / 涏出的位移与透明度过渡。
 *
 * 不依赖隐藏 Dock：两项开关互相独立。
 *
 * 目标：`com.miui.home`
 */
class HomeDockNoAnimationHook : BaseHook() {

    override val key: String = MiuiHomeLoad.KEY_NO_DOCK_ANIMATION

    override val deviceScope: Set<DeviceType> = PAD_ONLY

    override fun init() {
        val loader = target.classLoader
        installAfterApplicationReady(tag, loader) { installNoAnimation(loader) }
    }

    private fun installNoAnimation(loader: ClassLoader?) {
        val runner = Reflect.findClassIfExists(DOCK_ANIMATION_RUNNER, loader)
        if (runner == null) {
            HookHelper.log("$tag: $DOCK_ANIMATION_RUNNER not found, skip")
            return
        }
        // `runAnimation` 是私有方法，且参数类型（Transaction / Function0）在编译期无法引用，
        // 因此按「方法名 + 参数个数」定位。
        val runAnimation = runner.declaredMethods.firstOrNull {
            it.name == RUN_ANIMATION && it.parameterTypes.size == 2
        }
        if (runAnimation == null) {
            HookHelper.log("$tag: $RUN_ANIMATION not found on $DOCK_ANIMATION_RUNNER, skip")
            return
        }
        runAnimation.isAccessible = true
        HookHelper.intercept(runAnimation, mode = ExceptionMode.PASSTHROUGH) { chain ->
            runCatching {
                val transaction = chain.getArg(0) ?: return@runCatching
                transaction.javaClass.methods
                    .firstOrNull { it.name == SET_ANIMATED && it.parameterTypes.size == 1 }
                    ?.invoke(transaction, false)
            }.onFailure { HookHelper.log("$tag: disable dock animation failed", it) }
            chain.proceed()
        }
        HookHelper.log("$tag: $DOCK_ANIMATION_RUNNER#$RUN_ANIMATION hooked (animated -> false)")
    }

    companion object {
        private const val RUN_ANIMATION = "runAnimation"
        private const val SET_ANIMATED = "setAnimated"
    }
}

/**
 * Dock 仅在桌面（HOME_STATE）显示，应用内与最近任务都隐藏。
 *
 * 平板 Dock 的显隐由 `DockStateManager` 的状态（HOME / RECENT / APP）驱动，
 * 每次状态切换都会生成一个 `Transaction` 交给 `DockAnimationRunner` 执行，
 * 最终统一落到 `applyAlpha(目标透明度)`。因此这里：
 *
 * 1. 在 `DockStateManager#getState()` / `#observeState()` 时记下实例；
 * 2. `DockAnimationRunner#applyAlpha()` 执行前，如果当前不是 `HOME_STATE`
 *    就把目标透明度强制成 0。
 *
 * 不改变状态机本身，只影响可视效果，Dock 的图标 / 拖拽链路全部保留。
 *
 * 目标：`com.miui.home`
 */
class HomeDockDesktopOnlyHook : BaseHook() {

    override val key: String = MiuiHomeLoad.KEY_DOCK_DESKTOP_ONLY

    override val deviceScope: Set<DeviceType> = PAD_ONLY

    @Volatile
    private var stateManager: Any? = null

    override fun init() {
        val loader = target.classLoader
        installAfterApplicationReady(tag, loader) { installDesktopOnly(loader) }
    }

    private fun installDesktopOnly(loader: ClassLoader?) {
        val managerClass = Reflect.findClassIfExists(DOCK_STATE_MANAGER, loader)
        if (managerClass == null) {
            HookHelper.log("$tag: $DOCK_STATE_MANAGER not found, skip")
            return
        }
        // 尽早拿到 DockStateManager 实例：observeState / getState 都会被框架调用。
        listOf("observeState", "getState").forEach { name ->
            Reflect.findMethodIfExists(managerClass, name)?.let { method ->
                HookHelper.hookAfter(method) { param -> stateManager = param.thisObject }
            }
        }

        val runner = Reflect.findClassIfExists(DOCK_ANIMATION_RUNNER, loader)
        val applyAlpha = runner?.let { clazz ->
            Reflect.findMethodIfExists(clazz, "applyAlpha", Float::class.javaPrimitiveType!!)
        }
        if (applyAlpha == null) {
            HookHelper.log("$tag: applyAlpha not found, skip")
            return
        }
        HookHelper.hookBefore(applyAlpha) { param ->
            if (!isHomeState()) param.setArg(0, 0f)
        }
        HookHelper.log("$tag: dock desktop-only installed (hidden outside HOME_STATE)")
    }

    /** 当前是否处于桌面状态；状态未知时放行，避免把桌面 Dock 一并隐藏。 */
    private fun isHomeState(): Boolean {
        val manager = stateManager ?: return true
        val state = runCatching { Reflect.callMethod(manager, "getState") }.getOrNull()
            ?: return true
        return (state as? Enum<*>)?.name == HOME_STATE
    }

    companion object {
        private const val DOCK_STATE_MANAGER =
            "com.miui.home.launcher.dock.v3.state.DockStateManager"
        private const val HOME_STATE = "HOME_STATE"
    }
}

/**
 * 把实际安装推迟到 `com.miui.home.launcher.Application#onCreate` 之后。
 *
 * 不能在 `onPackageReady` 阶段碰 `DeviceConfig` / `GridController` 这类类：
 * 它们的 `<clinit>` 会访问 `Application.getInstance().getResources()`，
 * 那时 Application 还没建好，会抛 `ExceptionInInitializerError` 让桌面启动即崩
 * （MiuiHomeMod 已在真机上踩过这个坑）。
 */
private fun installAfterApplicationReady(
    tag: String,
    loader: ClassLoader?,
    install: () -> Unit,
) {
    val applicationClass = Reflect.findClassIfExists(APPLICATION, loader)
    if (applicationClass == null) {
        HookHelper.log("$tag: $APPLICATION not found, skip")
        return
    }
    val onCreate = Reflect.findMethodIfExists(applicationClass, "onCreate")
    if (onCreate == null) {
        HookHelper.log("$tag: $APPLICATION#onCreate not found, skip")
        return
    }
    val installed = AtomicBoolean(false)
    HookHelper.intercept(onCreate, mode = ExceptionMode.PASSTHROUGH) { chain ->
        if (installed.compareAndSet(false, true)) {
            runCatching { install() }.onFailure { HookHelper.log("$tag: install failed", it) }
        }
        chain.proceed()
    }
    HookHelper.log("$tag: waiting for $APPLICATION#onCreate")
}

private const val APPLICATION = "com.miui.home.launcher.Application"
