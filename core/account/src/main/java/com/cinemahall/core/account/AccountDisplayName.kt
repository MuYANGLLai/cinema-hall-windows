package com.cinemahall.core.account

/**
 * 显示名的归一化（契约 §8.5 的 P2 = B+ 定稿）。
 *
 * 规则与服务端 `accounts.mjs` 的 `normalizeDisplayName` 逐条对齐：
 *  - 去首尾空白后长度 **1..32**；
 *  - 拒绝控制字符（含换行、制表、零宽字符）；
 *  - 不合法一律 `BAD_PAYLOAD`，**不做静默回退**（不会「截断到 32 个字」）。
 *
 * 客户端先校验的理由与码归一化相同：让错误当场出现在输入框下面，而不是等一个往返。
 */
object AccountDisplayName {

    const val MAX_LENGTH = 32

    /** @return 归一化后的名字；不合法返回 null。 */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        // 只去**首尾**空白：中间的空格是名字的一部分（「张 三」）。
        val trimmed = raw.trim()
        if (trimmed.length !in 1..MAX_LENGTH) return null
        for (ch in trimmed) {
            // `isISOControl` 覆盖 0x00-0x1F 与 0x7F-0x9F；显式挡零宽字符
            // （U+200B..U+200D、U+FEFF）—— 它们在视觉上不可见，会让「名字看起来一样
            // 但其实是两个人」。
            if (ch.isISOControl()) return null
            if (ch in '\u200B'..'\u200D' || ch == '\uFEFF') return null
        }
        return trimmed
    }

    /** 服务端也会拒绝空名；这里给界面用的人话原因。 */
    fun validationMessage(raw: String?): String? = when {
        raw == null -> "昵称不能为空"
        raw.trim().isEmpty() -> "昵称不能为空"
        raw.trim().length > MAX_LENGTH -> "昵称最多 $MAX_LENGTH 个字符"
        normalize(raw) == null -> "昵称包含不允许的字符"
        else -> null
    }
}
