package com.cinemahall.core.media

/**
 * 平台画面的不透明句柄（契约 §8.2）。
 *
 * Android 实现是 `AndroidPlaybackSurface(View)`；桌面实现是 `AwtPlaybackSurface(Canvas)`。
 * 端口本身不 import 任何平台类型。
 */
interface PlaybackSurface
