package com.darknight.miuisettingmod.hook.base

import com.darknight.miuisettingmod.hook.demo.DemoLoad
import com.darknight.miuisettingmod.hook.settings.SettingsLoad
import com.darknight.miuisettingmod.hook.systemui.SystemUiLoad

/**
 * 目标 Load 注册表。
 *
 * 新增目标包时，在这里登记对应的 [BaseLoad] 实现即可。
 */
object HookEntryRegistry {

    private val loads: List<BaseLoad> = listOf(
        DemoLoad(),
        SettingsLoad(),
        SystemUiLoad(),
    )

    fun loadsFor(packageName: String): List<BaseLoad> =
        loads.filter { it.targetPackages.contains(packageName) }

    fun all(): List<BaseLoad> = loads
}
