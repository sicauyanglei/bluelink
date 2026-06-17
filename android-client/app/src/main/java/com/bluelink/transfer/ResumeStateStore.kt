package com.bluelink.transfer

import android.content.Context
import android.net.Uri

/**
 * P1-4: 断点续传状态持久化到 SharedPreferences
 * 保存下载文件名、URI、已下载字节数，App 重启后可恢复
 */
class ResumeStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("bluelink_resume", Context.MODE_PRIVATE)

    fun saveDownloadFileName(originalName: String, actualName: String) {
        prefs.edit().putString("name_$originalName", actualName).apply()
    }

    fun getDownloadFileName(originalName: String): String? {
        return prefs.getString("name_$originalName", null)
    }

    fun saveDownloadFileUri(originalName: String, uri: Uri) {
        prefs.edit().putString("uri_$originalName", uri.toString()).apply()
    }

    fun getDownloadFileUri(originalName: String): Uri? {
        val str = prefs.getString("uri_$originalName", null) ?: return null
        return try { Uri.parse(str) } catch (e: Exception) { null }
    }

    fun saveDownloadOffset(originalName: String, offset: Long) {
        prefs.edit().putLong("offset_$originalName", offset).apply()
    }

    fun getDownloadOffset(originalName: String): Long {
        return prefs.getLong("offset_$originalName", 0L)
    }

    fun clearDownloadState(originalName: String) {
        prefs.edit().apply {
            remove("name_$originalName")
            remove("uri_$originalName")
            remove("offset_$originalName")
        }.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
