package com.cinemahall.core.media

import java.text.Normalizer

/** 一次一致性校验的结论。 */
sealed interface MediaCheck {

    /** 三项全过 —— 允许开播。 */
    data object Match : MediaCheck

    /** 有一项不一致 —— **阻止开播**（§7：不提供「强制继续」）。 */
    data class Mismatch(val reason: MismatchReason) : MediaCheck
}

/**
 * 不一致的具体项。**必须区分到这一层**：任务书 §5.2 要求「任一不一致则阻止开播
 * 并**明确提示**」，只说「文件不一致」等于没说 —— 用户无法判断是自己选错文件、
 * 还是对方的片源清晰度不同。
 */
sealed interface MismatchReason {

    data class FileName(val local: String, val remote: String) : MismatchReason

    data class Size(val localBytes: Long, val remoteBytes: Long) : MismatchReason

    /**
     * [index] 是块序号，[label] 是给人看的名字（首段 / 中段 / 尾段 / 整文件）。
     *
     * 为什么把展示用的 label 放进值对象：整文件模式只有一块，此时叫「首段」是**错的**
     * （会让人以为只校验了开头），而比对处是唯一同时知道「块数」与「块序号」的地方。
     * 放到界面层再拼会把这份知识拆成两处，迟早不一致。
     */
    data class Block(
        val index: Int,
        val label: String,
        val localHash: String,
        val remoteHash: String,
    ) : MismatchReason

    /** 两端的块数不同。正常流程走不到这里（块数由 `size` 决定，而 `size` 先于它比对）。 */
    data class BlockCount(val localCount: Int, val remoteCount: Int) : MismatchReason
}

/**
 * §7 的三步比对：**文件名 → 文件大小 → 分块抽样哈希**，顺序不可换。
 *
 * 「依次」这两个字是有实际后果的：改动文件大小（截断 / 追加）必然会同时改变
 * 中段或尾段的哈希，若先比哈希就会报「第 3 块不一致」，而真实原因是大小不同。
 * 按 §7 的顺序，用户拿到的是**原因**而不是**现象**。
 */
object MediaSignatureComparator {

    /**
     * NFC 归一化。同一个文件名从不同来源（浏览器下载、拷贝、解压）到达手机时，
     * Unicode 形式可能是 NFC 也可能是 NFD —— 字节不同、**显示完全一样**。
     * 不归一化就会出现「看起来一样却说文件不一致」这种无法解释的误报，
     * 中文与 emoji 文件名尤其容易踩到。
     */
    fun normalizeFileName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

    fun compare(local: MediaSignature, remote: MediaSignature): MediaCheck {
        if (normalizeFileName(local.fileName) != normalizeFileName(remote.fileName)) {
            return MediaCheck.Mismatch(MismatchReason.FileName(local.fileName, remote.fileName))
        }

        if (local.sizeBytes != remote.sizeBytes) {
            return MediaCheck.Mismatch(MismatchReason.Size(local.sizeBytes, remote.sizeBytes))
        }

        // 防御分支：size 相同则两端必然用同一条抽样规则，块数也会相同。
        // 留着是因为「本机摘要来自旧版本客户端」这件事在真机上真的会发生。
        if (local.blocks.size != remote.blocks.size) {
            return MediaCheck.Mismatch(
                MismatchReason.BlockCount(local.blocks.size, remote.blocks.size),
            )
        }

        local.blocks.forEachIndexed { index, localHash ->
            val remoteHash = remote.blocks[index]
            if (localHash != remoteHash) {
                return MediaCheck.Mismatch(
                    MismatchReason.Block(
                        index = index,
                        label = local.blockLabel(index),
                        localHash = localHash,
                        remoteHash = remoteHash,
                    ),
                )
            }
        }

        return MediaCheck.Match
    }
}

/** 直接给界面用的一句话结论。集中在这里写，避免同一句话在两个界面里各写一份。 */
fun MismatchReason.describe(): String = when (this) {
    is MismatchReason.FileName ->
        "文件名不一致：本机「$local」，对端「$remote」"

    is MismatchReason.Size ->
        "文件大小不一致：本机 ${formatByteSize(localBytes)}，对端 ${formatByteSize(remoteBytes)}" +
            "（相差 ${formatByteSize(kotlin.math.abs(localBytes - remoteBytes))}）"

    is MismatchReason.Block ->
        "$label 取样哈希不一致（第 ${index + 1} 块）：本机 ${localHash.take(16)}…，" +
            "对端 ${remoteHash.take(16)}…"

    is MismatchReason.BlockCount ->
        "摘要结构不一致：本机 $localCount 块、对端 $remoteCount 块（多半是两端客户端版本不同）"
}

/**
 * 人类可读的字节数。**公开**：界面要显示两端摘要的大小，若各处再写一份换算，
 * 「本机 1.9 GiB / 对端 1948 MiB」这种看起来像不同的大小迟早会出现。
 * 只在提示文案里用，所以不做精确到小数点的换算。
 */
fun formatByteSize(bytes: Long): String {
    val abs = kotlin.math.abs(bytes)
    return when {
        abs < 1024 -> "$bytes 字节"
        abs < 1024L * 1024 -> "${bytes / 1024} KiB"
        abs < 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024)} MiB"
        else -> "${bytes / (1024L * 1024 * 1024)} GiB"
    }
}