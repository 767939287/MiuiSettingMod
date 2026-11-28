package com.darknight.miuisettingmod.hook.securitycenter

import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.dexkit.IDexKitList
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.util.BlockLogger
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * 阻断小米反诈监控（XSOF）。
 *
 * 参考 `io.github.yunyizea.xsofprivacy` 的识别思路，但只用 DexKit 的语义特征定位，
 * 不硬编码混淆后的类名 / 方法名（`Ra.k`、`Ta.e` 这类每版都会变）。
 *
 * 覆盖四类入口：
 * 1. `listeners`：采集服务的监听注册（截图 / 电话 / SIM / 飞行模式 / 呼叫转移 /
 *    AppOps / 网络状态）与配套注销，成对处理；
 * 2. `storage`：`fraud_detection_data.db` 的所有 insert / update；
 * 3. `provider`：`FraudDetectionProvider` 内部写入入口；
 * 4. `api`：`xsof_fraud_detection_*` 对外查询，直接以 `(null, 1017)` 拒绝，
 *    不伪造「零风险」评分。
 *
 * 候选取不到或不唯一时整组跳过并记日志，不猜。
 */
class XsofBlockHook : BaseHook() {

    override val key: String get() = SecurityCenterLoad.KEY_XSOF_BLOCK

    override fun useDexKit(): Boolean = true

    private class Group(
        val name: String,
        val methods: List<Method>,
        /** 回调参数下标；>= 0 表示这是对外查询接口，需要先回拒绝码。 */
        val callbackIndex: Int = -1,
        val reply: Method? = null,
    )

    private val groups = mutableListOf<Group>()

    override fun initDexKit(): Boolean {
        groups.clear()

        val service = findClasses("collector_service_v1") { c ->
            c.superClass("android.app.Service")
            c.usingStrings(EVENT_SCREENSHOT, EVENT_PHONE, EVENT_SIM)
        }.singleOrNull()
        if (service == null) {
            BlockLogger.skip(SCOPE, "listeners", "collector service not unique")
        } else {
            resolveListeners(service)
        }

        val database = findClasses("fraud_database_v1") { c ->
            c.superClass("android.database.sqlite.SQLiteOpenHelper")
            c.usingStrings("fraud_detection_data.db", "/securitycenter/", "screenshot", "install_app")
        }.singleOrNull()
        if (database == null) {
            BlockLogger.skip(SCOPE, "storage", "fraud database not unique")
            BlockLogger.skip(SCOPE, "provider.ingress", "fraud database not unique")
        } else {
            resolveStorage(database)
            resolveProvider(database)
        }

        resolveApi()

        val targets = groups.sumOf { it.methods.size }
        HookHelper.log("$tag: resolved ${groups.size} group(s), $targets target(s)")
        return groups.isNotEmpty()
    }

    // ---------------- 监听注册 ----------------

    private fun resolveListeners(service: Class<*>) {
        val serviceName = service.name
        // onCreate 里创建的 IntentFilter：截图 / 电话 / SIM / 飞行模式 / 安全中心网络状态。
        val registrations = findMethods("listeners_register_v1") { m ->
            m.declaredClass(serviceName)
            m.returnType("void")
            m.paramCount(0)
            m.invokeMethods {
                add { declaredClass("android.content.IntentFilter") }
                matchType = MatchType.Contains
            }
            m.callerMethods {
                add {
                    declaredClass(serviceName)
                    name("onCreate")
                }
                matchType = MatchType.Contains
            }
        }
        val appOps = findMethods("listeners_appops_v1") { m ->
            m.declaredClass(serviceName)
            m.returnType("void")
            m.paramCount(0)
            m.usingStrings("MIUI:10042", "appops")
        }
        val callForwarding = findMethods("listeners_call_forwarding_v1") { m ->
            m.declaredClass(serviceName)
            m.returnType("void")
            m.paramTypes("android.content.Context")
            m.usingStrings("getCallForwarding", "android.telephony.TelephonyManager\$CallForwardingInfoCallback")
        }
        val cleanup = findMethods("listeners_cleanup_v1") { m ->
            m.declaredClass(serviceName)
            m.returnType("void")
            m.paramCount(0)
            m.invokeMethods {
                add {
                    declaredClass("android.content.Context")
                    name("unregisterReceiver")
                }
                matchType = MatchType.Contains
            }
            m.callerMethods {
                add {
                    declaredClass(serviceName)
                    name("onDestroy")
                }
                matchType = MatchType.Contains
            }
        }.singleOrNull()
        if (registrations.size != 5 || appOps.size != 1 || callForwarding.size != 1 || cleanup == null) {
            BlockLogger.skip(
                SCOPE,
                "listeners",
                "shape mismatch: register=${registrations.size}, appops=${appOps.size}, " +
                    "callForwarding=${callForwarding.size}, cleanup=${cleanup != null}"
            )
            return
        }
        val networkRegister = findMethods("listeners_network_v1") { m ->
            m.returnType("void")
            m.paramTypes("android.content.Context")
            m.usingStrings("android.net.conn.CONNECTIVITY_CHANGE")
            m.invokeMethods {
                add {
                    declaredClass("android.content.Context")
                    name("registerReceiver")
                }
                matchType = MatchType.Contains
            }
            m.callerMethods {
                add {
                    declaredClass(serviceName)
                    name("onCreate")
                }
                matchType = MatchType.Contains
            }
        }.singleOrNull()
        if (networkRegister == null) {
            BlockLogger.skip(SCOPE, "listeners", "network registration not unique")
            return
        }
        val networkClass = networkRegister.declaringClass.name
        val networkUnregister = findMethods("listeners_network_unreg_v1") { m ->
            m.declaredClass(networkClass)
            m.returnType("void")
            m.paramTypes("android.content.Context")
            m.invokeMethods {
                add {
                    declaredClass("android.content.Context")
                    name("unregisterReceiver")
                }
                matchType = MatchType.Contains
            }
            m.callerMethods {
                add {
                    declaredClass(serviceName)
                    name(cleanup.name)
                }
                matchType = MatchType.Contains
            }
        }.singleOrNull()
        if (networkUnregister == null) {
            BlockLogger.skip(SCOPE, "listeners", "network unregistration not unique")
            return
        }
        val targets = (registrations + appOps + callForwarding + listOf(cleanup, networkRegister, networkUnregister))
            .distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString(",")}" }
        if (targets.size != 10) {
            BlockLogger.skip(SCOPE, "listeners", "distinct targets=${targets.size} (expected 10)")
            return
        }
        groups += Group("listeners", targets)
    }

    // ---------------- 本地记录写入 ----------------

    private fun resolveStorage(database: Class<*>) {
        val inserts = findMethods("storage_insert_v1") { m ->
            m.declaredClass(database.name)
            m.returnType("void")
            m.invokeMethods {
                add {
                    declaredClass("android.database.sqlite.SQLiteDatabase")
                    name("insert")
                }
                matchType = MatchType.Contains
            }
        }
        val updates = findMethods("storage_update_v1") { m ->
            m.declaredClass(database.name)
            m.returnType("void")
            m.invokeMethods {
                add {
                    declaredClass("android.database.sqlite.SQLiteDatabase")
                    name("update")
                }
                matchType = MatchType.Contains
            }
        }
        if (inserts.isEmpty() && updates.isEmpty()) {
            BlockLogger.skip(SCOPE, "storage", "no insert/update method")
            return
        }
        val note = buildString {
            append("insert=").append(inserts.size)
            append(", update=").append(updates.size)
            if (inserts.size != EXPECTED_INSERT_COUNT || updates.size != EXPECTED_UPDATE_COUNT) {
                append(" (expected ").append(EXPECTED_INSERT_COUNT)
                append('/').append(EXPECTED_UPDATE_COUNT).append(')')
            }
        }
        groups += Group("storage", (inserts + updates).distinct())
        HookHelper.log("$tag: storage resolved ($note)")
    }

    // ---------------- Provider 写入 ----------------

    private fun resolveProvider(database: Class<*>) {
        val provider = findClasses("fraud_provider_v1") { c ->
            c.superClass("android.content.ContentProvider")
            c.usingStrings(
                "com.xiaomi.security.xsof.FraudDetectionProvider",
                "install_app",
                "call_forwarding",
                "fraud_url",
            )
        }.singleOrNull()
        if (provider == null) {
            BlockLogger.skip(SCOPE, "provider.ingress", "provider class not unique")
            return
        }
        val writers = findMethods("provider_writers_v1") { m ->
            m.declaredClass(provider.name)
            m.returnType("void")
            m.paramTypes("android.content.ContentValues")
            m.callerMethods {
                add {
                    declaredClass(provider.name)
                    name("insert")
                }
                matchType = MatchType.Contains
            }
            m.invokeMethods {
                add { declaredClass(database.name) }
                matchType = MatchType.Contains
            }
        }
        if (writers.size != EXPECTED_PROVIDER_WRITERS) {
            BlockLogger.skip(
                SCOPE,
                "provider.ingress",
                "writers=${writers.size} (expected $EXPECTED_PROVIDER_WRITERS)"
            )
            return
        }
        groups += Group("provider.ingress", writers)
    }

    // ---------------- 对外查询 ----------------

    private fun resolveApi() {
        for ((kind, index) in API_KINDS) {
            val found = findMethods("api_${kind}_v1") { m ->
                m.returnType("void")
                m.usingStrings(
                    "xsof_fraud_detection_$kind",
                    "Call API 004_$index from package : ",
                )
                m.usingNumbers(REJECT_CODE)
                m.invokeMethods {
                    add {
                        declaredClass("android.os.Binder")
                        name("getCallingPid")
                    }
                    matchType = MatchType.Contains
                }
            }
            if (found.size != 1) {
                BlockLogger.skip(SCOPE, "api.$kind", "candidates=${found.size}")
                continue
            }
            val method = found.first().apply { isAccessible = true }
            val binding = findRejectBinding(method)
            if (binding == null) {
                BlockLogger.skip(SCOPE, "api.$kind", "callback signature not found")
                continue
            }
            groups += Group("api.$kind", listOf(method), binding.first, binding.second)
        }
    }

    /**
     * 找到查询方法的回调参数与其 `(String, int)` 回复方法。
     * 返回 `callback 参数下标 to 回复方法`。
     */
    private fun findRejectBinding(method: Method): Pair<Int, Method>? {
        for (index in method.parameterTypes.indices.reversed()) {
            val type = method.parameterTypes[index]
            if (!type.isInterface) continue
            val reply = type.declaredMethods.firstOrNull { candidate ->
                candidate.returnType == Void.TYPE &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == String::class.java &&
                    candidate.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: continue
            reply.isAccessible = true
            return index to reply
        }
        return null
    }

    // ---------------- 安装 ----------------

    override fun init() {
        if (groups.isEmpty()) {
            HookHelper.log("$tag: no group resolved, skip")
            return
        }
        var installed = 0
        for (group in groups) {
            for (method in group.methods) {
                runCatching { installOne(group, method) }
                    .onSuccess { installed++ }
                    .onFailure { HookHelper.log("$tag: install failed ${group.name}", it) }
            }
            BlockLogger.group(SCOPE, group.name, group.methods.size)
        }
        HookHelper.log("$tag: $installed hook(s) installed")
    }

    private fun installOne(group: Group, method: Method) {
        method.isAccessible = true
        HookHelper.hookBefore(method) { param ->
            if (!HookPrefs.getBoolean(key, false)) return@hookBefore
            if (group.callbackIndex >= 0) {
                // 对外查询：主动回拒绝码，再跳过原实现（原实现会去查库）。
                val reply = group.reply
                val callback = param.args.getOrNull(group.callbackIndex)
                if (reply != null && callback != null) {
                    runCatching { reply.invoke(callback, null, REJECT_CODE) }
                        .onFailure { HookHelper.log("$tag: reject callback failed", it) }
                }
                BlockLogger.blocked(SCOPE, group.name, method, "reject=$REJECT_CODE")
            } else {
                BlockLogger.blocked(SCOPE, group.name, method)
            }
            param.setResultValue(null)
        }
    }

    // ---------------- DexKit 查询辅助 ----------------

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
        private const val SCOPE = "XSOF"

        private const val EVENT_SCREENSHOT = "miui.intent.TAKE_SCREENSHOT"
        private const val EVENT_PHONE = "android.intent.action.PHONE_STATE"
        private const val EVENT_SIM = "android.intent.action.SIM_STATE_CHANGED"

        private const val EXPECTED_INSERT_COUNT = 8
        private const val EXPECTED_UPDATE_COUNT = 3
        private const val EXPECTED_PROVIDER_WRITERS = 3

        /** 拒绝码：与原模块保持一致，客户端会得到「无权限 / 服务错误」。 */
        private const val REJECT_CODE = 1017

        private val API_KINDS = listOf(
            "current" to "1",
            "past" to "2",
            "duration" to "3",
            "score" to "4",
        )
    }
}
