package com.zhiwo.app

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获。
 *
 * 之前崩了就是崩了，什么都不留。现在把异常堆栈和设备信息写到
 * filesDir/crash.log，用户可以在设置里导出这个文件发过来。
 */
class ZhiwoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable) {
        val dir = File(filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "crash.log")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        val info = buildString {
            append("\n===== ").append(stamp).append(" =====\n")
            append("app: ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n')
            append("android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("thread: ").append(thread.name).append('\n')
            append(Log.getStackTraceString(error)).append('\n')
        }
        // 只留最近 200KB，别让日志把磁盘吃满
        if (file.exists() && file.length() > 200 * 1024) {
            file.writeText(info)
        } else {
            file.appendText(info)
        }
    }
}
