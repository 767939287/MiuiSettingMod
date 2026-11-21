package com.darknight.miuisettingmod

import android.app.Application
import com.darknight.miuisettingmod.prefs.ConfigState
import com.darknight.miuisettingmod.prefs.OptionRegistry
import com.darknight.miuisettingmod.prefs.PrefsStore
import com.darknight.miuisettingmod.ui.screen.features.featureSpecs
import com.darknight.miuisettingmod.xposed.HookStatusStore
import com.darknight.miuisettingmod.xposed.SafeModeReader
import com.darknight.miuisettingmod.xposed.XposedServiceManager

class TemplateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PrefsStore.init(this)
        ConfigState.init(this)
        OptionRegistry.registerAll(featureSpecs())
        HookStatusStore.initialize(this)
        SafeModeReader.initialize(this)
        XposedServiceManager.init()
    }
}
