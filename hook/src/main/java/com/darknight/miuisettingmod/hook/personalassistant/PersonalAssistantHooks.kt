package com.darknight.miuisettingmod.hook.personalassistant

import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.rule.HookSkippedException
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect

/**
 * com.miui.personalassistant（负一屏 / 小组件中心）进程的 hook 入口。
 */
class PersonalAssistantLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            PaidWidgetBypassHook(),
            HookPrefs.getBoolean(KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.miui.personalassistant"
        const val KEY = "theme_crack_personal_assistant_enabled"
    }
}

/**
 * 绕过小组件中心的付费 / 购买状态校验。
 *
 * 每个判定单独 `runCatching`，单点类名或方法名变化不会影响其它判定。
 */
class PaidWidgetBypassHook : BaseHook() {

    override val key: String = PersonalAssistantLoad.KEY

    /** 本 ROM 上实际挂上的方法数；一个都没挂上说明类名 / 方法名全变了，应当标记为未生效。 */
    private var installed = 0

    override fun init() {
        // 付费小组件总开关：关闭后不再走付费逻辑。
        installConstant(
            "com.miui.maml.widget.edit.MamlutilKt",
            "themeManagerSupportPaidWidget",
            false,
        )
        // 允许直接添加到桌面 / 下载。
        installConstant(
            "com.miui.personalassistant.picker.business.detail.PickerDetailViewModel",
            "isCanDirectAddMaMl",
            true,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.utils.PickerDetailDownloadManager\$Companion",
            "isCanDownload",
            true,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.utils.PickerDetailUtil",
            "isCanAutoDownloadMaMl",
            true,
        )
        // 详情页购买状态：一律「未付费 + 已拥有」。
        installConstant(
            "com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponse",
            "isPay",
            false,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponse",
            "isBought",
            true,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponseWrapper",
            "isPay",
            false,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponseWrapper",
            "isBought",
            true,
        )
        // 页面状态：不检查购买状态、不阻止添加。
        installConstant(
            "com.miui.personalassistant.picker.business.detail.PickerDetailViewModel",
            "shouldCheckMamlBoughtState",
            false,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.PickerDetailViewModel",
            "isTargetPositionMamlPayAndDownloading",
            false,
        )
        installConstant(
            "com.miui.personalassistant.picker.business.detail.PickerDetailViewModel",
            "checkIsIndependentProcessWidgetForPosition",
            true,
        )

        if (installed == 0) {
            throw HookSkippedException("no paid widget method matched on this ROM")
        }
        HookHelper.log("$tag: $installed method(s) hooked (paid widget bypass)")
    }

    private fun installConstant(className: String, methodName: String, value: Boolean) {
        runCatching {
            val clazz = Reflect.findClassIfExists(className, target.classLoader)
            if (clazz == null) {
                HookHelper.log("$tag: $className not found")
                return@runCatching
            }
            // 同名方法可能有多个重载：手机版多为无参，平板版则带了参数
            // （如 `themeManagerSupportPaidWidget(Context)`、`isCanDirectAddMaMl(int)`），
            // 因此按名字匹配全部重载，不再依赖无参签名。
            val methods = clazz.declaredMethods.filter { it.name == methodName }
            if (methods.isEmpty()) {
                HookHelper.log("$tag: $className#$methodName not found")
                return@runCatching
            }
            methods.forEach { method ->
                method.isAccessible = true
                HookHelper.hookReplace(method) { value }
                installed++
            }
        }.onFailure { HookHelper.log("$tag: $className#$methodName failed", it) }
    }
}
