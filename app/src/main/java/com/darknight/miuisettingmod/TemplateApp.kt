package com.darknight.miuisettingmod

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        // 目标进程的回报广播常在 App 在后台时投递（或被 ROM 冻结），
        // 只靠广播更新会导致「不重启 App 就看不到生效状态」，
        // 每次回到前台主动重读一次本地证据。
        registerActivityLifecycleCallbacks(StatusReloader)
    }

    private object StatusReloader : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            HookStatusStore.reload()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
