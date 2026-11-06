package com.cinemahall.core.media

/**
 * 一条音轨在本机的可解性。
 *
 * @property mimeType 轨道表里那条音轨的 MIME。认不出时是 [MimeTypes.AUDIO_UNKNOWN]。
 * @property supported 本机（平台解码器 + FFmpeg 软解扩展）能不能解它。
 */
data class AudioTrackSupport(
    val mimeType: String,
    val supported: Boolean,
)

/**
 * 「为什么没有声音」的判定与措辞（2026-10-09 加）。
 *
 * ── 它是为哪个具体故障建的 ──────────────────────────────────────────────────
 * 实测一部 `A_DTS` 的 MKV（7 声道 / 48 kHz）：**画面正常、一条错误都不报、就是没有声音**。
 * 根因是本机播放栈里没有任何 DTS 解码器 —— 平台解码器清单里从来没有 DTS，而 ExoPlayer
 * 的 DTS 解码器 `dca` 只存在于 FFmpeg 扩展里。这个类做的就是**把这件事说出来**：
 * 从轨道表判断「有音轨、但一条都解不了」，并给出一句能据此行动的话。
 *
 * ── 为什么判据是「一条都解不了」而不是「有解不了的那条」──────────────
 * 这条提示说的是「本机缺解码器」，所以它只该在**真的会没声音**时出现。
 * 一个 MKV 可以有多条音轨，而轨道表上的 `isTrackSupported` 已经是「任一渲染器能解」的
 * 或值（平台解码器与扩展解码器是叠加的）：只要还有一条能解，正常那台机器上就有声音，
 * 这时报「解不了 DTS」只会让人去查一个不存在的问题 ⇒ 不报。
 * 反过来，「一条都解不了」意味着没有任何一条轨道存在解码路径 ⇒ **必然无声**，
 * 那句话才值得占位置。这个方向不依赖选择器怎么挑轨道。
 * （万一「有能解的、但播放仍然失败」，那会以真错误的形式走 `onPlayerErrorChanged`，
 * 由 `AudioDiagnosis.errorText` 那条路报出来 —— 两条路覆盖的是不同的故障。）
 *
 * ── [EXTENSION_ONLY_MIMES] 那张表为什么必须按实测来 ─────────────────────────
 * 它承载一个**反向推断**：某个 mime 报告「不支持」，就能断定软解扩展没生效。这个推断
 * 只在「扩展**确实**提供该编码」时成立，否则会把「这条编码连扩展也没有」误诊成
 * 「扩展没装上」，让人去查一个压根不存在的打包问题。所以那张表是逐条对着
 * `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` 的字节码与 `.so` 符号核出来的，
 * 结果见它自己的注释 —— 其中 `audio/ac4`、`audio/gsm`、`audio/vorbis`、`audio/opus`
 * 虽然在本 build 的 mime→codec 映射表里，但 `.so` 里**没有**对应解码器，因此**不能**进表。
 */
object AudioDiagnosis {

    /**
     * 「这段影片会全程无声」的一句话。返回 null = 不需要提示。
     *
     * 三种情况返回 null：轨道表还没解析出来（刚进页面）、本来就没有音轨、至少有一条能解。
     *
     * ── 为什么分两段说 ────────────────────────────────────────────────────────
     * 不支持的那些编码分两类，处置完全不同：**扩展覆盖的**（不支持 ⇒ 扩展没生效 ⇒ 去查打包）
     * 与**扩展也不覆盖的**（⇒ 只能换音轨 / 换片源）。两者同时出现时两句都要给，
     * 否则会漏掉其中一件该做的事。都是扩展覆盖的那一支不再重复列一遍 mime —— 上一句刚写过。
     */
    fun notice(tracks: List<AudioTrackSupport>): String? {
        if (tracks.isEmpty() || tracks.any { it.supported }) return null

        val unsupported = tracks.map { it.mimeType }.distinct().sorted()
        val covered = unsupported.filter { it in EXTENSION_ONLY_MIMES }
        val uncovered = unsupported.filterNot { it in EXTENSION_ONLY_MIMES }

        val head = "本机一条音轨都解不了（${unsupported.joinToString("、")}）。"

        // 只要有一条落在扩展覆盖范围内，就能断定扩展没生效 —— 若它生效了，那条本来会被解掉。
        val coveredPart = when {
            covered.isEmpty() -> ""
            covered.size == unsupported.size ->
                "这些编码都由软解扩展提供 —— 出现这一行说明扩展没有生效：" +
                    "先查 APK 里有没有带 arm64-v8a 的 libffmpegJNI.so，而不是去换片源。"
            else ->
                "其中 ${covered.joinToString("、")} 由软解扩展提供 —— 出现这一行说明扩展没有生效：" +
                    "先查 APK 里有没有带 arm64-v8a 的 libffmpegJNI.so。"
        }

        val uncoveredPart = if (uncovered.isEmpty()) {
            ""
        } else {
            "${uncovered.joinToString("、")} 不在软解扩展覆盖范围内，这几条只能换音轨或换片源。"
        }

        return head + coveredPart + uncoveredPart
    }

    /**
     * 播放错误的一句话解释，末尾带上原始错误码（验收时照着念就能定位）。
     *
     * @param errorCodeName `PlaybackException.getErrorCodeName()`。
     * @param message `PlaybackException.getMessage()`，通常是底层那一长串（含 mime、编解码器名）。
     *   有就附在后面 —— 「看不到失败原因」正是这次要修的东西，把人话和原始信息一起给出来，
     *   既不要求用户看懂 `MediaCodecAudioRenderer error, index=0, format=...`，也不必再去翻日志。
     */
    fun errorText(errorCodeName: String, message: String?): String {
        val explain = when (errorCodeName) {
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED" ->
                "本机没有这条轨道所需的解码器"
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES" ->
                "这条轨道超出本机解码能力（分辨率 / 声道数 / 码率高过硬件上限）"
            "ERROR_CODE_DECODER_INIT_FAILED" ->
                "解码器初始化失败（硬件解码器可能已被别的应用占满）"
            "ERROR_CODE_DECODER_QUERY_FAILED" ->
                "查询本机解码器能力失败"
            "ERROR_CODE_DECODING_FAILED" ->
                "解码失败（片源这一段可能已损坏）"
            "ERROR_CODE_DECODING_RESOURCES_RECLAIMED" ->
                "解码资源被系统回收（后台内存压力过大，重进页面通常能恢复）"
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ->
                "这个容器格式不被支持"
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED" ->
                "容器结构损坏，读到一半读不下去了"
            "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED", "ERROR_CODE_PARSING_MANIFEST_MALFORMED" ->
                "这个流媒体清单不被支持或已损坏"
            "ERROR_CODE_IO_FILE_NOT_FOUND" ->
                "文件打不开了（被移动 / 删除，或上次选文件给的授权已失效）"
            "ERROR_CODE_IO_NO_PERMISSION" ->
                "没有读取这个文件的权限"
            "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE" ->
                "读到了文件之外（文件比它自己声明的长度短？）"
            "ERROR_CODE_AUDIO_TRACK_INIT_FAILED" ->
                "音频输出初始化失败（音频设备被占用或不可用）"
            "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED" ->
                "往音频设备写数据失败"
            "ERROR_CODE_TIMEOUT" ->
                "操作超时"
            "ERROR_CODE_UNSPECIFIED" ->
                "播放失败"
            else ->
                "播放失败"
        }

        val head = "$explain（$errorCodeName）"
        val detail = message?.takeIf { it.isNotBlank() }
        return if (detail == null) head else "$head\n原始信息：$detail"
    }

    /**
     * 「只有软解扩展能解」的编码。
     *
     * ── 入表条件（两条都要满足，缺一不可）────────────────────────────────────────
     *  1. `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` 的 mime→codec 映射表里有它
     *     （`FfmpegLibrary.getCodecName` 反汇编：本 build 的表共 20 项）；
     *  2. 该 codec 的解码器**确实编进了** `libffmpegJNI.so`（arm64-v8a 版逐符号核过）。
     * 另外这些编码在 Android 官方 supported media formats 的音频解码器清单里**没有**
     * 保证（有的机型带，所以真带的时候 `isTrackSupported` 会是 true，那就不进这条提示）。
     *
     * ── 逐条依据（mime → codec → `.so` 符号）─────────────────────────────────────
     * ```
     * audio/ac3          -> ac3       -> ff_ac3_decoder       ✓
     * audio/eac3         -> eac3      -> ff_eac3_decoder      ✓
     * audio/eac3-joc     -> eac3      -> ff_eac3_decoder      ✓   （JOC 由 eac3 解码器吃掉）
     * audio/true-hd      -> truehd    -> ff_mlp_decoder       ✓
     * audio/vnd.dts      -> dca       -> ff_dca_decoder       ✓
     * audio/vnd.dts.hd   -> dca       -> ff_dca_decoder       ✓
     * audio/alac         -> alac      -> ff_alac_decoder      ✓
     * audio/g711-mlaw    -> pcm_mulaw -> ff_pcm_mulaw_decoder ✓
     * audio/g711-alaw    -> pcm_alaw  -> ff_pcm_alaw_decoder  ✓
     * ```
     *
     * ── 故意**不**入表的那些 ────────────────────────────────────────────────────
     *  - `MimeTypes.AUDIO_DTS_EXPRESS`（`audio/vnd.dts.hd;profile=lbr`）与 `AUDIO_DTS_X`
     *    （`audio/vnd.dts.uhd;profile=p2`）：**映射表里根本没有这两个 mime**，所以即使
     *    扩展正常加载也解不了。当成「扩展没装上」是误诊。
     *  - `MimeTypes.AUDIO_AC4`（表里有 `ac4` 之名，但 `.so` 里 `ff_ac4_decoder` 0 命中）、
     *    `AUDIO_MSGSM`（`ff_gsm_decoder` 0 命中）、`AUDIO_VORBIS` / `AUDIO_OPUS`
     *    （映射表里有，`.so` 里 0 命中）：同一条理由。
     *  - `MimeTypes.AUDIO_UNKNOWN`：认不出的编码，说什么都是猜。
     *
     * ⚠️ **换扩展版本或升 media3 时必须重核这张表** —— 它描述的是那个制品的编入情况，
     * 不是 media3 的 API 契约。重核方法：`javap -c -p -constants
     * androidx.media3.decoder.ffmpeg.FfmpegLibrary` 看映射表，再在 `libffmpegJNI.so` 里
     * 数 `ff_<codec>_decoder` 符号。
     */
    private val EXTENSION_ONLY_MIMES: Set<String> = setOf(
        AudioMimeTypes.AC3,
        AudioMimeTypes.E_AC3,
        AudioMimeTypes.E_AC3_JOC,
        AudioMimeTypes.TRUEHD,
        AudioMimeTypes.DTS,
        AudioMimeTypes.DTS_HD,
        AudioMimeTypes.ALAC,
        AudioMimeTypes.MLAW,
        AudioMimeTypes.ALAW,
    )
}
