package com.darknight.miuisettingmod.hook.util

import com.darknight.miuisettingmod.hook.xposed.HookHelper
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拦截日志。
 *
 * 每条目标方法首次命中记一条完整日志，之后每 [SAMPLE] 次再记一条，
 * 避免高频路径（耗电排行渲染、广播回调）刷爆 LSPosed 日志。
 */
object BlockLogger {

    private const val SAMPLE = 20

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    private fun countKey(scope: String, group: String, method: Method): String =
        "$scope|$group|${method.declaringClass.name}#${method.name}"

    /**
     * 记录一次拦截。
     *
     * @param scope 组件名（XSOF / GuardProvider / SystemHelper …）
     * @param group 功能分组名
     * @param method 被拦截的方法
     * @param detail 额外信息（可为空）
     */
    fun blocked(scope: String, group: String, method: Method, detail: String? = null) {
        val key = countKey(scope, group, method)
        val hits = counters.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        if (hits != 1 && hits % SAMPLE != 0) return
        val suffix = if (detail.isNullOrEmpty()) "" else " $detail"
        HookHelper.log(
            "[$scope] BLOCK group=$group method=${method.declaringClass.name}#${method.name} hits=$hits$suffix"
        )
    }

    /** 某个功能分组解析完成后的汇总日志。 */
    fun group(scope: String, group: String, methods: Int, note: String? = null) {
        val suffix = if (note.isNullOrEmpty()) "" else " ($note)"
        HookHelper.log("[$scope] INSTALLED $group targets=$methods$suffix")
    }

    /** 某个功能分组跳过（证据不足 / 候选不唯一）。 */
    fun skip(scope: String, group: String, reason: String) {
        HookHelper.log("[$scope] SKIPPED $group: $reason")
    }
}
