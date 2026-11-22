package com.darknight.miuisettingmod.hook.status

/**
 * Hook 生效状态上报协议（hook 进程 → App）。
 *
 * 采用**定向广播**而非「hook 侧写远程偏好」：
 * libxposed 远程文件在多数框架上对 hook 侧为只读，hook 侧无法写入；
 * 而广播由被注入的目标进程以自身 UID 发出，App 侧可通过发送者身份校验，
 * 得到「该进程确实装了哪些 hook」的正向证据。
 *
 * 触发时机：目标进程在 [com.darknight.miuisettingmod.hook.base.BaseLoad] 中完成一组 hook
 * 注册后，合并本进程已成功安装的配置键，一次性上报。
 */
object HookStatusContract {

    /** 模块包名。 */
    const val MODULE_PACKAGE = "com.darknight.miuisettingmod"

    /** App 侧接收器完整类名（与清单注册一致）。 */
    const val RECEIVER_CLASS = "com.darknight.miuisettingmod.xposed.HookStatusReceiver"

    /** 上报动作。 */
    const val ACTION_HOOKS_ACTIVE = "com.darknight.miuisettingmod.action.HOOKS_ACTIVE"

    /** 来源目标包名。 */
    const val EXTRA_SOURCE_PACKAGE = "source_package"

    /** 来源进程名。 */
    const val EXTRA_SOURCE_PROCESS = "source_process"

    /** 模块 versionCode；0 表示未能解析（App 侧按未知处理）。 */
    const val EXTRA_MODULE_VERSION = "module_version"

    /** 本进程已成功安装的配置键集合（`ArrayList<String>`）。 */
    const val EXTRA_INSTALLED_KEYS = "installed_keys"

    /**
     * 本进程实际尝试安装的配置键集合（`ArrayList<String>`，含成功与失败）。
     *
     * App 侧用它区分「目标进程已启动但该功能未生效」与「目标进程尚未回报」。
     */
    const val EXTRA_EVALUATED_KEYS = "evaluated_keys"

    /** 本进程安装失败的配置键集合（`ArrayList<String>`）。 */
    const val EXTRA_FAILED_KEYS = "failed_keys"

    /** system_server 在 hook 侧上报时使用的来源包名。 */
    const val SYSTEM_SERVER_PACKAGE = "android"

    /** App 侧作用域与配置里 system_server 写作 `system`。 */
    const val SYSTEM_SERVER_ALIAS = "system"

    /**
     * 归一化来源包名：`android` 与 `system` 视为同一个系统进程。
     *
     * hook 侧 `PackageTarget.fromSystemServer()` 取的是 `android`，而 App 侧配置项
     * 与 LSPosed 作用域里写作 `system`，不归一化会导致两边永远匹配不上，
     * 状态只能停在「等待启动」。
     */
    fun normalizePackage(packageName: String): String =
        if (packageName == SYSTEM_SERVER_PACKAGE) SYSTEM_SERVER_ALIAS else packageName
}
