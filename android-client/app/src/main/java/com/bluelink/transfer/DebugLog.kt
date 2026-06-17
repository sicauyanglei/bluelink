package com.bluelink.transfer

import android.util.Log

/**
 * P2-2: 调试日志工具
 *
 * - Debug 构建：所有日志正常输出
 * - Release 构建：通过 ProGuard -assumenosideeffects 剥离 Log.d/Log.v
 *   此处 BuildConfig.DEBUG 检查提供双重保险，并允许运行时控制
 */
object DebugLog {
    private const val DEFAULT_TAG = "BluLink"

    fun d(tag: String = DEFAULT_TAG, msg: String) {
        if (com.bluelink.transfer.BuildConfig.DEBUG) {
            Log.d(tag, msg)
        }
    }

    fun d(tag: String = DEFAULT_TAG, msg: String, tr: Throwable) {
        if (com.bluelink.transfer.BuildConfig.DEBUG) {
            Log.d(tag, msg, tr)
        }
    }

    fun v(tag: String = DEFAULT_TAG, msg: String) {
        if (com.bluelink.transfer.BuildConfig.DEBUG) {
            Log.v(tag, msg)
        }
    }

    /** 错误日志：release 也保留 */
    fun e(tag: String = DEFAULT_TAG, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }

    /** 警告日志：release 也保留 */
    fun w(tag: String = DEFAULT_TAG, msg: String) {
        Log.w(tag, msg)
    }

    fun i(tag: String = DEFAULT_TAG, msg: String) {
        Log.i(tag, msg)
    }
}
