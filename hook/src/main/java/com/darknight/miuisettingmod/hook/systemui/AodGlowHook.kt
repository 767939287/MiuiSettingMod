package com.darknight.miuisettingmod.hook.systemui

import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.device.DeviceType
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.status.HookStatusReporter
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关闭息屏显示（AOD）来通知时的波纹 / 粒子光效，通知图标与时钟照常显示。
 *
 * ## 为什么不能直接用 `target.classLoader`
 * `com.miui.aod.AODView` 运行在 `com.android.systemui` 进程，但类由 SystemUI 的
 * **插件 ClassLoader** 持有，主 ClassLoader 里 `Class.forName` 拿不到。
 *
 * 获取插件 ClassLoader 的策略：
 * 1. 主路径：hook `PluginInstance$PluginFactory#createPluginContext()` 返回值
 *    （`PluginContextWrapper` 是 `ContextWrapper`，其 `classLoader` 即插件 loader）；
 * 2. 兜底：在**后台线程**按 2s 一轮、最多 6 轮遍历
 *    `PluginInstanceInjector.sClassLoaders`（同一 loader 只试一次）。
 *
 * 旧实现把兜底探测跑在主线程上，且每轮 `Thread.getAllStackTraces()` +
 * 对上百个线程的 `contextClassLoader` 重复 `Class.forName`（注定抛
 * `ClassNotFoundException`），造成 SystemUI 周期性卡顿，已移除。
 *
 * ## 热重载
 * 插件工厂 hook 常驻（开关关闭时回调直接放行），`init()` 注册配置监听：
 * 开关打开后无需重启 SystemUI，下次插件上下文创建或兜底探测即可生效。
 *
 * ## 安全约束
 * 禁止 hook `ClassLoader.loadClass` / `Class.forName` 等类加载热路径（历史版本因此
 * 导致 SystemUI 崩溃循环）。所有 hook 使用 [ExceptionMode.PASSTHROUGH]，回调内部
 * 只记日志，不向外抛异常。
 */
class AodGlowHook : BaseHook() {

    override val key: String = SystemUiLoad.KEY_AOD_GLOW

    /** 平板没有息屏显示，直接不安装（手机 / 折叠屏照常）。 */
    override val deviceScope: Set<DeviceType> =
        setOf(DeviceType.PHONE, DeviceType.FOLD)

    /** 目标方法是否已挂上 hook。 */
    private val installed = AtomicBoolean(false)

    /** 兜底探测是否正在跑。 */
    private val probing = AtomicBoolean(false)

    /** 已探测过的 ClassLoader（避免对同一个 loader 反复 `Class.forName`）。 */
    private val probedLoaders = ConcurrentHashMap.newKeySet<ClassLoader>()

    /** 开关状态（热重载时由配置监听更新）。 */
    @Volatile
    private var enabled = false

    /** 主线程 Handler，用于延时探测与复位动画状态。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 兜底探测线程（只在需要时创建，不在主线程）。 */
    @Volatile
    private var probeHandler: Handler? = null

    private val probeHandlerLock = Any()

    // ---- AODView 反射成员（插件 ClassLoader 加载，只能运行期解析并缓存）----

    @Volatile
    private var hostField: Field? = null

    @Volatile
    private var styleInfoField: Field? = null

    @Volatile
    private var animationViewField: Field? = null

    @Volatile
    private var stopAnimationMethod: Method? = null

    @Volatile
    private var setNotificationAnimateMethod: Method? = null

    @Volatile
    private var fireAnimateStateMethod: Method? = null

    override fun init() {
        val loader = target.classLoader
        // 主路径常驻：开关关闭时回调直接 `proceed()`（单次调用，开销可忽略），
        // 这样开关可以热重载。
        hookPluginFactory(loader)
        enabled = HookPrefs.getBoolean(key, false)
        if (enabled) startProbing(loader)
        HookPrefs.registerListener {
            val now = HookPrefs.getBoolean(key, false)
            if (now == enabled) return@registerListener
            enabled = now
            HookHelper.log("$tag: enabled=$now (hot reload)")
            if (now) startProbing(loader)
        }
    }

    /** hook `PluginInstance$PluginFactory#createPluginContext()`，从返回值取插件 ClassLoader。 */
    private fun hookPluginFactory(loader: ClassLoader?) {
        runCatching {
            val factory = Reflect.findClass(PLUGIN_FACTORY, loader)
            val method = factory.getDeclaredMethod(CREATE_PLUGIN_CONTEXT)
            method.isAccessible = true
            HookHelper.intercept(method, mode = ExceptionMode.PASSTHROUGH) { chain ->
                val result = chain.proceed()
                runCatching {
                    val pluginLoader = (result as? ContextWrapper)?.classLoader
                    if (pluginLoader != null) scheduleProbe(pluginLoader)
                }.onFailure { HookHelper.log("$tag: plugin loader probe failed", it) }
                result
            }
            HookHelper.log("$tag: $PLUGIN_FACTORY#$CREATE_PLUGIN_CONTEXT hooked")
        }.onFailure { HookHelper.log("$tag: hook $PLUGIN_FACTORY failed", it) }
    }

    /** 延迟后用插件 ClassLoader 取 `AODView`（避开插件上下文创建途中加载类）。 */
    private fun scheduleProbe(loader: ClassLoader) {
        if (installed.get()) return
        ensureProbeHandler().postDelayed({ probe(loader, dedupe = false) }, RESOLVE_DELAY_MS)
    }

    /** 启动兜底探测：后台线程遍历插件 loader 注册表。 */
    private fun startProbing(loader: ClassLoader?) {
        if (installed.get()) return
        // 兜底一：插件 dex 若挂在主 ClassLoader 上，可直接取。
        loader?.let { probe(it, dedupe = false) }
        if (installed.get()) return
        if (!probing.compareAndSet(false, true)) return

        val handler = ensureProbeHandler()
        var round = 0
        val task = object : Runnable {
            override fun run() {
                if (installed.get() || !enabled) {
                    probing.set(false)
                    return
                }
                if (round >= MAX_ROUNDS) {
                    probing.set(false)
                    HookHelper.log("$tag: 探测 $MAX_ROUNDS 轮仍未找到 $AOD_VIEW，放弃")
                    HookStatusReporter.markFailed(key, overrideInstalled = true)
                    return
                }
                round++
                runCatching { scanPluginRegistry(loader) }.onFailure {
                    if (round == 1) HookHelper.log("$tag: read plugin registry failed", it)
                }
                handler.postDelayed(this, PROBE_INTERVAL_MS)
            }
        }
        handler.postDelayed(task, PROBE_INTERVAL_MS)
    }

    /** 只读遍历 `PluginInstanceInjector.sClassLoaders`（后台线程执行）。 */
    private fun scanPluginRegistry(loader: ClassLoader?) {
        val injector = runCatching { Reflect.findClass(PLUGIN_INJECTOR, loader) }.getOrNull() ?: return
        val field = runCatching {
            injector.getDeclaredField("sClassLoaders").apply { isAccessible = true }
        }.getOrNull() ?: return
        val map = runCatching { field.get(null) as? Map<*, *> }.getOrNull() ?: return
        for (value in map.values) {
            (value as? ClassLoader)?.let { probe(it) }
        }
    }

    private fun ensureProbeHandler(): Handler {
        probeHandler?.let { return it }
        synchronized(probeHandlerLock) {
            probeHandler?.let { return it }
            val thread = HandlerThread("MiuiSettingMod-AodProbe")
            thread.start()
            return Handler(thread.looper).also { probeHandler = it }
        }
    }

    /**
     * 尝试用指定 ClassLoader 取 `AODView`，取到就挂钩。
     *
     * @param dedupe 注册表扫描时为 true：同一个 loader 每轮都试一次 `Class.forName`
     *   会产生大量 `ClassNotFoundException`（异常要填栈），因此去重。
     */
    private fun probe(loader: ClassLoader, dedupe: Boolean = true) {
        if (installed.get()) return
        if (dedupe && !probedLoaders.add(loader)) return
        val clazz = runCatching { Reflect.findClass(AOD_VIEW, loader) }.getOrNull() ?: return
        install(clazz)
    }

    private fun install(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        try {
            val method = clazz.getDeclaredMethod(SHOW_ANIMATE, String::class.java)
                .apply { isAccessible = true }
            // 反射成员只解析一次（每次来通知都走 showAnimate）
            hostField = findField(clazz, "mHost")
            styleInfoField = findField(clazz, "mStyleInfo")
            animationViewField = findField(clazz, "mAnimationView")
            if (hostField == null || animationViewField == null) {
                // 拿不到宿主/动画视图就无法复刻状态，宁可不动，保持原生动画，
                // 否则会出现「光效没了但 AOD 来通知不亮」的副作用。
                HookHelper.log(
                    "$tag: mHost=${hostField != null} mAnimationView=${animationViewField != null}，" +
                        "不接管 $SHOW_ANIMATE"
                )
                installed.set(false)
                return
            }
            HookHelper.intercept(method, mode = ExceptionMode.PASSTHROUGH) { chain ->
                // 热重载：开关关闭时原样放行（光效恢复），无需重启 SystemUI。
                if (!enabled) return@intercept chain.proceed()
                // 不创建 AnimationDrawer、不播放动画，只补上原逻辑里的 AOD 状态。
                runCatching { holdAodState(chain.thisObject) }
                    .onFailure { HookHelper.log("$tag: hold AOD state failed", it) }
                null
            }
            HookStatusReporter.markInstalled(key)
            HookHelper.log("$tag: $AOD_VIEW#$SHOW_ANIMATE hooked (glow disabled)")
        } catch (t: Throwable) {
            installed.set(false)
            HookHelper.log("$tag: hook $AOD_VIEW#$SHOW_ANIMATE failed", t)
        }
    }

    /**
     * 复刻 `AODView.showAnimate` 的状态调用：
     * `mHost.setNotificationAnimate(true)` + `fireAnimateState()`，延时后复位为 false。
     *
     * 必须复刻原方法的前置判断：`mStyleInfo instanceof FlipLinkageClockCategoryInfo`
     * 或 `mAnimationView == null` 时原生直接 return，否则会在原生根本不进入动画态
     * 的场景（联动时钟样式）里凭空打开 `setNotificationAnimate(true)`。
     * 原方法还会先 `mAnimationView.stopAnimation()`，这里一并补上。
     */
    private fun holdAodState(aodView: Any?) {
        val instance = aodView ?: return
        val style = styleInfoField?.get(instance)
        if (style != null && isFlipLinkageStyle(style)) return
        val animationView = animationViewField?.get(instance) ?: return
        stopAnimation(animationView)
        val host = hostField?.get(instance) ?: return
        val setAnimate = resolveSetNotificationAnimate(host) ?: return
        val fireState = resolveFireAnimateState(host)

        setAnimate.invoke(host, true)
        fireState?.invoke(host)

        mainHandler.postDelayed({
            runCatching {
                setAnimate.invoke(host, false)
                fireState?.invoke(host)
            }
        }, HOLD_MS)
    }

    /** 等价的 `styleInfo instanceof FlipLinkageClockCategoryInfo`（按类名链比对，不需额外加载类）。 */
    private fun isFlipLinkageStyle(style: Any): Boolean {
        var current: Class<*>? = style.javaClass
        while (current != null) {
            if (current.name == FLIP_LINKAGE_CLASS) return true
            current = current.superclass
        }
        return false
    }

    /** 复刻原方法里的 `mAnimationView.stopAnimation()`。 */
    private fun stopAnimation(animationView: Any) {
        val method = stopAnimationMethod ?: runCatching {
            animationView.javaClass.getMethod("stopAnimation")
        }.getOrNull()?.also { stopAnimationMethod = it }
        runCatching { method?.invoke(animationView) }
    }

    /** 只匹配 `setNotificationAnimate(boolean)`，避免命中其他同名重载。 */
    private fun resolveSetNotificationAnimate(host: Any): Method? {
        setNotificationAnimateMethod?.let { return it }
        val method = host.javaClass.methods.firstOrNull {
            it.name == "setNotificationAnimate" && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == java.lang.Boolean.TYPE ||
                    it.parameterTypes[0] == java.lang.Boolean::class.java)
        }
        setNotificationAnimateMethod = method
        return method
    }

    private fun resolveFireAnimateState(host: Any): Method? {
        fireAnimateStateMethod?.let { return it }
        val method = host.javaClass.methods.firstOrNull {
            it.name == "fireAnimateState" && it.parameterTypes.isEmpty()
        }
        fireAnimateStateMethod = method
        return method
    }

    /** 沿类继承链查找字段（含 private）。 */
    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                return current!!.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        private const val AOD_VIEW = "com.miui.aod.AODView"
        private const val SHOW_ANIMATE = "showAnimate"

        /** 联动时钟样式：原生 `showAnimate` 遇到该样式直接 return。 */
        private const val FLIP_LINKAGE_CLASS = "com.miui.aod.category.FlipLinkageClockCategoryInfo"

        /** SystemUI 内部插件工厂。 */
        private const val PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
        private const val CREATE_PLUGIN_CONTEXT = "createPluginContext"

        /** MIUI 插件 ClassLoader 注册表（兜底探测用）。 */
        private const val PLUGIN_INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"

        private const val RESOLVE_DELAY_MS = 100L

        /** 动画状态的保持时长，模拟原动画结束时机。 */
        private const val HOLD_MS = 1_200L

        /** 兜底探测轮次：插件一般启动后很快加载，不宜长期占用后台线程。 */
        private const val PROBE_INTERVAL_MS = 2_000L
        private const val MAX_ROUNDS = 6
    }
}
