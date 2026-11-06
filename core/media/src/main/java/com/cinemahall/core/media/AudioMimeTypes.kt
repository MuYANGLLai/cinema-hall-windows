package com.cinemahall.core.media

/**
 * `AudioDiagnosis` 用到的 MIME 常量。
 *
 * Phase 1 起 `core:media` 是纯 JVM，不能 import `androidx.media3.common.MimeTypes`。
 * 这些字符串与 Media3 的常量取值一致；`AudioDiagnosisAdapter`（`core:media-android`）
 * 负责把 Media3 的 `Tracks` 映射成 [AudioTrackSupport]。
 */
internal object AudioMimeTypes {
    const val AAC = "audio/mp4a-latm"
    const val AC3 = "audio/ac3"
    const val E_AC3 = "audio/eac3"
    const val E_AC3_JOC = "audio/eac3-joc"
    const val TRUEHD = "audio/true-hd"
    const val DTS = "audio/vnd.dts"
    const val DTS_HD = "audio/vnd.dts.hd"
    const val DTS_X = "audio/vnd.dts.uhd;profile=p2"
    const val ALAC = "audio/alac"
    const val MLAW = "audio/g711-mlaw"
    const val ALAW = "audio/g711-alaw"
    const val AC4 = "audio/ac4"
    const val UNKNOWN = "audio/unknown"
}
