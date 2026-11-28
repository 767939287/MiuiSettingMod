package com.darknight.miuisettingmod.hook.guardprovider

import android.app.Service
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.dexkit.IDexKitList
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.util.BlockLogger
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.reflect.Method

/**
 * com.miui.guardprovider（系统安全组件）的 hook 入口。
 *
 * 只处理 AI 通话 / 音视频反诈检测服务，不动应用扫描、病毒库等常规能力。
 */
class GuardProviderLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            AiGuardServiceBlockHook(),
            HookPrefs.getBoolean(AiGuardServiceBlockHook.KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.miui.guardprovider"
    }
}

/**
 * 停用 GuardProvider 的 AI 反诈检测服务。
 *
 * 目标方法：`AiGuardService#onStartCommand(Intent,int,int)` —— 拉起检测服务的唯一入口。
 * 拦截后立即 `stopSelf(startId)` 并返回 `START_NOT_STICKY`，服务不会有粘性重启；
 * 与参考模块一致，这是「关闭服务」而不是「隐藏提示」。
 *
 * 类名用特征定位（`PHONE_SCAM_SCENE` / `VIDEO_SCAM_SCENE` / `ai_guard_function_switch`），
 * 不硬编码混淆名。
 */
class AiGuardServiceBlockHook : BaseHook() {

    override val key: String get() = KEY

    override fun useDexKit(): Boolean = true

    private var startCommand: Method? = null

    override fun initDexKit(): Boolean {
        val methods = optionalMemberList<Method>(
            "ai_guard_start_command_v1",
            IDexKitList { bridge ->
                bridge.findMethod {
                    matcher {
                        name("onStartCommand")
                        returnType("int")
                        paramTypes("android.content.Intent", "int", "int")
                        usingStrings("COMMAND", "CAPABILITIES", "PHONE_SCAM_SCENE", "VIDEO_SCAM_SCENE")
                        invokeMethods {
                            add {
                                declaredClass("android.app.Service")
                                name("startForeground")
                            }
                            matchType = MatchType.Contains
                        }
                    }
                }
            },
        ).orEmpty()
        val method = methods.singleOrNull()
        if (method == null) {
            BlockLogger.skip(SCOPE, "guard.ai_detection", "candidates=${methods.size}")
            return false
        }
        if (!Service::class.java.isAssignableFrom(method.declaringClass)) {
            BlockLogger.skip(SCOPE, "guard.ai_detection", "${method.declaringClass.name} is not a Service")
            return false
        }
        startCommand = method.apply { isAccessible = true }
        HookHelper.log("$tag: located ${method.declaringClass.name}#onStartCommand")
        return true
    }

    override fun init() {
        val method = startCommand
        if (method == null) {
            HookHelper.log("$tag: not resolved, skip")
            return
        }
        HookHelper.hookBefore(method) { param ->
            if (!HookPrefs.getBoolean(key, false)) return@hookBefore
            val service = param.thisObject as? Service
            val startId = param.args.getOrNull(2) as? Int
            if (service != null && startId != null) {
                runCatching { service.stopSelf(startId) }
                    .onFailure { HookHelper.log("$tag: stopSelf failed", it) }
            }
            BlockLogger.blocked(SCOPE, "guard.ai_detection", method)
            param.setResultValue(Service.START_NOT_STICKY)
        }
    }

    companion object {
        const val KEY = "guard_ai_detection_disable"
        private const val SCOPE = "GuardProvider"
    }
}
