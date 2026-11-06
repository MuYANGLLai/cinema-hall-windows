package com.cinemahall.core.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LinkStatsParser] 的纯逻辑单测。
 *
 * 为什么这段值得认真测：`getStats()` 的字段名非常容易记错
 * （`address` 而不是 `ip`、`currentRoundTripTime` 的单位是**秒**而不是毫秒、
 * `nominated` 与 `selected` 两个版本都存在），而**写错的症状只是诊断页显示空白**，
 * 功能完全正常 —— 靠真机手测几乎不可能发现。这里用真实形状的 stats 夹具逐项钉住。
 *
 * 夹具形状照着 libwebrtc 的 `RTCStatsReport` 抄：每条记录带 `id` 与 `type`，
 * 其余字段在 `members` 里。
 */
class LinkStatsParserTest {

    // ── 夹具 ───────────────────────────────────────────────────────────────

    /** §3.7.3 的**默认**形态：走得是 TURN 中继，远端还是 IPv6。 */
    private val relayPath = listOf(
        mapOf(
            "id" to "CP1",
            "type" to "candidate-pair",
            "state" to "succeeded",
            "nominated" to true,
            "localCandidateId" to "CL1",
            "remoteCandidateId" to "CR1",
            "currentRoundTripTime" to 0.042,
            "bytesSent" to 12_345L,
            "bytesReceived" to 67_890L,
        ),
        mapOf(
            "id" to "CL1",
            "type" to "local-candidate",
            "candidateType" to "relay",
            "protocol" to "udp",
            "relayProtocol" to "udp",
            "address" to "47.100.1.2",
        ),
        mapOf(
            "id" to "CR1",
            "type" to "remote-candidate",
            "candidateType" to "host",
            "protocol" to "udp",
            "address" to "2408:8240:5a00::1",
        ),
        mapOf(
            "id" to "CL2",
            "type" to "local-candidate",
            "candidateType" to "host",
            "protocol" to "udp",
            "address" to "192.168.1.9",
        ),
        mapOf(
            "id" to "CL3",
            "type" to "local-candidate",
            "candidateType" to "srflx",
            "protocol" to "udp",
            "address" to "120.244.1.1",
        ),
        mapOf("id" to "T1", "type" to "transport", "selectedCandidatePairId" to "CP1"),
    )

    // ── 用例 ───────────────────────────────────────────────────────────────

    @Test
    fun `走中继时给出中继路径与中继协议_且不判成异常`() {
        val stats = LinkStatsParser.parse(relayPath)!!

        assertEquals("relay", stats.local?.candidateType)
        assertTrue("本地是 relay 就该判成走中继", stats.usesRelay)
        // §9.3 要求：中继只是**中性信息**，不能标红。这里给的是中性文案。
        assertEquals("TURN 中继", stats.pathLabel)
        assertEquals("udp", stats.relayProtocol)
        assertEquals(AddressFamily.IPV4, stats.local?.addressFamily)
        // 远端是 IPv6 —— §9.3 要这一项来验证 §3.7.5 的 IPv6 机会是否真实存在
        assertEquals(AddressFamily.IPV6, stats.remote?.addressFamily)
    }

    @Test
    fun `RTT 从秒换算成毫秒`() {
        // 写成「直接取 currentRoundTripTime」不会有任何报错，只是诊断页显示 0.042 ms
        assertEquals(42.0, LinkStatsParser.parse(relayPath)!!.currentRoundTripTimeMs!!, 1e-9)
    }

    @Test
    fun `收发字节取自选中 pair_而不是 transport 或默认值`() {
        val stats = LinkStatsParser.parse(relayPath)!!
        assertEquals(12_345L, stats.bytesSent)
        assertEquals(67_890L, stats.bytesReceived)
    }

    @Test
    fun `本地候选的类型与地址族分布要能回答有没有拿到 IPv6`() {
        val stats = LinkStatsParser.parse(relayPath)!!

        assertEquals(3, stats.gatheredLocalCandidates.size)
        assertEquals(mapOf("relay" to 1, "host" to 1, "srflx" to 1), stats.localTypeCounts)
        assertEquals(mapOf(AddressFamily.IPV4 to 3), stats.localFamilyCounts)
    }

    /**
     * `transport.selectedCandidatePairId` 是 libwebrtc 自己指的「当前这条」，
     * **优先级必须高于**按 `nominated && succeeded` 扫出来的那条 ——
     * ICE 切换路径（例如从 relay 换上 P2P）后会短暂存在多条 nominated+succeeded 的 pair，
     * 此时若按扫描取第一条，诊断页会显示成**已经不用了的那条**。
     */
    @Test
    fun `transport 指定的 pair 优先于扫描命中的 pair`() {
        val entries = relayPath + listOf(
            mapOf(
                "id" to "CP2",
                "type" to "candidate-pair",
                "state" to "succeeded",
                "nominated" to true,
                "localCandidateId" to "CL2",
                "remoteCandidateId" to "CR1",
                "currentRoundTripTime" to 0.5,
            ),
        )

        val stats = LinkStatsParser.parse(entries)!!

        // transport 指的是 CP1（经 CL1 = relay），不是扫到的 CP2（经 CL2 = host）
        assertEquals("relay", stats.local?.candidateType)
        assertEquals(42.0, stats.currentRoundTripTimeMs!!, 1e-9)
    }

    /** 没有 transport 指向时，退回 `nominated && succeeded`。 */
    @Test
    fun `没有 transport 指示时按 nominated 与 succeeded 挑`() {
        val entries = relayPath.filterNot { it["type"] == "transport" } + listOf(
            mapOf(
                "id" to "CP2",
                "type" to "candidate-pair",
                "state" to "succeeded",
                "nominated" to false,
                "localCandidateId" to "CL2",
                "remoteCandidateId" to "CR1",
            ),
        )

        val stats = LinkStatsParser.parse(entries)!!

        // CP1 是 nominated=true，CP2 不是 ⇒ 必须挑 CP1
        assertEquals("relay", stats.local?.candidateType)
    }

    /** 还在打洞时诊断页也该有东西可显示，而不是一片空白。 */
    @Test
    fun `只有 inprogress 的 pair_也要给出候选分布与不完整快照`() {
        val entries = listOf(
            mapOf("id" to "CP1", "type" to "candidate-pair", "state" to "inprogress", "localCandidateId" to "CL1"),
            mapOf("id" to "CL1", "type" to "local-candidate", "candidateType" to "host", "address" to "192.168.1.9"),
        )

        val stats = LinkStatsParser.parse(entries)!!

        assertEquals("host", stats.local?.candidateType)
        assertFalse(stats.usesRelay)
        assertEquals("P2P 直连", stats.pathLabel)
        assertNull("还没出 RTT 就该是 null，而不是 0", stats.currentRoundTripTimeMs)
    }

    @Test
    fun `空 stats 返回 null_让 UI 显示破折号而不是 0`() {
        assertNull(LinkStatsParser.parse(emptyList()))
    }

    /** 只有一条候选、没有任何 pair：仍然给候选分布（排障时这正是最需要的信息）。 */
    @Test
    fun `只有候选没有 pair_也返回候选分布`() {
        val stats = LinkStatsParser.parse(
            listOf(mapOf("id" to "CL1", "type" to "local-candidate", "candidateType" to "host", "address" to "10.0.0.2")),
        )!!

        assertEquals(1, stats.gatheredLocalCandidates.size)
        assertNull("没有 pair 就不该编造 RTT", stats.local)
        assertNull(stats.currentRoundTripTimeMs)
    }

    /**
     * 还没选出候选对时，「路径」那一行必须是「未选出」。
     *
     * 这条是 2026-10-09 的真实误判换来的：两端都开着 `强制中继（relay-only）`、
     * 本地候选也已经产出了 **2 条 relay**，但 IceConnectionState 已是 FAILED、
     * 「选中候选对」两行都是 `—`。此时旧实现按 `usesRelay == false` 落到兜底值
     * 「P2P 直连」，与 relay-only 开关直接矛盾 —— 读的人只能去查开关和 coturn。
     * 而事实上「路径」问的是「选中了哪条候选对」，没选中就没有类型可说。
     */
    @Test
    fun `没有选出候选对时_路径说未选出而不是 P2P 直连`() {
        // 形状照抄真机上那张图：两条 relay 候选、没有任何 candidate-pair
        val stats = LinkStatsParser.parse(
            listOf(
                mapOf(
                    "id" to "CL1", "type" to "local-candidate",
                    "candidateType" to "relay", "protocol" to "udp",
                    "address" to "101.133.234.226",
                ),
                mapOf(
                    "id" to "CL2", "type" to "local-candidate",
                    "candidateType" to "relay", "protocol" to "tcp",
                    "address" to "101.133.234.226",
                ),
            ),
        )!!

        assertNull(stats.local)
        assertNull(stats.remote)
        assertFalse("没选中 pair 就不能说是中继", stats.usesRelay)
        assertEquals(LinkStats.NO_PAIR_LABEL, stats.pathLabel)
        assertEquals("未选出", stats.pathLabel)
        assertEquals(mapOf("relay" to 2), stats.localTypeCounts)
    }

    /**
     * 候选必须给出**地址（带端口）**，不能只给数量与类型分布。
     *
     * 依据 §8.4 第 3 步：relay-only 建连失败时，「安全组没放行 relay 段」与
     * 「`external-ip` 没生效」在界面上完全同形（候选齐、一条没选出、收发 0 字节）。
     * 手机端唯一能当场分开两者的是候选里的地址：`101.133.234.226:5xxxx` = 公网映射正确、
     * 该去查安全组；`172.24.x.x:5xxxx` = 回去改 coturn。少了这一行，两个成因就没法区分。
     */
    @Test
    fun `候选要给出地址与端口_才能分开公网映射与内网地址`() {
        val stats = LinkStatsParser.parse(
            listOf(
                mapOf(
                    "id" to "CL1", "type" to "local-candidate",
                    "candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "udp",
                    "address" to "101.133.234.226", "port" to 52341,
                ),
                mapOf(
                    "id" to "CL2", "type" to "local-candidate",
                    "candidateType" to "relay", "protocol" to "tcp", "relayProtocol" to "tcp",
                    // 内网地址也必须原样显示 —— 它正是「external-ip 没生效」的判据
                    "address" to "172.24.63.242", "port" to 54021,
                ),
                mapOf(
                    "id" to "CL3", "type" to "local-candidate",
                    "candidateType" to "host", "address" to "192.168.1.9",
                ),
            ),
        )!!

        assertEquals("101.133.234.226:52341", stats.gatheredLocalCandidates[0].addressWithPort)
        assertEquals("relay/udp/IPv4", stats.gatheredLocalCandidates[0].label)
        assertEquals("172.24.63.242:54021", stats.gatheredLocalCandidates[1].addressWithPort)
        // 这一版 stats 没给 port 时只显示地址，**不能编**一个端口出来
        assertEquals("192.168.1.9", stats.gatheredLocalCandidates[2].addressWithPort)
    }

    /** IPv6 必须加方括号，否则端口会被读成地址的最后一段。 */
    @Test
    fun `IPv6 候选写地址端口时要加方括号`() {
        val candidate = IceCandidateSummary(
            candidateType = "relay",
            protocol = "udp",
            relayProtocol = "udp",
            address = "2408:8240:5a00::1",
            port = 3478,
        )

        assertEquals("[2408:8240:5a00::1]:3478", candidate.addressWithPort)
        assertNull(
            "没有地址就不能编出 `null:端口`",
            IceCandidateSummary(null, null, null, null, 3478).addressWithPort,
        )
    }

    @Test
    fun `地址族判定_冒号是 IPv6_不带冒号是 IPv4_mDNS 不猜`() {
        assertEquals(AddressFamily.IPV6, AddressFamily.of("2408:8240:5a00::1"))
        assertEquals(AddressFamily.IPV6, AddressFamily.of("fe80::1"))
        assertEquals(AddressFamily.IPV4, AddressFamily.of("192.168.1.9"))
        assertEquals(AddressFamily.IPV4, AddressFamily.of("0.0.0.0"))
        assertEquals(AddressFamily.UNKNOWN, AddressFamily.of(null))
        assertEquals(AddressFamily.UNKNOWN, AddressFamily.of(""))
        // mDNS 主机名必须判成未知：把它当成 IPv4 会让 §3.7.5 的 IPv6 结论反过来
        assertEquals(AddressFamily.UNKNOWN, AddressFamily.of("4f2a-8c1b-33d9.local"))
    }

    /** 缺 `id` / `type` 的记录直接跳过，不能让一条脏记录把整次解析带崩。 */
    @Test
    fun `缺 id 或 type 的记录被跳过而不是抛异常`() {
        val stats = LinkStatsParser.parse(
            listOf(
                mapOf("candidateType" to "relay"),
                mapOf("id" to "X", "candidateType" to "relay"),
                mapOf("id" to "CL1", "type" to "local-candidate", "candidateType" to "host", "address" to "10.0.0.2"),
            ),
        )!!

        assertEquals(1, stats.gatheredLocalCandidates.size)
        assertEquals("host", stats.gatheredLocalCandidates.single().candidateType)
    }

    /** 老版本把 `nominated` 叫 `selected`、把 `address` 叫 `ip`：两个别名都要认。 */
    @Test
    fun `字段别名 nominated 与 selected、address 与 ip 都要认`() {
        val stats = LinkStatsParser.parse(
            listOf(
                mapOf(
                    "id" to "CP1",
                    "type" to "candidate-pair",
                    "state" to "succeeded",
                    "selected" to true,
                    "localCandidateId" to "CL1",
                ),
                mapOf("id" to "CL1", "type" to "local-candidate", "candidateType" to "srflx", "ip" to "1.2.3.4"),
            ),
        )!!

        assertEquals("srflx", stats.local?.candidateType)
        assertEquals("1.2.3.4", stats.local?.address)
        assertEquals(AddressFamily.IPV4, stats.local?.addressFamily)
    }
}
