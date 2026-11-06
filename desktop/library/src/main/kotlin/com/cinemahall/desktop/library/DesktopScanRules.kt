package com.cinemahall.desktop.library

import java.io.File

/**
 * W2 的扫描判据（纯函数）。
 *
 * ── 为什么“白名单”而不是“黑名单” ────────────────────────────────────────────
 * Windows 上 `.mp4` 的兄弟扩展名有几十个（`.mp4v .m4v .mkv .webm .avi .wmv .mov
 * .flv .ts .m2ts .mpg .mpeg .vob .rmvb .3gp .ogv .divx`），而“其余全是视频”的
 * 黑名单写法会把 `.tmp .part .iso` 全收进来。用户目录里最不缺的就是这些半成品。
 *
 * 白名单只收 libVLC 在 Windows 上确实能解的容器；`.rm/.rmvb` 因为 3.0.21 的解码器
 * 覆盖不稳，刻意不收 —— 收进来之后“扫到却播不了”比“没扫到”更难解释。
 *
 * ── 为什么要跳系统目录 ──────────────────────────────────────────────────────
 * 用户很可能把整个 `D:\` 或 `C:\Users\xxx` 加进来。不跳的话：
 *  - `C:\Windows\WinSxS` 里有几十万个文件，扫描直接从“秒级”变成“分钟级”；
 *  - `$Recycle.Bin` 里的“已删除”文件会被当成新片重新收录；
 *  - `AppData\Local\Packages` 里有大量同名缓存副本，会造出重复条目。
 *
 * 判据是**目录名**（不区分大小写）而不是完整路径：这些目录无论挂在哪个盘下，
 * 名字都一样。
 */
object DesktopScanRules {

    /** 收进来的容器（小写）。改这里要同步改《Windows使用说明》的支持格式表。 */
    val extensions: Set<String> = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "wmv", "flv", "webm",
        "ts", "m2ts", "mpg", "mpeg", "vob", "ogv", "3gp", "divx",
    )

    /** 一律不进的目录（小写比较）。 */
    private val systemDirectoryNames: Set<String> = setOf(
        "\$recycle.bin",
        "system volume information",
        "windows",
        "windows.old",
        "program files",
        "program files (x86)",
        "programdata",
        "appdata",
        "recovery",
        "perflogs",
        "msocache",
        "config.msi",
        "\$windows.~bt",
        "\$windows.~ws",
        "node_modules",
    )

    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot < 0 || dot == fileName.length - 1) "" else fileName.substring(dot + 1).lowercase()
    }

    fun isVideo(fileName: String): Boolean = extensionOf(fileName) in extensions

    /** `.hidden` 与 Windows 的“隐藏”属性都算隐藏。属性判断需要真实文件，见 [isHiddenFile]。 */
    fun isHiddenName(fileName: String): Boolean = fileName.startsWith(".")

    fun isHiddenFile(file: File): Boolean = isHiddenName(file.name) || runCatching { file.isHidden }.getOrDefault(false)

    fun isSystemDirectory(dirName: String): Boolean = dirName.lowercase() in systemDirectoryNames

    /** 文件名 → 展示名（去掉扩展名）。只用于界面，不参与任何比对。 */
    fun displayName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }
}
