package com.cinemahall.desktop.media

import java.io.File

/**
 * libVLC 的位置解析（W1 / W5，Phase 0 §2.1 的落地方案）。
 *
 * ── 为什么要自己找，而不是用 vlcj 的 `NativeDiscovery` ────────────────────────
 * `NativeDiscovery` 只认「系统安装的 VLC」与 `jna.library.path`，而本项目的交付形态是
 * **zip 解压即用、不要求机器上装 VLC**（W5 门禁）。所以 libVLC 跟着 exe 走：
 *
 * ```text
 * cinema-hall/
 *   cinema-hall.exe
 *   data/            ← 用户数据，**不随发布包分发**
 *   vlc/             ← libvlc.dll + libvlccore.dll + plugins/
 * ```
 *
 * 开发态则直接指 `third_party/vlc-3.0.21`（Phase 0 用的同一份 3.0.21 win64）。
 *
 * ── 找不到时要“响亮地失败” ──────────────────────────────────────────────────
 * 返回 null 而不是抛异常：调用方（UI）要把「没找到 VLC」显示成一句可操作的话，
 * 而不是让窗口在启动时消失。
 */
object VlcDiscovery {

    /** vlcj 需要的 native 入口（缺它 JNA 会在 dlopen 时抛 UnsatisfiedLinkError）。 */
    private const val LIBRARY_NAME = "libvlc.dll"

    /**
     * 按优先级列出候选目录。
     *
     * 顺序刻意是「离 exe 越近越优先」：打包后如果用户把一个旧 `vlc/` 留在 exe 旁边，
     * 我们应该用它而不是开发机上的 `_spike` 副本 —— 否则会在别人的机器上莫名其妙
     * 地用上一份不存在的路径。
     */
    fun candidates(explicit: File? = null): List<File> {
        val list = LinkedHashSet<File>()
        explicit?.let { list += it }
        System.getProperty("cinema.vlcDir")?.let { list += File(it) }
        System.getenv("CINEMA_VLC_DIR")?.let { list += File(it) }

        val packaged = System.getProperty("jpackage.app-path")?.let { File(it).parentFile }
        if (packaged != null) {
            list += packaged.resolve("vlc")
            list += packaged.resolve("app").resolve("vlc")
        }

        val workDir = File(System.getProperty("user.dir"))
        list += workDir.resolve("vlc")
        list += workDir.resolve("third_party").resolve("vlc-3.0.21")
        list += workDir.parentFile?.resolve("third_party")?.resolve("vlc-3.0.21") ?: workDir
        // Phase 0 遗留的开发态副本（本机专用；换机器时会被前几条命中，不影响交付）。
        list += File("D:/AI/minimax/_spike/third_party/vlc-3.0.21")

        return list.toList()
    }

    fun locate(explicit: File? = null): File? =
        candidates(explicit).firstOrNull { it.resolve(LIBRARY_NAME).isFile }

    /**
     * 把 libVLC 目录告诉 JNA。**必须在构造 `MediaPlayerFactory` 之前调用** ——
     * JNA 只在第一次 `Native.load` 时读这个属性。
     */
    fun install(vlcDir: File) {
        System.setProperty("jna.library.path", vlcDir.absolutePath)
        System.setProperty("cinema.vlcDir", vlcDir.absolutePath)
    }

    /** 版本串要等 factory 起来才知道；这里只给「找没找到、找的是哪一份」。 */
    fun describe(vlcDir: File?): String = when {
        vlcDir == null -> "未找到 libVLC（$LIBRARY_NAME）"
        else -> vlcDir.absolutePath
    }
}
