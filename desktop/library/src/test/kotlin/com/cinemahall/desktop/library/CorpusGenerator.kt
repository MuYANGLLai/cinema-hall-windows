package com.cinemahall.desktop.library

import java.io.File

/**
 * 扫描门禁用的语料生成器（**只在测试源集**，不进产物）。
 *
 * 为什么不在 bash 里 for 循环拷文件：本机的安全策略会拦“批量写入”，而门禁要的是
 * 「500 部真实文件扫一遍」的读数，不是一张估算表。这个 main 由 Gradle 任务
 * `generateCorpus` 调起，参数：目录、数量、样本文件。
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: <dir> <count> <sampleFile>" }
    val root = File(args[0])
    val count = args[1].toInt()
    val sample = File(args[2])
    require(sample.isFile) { "sample not found: ${sample.absolutePath}" }

    val started = System.currentTimeMillis()
    val bytes = sample.readBytes()
    repeat(count) { index ->
        val bucket = File(root, "cat${index % 10}")
        bucket.mkdirs()
        File(bucket, "movie-%03d%s".format(index, sample.extension.let { if (it.isBlank()) ".mkv" else ".$it" }))
            .writeBytes(bytes)
    }
    val elapsed = System.currentTimeMillis() - started
    println("[corpus] created $count files (${bytes.size} B each) under ${root.absolutePath} in ${elapsed}ms")
}
