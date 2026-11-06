package com.cinemahall.core.account

/**
 * 入场码 / 恢复码的归一化（与 `server/src/accounts.mjs` 的 `normalizeCode` **逐条对齐**）。
 *
 * ── 为什么客户端也要归一化 ──────────────────────────────────────────────────
 * 服务端会归一化，但客户端的归一化不是为了「替服务端做」：
 *  1. 用户抄下来的码常带空格与分隔符（`ABCD-EFGH-JKMN`），不归一化就没法做
 *     「长度/字符集」的前置校验，只能把明显打错的请求也发出去，再等一个往返的
 *     `CODE_INVALID`；
 *  2. 激活页要能**当场**告诉用户「码位数不对」，而不是转两秒圈再报错。
 *
 * 两边的规则必须一样，否则会出现「客户端说格式正确、服务端说无效」的迷惑组合。
 * 服务端原文（`accounts.mjs:77`）：
 * ```js
 * const cleaned = raw.replace(/[\s-]/g, '').toUpperCase();
 * if (cleaned.length !== 8 && cleaned.length !== 12) return null;
 * for (const ch of cleaned) if (!ROOM_CODE_ALPHABET.includes(ch)) return null;
 * ```
 *
 * ── 字符集为什么是这 31 个 ───────────────────────────────────────────────────
 * `23456789ABCDEFGHJKMNPQRSTUVWXYZ` —— 剔掉 `0/O/1/I/L`。手抄码时那几个字符
 * 分不开，是房间号沿用的同一套字母表（`server/src/roomCodes.mjs`）。
 */
object AccountCodeNormalizer {

    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    const val INVITE_LENGTH = 8
    const val RECOVERY_LENGTH = 12

    /**
     * 去空白与 `-`、转大写，校验长度（8 或 12）与字符集。
     *
     * @return 归一化后的码；**不合法返回 null**（调用方映射成 `CODE_INVALID`，
     *   与方案 §6.3 的「不合法一律 BAD_PAYLOAD / CODE_INVALID，不做静默回退」一致）。
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                if (ch.isWhitespace() || ch == '-') continue
                append(ch.uppercaseChar())
            }
        }
        if (cleaned.length != INVITE_LENGTH && cleaned.length != RECOVERY_LENGTH) return null
        for (ch in cleaned) {
            if (!ALPHABET.contains(ch)) return null
        }
        return cleaned
    }

    /** 12 位恢复码的展示形式：`XXXX-XXXX-XXXX`（8 位入场码不分组）。 */
    fun formatForDisplay(code: String): String {
        val normalized = normalize(code) ?: return code
        if (normalized.length != RECOVERY_LENGTH) return normalized
        return normalized.chunked(4).joinToString("-")
    }
}
