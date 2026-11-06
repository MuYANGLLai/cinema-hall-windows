package com.cinemahall.core.media

/**
 * 平台无关的片源引用（契约 §8.2）。
 *
 * - Android：`content://` 或 `file://`
 * - 桌面：绝对路径
 *
 * 播放实现自己负责把 [uri] 解析成平台类型；端口不再暴露 `android.net.Uri`。
 */
data class MediaRef(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
)
