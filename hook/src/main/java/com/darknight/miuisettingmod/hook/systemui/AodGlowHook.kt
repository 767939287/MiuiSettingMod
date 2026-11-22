package com.darknight.miuisettingmod.hook.systemui

import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.device.DeviceType
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关闭息屏显示（AOD）来通知时的波纹 / 粒子光效，通知图标与时钟照常显示。
 *
 * ## 为什么不能直接用 `target.classLoader`
 * `com.miui.aod.AODView` 运行在 `com.android.systemui` 进程，但类由 SystemUI 的
 * **插件 ClassLoader** 持有，主 ClassLoader 里 `Class.forName` 拿不到。
 *
 * 获取插件 ClassLoader 的三层策略：
 * 1. 主路径：hook `PluginInstance$PluginFactory#createPluginContext()` 返回值
 *    （`PluginContextWrapper` 是 `ContextWrapper`，其 `classLoader` 即插件 loader）；
 * 2. 兜底一：用主 ClassLoader 直接尝试加载（插件 dex 挂在主 loader 的场景）；
 * 3. 兜底二：每 3 秒遍历 `PluginInstanceInjector.sClassLoaders` 与全部线程的
 *    `contextClassLoader`，最多 40 轮。
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

    /** 兜底探测循环是否已启动（每个进程一次）。 */
    private val probing = AtomicBoolean(false)

    /** 主线程 Handler，用于延时探测与复位动画状态。 */
    private var mainHandler: Handler? = null

    override fun init() {
        val loader = target.classLoader
        val handler = Handler(Looper.getMainLooper())
        mainHandler = handler

        // 主路径：插件加载时直接拿到插件 ClassLoader。
        hookPluginFactory(loader, handler)
        // 兜底一：插件 dex 若挂在主 ClassLoader 上，可直接取。
        loader?.let { probe(it) }
        // 兜底二：定时遍历插件注册表与线程上下文（只读）。
        startProbeLoop(loader, handler)
    }

    /** hook `PluginInstance$PluginFactory#createPluginContext()`，从返回值取插件 ClassLoader。 */
    private fun hookPluginFactory(loader: ClassLoader?, handler: Handler) {
        runCatching {
            val factory = Reflect.findClass(PLUGIN_FACTORY, loader)
            val method = factory.getDeclaredMethod(CREATE_PLUGIN_CONTEXT)
            method.isAccessible = true
            HookHelper.intercept(method, mode = ExceptionMode.PASSTHROUGH) { chain ->
                val result = chain.proceed()
                runCatching {
                    val pluginLoader = (result as? ContextWrapper)?.classLoader
                    if (pluginLoader != null) scheduleProbe(pluginLoader, handler)
                }.onFailure { HookHelper.log("$tag: plugin loader probe failed", it) }
                result
            }
            HookHelper.log("$tag: $PLUGIN_FACTORY#$CREATE_PLUGIN_CONTEXT hooked")
        }.onFailure { HookHelper.log("$tag: hook $PLUGIN_FACTORY failed", it) }
    }

    /** 延迟后用插件 ClassLoader 取 `AODView`（避开插件上下文创建途中加载类）。 */
    private fun scheduleProbe(loader: ClassLoader, handler: Handler) {
        if (installed.get()) return
        handler.postDelayed({
            if (installed.get()) return@postDelayed
            val clazz = runCatching { Reflect.findClass(AOD_VIEW, loader) }.getOrNull()
            if (clazz == null) {
                HookHelper.log("$tag: $AOD_VIEW not found via plugin loader")
                return@postDelayed
            }
            install(clazz)
        }, RESOLVE_DELAY_MS)
    }

    /** 兜底：定时遍历 MIUI 插件注册表与线程上下文 ClassLoader。 */
    private fun startProbeLoop(loader: ClassLoader?, handler: Handler) {
        if (!probing.compareAndSet(false, true)) return
        var round = 0
        val task = object : Runnable {
            override fun run() {
                if (installed.get() || round >= MAX_ROUNDS) {
                    HookHelper.log("$tag: probe finished, installed=${installed.get()} rounds=$round")
                    return
                }
                round++
                runCatching {
                    val injector = Reflect.findClass(PLUGIN_INJECTOR, loader)
                    val field = injector.getDeclaredField("sClassLoaders")
                    field.isAccessible = true
                    val map = field.get(null) as? Map<*, *> ?: return@runCatching
                    for (value in map.values) {
                        (value as? ClassLoader)?.let { probe(it) }
                    }
                }.onFailure {
                    if (round == 1) {
                        HookHelper.log("$tag: read plugin registry failed", it)
                    }
                }

                runCatching {
                    for (thread in Thread.getAllStackTraces().keys) {
                        thread.contextClassLoader?.let { probe(it) }
                    }
                }

                handler.postDelayed(this, PROBE_INTERVAL_MS)
            }
        }
        handler.postDelayed(task, PROBE_INTERVAL_MS)
    }

    /** 尝试用指定 ClassLoader 取 `AODView`，取到就挂钩。 */
    private fun probe(loader: ClassLoader) {
        if (installed.get()) return
        val clazz = runCatching { Reflect.findClass(AOD_VIEW, loader) }.getOrNull() ?: return
        install(clazz)
    }

    private fun install(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val method = clazz.getDeclaredMethod(SHOW_ANIMATE, String::class.java)
            method.isAccessible = true
            HookHelper.intercept(method, mode = ExceptionMode.PASSTHROUGH) { chain ->
                // 不创建 AnimationDrawer、不播放动画，但补上原逻辑里的 AOD 显示状态，
                // 否则 SystemUI 认为没有通知动画，来通知时 AOD 不亮。
                holdAodState(chain.thisObject)
                null
            }
            HookHelper.log("$tag: $AOD_VIEW#$SHOW_ANIMATE hooked (glow disabled)")
        }.onFailure {
            installed.set(false)
            HookHelper.log("$tag: hook $AOD_VIEW#$SHOW_ANIMATE failed", it)
        }
    }

    /**
     * 复刻 `AODView.showAnimate` 中 `drawer != null` 分支的状态调用：
     * `mHost.setNotificationAnimate(true)` + `fireAnimateState()`，延时后复位为 false。
     */
    private fun holdAodState(aodView: Any?) {
        val instance = aodView ?: return
        runCatching {
            val hostField = findField(instance.javaClass, "mHost") ?: run {
                HookHelper.log("$tag: mHost field not found")
                return@runCatching
            }
            val host = hostField.get(instance) ?: return@runCatching
            val setAnimate = host.javaClass.methods.firstOrNull {
                it.name == "setNotificationAnimate" && it.parameterTypes.size == 1
            } ?: return@runCatching
            val fireState = host.javaClass.methods.firstOrNull { it.name == "fireAnimateState" }

            setAnimate.invoke(host, true)
            fireState?.invoke(host)

            mainHandler?.postDelayed({
                runCatching {
                    setAnimate.invoke(host, false)
                    fireState?.invoke(host)
                }
            }, HOLD_MS)
        }.onFailure { HookHelper.log("$tag: hold AOD state failed", it) }
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

        /** SystemUI 内部插件工厂。 */
        private const val PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
        private const val CREATE_PLUGIN_CONTEXT = "createPluginContext"

        /** MIUI 插件 ClassLoader 注册表（兜底探测用）。 */
        private const val PLUGIN_INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"

        private const val RESOLVE_DELAY_MS = 100L

        /** 动画状态的保持时长，模拟原动画结束时机。 */
        private const val HOLD_MS = 1_200L

        private const val PROBE_INTERVAL_MS = 3_000L
        private const val MAX_ROUNDS = 40
    }
}
