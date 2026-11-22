package com.darknight.miuisettingmod.hook.status

import android.annotation.SuppressLint
import android.app.Application
import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.darknight.miuisettingmod.hook.xposed.HookHelper

/**
 * Hook 生效状态上报器（hook 进程侧）。
 *
 * 目标进程在每条 hook 安装成功后调用 [markInstalled] 记录已安装的配置键；
 * [flush] 由 `XposedEntry` 在本包**全部 Load 完成后**调用一次，合并上报（每个进程一次广播，
 * 作为一次进程启动证据；多 Load 各自 flush 时先到者会把后到者的键顶掉，故不能各自 flush）。
 *
 * 由于 libxposed 的 `onPackageReady` 早于 `Application` 创建（API 文档：「is ready to create
 * Application」），此时通常拿不到进程上下文。因此 [flush] 会挂载一次
 * `Application.attach(Context)` 生命周期钩子，在上下文就绪后补发；若已就绪则直接上报。
 */
object HookStatusReporter {

    private val installedKeys = LinkedHashSet<String>()
    private val evaluatedKeys = LinkedHashSet<String>()
    private val failedKeys = LinkedHashSet<String>()

    @Volatile
    private var pendingSource: Pair<String, String>? = null

    @Volatile
    private var reported = false

    @Volatile
    private var attachHookInstalled = false

    @Volatile
    private var deferredRetryScheduled = false

    /** 系统侧（AMS）是否已可接收广播；确认可用了就不再重复探测。 */
    @Volatile
    private var broadcastReadyCached = false

    /** 延迟重试轮次，用于收敛重试次数。 */
    private var retryCount = 0

    /** 记录一个本进程实际尝试安装的配置键（无论成功或失败）。 */
    @Synchronized
    fun markEvaluated(key: String) {
        if (key.isNotBlank()) evaluatedKeys.add(key)
    }

    /** 记录一个已成功安装的配置键。 */
    @Synchronized
    fun markInstalled(key: String) {
        if (key.isBlank()) return
        evaluatedKeys.add(key)
        failedKeys.remove(key)
        installedKeys.add(key)
    }

    /** 记录一个安装失败的配置键，用于 App 侧显示「不可用」。 */
    @Synchronized
    fun markFailed(key: String) {
        if (key.isBlank()) return
        evaluatedKeys.add(key)
        if (key !in installedKeys) failedKeys.add(key)
    }

    /**
     * 合并上报当前进程尝试安装的配置键；无任何尝试时静默跳过。
     */
    @Synchronized
    fun flush(sourcePackage: String, sourceProcess: String) {
        pendingSource = sourcePackage to sourceProcess
        if (evaluatedKeys.isEmpty()) return
        val context = currentApplication() ?: systemContext()
        if (context != null && reportIfPending(context)) return
        // 上下文拿不到，或系统尚未就绪（system_server 启动早期）：补挂兜底钩子并轮询重试。
        if (context == null) ensureAttachHook()
        scheduleDeferredRetry()
    }

    @Synchronized
    fun reset() {
        installedKeys.clear()
        evaluatedKeys.clear()
        failedKeys.clear()
        reported = false
        retryCount = 0
        deferredRetryScheduled = false
    }

    /** 挂载 `Application.attach(Context)`，在进程上下文就绪后补发一次状态。 */
    @SuppressLint("DiscouragedPrivateApi")
    private fun ensureAttachHook() {
        if (attachHookInstalled) return
        attachHookInstalled = true
        try {
            val attach = Application::class.java
                .getDeclaredMethod("attach", Context::class.java)
                .apply { isAccessible = true }
            HookHelper.intercept(attach) { chain ->
                val result = chain.proceed()
                val context = (chain.getArg(0) as? Context)?.let { it.applicationContext ?: it }
                if (context != null && !reportIfPending(context)) scheduleDeferredRetry()
                result
            }
        } catch (t: Throwable) {
            attachHookInstalled = false
            HookHelper.log("hook status: attach hook unavailable", t)
        }
    }

    /**
     * 上下文与系统就绪条件都满足时上报一次。
     *
     * @return 是否已完成上报（成功或此前已上报）；false 表示需要继续等待重试。
     */
    @Synchronized
    private fun reportIfPending(context: Context): Boolean {
        val source = pendingSource ?: return false
        if (reported) return true
        if (evaluatedKeys.isEmpty()) return false
        // system_server 启动早期（startBootstrapServices 之前）ActivityThread 已经能给出
        // system context，但 AMS 还没注册，sendBroadcast 会因 IActivityManager 为 null 抛 NPE。
        if (!broadcastReady()) {
            HookHelper.log("hook status: waiting for system ready @ ${source.second}")
            return false
        }
        reported = report(
            context = context,
            sourcePackage = source.first,
            sourceProcess = source.second,
            installed = ArrayList(installedKeys),
            evaluated = ArrayList(evaluatedKeys),
            failed = ArrayList(failedKeys),
        )
        return reported
    }

    /** @return 广播是否成功发出。 */
    private fun report(
        context: Context,
        sourcePackage: String,
        sourceProcess: String,
        installed: ArrayList<String>,
        evaluated: ArrayList<String>,
        failed: ArrayList<String>,
    ): Boolean {
        return try {
            val intent = Intent(HookStatusContract.ACTION_HOOKS_ACTIVE)
                .setComponent(
                    ComponentName(
                        HookStatusContract.MODULE_PACKAGE,
                        HookStatusContract.RECEIVER_CLASS,
                    )
                )
                .putExtra(HookStatusContract.EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(HookStatusContract.EXTRA_SOURCE_PROCESS, sourceProcess)
                .putExtra(HookStatusContract.EXTRA_MODULE_VERSION, moduleVersionCode(context))
                .putStringArrayListExtra(HookStatusContract.EXTRA_INSTALLED_KEYS, installed)
                .putStringArrayListExtra(HookStatusContract.EXTRA_EVALUATED_KEYS, evaluated)
                .putStringArrayListExtra(HookStatusContract.EXTRA_FAILED_KEYS, failed)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val options = BroadcastOptions.makeBasic().apply {
                    setShareIdentityEnabled(true)
                }
                context.sendBroadcast(intent, null, options.toBundle())
            } else {
                context.sendBroadcast(intent)
            }
            HookHelper.log(
                "hook status reported: installed=$installed failed=$failed @ $sourceProcess"
            )
            true
        } catch (t: Throwable) {
            HookHelper.log("hook status report failed @ $sourceProcess", t)
            false
        }
    }

    /**
     * 系统是否已经能接收广播。
     *
     * - system_server 早期（`startBootstrapServices` 之前）：`ActivityManager.getService()`
     *   为 null，此时 `sendBroadcast` 会在 `ContextImpl` 里对空的 `IActivityManager` 抛 NPE；
     * - AMS 已注册但尚未 `systemReady`：广播只会发给已注册的 receiver，清单静态接收器收不到；
     * - 普通 App 进程：AMS 早已就绪，一次通过后走缓存。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun broadcastReady(): Boolean {
        if (broadcastReadyCached) return true
        val activityManager = runCatching {
            Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrNull() ?: return false
        // 优先问系统自身：`ActivityManager.isSystemReady()` 在 system_server 进程里读本地标志，
        // 普通进程走一次 binder 调用。拿不到（个别 ROM 裁掉）就按已注册即可用放行，
        // 避免永远不上报。
        val ready = runCatching {
            Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("isSystemReady")
                .apply { isAccessible = true }
                .invoke(null) as? Boolean
        }.getOrNull()
            ?: runCatching {
                activityManager.javaClass.getMethod("isSystemReady")
                    .invoke(activityManager) as? Boolean
            }.getOrNull()
            ?: true
        if (ready) broadcastReadyCached = true
        return ready
    }

    @SuppressLint("PrivateApi")
    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    /**
     * system_server 等没有 Application 的进程：退回系统上下文上报。
     * `flush` 调用时机可能早于系统上下文创建，拿不到时由 [scheduleDeferredRetry] 稍后补发。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: return@runCatching null
        var current: Class<*>? = thread.javaClass
        while (current != null) {
            val method = runCatching { current.getDeclaredMethod("getSystemContext") }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(thread) as? Context
            }
            current = current.superclass
        }
        null
    }.getOrNull()

    /**
     * 轮询等待系统就绪后补发。
     *
     * system_server 在 `onSystemServerStarting` 阶段既没有 Application，AMS 也可能还没注册，
     * 因此不能只等一次：按 [DEFERRED_RETRY_MS] 轮询，最多 [MAX_RETRIES] 轮。
     */
    @Synchronized
    private fun scheduleDeferredRetry() {
        if (deferredRetryScheduled) return
        if (reported || pendingSource == null) return
        if (retryCount >= MAX_RETRIES) {
            HookHelper.log("hook status: giving up after $retryCount retries")
            return
        }
        deferredRetryScheduled = true
        val posted = runCatching {
            Handler(Looper.getMainLooper()).postDelayed({ retryOnce() }, DEFERRED_RETRY_MS)
        }.isSuccess
        if (!posted) {
            deferredRetryScheduled = false
            HookHelper.log("hook status: deferred retry unavailable")
        }
    }

    @Synchronized
    private fun retryOnce() {
        deferredRetryScheduled = false
        retryCount++
        if (reported || pendingSource == null) return
        val context = currentApplication() ?: systemContext()
        if (context == null || !reportIfPending(context)) {
            scheduleDeferredRetry()
        }
    }

    private fun moduleVersionCode(context: Context): Long = runCatching {
        context.packageManager
            .getPackageInfo(HookStatusContract.MODULE_PACKAGE, 0)
            .longVersionCode
    }.getOrDefault(0L)

    private const val DEFERRED_RETRY_MS = 5_000L

    /** 最多重试轮次（5 秒 × 24 ≈ 2 分钟，足够覆盖 system_server 启动）。 */
    private const val MAX_RETRIES = 24
}
