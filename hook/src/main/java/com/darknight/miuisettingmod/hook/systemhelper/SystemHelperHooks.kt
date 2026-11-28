package com.darknight.miuisettingmod.hook.systemhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.dexkit.IDexKitList
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.util.BlockLogger
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * com.mobiletools.systemhelper（运营商统计组件）的 hook 入口。
 *
 * 只处理遥测采集链路：许可判断、加密上报、应用统计线程与 7 个专用广播接收器。
 */
class SystemHelperLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            SystemHelperTelemetryBlockHook(),
            HookPrefs.getBoolean(SystemHelperTelemetryBlockHook.KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.mobiletools.systemhelper"
    }
}

/**
 * 阻断 SystemHelper 的运营商遥测上报。
 *
 * 四组目标（均按 DEX 特征定位，参考 `xsofprivacy` 的匹配条件）：
 * - `helper.policy_gate`：读取「运营商服务改进计划」许可的静态布尔方法 → 返回 false；
 * - `helper.encrypted_post`：`HttpHelper` 里走 `application/encrypted-json` 的上报方法
 *   （含新版的未加密兜底发送）→ 返回 false；
 * - `helper.app_usage`：`QueryAppInfoThread#run`（枚举已安装应用，`usagestats`）；
 * - `helper.receiver.*`：7 个专用广播接收器（类名未混淆，直接反射定位）。
 *
 * 注意：策略门禁只改变本进程内的判断结果，不会修改系统里保存的许可设置。
 */
class SystemHelperTelemetryBlockHook : BaseHook() {

    override val key: String get() = KEY

    override fun useDexKit(): Boolean = true

    private class Target(val group: String, val method: Method, val falseResult: Boolean)

    private val targets = mutableListOf<Target>()

    override fun initDexKit(): Boolean {
        targets.clear()
        resolvePolicyGate()
        resolveEncryptedPost()
        resolveAppUsage()
        resolveReceivers()
        HookHelper.log("$tag: ${targets.size} target(s) resolved")
        return targets.isNotEmpty()
    }

    override fun init() {
        if (targets.isEmpty()) {
            HookHelper.log("$tag: no target resolved, skip")
            return
        }
        for (target in targets) {
            runCatching {
                target.method.isAccessible = true
                val method = target.method
                HookHelper.hookBefore(method) { param ->
                    if (!HookPrefs.getBoolean(key, false)) return@hookBefore
                    BlockLogger.blocked(SCOPE, target.group, method)
                    param.setResultValue(if (target.falseResult) false else null)
                }
            }.onSuccess {
                BlockLogger.group(SCOPE, target.group, 1)
            }.onFailure {
                HookHelper.log("$tag: install failed ${target.group}", it)
            }
        }
    }

    // ---------------- 许可门禁 ----------------

    private fun resolvePolicyGate() {
        val methods = findMethods("policy_gate_v1") { m ->
            m.returnType("boolean")
            m.paramTypes("android.content.Context")
            m.usingStrings(
                "operator_service_improve_plan",
                "device_provisioned",
                "RegisterUtils",
                "checkUserPermission:",
            )
            m.invokeMethods {
                add {
                    declaredClass("android.provider.Settings\$Secure")
                    name("getInt")
                }
                add {
                    declaredClass("android.provider.Settings\$Global")
                    name("getInt")
                }
                matchType = MatchType.Contains
            }
        }
        addSingle("helper.policy_gate", methods, falseResult = true)
    }

    // ---------------- 加密上报 ----------------

    private fun resolveEncryptedPost() {
        val httpClass = findClasses("http_helper_class_v1") { c ->
            c.usingStrings("HttpHelper", "sendHttpRequest() path = ", "application/encrypted-json")
        }.singleOrNull()
        if (httpClass == null) {
            BlockLogger.skip(SCOPE, "helper.encrypted_post", "telemetry class not unique")
            return
        }
        val encrypted = findMethods("encrypted_send_v1") { m ->
            m.declaredClass(httpClass.name)
            m.returnType("boolean")
            m.paramTypes("java.lang.String", "byte[]", "byte[]")
            m.usingStrings("application/encrypted-json")
        }
        val fallback = findMethods("fallback_send_v1") { m ->
            m.declaredClass(httpClass.name)
            m.returnType("boolean")
            m.paramTypes("java.lang.String", "byte[]")
        }
        if (encrypted.isEmpty()) {
            BlockLogger.skip(SCOPE, "helper.encrypted_post", "encrypted sender not found")
            return
        }
        encrypted.forEach { targets += Target("helper.encrypted_post", it, falseResult = true) }
        fallback.forEach { targets += Target("helper.encrypted_post.fallback", it, falseResult = true) }
    }

    // ---------------- 应用统计线程 ----------------

    private fun resolveAppUsage() {
        val methods = findMethods("app_usage_thread_v1") { m ->
            m.name("run")
            m.returnType("void")
            m.paramCount(0)
            m.usingStrings("usagestats")
            m.invokeMethods {
                add {
                    declaredClass("android.content.pm.PackageManager")
                    name("getInstalledApplications")
                }
                matchType = MatchType.Contains
            }
            m.declaredClass {
                superClass("java.lang.Thread")
                usingStrings(
                    "QueryAppInfoThread",
                    "queryAppUsageStatsDescend  isUserPermissionOpen :",
                )
            }
        }
        addSingle("helper.app_usage", methods, falseResult = false)
    }

    // ---------------- 专用广播接收器 ----------------

    private fun resolveReceivers() {
        var found = 0
        for (name in RECEIVERS) {
            val clazz = Reflect.findClassIfExists("$RECEIVER_PACKAGE.$name", target.classLoader)
            if (clazz == null || !BroadcastReceiver::class.java.isAssignableFrom(clazz)) {
                BlockLogger.skip(SCOPE, "helper.receiver.$name", "class not found")
                continue
            }
            val method = clazz.declaredMethods.firstOrNull { candidate ->
                candidate.name == "onReceive" &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == Context::class.java &&
                    candidate.parameterTypes[1] == Intent::class.java
            }
            if (method == null) {
                BlockLogger.skip(SCOPE, "helper.receiver.$name", "onReceive not found")
                continue
            }
            targets += Target("helper.receiver.$name", method, falseResult = false)
            found++
        }
        HookHelper.log("$tag: ${found}/${RECEIVERS.size} receiver(s) resolved")
    }

    private fun addSingle(group: String, methods: List<Method>, falseResult: Boolean) {
        val method = methods.singleOrNull()
        if (method == null) {
            BlockLogger.skip(SCOPE, group, "candidates=${methods.size}")
            return
        }
        targets += Target(group, method, falseResult)
    }

    private fun findClasses(key: String, build: (ClassMatcher) -> Unit): List<Class<*>> =
        optionalMemberList<Class<*>>(
            key,
            IDexKitList { bridge -> bridge.findClass { matcher { build(this) } } },
        ).orEmpty()

    private fun findMethods(key: String, build: (MethodMatcher) -> Unit): List<Method> =
        optionalMemberList<Method>(
            key,
            IDexKitList { bridge -> bridge.findMethod { matcher { build(this) } } },
        ).orEmpty()

    companion object {
        const val KEY = "systemhelper_telemetry_disable"

        private const val SCOPE = "SystemHelper"
        private const val RECEIVER_PACKAGE = "com.mobiletools.systemhelper.control"

        private val RECEIVERS = listOf(
            "AppInfoRegisterReceiver",
            "ScheduleRegisterReceiver",
            "GetSignalInfoReceiver",
            "SignalInfoRegisterReceiver",
            "OnBootReceiver",
            "SIMStateReceiver",
            "ConnectStateReceiver",
        )
    }
}
