package com.cinemahall.desktop.app.ui

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 播放时间：`m:ss` / `h:mm:ss`。负值与 0 都显示 `0:00`。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun formatClock(dateTime: LocalDateTime = LocalDateTime.now()): String = dateTime.format(clock)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.1f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format("%.0f KiB", bytes / 1_024.0)
    else -> "$bytes B"
}
