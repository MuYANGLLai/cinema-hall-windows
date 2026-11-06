package com.cinemahall.core.rtc

/**
 * 一条 ICE 候选的摘要。
 *
 * **刻意全是 `String?` 而不是 `org.webrtc` 的类型**：`getStats()` 给的本来就是字符串
 * （`candidateType` / `protocol` / `relayProtocol` 在 stats 里都是字符串字段），
 * 而且这样诊断页与单测都不需要碰 SDK（与接缝 1 收掉 OkHttp 是同一条理由）。
 */
data class IceCandidateSummary(
    /** `host` / `srflx` / `prflx` / `relay`。 */
    val candidateType: String?,
    /** 传输协议：`udp` / `tcp`。 */
    val protocol: String?,
    /** 中继协议：`udp` / `tcp` / `tls`。只有 relay 候选上才有。 */
    val relayProtocol: String?,
    val address: String?,
    /**
     * 端口。relay 候选上就是 `min-port`–`max-port` 段里的那个端口 ——
     * 它必须与安全组放行的端口段一致，所以诊断页要看得见（§8.4 第 3 步）。
     */
    val port: Int? = null,
) {

    val isRelay: Boolean get() = candidateType == "relay"

    val addressFamily: AddressFamily get() = AddressFamily.of(address)

    /**
     * 诊断页用的一行文案，例：`relay/udp/IPv4`。
     *
     * 三项用 `/` 连成**一个** token（不是 `relay/udp IPv4`）—— 这是自 1.4 起就上屏的形状，
     * 2026-10-09 补测时才发现注释写的是带空格的版本，属于注释漂移，已按实现对齐
     * （不动实现：验收期中途改屏幕文案没有收益，只会让已记下的现象对不上）。
     */
    val label: String
        get() = listOfNotNull(
            candidateType,
            protocol,
            addressFamily.label.takeIf { it != AddressFamily.UNKNOWN.label },
        ).joinToString("/")

    /**
     * `地址:端口`（IPv6 加方括号，否则端口会被读成地址的最后一段）。
     *
     * 依据 §8.4 第 3 步：relay-only 建连失败时，「安全组没放行 relay 段」与
     * 「`external-ip` 没生效」在界面上**完全同形** —— 候选都齐、一条也没选出来、
     * 收发 0 字节。唯一能在手机上当场把两者分开的就是候选里的地址：
     * 公网 IP = 映射正确，该去查安全组；内网 `172.24.x.x` = 回去改 coturn。
     * 只给数量与类型分布时这两件事一模一样，2026-10-08 与 10-09 两次都因此打转。
     *
     * `port` 缺失时只给地址（不编一个端口出来）。
     */
    val addressWithPort: String?
        get() {
            val host = address ?: return null
            val bracketed = if (host.contains(':')) "[$host]" else host
            return if (port == null) bracketed else "$bracketed:$port"
        }
}

/**
 * 地址族。§9.3 要求诊断页显示它，**用途是验证 §3.7.5 的 IPv6 机会是否真实存在** ——
 * 如果两端都拿不到公网 IPv6，那「IPv6 让 P2P 翻盘」这条假设就可以直接划掉。
 */
enum class AddressFamily(val label: String) {
    IPV4("IPv4"),
    IPV6("IPv6"),
    UNKNOWN("未知"),
    ;

    companion object {

        /**
         * 判定规则就是「含不含冒号」——IPv6 字面量必然含 `:`，IPv4 必然不含。
         *
         * 特意把 mDNS 主机名（libwebrtc 在某些平台会用 `xxxx.local` 代替真实 IP）
         * 判为 [UNKNOWN]：它**不能**用来回答「有没有 IPv6」，若把它当成 IPv4
         * 会让 §3.7.5 的结论反过来。
         */
        fun of(address: String?): AddressFamily {
            val value = address?.trim().orEmpty()
            if (value.isEmpty()) return UNKNOWN
            if (value.endsWith(".local", ignoreCase = true)) return UNKNOWN
            return if (value.contains(':')) IPV6 else IPV4
        }
    }
}

/**
 * 链路统计快照（诊断页数据源，§9.3 的「连接类型 / 中继协议 / RTT」三项）。
 *
 * 注意 §9.3 的一条措辞要求：**走中继不能显示成警告或异常色** ——
 * 依据 §3.7.3，中继是默认数据面、P2P 才是意外之喜。所以这里只给中性的事实
 * （[usesRelay] / [pathLabel]），把「什么算异常」留给 UI 决定。
 */
data class LinkStats(
    /** 选中 candidate pair 上的本地候选。 */
    val local: IceCandidateSummary?,
    /** 同上，远端候选。 */
    val remote: IceCandidateSummary?,
    /** 选中 pair 的 `currentRoundTripTime`，已由秒换算成毫秒。 */
    val currentRoundTripTimeMs: Double?,
    val bytesSent: Long?,
    val bytesReceived: Long?,
    /** 本端收集到的**全部**候选（排障用：数量与类型分布）。 */
    val gatheredLocalCandidates: List<IceCandidateSummary>,
) {

    val usesRelay: Boolean get() = local?.isRelay == true || remote?.isRelay == true

    /** 实际中继协议（§9.3）。 */
    val relayProtocol: String? get() = local?.relayProtocol ?: remote?.relayProtocol

    /**
     * 中性文案，不做异常色。
     *
     * **还没有选中 pair 时必须说「未选出」**，不能落到「P2P 直连」这个兜底值 ——
     * 2026-10-09 的 relay-only 排障里它骗过一次：两端都开着 `强制中继`、都还在等
     * 候选对，这一行却显示「P2P 直连」，被读成「relay-only 没生效」，
     * 白白去查开关和 coturn。依据 §9.3，这一行回答的是「**选中的是哪条**候选对」，
     * 没选中就没有类型可说。
     */
    val pathLabel: String
        get() = when {
            local == null && remote == null -> NO_PAIR_LABEL
            usesRelay -> "TURN 中继"
            else -> "P2P 直连"
        }

    /** 本地候选的类型分布，例：`{host=3, srflx=1, relay=2}`。 */
    val localTypeCounts: Map<String, Int>
        get() = gatheredLocalCandidates
            .mapNotNull { it.candidateType }
            .groupingBy { it }
            .eachCount()

    /** 本地候选的地址族分布 —— 回答 §3.7.5「IPv6 到底拿没拿到」。 */
    val localFamilyCounts: Map<AddressFamily, Int>
        get() = gatheredLocalCandidates
            .map { it.addressFamily }
            .groupingBy { it }
            .eachCount()

    companion object {
        /**
         * 还没选出候选对时「路径」那一行的文案（§9.3）。
         *
         * 刻意**不复用** `—`：`—` 在诊断页表示「这一项没有数据源」（`RtcPanel` 里
         * 各处 `?: "—"` 就是那个意思），而这里是「有数据源、只是还没有结果」，
         * 两者要在屏幕上看得出区别。
         */
        const val NO_PAIR_LABEL = "未选出"
    }
}

/**
 * 音频 RTP 流的收发计数（阶段一 1.7 的 `音频收发` 一行，2026-10-11 补）。
 *
 * ── 为什么单独一个类型，不塞进 [LinkStats] ───────────────────────────────────
 * [LinkStats] 回答的是「这条链路怎么走的」（候选、路径、RTT、字节数），它随
 * `PeerConnection` 生灭；而这两个数回答的是「通话有没有真的在走音频」，只有通话
 * 相关的一方（`CallAudioState`）才需要，且它的显示条件与通话开关绑定。
 * 混在一起的话，诊断页会开始显示「没通话时也有音频包」这种读不懂的东西
 * （对端开通话、本端没开时，包**确实**会到）。
 */
data class AudioRtpCounters(
    /** `outbound-rtp`（audio）的 `packetsSent`。null = 没有这一路的记录。 */
    val packetsSent: Long?,
    /** `inbound-rtp`（audio）的 `packetsReceived`。null = 没有这一路的记录。 */
    val packetsReceived: Long?,
)

/**
 * 把 `PeerConnection.getStats()` 的结果解析成 [LinkStats]。
 *
 * **纯函数**：输入是「每条 stats 记录一个 Map」，不碰任何 `org.webrtc` 类型。
 * 这样才能在 JVM 单测里用真实形状的 stats 夹具跑 —— 而这段解析恰恰是最容易写错、
 * 又最难在真机上定位的部分（字段名写错只表现为诊断页显示空白，不影响功能）。
 *
 * 输入形状约定：每个 Map 必须带 `id` 与 `type` 两个键。
 * `org.webrtc.RTCStats` 把它们单独暴露成 `id` / `type`，**不在 `members` 里**，
 * 所以 `WebRtcPeerLink` 收集时要把它们补回 Map（见那边的 `statsEntries()`）。
 */
object LinkStatsParser {

    fun parse(entries: List<Map<String, Any?>>): LinkStats? {
        if (entries.isEmpty()) return null

        val byId = HashMap<String, Map<String, Any?>>(entries.size)
        for (entry in entries) {
            val id = str(entry, "id") ?: continue
            byId[id] = entry
        }

        val gathered = entries
            .filter { str(it, "type") == "local-candidate" }
            .map { candidateSummary(it) }

        val pair = selectPair(entries, byId)
        val local = pair?.let { byId[str(it, "localCandidateId").orEmpty()] }?.let { candidateSummary(it) }
        val remote = pair?.let { byId[str(it, "remoteCandidateId").orEmpty()] }?.let { candidateSummary(it) }

        // 一条候选都没有、也没有任何 pair：说明 stats 还没准备好，如实返回「没有数据」
        if (pair == null && gathered.isEmpty()) return null

        // currentRoundTripTime 在 stats 里的单位是**秒**（浮点），诊断页要毫秒
        val rttSeconds = pair?.let { dbl(it, "currentRoundTripTime") }
            ?: entries.firstOrNull { str(it, "type") == "transport" }?.let { dbl(it, "currentRoundTripTime") }

        return LinkStats(
            local = local,
            remote = remote,
            currentRoundTripTimeMs = rttSeconds?.times(1_000),
            bytesSent = pair?.let { lng(it, "bytesSent") }
                ?: entries.firstOrNull { str(it, "type") == "transport" }?.let { lng(it, "bytesSent") },
            bytesReceived = pair?.let { lng(it, "bytesReceived") }
                ?: entries.firstOrNull { str(it, "type") == "transport" }?.let { lng(it, "bytesReceived") },
            gatheredLocalCandidates = gathered,
        )
    }

    /**
     * 音频 RTP 流的包计数。没有任何音频 RTP 条目时返回 null（不编一个 0 出来）。
     *
     * ── 字段名是实证过的，不是照着规范猜的 ──────────────────────────────────────
     * `io.github.webrtc-sdk:android:150.7871.01` 的 `libjingle_peerconnection_so.so`
     * （arm64-v8a，12,287,312 字节）里 `packetsSent` / `packetsReceived` /
     * `outbound-rtp` / `inbound-rtp` / `kind` / `mediaType` 六个字面量都在。
     * 这一步值得做：字段名写错**不影响功能**，只表现为屏幕上少一个读数 ——
     * 而这一格的全部价值就在那个读数上。
     *
     * 媒体类型在现版本叫 `kind`、老版本叫 `mediaType`，两个都认（与本文件里
     * `address ?: ip` 那条兼容是同一个理由）。多个同向条目**求和**：一条音频 m 行
     * 正常只会有一条，但求和不依赖这个假设。
     */
    fun parseAudioRtp(entries: List<Map<String, Any?>>): AudioRtpCounters? {
        val audio = entries.filter { isAudioRtp(it) }
        if (audio.isEmpty()) return null
        return AudioRtpCounters(
            packetsSent = sum(audio.filter { str(it, "type") == "outbound-rtp" }, "packetsSent"),
            packetsReceived = sum(audio.filter { str(it, "type") == "inbound-rtp" }, "packetsReceived"),
        )
    }

    /**
     * 回声抑制量（ERLE，dB）：`media-source`（audio）条目上的 `echoReturnLossEnhancement`。
     *
     * ── 为什么是「唯一」能看出 AEC 有没有干活的那个数 ─────────────────────────────
     * 「对端说听到回声」有两种成因，而它们在屏幕上完全同形：AEC 根本没生效、或者生效了但
     * 被外放声压压垮（评审 §3.6 第 4 条）。这一格把两者分开：0 dB 上下 = 一点都没消；
     * 15 dB 以上 = 在正常工作。值来自 `AudioProcessing::GetStatistics()`，也就是 AEC3
     * 自己的 `GetMetrics()`。
     *
     * ── 字段名是实证过的，不是照着规范猜的 ──────────────────────────────────────
     * 同一个 AAR 的 `libjingle_peerconnection_so.so`（arm64-v8a，12,287,312 字节）里
     * `media-source` / `audioLevel` / `totalAudioEnergy` / `echoReturnLossEnhancement`
     * 四个字面量都在，而**短的 `echoReturnLoss` 不在**（串表里只有长的那一个，
     * `IndexOf("echoReturnLoss")` 命中的就是长名的开头）—— 所以只读长名这一个，
     * 别照着 W3C 规范把两个都写上，那会多出一个永远为 null 的读数。
     *
     * 没有任何 `media-source`（audio）条目、或条目上没有这个字段时返回 null（不编一个
     * 0 dB 出来）：`-` 是「还没读到」，`0.0 dB` 是「读到了、AEC 一点都没消」，
     * 两者必须分得开（与 [parseAudioRtp] 那条同一条理由）。
     *
     * ⚠️ **对端没出声时它也会接近 0** —— 没有回声可消，不等于 AEC 坏了。
     */
    fun parseEchoSuppression(entries: List<Map<String, Any?>>): Double? =
        entries.firstOrNull { str(it, "type") == "media-source" && str(it, "kind") == "audio" }
            ?.let { dbl(it, "echoReturnLossEnhancement") }

    private fun isAudioRtp(entry: Map<String, Any?>): Boolean {
        val type = str(entry, "type")
        if (type != "outbound-rtp" && type != "inbound-rtp") return false
        // 媒体类型缺失的条目**不算**音频：宁可少报一个读数，也不要把视频包数当音频报。
        return (str(entry, "kind") ?: str(entry, "mediaType")) == "audio"
    }

    /** 求和。一条都没有、或所有条目都没有这个字段时返回 null（不编一个 0 出来）。 */
    private fun sum(entries: List<Map<String, Any?>>, key: String): Long? {
        if (entries.isEmpty()) return null
        val values = entries.mapNotNull { lng(it, key) }
        return if (values.isEmpty()) null else values.sum()
    }

    /**
     * 选「当前生效的那条 pair」。优先级从确定到兜底：
     *  1. `transport.selectedCandidatePairId` —— libwebrtc 自己指的那条，最可信；
     *  2. `nominated && state == succeeded` —— 标准规范里的判据；
     *  3. 只 `succeeded`；
     *  4. 只 `nominated`；
     *  5. `inprogress` —— 还在打洞时诊断页也该有东西可显示，而不是空白。
     */
    private fun selectPair(
        entries: List<Map<String, Any?>>,
        byId: Map<String, Map<String, Any?>>,
    ): Map<String, Any?>? {
        entries.firstOrNull { str(it, "type") == "transport" }
            ?.let { str(it, "selectedCandidatePairId") }
            ?.let { byId[it] }
            ?.let { return it }

        val pairs = entries.filter { str(it, "type") == "candidate-pair" }
        pairs.firstOrNull { nominated(it) && str(it, "state") == "succeeded" }?.let { return it }
        pairs.firstOrNull { str(it, "state") == "succeeded" }?.let { return it }
        pairs.firstOrNull { nominated(it) }?.let { return it }
        pairs.firstOrNull { str(it, "state") == "inprogress" }?.let { return it }
        return pairs.firstOrNull()
    }

    /** 有的版本给 `nominated: true`，老版本给 `selected: true`，两个都认。 */
    private fun nominated(entry: Map<String, Any?>): Boolean =
        bool(entry, "nominated") || bool(entry, "selected")

    private fun candidateSummary(entry: Map<String, Any?>): IceCandidateSummary = IceCandidateSummary(
        candidateType = str(entry, "candidateType"),
        protocol = str(entry, "protocol"),
        relayProtocol = str(entry, "relayProtocol"),
        // 注意键名：stats 里的本地候选字段是 `address`，不是 `ip`
        address = str(entry, "address") ?: str(entry, "ip"),
        port = int(entry, "port"),
    )

    private fun str(entry: Map<String, Any?>, key: String): String? = entry[key] as? String

    private fun int(entry: Map<String, Any?>, key: String): Int? = when (val value = entry[key]) {
        // `port` 在 stats 里是数字（unsigned short），不取字符串
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun bool(entry: Map<String, Any?>, key: String): Boolean = when (val value = entry[key]) {
        is Boolean -> value
        // 有些平台把布尔值也序列化成字符串
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun dbl(entry: Map<String, Any?>, key: String): Double? = when (val value = entry[key]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun lng(entry: Map<String, Any?>, key: String): Long? = when (val value = entry[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
