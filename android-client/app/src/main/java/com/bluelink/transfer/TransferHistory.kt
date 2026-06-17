package com.bluelink.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * P2-8: 传输历史记录 + 统计
 *
 * 使用 SharedPreferences + JSON 持久化传输记录，
 * 最多保留 200 条，FIFO 淘汰。
 */
object TransferHistory {
    private const val PREFS_NAME = "bluelink_transfer_history"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 200

    enum class Direction(val label: String) { DOWNLOAD("下载"), UPLOAD("上传") }

    data class Record(
        val direction: Direction,
        val fileName: String,
        val sizeBytes: Long,
        val success: Boolean,
        val timestamp: Long,
        val durationMs: Long
    )

    data class Stats(
        val totalDownloads: Int,
        val totalUploads: Int,
        val totalDownloadBytes: Long,
        val totalUploadBytes: Long,
        val successCount: Int,
        val failCount: Int,
        val avgSpeedBps: Long
    ) {
        val totalCount get() = totalDownloads + totalUploads
        val successRate: Float get() = if (totalCount > 0) successCount.toFloat() / totalCount else 0f
    }

    /** 记录一次传输 */
    fun record(
        context: Context,
        direction: Direction,
        fileName: String,
        sizeBytes: Long,
        success: Boolean,
        durationMs: Long
    ) {
        val records = getAll(context).toMutableList()
        records.add(0, Record(direction, fileName, sizeBytes, success, System.currentTimeMillis(), durationMs))
        if (records.size > MAX_RECORDS) {
            records.subList(MAX_RECORDS, records.size).clear()
        }
        save(context, records)
    }

    /** 获取全部记录（最新在前） */
    fun getAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    direction = Direction.valueOf(o.getString("direction")),
                    fileName = o.getString("fileName"),
                    sizeBytes = o.getLong("sizeBytes"),
                    success = o.getBoolean("success"),
                    timestamp = o.getLong("timestamp"),
                    durationMs = o.getLong("durationMs")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 统计 */
    fun getStats(context: Context): Stats {
        val records = getAll(context)
        var dl = 0; var ul = 0
        var dlBytes = 0L; var ulBytes = 0L
        var ok = 0; var fail = 0
        var totalBytes = 0L; var totalMs = 0L
        for (r in records) {
            if (r.success) ok++ else fail++
            if (r.direction == Direction.DOWNLOAD) {
                dl++; dlBytes += r.sizeBytes
            } else {
                ul++; ulBytes += r.sizeBytes
            }
            totalBytes += r.sizeBytes
            totalMs += r.durationMs
        }
        val avgSpeed = if (totalMs > 0) totalBytes * 1000 / totalMs else 0L
        return Stats(dl, ul, dlBytes, ulBytes, ok, fail, avgSpeed)
    }

    /** 清空历史 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_RECORDS).apply()
    }

    private fun save(context: Context, records: List<Record>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("direction", r.direction.name)
                put("fileName", r.fileName)
                put("sizeBytes", r.sizeBytes)
                put("success", r.success)
                put("timestamp", r.timestamp)
                put("durationMs", r.durationMs)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, arr.toString()).apply()
    }
}
