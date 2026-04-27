package com.bluelink.transfer

data class FileItem(
    val name: String,
    val size: Long,
    val modifiedTime: String,
    val isDirectory: Boolean,
    val isParentDirectory: Boolean = false
) {
    val formattedSize: String
        get() = if (isDirectory) "<DIR>" else formatSize(size)

    private fun formatSize(bytes: Long): String {
        val sizes = listOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var order = 0
        while (size >= 1024 && order < sizes.size - 1) {
            order++
            size /= 1024
        }
        return "%.2f %s".format(size, sizes[order])
    }
}
