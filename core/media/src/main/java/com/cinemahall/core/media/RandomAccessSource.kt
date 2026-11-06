package com.cinemahall.core.media

import java.io.Closeable

/**
 * 可随机访问的字节来源。
 *
 * 抽成接口的目的是让「读哪几段、怎么算哈希」这段逻辑能在 JVM 单测里用内存数组
 * 真跑起来 —— §7 的三条判定（改名 / 改大小 / 改中间字节）是这一步的核心验收项，
 * 不该等到插上平板才能验证。Android 侧的 [ContentResolverMediaSource] 于是只
 * 负责一件事：把字节读出来。
 */
interface RandomAccessSource : Closeable {

    /**
     * 文件总大小。**它是抽样规则的输入**，所以必须准 —— 报小了会漏掉尾段，
     * 报大了会读到 EOF 得到一段短数据，两种都会让摘要与对端对不上。
     */
    val sizeBytes: Long

    /**
     * 从 [offset] 起读最多 [length] 字节。
     *
     * **允许短读**：返回值可以少于 [length]（真实的 socket / channel 就是这么干的），
     * 到文件尾返回空数组。调用方 [MediaFingerprinter] 会循环补齐，因此实现不必
     * 保证一次读满 —— 反过来，要求「必须读满」会让实现里到处是容易写错的循环。
     */
    fun readAt(offset: Long, length: Int): ByteArray

    override fun close() = Unit
}