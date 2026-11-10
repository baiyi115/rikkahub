package me.rerere.rikkahub.data.ai.tools.local

import java.io.File

/** 本机 Shell 的常量: 使用 Android 自带的 /system/bin/sh 与 toybox 工具 */
internal const val SYSTEM_SHELL = "/system/bin/sh"
internal const val SETSID = "/system/bin/setsid"
internal const val LOCAL_SHELL_DIR = "local_shell"
internal const val LOCAL_SHELL_TMP_DIR = "local_shell_tmp"
internal const val LOCAL_SHELL_JOBS_DIR = "jobs"

/** 每个流保留的最大字符数 */
internal const val MAX_OUTPUT_CHARS = 32 * 1024

internal const val DEFAULT_TIMEOUT_SECONDS = 30L
internal const val MAX_TIMEOUT_SECONDS = 600L

/** root 下额外包含 Magisk/KernelSU/APatch 的工具目录 */
internal const val DEFAULT_PATH =
    "/system/bin:/system/xbin:/vendor/bin:/product/bin:/system_ext/bin:/sbin:/data/local/tmp:" +
        "/debug_ramdisk:/data/adb/ksu/bin:/data/adb/ap/bin"

/**
 * su 位置探测。只检查文件, 不执行 su, 因此不会弹出授权窗口。
 */
object LocalShellRuntime {
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    @Volatile
    private var cachedSuPath: String? = null

    @Volatile
    private var probed = false

    fun suPath(): String? {
        if (!probed) {
            synchronized(this) {
                if (!probed) {
                    cachedSuPath = SU_PATHS.firstOrNull { path ->
                        runCatching { File(path).let { it.isFile && it.canExecute() } }.getOrDefault(false)
                    }
                    probed = true
                }
            }
        }
        return cachedSuPath
    }

    val isRootAvailable: Boolean
        get() = suPath() != null

    fun refresh() {
        synchronized(this) {
            probed = false
            cachedSuPath = null
        }
    }
}

data class LocalShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    /** 超时或 shell 退出导致会话已关闭 */
    val sessionClosed: Boolean = false,
)

internal fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"
