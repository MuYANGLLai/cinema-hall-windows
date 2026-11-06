package com.cinemahall.core.rtc

/**
 * 音频 m 行的方向（契约 §8.3）。
 *
 * 纯 JVM 的 `CallAudio` 不再 import `org.webrtc.RtpTransceiverDirection`；
 * `core:rtc-android` 的 `LibwebrtcAudioDirection` 负责与 WebRTC 枚举互转。
 *
 * 注意：WebRTC 的 `STOPPED` 在平台映射里并入 [INACTIVE] —— 两者对
 * `localSending`/`remoteSending` 的判定完全相同。
 */
enum class AudioDirection {
    SEND_RECV,
    SEND_ONLY,
    RECV_ONLY,
    INACTIVE,
}
