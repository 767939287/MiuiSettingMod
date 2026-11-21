package com.darknight.miuisettingmod.hook.thememanager

import android.os.Environment
import com.darknight.miuisettingmod.hook.base.BaseHook
import com.darknight.miuisettingmod.hook.base.BaseLoad
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.dexkit.IDexKit
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.xposed.HookHelper
import com.darknight.miuisettingmod.hook.xposed.Reflect
import java.io.File
import java.lang.reflect.Method

/**
 * com.android.thememanager（主题商店）进程的 hook 入口。
 *
 * 全部规则共用同一个配置开关 `theme_crack_theme_manager_enabled`。
 */
class ThemeManagerLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        val enabled = HookPrefs.getBoolean(KEY, false)
        initHook(MarkResourceBoughtHook(), enabled)
        initHook(ZeroDiscountPriceHook(), enabled)
        initHook(ThemeManagerPaidWidgetHook(), enabled)
        initHook(ThemeManagerDrmResultHook(), enabled)
        initHook(LargeIconRightsHook(), enabled)
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.thememanager"
        const val KEY = "theme_crack_theme_manager_enabled"
    }
}

/**
 * 在线资源详情转本地模型时，把 `bought` 字段强制标记为已购买。
 *
 * 目标：`com.android.thememanager.detail.theme.model.OnlineResourceDetail#toResource`
 */
class MarkResourceBoughtHook : BaseHook() {

    override val key: String = ThemeManagerLoad.KEY

    override fun init() {
        val clazz = Reflect.findClassIfExists(ONLINE_DETAIL_CLASS, target.classLoader)
        if (clazz == null) {
            HookHelper.log("$tag: $ONLINE_DETAIL_CLASS not found, skip")
            return
        }
        val methods = clazz.declaredMethods.filter { it.name == "toResource" }
        if (methods.isEmpty()) {
            HookHelper.log("$tag: toResource not found on ${clazz.name}, skip")
            return
        }
        methods.forEach { method ->
            method.isAccessible = true
            HookHelper.hookAfter(method) { param ->
                val self = param.thisObject ?: return@hookAfter
                runCatching { Reflect.setObjectField(self, BOUGHT_FIELD, true) }
                    .onFailure {
                        // 字段名变化时退化为按类型 + 名称扫描。
                        runCatching { markByScan(self) }
                    }
            }
        }
        HookHelper.log("$tag: ${clazz.name}#toResource hooked (bought=true)")
    }

    private fun markByScan(instance: Any) {
        instance.javaClass.declaredFields
            .firstOrNull {
                it.type == java.lang.Boolean.TYPE &&
                    it.name.contains("bought", ignoreCase = true)
            }
            ?.let { field ->
                field.isAccessible = true
                field.setBoolean(instance, true)
            }
    }

    companion object {
        private const val ONLINE_DETAIL_CLASS =
            "com.android.thememanager.detail.theme.model.OnlineResourceDetail"
        private const val BOUGHT_FIELD = "bought"
    }
}

/**
 * 把折扣价视图里所有 `(int, int) -> void` 方法的第二个参数改成 0。
 *
 * 目标：`com.android.thememanager.basemodule.views.DiscountPriceView`
 */
class ZeroDiscountPriceHook : BaseHook() {

    override val key: String = ThemeManagerLoad.KEY

    override fun init() {
        val clazz = Reflect.findClassIfExists(DISCOUNT_VIEW_CLASS, target.classLoader)
        if (clazz == null) {
            HookHelper.log("$tag: $DISCOUNT_VIEW_CLASS not found, skip")
            return
        }
        val methods = clazz.declaredMethods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.all { it == Integer.TYPE }
        }
        if (methods.isEmpty()) {
            HookHelper.log("$tag: no (int, int) method on ${clazz.name}, skip")
            return
        }
        methods.forEach { method ->
            method.isAccessible = true
            HookHelper.hookBefore(method) { param -> param.setArg(1, 0) }
        }
        HookHelper.log("$tag: ${clazz.name} ${methods.size} method(s) price -> 0")
    }

    companion object {
        private const val DISCOUNT_VIEW_CLASS =
            "com.android.thememanager.basemodule.views.DiscountPriceView"
    }
}

/**
 * 关闭主题商店侧的付费小组件支持判定。
 *
 * 目标：`com.miui.maml.widget.edit.MamlutilKt#themeManagerSupportPaidWidget`
 */
class ThemeManagerPaidWidgetHook : BaseHook() {

    override val key: String = ThemeManagerLoad.KEY

    override fun init() {
        val clazz = Reflect.findClassIfExists(MAML_UTIL_CLASS, target.classLoader)
        if (clazz == null) {
            HookHelper.log("$tag: $MAML_UTIL_CLASS not found, skip")
            return
        }
        val method = Reflect.findMethodIfExists(clazz, "themeManagerSupportPaidWidget")
        if (method == null) {
            HookHelper.log("$tag: themeManagerSupportPaidWidget not found, skip")
            return
        }
        HookHelper.hookReplace(method) { false }
        HookHelper.log("$tag: themeManagerSupportPaidWidget -> false installed")
    }

    companion object {
        private const val MAML_UTIL_CLASS = "com.miui.maml.widget.edit.MamlutilKt"
    }
}

/**
 * 主题校验方法返回 `DRM_SUCCESS`。
 *
 * 方法位置用 DexKit 按字符串特征定位（该类在不同 HyperOS 版本被混淆/改名）：
 * `theme` / `ThemeManagerTag` / `/system` / `check rights isLegal:`
 */
class ThemeManagerDrmResultHook : BaseHook() {

    override val key: String = ThemeManagerLoad.KEY

    override fun useDexKit(): Boolean = true

    private var targetMethod: Method? = null

    override fun initDexKit(): Boolean {
        val method = optionalMember<Method>(
            MEMBER_KEY,
            IDexKit { bridge ->
                bridge.findMethod {
                    matcher { usingStrings(*DRM_STRINGS) }
                }.firstOrNull()
                    ?: throw ReflectiveOperationException("theme DRM method not found")
            },
        ) ?: return false
        method.isAccessible = true
        targetMethod = method
        return true
    }

    override fun init() {
        val method = targetMethod ?: return
        val success = resolveDrmSuccess() ?: run {
            HookHelper.log("$tag: DrmResult.DRM_SUCCESS not found, skip")
            return
        }
        HookHelper.hookAfter(method) { param -> param.setResultValue(success) }
        HookHelper.log("$tag: ${method.declaringClass.name}#${method.name} -> DRM_SUCCESS")
    }

    private fun resolveDrmSuccess(): Any? {
        val clazz = Reflect.findClassIfExists(DRM_RESULT_CLASS, target.classLoader) ?: return null
        return clazz.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "DRM_SUCCESS" }
    }

    companion object {
        private const val MEMBER_KEY = "theme_drm_result"
        private const val DRM_RESULT_CLASS = "miui.drm.DrmManager\$DrmResult"
        private val DRM_STRINGS = arrayOf(
            "theme",
            "ThemeManagerTag",
            "/system",
            "check rights isLegal:",
        )
    }
}

/**
 * 伪造大图标授权凭证文件。
 *
 * 命中的方法在应用图标包时被调用，这里反射取它的 `Resource` 字段拿到 productId，
 * 然后在主题商店外部目录创建 `<productId>-largeicons.mra` 空文件，
 * 让后续「是否已授权」的检查直接通过。
 *
 * 方法位置用 DexKit 按字符串特征定位：
 * `apply failed` / `/data/system/theme/large_icons/` / `default_large_icon_product_id` /
 * `largeicons` / `relativePackageList is empty`
 */
class LargeIconRightsHook : BaseHook() {

    override val key: String = ThemeManagerLoad.KEY

    override fun useDexKit(): Boolean = true

    private var targetMethod: Method? = null

    override fun initDexKit(): Boolean {
        val method = optionalMember<Method>(
            MEMBER_KEY,
            IDexKit { bridge ->
                bridge.findMethod {
                    matcher { usingStrings(*LARGE_ICON_STRINGS) }
                }.firstOrNull()
                    ?: throw ReflectiveOperationException("large icon method not found")
            },
        ) ?: return false
        method.isAccessible = true
        targetMethod = method
        return true
    }

    override fun init() {
        val method = targetMethod ?: return
        HookHelper.hookBefore(method) { param ->
            runCatching { writeRightsFile(param.thisObject) }
                .onFailure { HookHelper.log("$tag: write rights file failed", it) }
        }
        HookHelper.log("$tag: ${method.declaringClass.name}#${method.name} hooked (rights file)")
    }

    private fun writeRightsFile(holder: Any?) {
        val instance = holder ?: return
        val resourceField = instance.javaClass.declaredFields.firstOrNull {
            it.type.name == RESOURCE_CLASS
        } ?: return
        resourceField.isAccessible = true
        val resource = resourceField.get(instance) ?: return
        val productId = Reflect.callMethod(resource, "getProductId") ?: return

        val dir = rightsDir()
        if (!dir.exists() && !dir.mkdirs()) {
            HookHelper.log("$tag: cannot create $dir")
            return
        }
        val file = File(dir, "$productId-largeicons.mra")
        if (!file.exists()) {
            file.createNewFile()
            HookHelper.log("$tag: created ${file.name}")
        }
    }

    @Suppress("DEPRECATION")
    private fun rightsDir(): File = File(
        Environment.getExternalStorageDirectory(),
        "Android/data/${ThemeManagerLoad.TARGET_PACKAGE}/files/MIUI/theme/.data/rights/theme",
    )

    companion object {
        private const val MEMBER_KEY = "large_icon_rights"
        private const val RESOURCE_CLASS =
            "com.android.thememanager.basemodule.resource.model.Resource"
        private val LARGE_ICON_STRINGS = arrayOf(
            "apply failed",
            "/data/system/theme/large_icons/",
            "default_large_icon_product_id",
            "largeicons",
            "relativePackageList is empty",
        )
    }
}
