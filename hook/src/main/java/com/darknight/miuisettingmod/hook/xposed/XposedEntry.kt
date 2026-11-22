package com.darknight.miuisettingmod.hook.xposed

import com.darknight.miuisettingmod.hook.base.HookEntryRegistry
import com.darknight.miuisettingmod.hook.base.PackageTarget
import com.darknight.miuisettingmod.hook.prefs.HookPrefs
import com.darknight.miuisettingmod.hook.safemode.SafeModeManager
import com.darknight.miuisettingmod.hook.status.HookStatusContract
import com.darknight.miuisettingmod.hook.status.HookStatusReporter
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 102 模块入口。
 *
 * 负责：初始化封装层、按包分发 Load。
 */
class XposedEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookHelper.init(this)
        HookPrefs.init(getRemotePreferences(HookPrefs.GROUP))
        SafeModeManager.init(this)
        HookHelper.log("module loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        // 保证开机 / 冷启动时即使 onModuleLoaded 顺序异常也能读到最新配置。
        HookPrefs.init(getRemotePreferences(HookPrefs.GROUP))
        SafeModeManager.init(this)
        // 兜底：重复崩溃时自动跳过该包全部 hook，避免系统应用反复崩溃导致无法开机。
        if (SafeModeManager.handleStart(param.packageName)) return
        val target = PackageTarget.from(param)
        HookEntryRegistry.loadsFor(target.packageName).forEach { load ->
            runCatching { load.onPackageReady(target) }
                .onFailure { HookHelper.log("load failed for ${target.packageName}", it) }
        }
        // 全部 Load 完成后统一上报生效状态（每个进程一次广播；多 Load 不会互相顶掉）。
        HookStatusReporter.flush(target.packageName, target.processName)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // system_server 不创建 Application，`onModuleLoaded` 里的远程偏好若未就绪，
        // 这里会读到默认值：开关全为 false → 没有任何 hook 安装、也不会产生上报。
        runCatching { HookPrefs.init(getRemotePreferences(HookPrefs.GROUP)) }
            .onFailure { HookHelper.log("system_server: remote prefs unavailable", it) }
        // system_server 同样接入兜底机制，防止 hook 异常导致崩溃、无法开机。
        SafeModeManager.init(this)
        if (SafeModeManager.handleStart(HookStatusContract.SYSTEM_SERVER_PACKAGE)) {
            HookHelper.log("SafeMode: system_server is in safe mode, skip hooks")
            return
        }
        val target = PackageTarget.fromSystemServer(param)
        HookEntryRegistry.loadsFor(target.packageName).forEach { load ->
            runCatching { load.onPackageReady(target) }
                .onFailure { HookHelper.log("load failed for system_server", it) }
        }
        // 全部 Load 完成后统一上报生效状态（每个进程一次广播；多 Load 不会互相顶掉）。
        HookStatusReporter.flush(target.packageName, target.processName)
    }
}
