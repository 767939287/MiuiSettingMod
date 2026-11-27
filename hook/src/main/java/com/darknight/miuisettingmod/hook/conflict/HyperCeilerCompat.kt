package com.darknight.miuisettingmod.hook.conflict

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import com.darknight.miuisettingmod.hook.xposed.HookHelper

/**
 * 只读访问 HyperCeiler 的开关，用于检测两个模块 hook 同一方法的冲突。
 *
 * HyperCeiler 暴露了 exported 的 [PROVIDER_AUTHORITY]：
 * `content://<authority>/boolean/<key>` 返回一行 `data`（0/1）。
 * 读取失败（未安装、provider 未注册、旧版本没有该 URI）一律返回 null，
 * 视为「未知」，调用方应保持本模块原有行为，不得据此改变 hook 逻辑。
 *
 * ## 使用约束
 * - **不要在 system_server 里调用**：查询其它进程的 ContentProvider 需要先启动
 *   对方进程，system_server 自调用存在超时/死锁风险（[warn] 内有进程名兜底）。
 * - 本类只用于**日志提示**，不参与 hook 决策。跳过自家 hook 会让用户的开关
 *   变成「打开也不生效」，比两个模块重复 hook 更难排查。
 * - 查询未启动的 provider 会让 AMS 拉起 HyperCeiler 进程，因此只在进程启动时
 *   检测一次，不要放在高频回调里。
 */
object HyperCeilerCompat {

    private const val PROVIDER_AUTHORITY =
        "com.sevtinge.hyperceiler.provider.sharedprefs"

    /** 系统框架 → 其他 →「引导式访问」。 */
    const val KEY_GUIDED_ACCESS =
        "prefs_key_system_framework_guided_access"

    /** 系统框架 → 其他 →「增强屏蔽」（固定期间隐藏状态栏与手势提示线）。 */
    const val KEY_GUIDED_ACCESS_STATUS =
        "prefs_key_system_framework_guided_access_status"

    /** 桌面 → 底栏 →「隐藏 Dock」。 */
    const val KEY_HIDE_DOCK = "prefs_key_home_dock_hide_dock"

    /** 桌面 → 布局 →「底栏高度」自定义（hook `DeviceConfig#getHotSeatsHeight`）。 */
    const val KEY_HOTSEATS_HEIGHT =
        "prefs_key_home_layout_hotseats_height_enable"

    /** 桌面 → 布局 →「解锁平板网格」。 */
    const val KEY_PAD_GRID = "prefs_key_home_layout_pad_grid_enable"

    /**
     * 当前进程可用的 system context。
     *
     * 用 `ActivityThread#getSystemContext()` 而不是 `currentApplication()`：
     * 后者在 `Application#onCreate` 之前为 null，而 hook 安装时机可能更早。
     */
    @SuppressLint("PrivateApi")
    private val context: Context? by lazy {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread
                .getMethod("currentActivityThread")
                .invoke(null) ?: return@runCatching null
            activityThread.getMethod("getSystemContext").invoke(thread) as? Context
        }.getOrNull()
    }

    /**
     * 读取一个 HyperCeiler 布尔开关。
     *
     * provider 的 `data` 列按 int 写入；这里按字符串解析再转 int，避免列类型变化时
     * `getInt` 直接抛异常被吞成「未开启」。
     *
     * @return 实际值；null 表示读不到（未安装 / provider 缺失 / 查询异常）。
     */
    fun isEnabled(key: String): Boolean? {
        val ctx = context ?: return null
        return runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://$PROVIDER_AUTHORITY/boolean/$key"),
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)?.trim()?.toIntOrNull()?.let { it != 0 }
            }
        }.getOrNull()
    }

    /** 多个开关全部为 true 时才返回 true；任一未知按 false 处理。 */
    fun allEnabled(vararg keys: String): Boolean =
        keys.isNotEmpty() && keys.all { isEnabled(it) == true }

    /** 任一开关为 true 即返回 true（未知按 false 处理）。 */
    fun anyEnabled(vararg keys: String): Boolean =
        keys.any { isEnabled(it) == true }

    /**
     * 在后台线程做一次冲突检测并记录日志（要求 [keys] 全部为 true）。
     *
     * 不在调用线程直接查询：ContentResolver 是同步 Binder，provider 未启动时要等
     * AMS 拉起 HyperCeiler 进程，放在 SystemUI / 桌面的 hook 安装路径上会拖慢启动。
     *
     * @param tag 日志前缀，通常传 hook 的 `tag`
     * @param message 命中冲突时的提示文本
     * @param keys 需要全部为 true 才算命中的 HyperCeiler 开关
     */
    fun warnIfEnabled(tag: String, message: String, vararg keys: String) =
        warn(tag, message, requireAll = true, keys = keys)

    /** 与 [warnIfEnabled] 相同，但任一开关为 true 即命中。 */
    fun warnIfAnyEnabled(tag: String, message: String, vararg keys: String) =
        warn(tag, message, requireAll = false, keys = keys)

    private fun warn(
        tag: String,
        message: String,
        requireAll: Boolean,
        keys: Array<out String>,
    ) {
        Thread({
            runCatching {
                // system_server 自调用查询其它进程的 provider 有超时/死锁风险，直接跳过。
                if (isSystemServer()) return@runCatching
                val hit =
                    if (requireAll) allEnabled(*keys) else anyEnabled(*keys)
                if (hit) {
                    HookHelper.log("$tag: HyperCeiler 冲突提示 — $message")
                }
            }
        }, "MiuiSettingMod-HcCompat").apply { isDaemon = true }.start()
    }

    private fun isSystemServer(): Boolean =
        runCatching { Application.getProcessName() == "system_server" }
            .getOrDefault(false)
}
