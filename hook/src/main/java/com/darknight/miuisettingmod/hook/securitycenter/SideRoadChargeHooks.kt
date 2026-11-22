package com.darknight.miuisettingmod.hook.securitycenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.dexkit.IDexKitList
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.rule.HookSkippedException
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * com.miui.securitycenter（安全中心）进程的 hook 入口。
 *
 * 覆盖「旁路充电」（边玩边充直供）：
 * - 强制开启：插电后自动进入旁路充电；
 * - 阻止熄屏自动停止。
 *
 * `PowerSaveService` 运行在 `:remote` 子进程，libxposed 会按包名注入全部进程。
 */
class SecurityCenterLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        // PowerSaveService 只在 :remote 进程：主进程里既拿不到服务实例，
        // 又会让 DexKit 在多个进程各建一份会话并争写同一缓存文件。
        if (!target.processName.endsWith(PROCESS_SUFFIX)) {
            HookHelper.log("SecurityCenterLoad: skip process ${target.processName}")
            return
        }
        initHook(
            SideRoadChargeForceOnHook(),
            HookPrefs.getBoolean(SideRoadChargeForceOnHook.KEY, false),
        )
        initHook(
            SideRoadChargeBlockStopHook(),
            HookPrefs.getBoolean(SideRoadChargeBlockStopHook.KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.miui.securitycenter"

        /** `PowerSaveService` 所在子进程后缀。 */
        private const val PROCESS_SUFFIX = ":remote"

        /** 电源服务（真名，未混淆），旁路充电所有指令的唯一分发点。 */
        const val SERVICE_CLASS = "com.miui.powercenter.provider.PowerSaveService"

        const val ACTION_START = "com.miui.powercenter.action.STRAT_SIDE_ROAD_CHARGE"
        const val ACTION_STOP = "com.miui.powercenter.action.STOP_SIDE_ROAD_CHARGE"
        const val ACTION_STOP_WITH_UI = "com.miui.powercenter.action.STOP_SIDE_ROAD_CHARGE_WITH_UI"

        /** 机型能力标志：非空才代表该机支持旁路充电（`FeatureUtils.r()`）。 */
        const val PROP_SUPPORT = "persist.vendor.smart.bypass.plus"
    }
}

/**
 * 强制开启旁路充电。
 *
 * 不反射调用混淆类（管理器随版本改名：手机版 `p120i7.a`、平板版 `p134j7.a`），
 * 而是复用系统自身的指令通道：`PowerSaveService` 收到
 * [SecurityCenterLoad.ACTION_START] 后会走 `SideRoadChargeManager#C()`，
 * 由它写状态位、注册监听并评估当前场景。
 *
 * - `onCreate` 之后补发一次（此时管理器已初始化完毕）；
 * - 监听 `BATTERY_CHANGED`，插电时补发（拔插后重新进入旁路充电）；
 * - 模块开关关闭时发 `STOP_SIDE_ROAD_CHARGE_WITH_UI`，把系统状态位还原，
 *   不把「已开启」的痕迹留在系统设置里。
 *
 * **安全边界（重要）**：这里只把系统自己的开关置为「开」，真正的供电切换仍由
 * `SideRoadChargeManager#e(int)` 判定 —— 电量低于门槛 `mTriggerSoc`、当前场景不在
 * 白名单 `r(scene)`、前台是桌面 / 副屏时**依旧不会开启**，与本机原生行为完全一致。
 * 不写 `smart_chg` 位、不伪造机型能力位、不绕过任何原生保护。
 *
 * 机型不支持（`persist.vendor.smart.bypass.plus` 为空）时只记日志、不伪造属性：
 * 伪造能力位会让不支持硬件的机型跑完整链路，底层必然拒绝，徒增异常。
 */
class SideRoadChargeForceOnHook : BaseHook() {

    override val key: String = KEY

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var listenerRegistered = false

    @Volatile
    private var lastRequestAt = 0L

    @Volatile
    private var lastEnabled = false

    /** 本模块是否已发出过开启指令（用于关闭时精确还原，不动用户手动开的状态）。 */
    @Volatile
    private var enabledByModule = false

    @Volatile
    private var unsupportedWarned = false

    /** 用户（或系统 UI）主动停止过：在拔电 / 重新打开模块开关前不再自动重开。 */
    @Volatile
    private var userStopped = false

    @Volatile
    private var appContext: Context? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            // 拔电即视为一次完整充电周期结束，恢复自动开启资格。
            if (plugged == 0) {
                userStopped = false
                return
            }
            if (!HookPrefs.getBoolean(KEY, false)) return
            if (!isWiredCharging(plugged)) return
            requestStart(ctx)
        }
    }

    override fun init() {
        val serviceClass = Reflect.findClassIfExists(
            SecurityCenterLoad.SERVICE_CLASS,
            target.classLoader,
        )
        if (serviceClass == null) {
            throw HookSkippedException("${SecurityCenterLoad.SERVICE_CLASS} not found")
        }

        val onCreate = Reflect.findMethodIfExists(serviceClass, "onCreate")
        if (onCreate == null) {
            throw HookSkippedException("PowerSaveService#onCreate not found")
        }
        HookHelper.hookAfter(onCreate) { param ->
            val context = param.thisObject as? Context ?: return@hookAfter
            val app = context.applicationContext ?: context
            appContext = app
            lastEnabled = HookPrefs.getBoolean(KEY, false)
            registerBatteryReceiver(app)
            registerPrefsListener()
            if (lastEnabled) requestStart(context)
        }
        HookHelper.log("$tag: PowerSaveService#onCreate hooked")

        val onStartCommand = Reflect.findMethodIfExists(
            serviceClass,
            "onStartCommand",
            Intent::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        if (onStartCommand == null) {
            throw HookSkippedException("PowerSaveService#onStartCommand not found")
        }
        // 记录用户主动停止（通知栏 / 安全中心 UI / 双卡同步指令），之后不再自动重开。
        HookHelper.hookBefore(onStartCommand) { param ->
            val action = (param.args.getOrNull(0) as? Intent)?.action ?: return@hookBefore
            if (action == SecurityCenterLoad.ACTION_STOP ||
                action == SecurityCenterLoad.ACTION_STOP_WITH_UI
            ) {
                userStopped = true
                HookHelper.log("$tag: user stop observed, auto start paused until unplug")
            }
        }
        HookHelper.hookAfter(onStartCommand) { param ->
            val context = param.thisObject as? Context ?: return@hookAfter
            if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
            // 自己发出的开启指令会再次进来，直接放行避免递归。
            val intent = param.args.getOrNull(0) as? Intent
            if (intent?.action == SecurityCenterLoad.ACTION_START) return@hookAfter
            // 延迟到 handler 队列外再发，避免在 onStartCommand 调用栈内重入 startService。
            mainHandler.postDelayed(
                { runCatching { requestStart(context) } },
                RESTART_DELAY_MS,
            )
        }
        HookHelper.log("$tag: PowerSaveService#onStartCommand hooked")
    }

    private fun registerBatteryReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // 显式声明不导出：避免其它应用向我伪造电池广播；
        // 老框架不接受该重载时退回两参版本（BATTERY_CHANGED 是系统保护广播，两参可用）。
        val ok = runCatching {
            context.registerReceiver(
                batteryReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
                null,
            )
        }.recoverCatching {
            context.registerReceiver(batteryReceiver, filter)
        }.isSuccess
        if (ok) {
            receiverRegistered = true
            HookHelper.log("$tag: battery receiver registered")
        } else {
            HookHelper.log("$tag: register battery receiver failed")
        }
    }

    /** 监听配置变化：打开即时请求、关闭时还原系统状态位。 */
    private fun registerPrefsListener() {
        if (listenerRegistered) return
        listenerRegistered = true
        HookPrefs.registerListener { onPrefsChanged() }
    }

    private fun onPrefsChanged() {
        val context = appContext ?: return
        val enabled = HookPrefs.getBoolean(KEY, false)
        if (enabled == lastEnabled) return
        lastEnabled = enabled
        if (enabled) {
            // 用户重新打开本开关，视为重新授权自动开启。
            userStopped = false
            requestStart(context)
        } else {
            restore(context)
        }
    }

    /**
     * 关闭模块开关时还原：发原生「带 UI 停止」指令，令管理器把状态位写回 0
     * 并清理监听。只有本模块发过开启指令时才执行，避免误伤用户自己开的旁路充电。
     */
    private fun restore(context: Context) {
        if (!enabledByModule) return
        enabledByModule = false
        runCatching {
            val intent = Intent().apply {
                setClassName(context.packageName, SecurityCenterLoad.SERVICE_CLASS)
                action = SecurityCenterLoad.ACTION_STOP_WITH_UI
            }
            context.startService(intent)
            HookHelper.log("$tag: module disabled, request restore")
        }.onFailure { HookHelper.log("$tag: restore request failed", it) }
    }

    private fun requestStart(context: Context) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        if (userStopped) return
        if (!isSupported()) {
            if (!unsupportedWarned) {
                unsupportedWarned = true
                HookHelper.log(
                    "$tag: ${SecurityCenterLoad.PROP_SUPPORT} is empty, " +
                        "this device does not report side road charge support"
                )
            }
            return
        }
        if (!isWiredCharging(currentPlugged(context))) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequestAt < THROTTLE_MS) return
        lastRequestAt = now
        runCatching {
            val intent = Intent().apply {
                setClassName(context.packageName, SecurityCenterLoad.SERVICE_CLASS)
                action = SecurityCenterLoad.ACTION_START
            }
            context.startService(intent)
            enabledByModule = true
            HookHelper.log("$tag: request side road charge start")
        }.onFailure { HookHelper.log("$tag: startService failed", it) }
    }

    /** 机型能力位（`FeatureUtils.r()` 的同一个判断）。 */
    private fun isSupported(): Boolean {
        val value = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, SecurityCenterLoad.PROP_SUPPORT) as? String
        }.getOrNull()
        return !value.isNullOrEmpty()
    }

    private fun currentPlugged(context: Context): Int = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            ?: 0
    }.getOrDefault(0)

    /** 有线供电；无线充电（4）不走旁路逻辑（系统原逻辑同样排除）。 */
    private fun isWiredCharging(plugged: Int): Boolean =
        plugged != 0 && plugged != BatteryManager.BATTERY_PLUGGED_WIRELESS

    companion object {
        const val KEY = "side_road_charge_force_on"

        /** 电池广播约 10 秒一次，这里限流避免频繁 startService。 */
        private const val THROTTLE_MS = 60_000L

        private const val RESTART_DELAY_MS = 500L
    }
}

/**
 * 阻止「熄屏自动停止」旁路充电。
 *
 * `SideRoadChargeManager` 注册了 `SCREEN_ON` / `SCREEN_OFF` 广播，收到熄屏后往内部
 * handler 发 `what = 11`，最终执行 `j()`。已核验 `j()` 只做两件事：
 * `PowerNoticeUtils#q()` 发一条「已停止」提示，以及写 `smart_chg` 关掉 0x400 旁路位
 * —— 「直供中」通知不受影响，所以吞掉本条消息不会留下错误通知或脏状态。
 *
 * 这里只吞掉这一条消息 —— 拔电收尾（`what = 6`）、用户主动停止（`what = 7` 与
 * `STOP_*` 指令）全部放行，避免状态错乱或用户无法关闭。
 *
 * 管理器类名随版本混淆（`p120i7.a` / `p134j7.a`），因此用 DexKit 按日志 TAG
 * `SideRoadChargeManager` 定位（管理器与内部 Handler 都带该字符串），再取含
 * `handleMessage` 的那个内部类。
 */
class SideRoadChargeBlockStopHook : BaseHook() {

    override val key: String = KEY

    private var handleMessage: Method? = null

    override fun useDexKit(): Boolean = true

    override fun initDexKit(): Boolean {
        val classes = optionalMemberList<Class<*>>(
            MEMBER_KEY,
            IDexKitList { bridge ->
                bridge.findClass { matcher { usingStrings(LOG_TAG) } }
            },
        )
        if (classes.isEmpty()) {
            HookHelper.log("$tag: DexKit found no class using \"$LOG_TAG\"")
            return false
        }
        val handlerClass = classes.firstOrNull { clazz ->
            clazz.declaredMethods.any { method ->
                method.name == HANDLE_MESSAGE &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Message::class.java
            }
        }
        if (handlerClass == null) {
            HookHelper.log(
                "$tag: no handler among ${classes.map { it.name }}",
            )
            return false
        }
        val method = Reflect.findMethodIfExists(handlerClass, HANDLE_MESSAGE, Message::class.java)
        if (method == null) {
            HookHelper.log("$tag: ${handlerClass.name}#$HANDLE_MESSAGE not found")
            return false
        }
        handleMessage = method
        HookHelper.log("$tag: located ${handlerClass.name}#$HANDLE_MESSAGE")
        return true
    }

    override fun init() {
        val method = handleMessage
        if (method == null) {
            HookHelper.log("$tag: handleMessage not resolved, skip")
            return
        }
        HookHelper.hookBefore(method) { param ->
            val message = param.args.getOrNull(0) as? Message ?: return@hookBefore
            if (message.what != MSG_SCREEN_OFF) return@hookBefore
            if (!HookPrefs.getBoolean(KEY, false)) return@hookBefore
            // 双保险：消息号语义变化时（其它 ROM 改 case 映射）不会误拦别的事件。
            if (isInteractive() != false) return@hookBefore
            HookHelper.log("$tag: block screen-off side road stop (what=${message.what})")
            param.setResultValue(null)
        }
    }

    /**
     * 当前是否真正处于熄屏状态。
     *
     * 返回 `null` 表示无法判断（取不到 Application / PowerManager）。此时**不拦截**，
     * 保持系统原生行为 —— 涉及供电链路时宁可功能不生效，也不能误伤其它消息。
     * 消息号 11 与 `SCREEN_OFF` 的对应关系已在手机版与平板版双向核验一致。
     */
    private fun isInteractive(): Boolean? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
            ?: return@runCatching null
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching null
        power.isInteractive
    }.getOrNull()

    companion object {
        const val KEY = "side_road_charge_block_stop"

        private const val MEMBER_KEY = "handler_class"

        /** 管理器与内部 Handler 共用的日志 TAG，用于 DexKit 定位。 */
        private const val LOG_TAG = "SideRoadChargeManager"

        private const val HANDLE_MESSAGE = "handleMessage"

        /** `SCREEN_OFF` 对应的消息号（手机版与平板版一致）。 */
        private const val MSG_SCREEN_OFF = 11
    }
}
