package com.cinemahall.desktop.app

import java.io.File

/**
 * 运行时路径（W5 的「data/ 与 exe 同级」）。
 *
 * ```text
 * cinema-hall/            ← app-image 根（zip 解压后的目录）
 *   cinema-hall.exe
 *   vlc/                  ← 随包分发
 *   data/                 ← 首次启动时创建；不随 zip 分发
 *     library.json        ← 根目录 + 时长缓存
 *     settings.json
 *     credentials.json    ← 账号 token（默认 profile；其它 profile 为 credentials-<名>.json）
 *     logs/app.log
 *     app-default.lock
 * ```
 *
 * ── 为什么开发态不用仓库根 `data/` ──────────────────────────────────────────
 * 那会让 `git status` 永远脏，而且打包脚本的 `exclude("**/data/**")` 会误伤一个
 * 恰好同名的源码目录。开发态固定落在 `desktopApp/data`。
 */
object AppPaths {

    private val packagedRoot: File? = System.getProperty("jpackage.app-path")
        ?.let { File(it).parentFile }

    val appRoot: File = packagedRoot ?: run {
        val workDir = File(System.getProperty("user.dir"))
        if (workDir.name.equals("desktopApp", ignoreCase = true)) workDir
        else File(workDir, "desktopApp")
    }

    val packaged: Boolean get() = packagedRoot != null

    val dataDir: File = File(appRoot, "data")

    val logsDir: File = File(dataDir, "logs")

    val libraryFile: File = File(dataDir, "library.json")

    val settingsFile: File = File(dataDir, "settings.json")

    fun lockFile(profile: String): File = File(dataDir, "app-$profile.lock")

    /**
     * 账号凭据文件（W7-C7）。
     *
     * **按 profile 分文件**：`data/` 是所有 profile 共用的（只有单实例锁按 profile 分），
     * 而同机双实例验证要用两个不同账号 —— 共用一个 `credentials.json` 会互相覆盖，
     * 症状是「刚激活好，另一个实例启动就变回激活页」。
     * 默认 profile 仍叫 `credentials.json`（与文档、排障习惯一致）。
     */
    fun credentialsFile(profile: String): File =
        if (profile == "default") File(dataDir, "credentials.json")
        else File(dataDir, "credentials-$profile.json")

    fun ensure() {
        dataDir.mkdirs()
        logsDir.mkdirs()
    }

    fun describe(): String = buildString {
        append(if (packaged) "打包态" else "开发态")
        append(" · appRoot=").append(appRoot.absolutePath)
    }
}
