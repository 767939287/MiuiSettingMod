package com.darknight.miuisettingmod.hook.securitycenter

import android.os.SystemClock
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.dexkit.IDexKitList
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.util.BlockLogger
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 耗电排行「屏幕」一行补上亮屏时长。
 *
 * 原生 `com.miui.powercenter.batteryhistory.i0#c(BatteryData,double,int,int)` 只在
 * `drainType == 5`（屏幕）时多拼一段 `G.p(ctx, usageTime)`，但 HyperOS 的框架侧给的
 * `usageTime` 是 0，`G.p(ctx, 0)` 返回空串，于是只剩「xx mAh | xx%」。
 *
 * 这里在渲染前把亮屏时长写回 `BatteryData.usageTime`，排版与格式化仍走原生
 * `G.p()`，四个布局分支（T 版 / 普通版 / 折叠屏换行）自动生效。
 *
 * 数据源：`miui.securitycenter.powercenter.BatteryStatsUtils#getBatteryStats()`（安全中心
 * 原生使用的框架入口）→ `android.os.BatteryStats#getScreenOnTime(STATS_SINCE_CHARGED)`，
 * 单位毫秒、与 `G.p()` 入参一致。
 */
class PowerRankScreenTimeHook : BaseHook() {

    override val key: String get() = SecurityCenterLoad.KEY_POWER_RANK_SCREEN_TIME

    override fun useDexKit(): Boolean = true

    private var renderMethod: Method? = null
    private var drainTypeField: Field? = null
    private var usageTimeField: Field? = null

    @Volatile
    private var cachedScreenOnMs = 0L

    @Volatile
    private var cachedAtUptime = 0L

    @Volatile
    private var lastLoggedMs = 0L

    @Volatile
    private var sourceFailureLogged = false

    override fun initDexKit(): Boolean {
        // 先按名字 + 签名找；个别版本会改方法名，再退化为「只按签名」。
        val methods = queryRender("render_screen_row_v1", withName = true)
            .takeIf { it.size == 1 }
            ?: queryRender("render_screen_row_v2", withName = false)
        if (methods.size != 1) {
            HookHelper.log("$tag: render candidates=${methods.size}, skip")
            return false
        }
        val method = methods.first().apply { isAccessible = true }
        val dataClass = method.parameterTypes.getOrNull(0)
            ?: return false.also { HookHelper.log("$tag: no BatteryData parameter") }
        val drain = runCatching { dataClass.getDeclaredField("drainType") }.getOrNull()
        val usage = runCatching { dataClass.getDeclaredField("usageTime") }.getOrNull()
        if (drain == null || usage == null) {
            HookHelper.log(
                "$tag: fields missing on ${dataClass.name} " +
                    "(drainType=${drain != null}, usageTime=${usage != null})"
            )
            return false
        }
        drain.isAccessible = true
        usage.isAccessible = true
        renderMethod = method
        drainTypeField = drain
        usageTimeField = usage
        HookHelper.log("$tag: located ${method.declaringClass.name}#$METHOD_NAME")
        return true
    }

    override fun init() {
        val method = renderMethod
        val drain = drainTypeField
        val usage = usageTimeField
        if (method == null || drain == null || usage == null) {
            HookHelper.log("$tag: not resolved, skip")
            return
        }
        HookHelper.hookBefore(method) { param ->
            if (!HookPrefs.getBoolean(key, false)) return@hookBefore
            val data = param.args.getOrNull(0) ?: return@hookBefore
            if (drain.getInt(data) != DRAIN_TYPE_SCREEN) return@hookBefore
            // 框架已经给了时长就不动，避免覆盖原生数值。
            if (usage.getLong(data) > 0L) return@hookBefore
            val screenOn = screenOnTimeMs()
            if (screenOn <= 0L) return@hookBefore
            usage.setLong(data, screenOn)
            if (screenOn != lastLoggedMs) {
                lastLoggedMs = screenOn
                BlockLogger.blocked(SCOPE, "power_rank.screen", method, "usageTime=$screenOn")
            }
        }
    }

    /**
     * 排行行渲染方法候选。
     *
     * @param withName 是否限定方法名；混淆后名字会变，所以留了「只按签名」的退路。
     */
    private fun queryRender(memberKey: String, withName: Boolean): List<Method> =
        optionalMemberList<Method>(
            memberKey,
            IDexKitList { bridge ->
                bridge.findMethod {
                    matcher {
                        if (withName) name(METHOD_NAME)
                        paramCount(4)
                        returnType("void")
                        paramTypes(BATTERY_DATA_CLASS, "double", "int", "int")
                    }
                }
            },
        ).orEmpty()

    /**
     * 亮屏时长（毫秒）。带 30 秒缓存：排行列表滚动会频繁重绑，电量统计变化很慢。
     * 取不到时返回 0，保持原生「不显示时长」的行为。
     */
    private fun screenOnTimeMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedScreenOnMs
        if (cached > 0L && now - cachedAtUptime < CACHE_TTL_MS) return cached
        val value = runCatching {
            val utils = Class.forName(BATTERY_STATS_UTILS, false, target.classLoader)
            val stats = Reflect.callStaticMethod(utils, "getBatteryStats") ?: return@runCatching 0L
            val which = Class.forName("android.os.BatteryStats")
                .getField("STATS_SINCE_CHARGED")
                .getInt(null)
            val screenOn = Class.forName("android.os.BatteryStats")
                .getMethod("getScreenOnTime", Int::class.javaPrimitiveType)
            screenOn.isAccessible = true
            (screenOn.invoke(stats, which) as? Long) ?: 0L
        }.getOrElse { throwable ->
            if (!sourceFailureLogged) {
                sourceFailureLogged = true
                HookHelper.log("$tag: screen-on time source unavailable", throwable)
            }
            0L
        }
        if (value > 0L) {
            cachedScreenOnMs = value
            cachedAtUptime = now
        }
        return value
    }

    companion object {
        private const val METHOD_NAME = "c"
        private const val BATTERY_DATA_CLASS = "com.miui.powercenter.legacypowerrank.BatteryData"
        private const val BATTERY_STATS_UTILS = "miui.securitycenter.powercenter.BatteryStatsUtils"
        private const val DRAIN_TYPE_SCREEN = 5
        private const val SCOPE = "PowerRank"
        private const val CACHE_TTL_MS = 30_000L
    }
}
