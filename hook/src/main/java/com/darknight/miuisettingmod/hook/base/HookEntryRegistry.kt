package com.darknight.miuisettingmod.hook.base

import com.darknight.miuisettingmod.hook.demo.DemoLoad
import com.darknight.miuisettingmod.hook.guardprovider.GuardProviderLoad
import com.darknight.miuisettingmod.hook.miuihome.MiuiHomeLoad
import com.darknight.miuisettingmod.hook.personalassistant.PersonalAssistantLoad
import com.darknight.miuisettingmod.hook.securitycenter.SecurityCenterLoad
import com.darknight.miuisettingmod.hook.settings.SettingsLoad
import com.darknight.miuisettingmod.hook.systemhelper.SystemHelperLoad
import com.darknight.miuisettingmod.hook.systemserver.SystemServerThemeLoad
import com.darknight.miuisettingmod.hook.systemui.SystemUiLoad
import com.darknight.miuisettingmod.hook.thememanager.ThemeManagerLoad

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
        ThemeManagerLoad(),
        PersonalAssistantLoad(),
        MiuiHomeLoad(),
        SecurityCenterLoad(),
        GuardProviderLoad(),
        SystemHelperLoad(),
        SystemServerThemeLoad(),
    )

    fun loadsFor(packageName: String): List<BaseLoad> =
        loads.filter { it.targetPackages.contains(packageName) }

    fun all(): List<BaseLoad> = loads
}
