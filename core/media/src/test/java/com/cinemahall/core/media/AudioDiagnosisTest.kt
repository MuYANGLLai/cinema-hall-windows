package com.cinemahall.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioDiagnosis] 的判定与措辞。
 *
 * ── 这里为什么只能测**纯函数**那一半 ────────────────────────────────────────
 * `AudioDiagnosis.tracks(Tracks)` 要一个真的 `Tracks`，那只能由一次真的 `prepare()` 产出
 * —— 本模块的单测全是纯 JVM，没有设备。所以 `tracks(...)` 由真机验收覆盖
 * （见《验收操作手册》§7.16），这里锁住的是「拿到轨道表之后怎么判、怎么说」。
 *
 * ── 为什么这些措辞值得写测试 ────────────────────────────────────────────────
 * 要防回归的是**分支判据**，不是字面量：
 *  1. 判「一条都解不了」而不是「有解不了的」—— 后者在多音轨片源上是误报；
 *  2. 「扩展覆盖」与「扩展也不覆盖」两类必须分开说 —— 前者指向查打包，后者指向换片源；
 *  3. `EXTENSION_ONLY_MIMES` 的边界（`AUDIO_AC4` / `AUDIO_DTS_X` 这类「看着像 DTS 家族
 *     但扩展其实不提供」的 mime）必须落在第二类。
 */
class AudioDiagnosisTest {

    private fun track(mime: String, supported: Boolean) = AudioTrackSupport(mime, supported)

    // ── notice：什么时候不该说话 ───────────────────────────────────────────────

    @Test
    fun `轨道表还没解析出来时不提示`() {
        assertNull(AudioDiagnosis.notice(emptyList()))
    }

    @Test
    fun `有音轨且能解时不提示`() {
        assertNull(AudioDiagnosis.notice(listOf(track(AudioMimeTypes.AAC, supported = true))))
    }

    @Test
    fun `多条音轨里只要有一条能解就不提示`() {
        // ExoPlayer 会在能解的里面挑一条，那时是有声音的。
        // 这时报「解不了 DTS」是误报，只会让人去查一个不存在的问题。
        val tracks = listOf(
            track(AudioMimeTypes.DTS, supported = false),
            track(AudioMimeTypes.AAC, supported = true),
        )
        assertNull(AudioDiagnosis.notice(tracks))
    }

    // ── notice：一条都解不了时该说什么 ─────────────────────────────────────────

    @Test
    fun `DTS 全解不了时判为扩展没生效`() {
        // 这是本轮那份真片源：`A_DTS` / 7 声道 / 48 kHz / 24-bit。
        val notice = AudioDiagnosis.notice(listOf(track(AudioMimeTypes.DTS, supported = false)))
        assertNotNull("DTS 全解不了时必须给提示", notice)
        val shown = notice!!
        assertTrue("应点出是哪种编码：$shown", shown.contains("audio/vnd.dts"))
        assertTrue("应指出扩展没生效：$shown", shown.contains("扩展没有生效"))
        // 必须给一个**可查**的东西，而不是「请联系管理员」。
        assertTrue("应点名要检查的产物：$shown", shown.contains("libffmpegJNI.so"))
        // 全部都在扩展覆盖范围内时不该再劝人换片源 —— 那会把注意力从真正的原因上引开。
        assertFalse("不该给换片源的建议：$shown", shown.contains("换音轨或换片源"))
    }

    @Test
    fun `扩展覆盖范围内的多种编码不支持时全部列出来且按序拼接`() {
        val tracks = listOf(
            track(AudioMimeTypes.TRUEHD, supported = false),
            track(AudioMimeTypes.DTS_HD, supported = false),
        )
        val shown = AudioDiagnosis.notice(tracks)!!
        // 去重 + 排序后再拼，措辞才稳定（否则同一条提示每次出现的顺序可能不同）。
        assertTrue("应按排序拼接：$shown", shown.contains("audio/true-hd、audio/vnd.dts.hd"))
        assertTrue("应指出扩展没生效：$shown", shown.contains("扩展没有生效"))
        assertTrue("应说「都由扩展提供」：$shown", shown.contains("这些编码都由软解扩展提供"))
    }

    @Test
    fun `重复的编码只列一次`() {
        val tracks = listOf(
            track(AudioMimeTypes.DTS, supported = false),
            track(AudioMimeTypes.DTS, supported = false),
        )
        val shown = AudioDiagnosis.notice(tracks)!!
        // 都在扩展覆盖范围内 ⇒ mime 只在开头那一句里出现一次，不再重复。
        assertEquals("重复编码应去重", 1L, countOf(shown, "audio/vnd.dts"))
    }

    @Test
    fun `两类编码混在一起时两句都要给`() {
        // 关键分支：DTS 说明扩展没生效，AC-4 说明扩展也解不了它 —— 处置不同，缺一句就漏一件事。
        val tracks = listOf(
            track(AudioMimeTypes.DTS, supported = false),
            // 映射表里有 `ac4` 之名，但实测 `.so` 里 `ff_ac4_decoder` 0 命中 ⇒ 扩展也解不了它。
            track(AudioMimeTypes.AC4, supported = false),
        )
        val shown = AudioDiagnosis.notice(tracks)!!
        assertTrue("应列出两种编码：$shown", shown.contains("audio/ac4、audio/vnd.dts"))
        assertTrue("应指明哪一类是扩展的问题：$shown", shown.contains("其中 audio/vnd.dts 由软解扩展提供"))
        assertTrue("应指出扩展没生效：$shown", shown.contains("扩展没有生效"))
        assertTrue("应给换片源的建议：$shown", shown.contains("audio/ac4 不在软解扩展覆盖范围内"))
    }

    @Test
    fun `只在扩展不覆盖的编码上不支持时不说是扩展问题`() {
        // `audio/vnd.dts.uhd;profile=p2`（DTS:X）：mime→codec 映射表里没有它，
        // 所以扩展正常加载也解不了 —— 这正是「看着像 DTS 家族但不能进表」的那个坑。
        val shown = AudioDiagnosis.notice(listOf(track(AudioMimeTypes.DTS_X, supported = false)))!!
        assertFalse("DTS:X 不在映射表里，不该判成扩展问题：$shown", shown.contains("扩展没有生效"))
        assertTrue("应给出换片源的建议：$shown", shown.contains("换音轨或换片源"))
    }

    @Test
    fun `认不出的编码走通用措辞`() {
        val shown = AudioDiagnosis.notice(listOf(track(AudioMimeTypes.UNKNOWN, supported = false)))!!
        assertTrue("应把 mime 原样给出：$shown", shown.contains(AudioMimeTypes.UNKNOWN))
        assertFalse("不得误诊为扩展问题：$shown", shown.contains("扩展没有生效"))
    }

    // ── errorText ─────────────────────────────────────────────────────────────

    @Test
    fun `每个错误码都带上原始码名，便于照屏幕念`() {
        val names = listOf(
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODER_QUERY_FAILED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_DECODING_RESOURCES_RECLAIMED",
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            "ERROR_CODE_IO_NO_PERMISSION",
            "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE",
            "ERROR_CODE_AUDIO_TRACK_INIT_FAILED",
            "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED",
            "ERROR_CODE_TIMEOUT",
        )
        for (name in names) {
            val text = AudioDiagnosis.errorText(name, message = null)
            assertTrue("$name 的措辞里应带上码名：$text", text.contains("（$name）"))
            assertTrue("$name 的措辞不应是空壳：$text", text.length > name.length + 4)
        }
    }

    @Test
    fun `缺解码器与音频输出初始化失败要分开说`() {
        // 这两条的处置完全不同（换片源 vs 释放音频设备），措辞混了就等于没报。
        val decode = AudioDiagnosis.errorText("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", null)
        val audio = AudioDiagnosis.errorText("ERROR_CODE_AUDIO_TRACK_INIT_FAILED", null)
        assertTrue(decode.startsWith("本机没有这条轨道所需的解码器"))
        assertTrue(audio.startsWith("音频输出初始化失败"))
    }

    @Test
    fun `认不出的错误码退化为通用措辞但仍然带码名`() {
        assertEquals(
            "播放失败（ERROR_CODE_SOMETHING_NEW）",
            AudioDiagnosis.errorText("ERROR_CODE_SOMETHING_NEW", null),
        )
    }

    @Test
    fun `有原始信息时附在第二行`() {
        val text = AudioDiagnosis.errorText(
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            "MediaCodecAudioRenderer error, index=0, format=Format(1, audio/vnd.dts)",
        )
        assertTrue(text.contains("\n原始信息：MediaCodecAudioRenderer error"))
    }

    @Test
    fun `空白或缺失的原始信息不产生第二个空行`() {
        for (message in listOf(null, "", "   ")) {
            val text = AudioDiagnosis.errorText("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", message)
            assertFalse("空白的 message 不该产生第二行：$text", text.contains('\n'))
            assertFalse("空白的 message 不该产生「原始信息」：$text", text.contains("原始信息"))
        }
    }

    @Test
    fun `空码名不会拼出括号以外的怪东西`() {
        // 防御性：`getErrorCodeName()` 理论上不会为空，真为空时也应可读。
        assertEquals("播放失败（）", AudioDiagnosis.errorText("", null))
    }

    private fun countOf(haystack: String, needle: String): Long {
        var count = 0L
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }
}
