import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.kotlin.serialization)
}

// W3：单窗口应用（左侧导航 / 播放器 / 影片库 / 设置 / 诊断）。
// W5：jpackage app-image + zip；`data/` 与 exe 同级、发布包不带 `data/`。
kotlin {
    jvmToolchain(17)
}

/**
 * Windows 分发版本号 —— **唯一一处**。
 *
 * 之前 `packageVersion` 与 `releaseZip` 的文件名各写各的，改版本时漏掉一处，
 * 就出现了「zip 叫 0.1.1、包里是 0.1.2」的包：只有版本号变了而名字没变，
 * 发出去没人能一眼看出是哪一版。现在两处都读它，改名只改这一行。
 */
val winVersion = "0.1.2"

dependencies {
    implementation(project(":desktop:media"))
    implementation(project(":desktop:library"))
    // W7：RTC 实现。`desktopApp` 只在这里装配，不直接碰 webrtc-java 的类型。
    implementation(project(":desktop:rtc"))
    implementation(project(":core:media"))
    implementation(project(":core:library"))
    // W7-C5：装配信令与会话。`core:session` 已经把 protocol / network / rtc / media
    // 全部 `api` 出来，这里再显式写一遍只是为了「装配点用到哪个域就声明哪个域」。
    implementation(project(":core:session"))
    implementation(project(":core:network"))
    implementation(project(":core:protocol"))
    // W7-C7：设备激活。纯 JVM 的账号客户端（HTTP + 启动状态机 + 凭据接缝）。
    // **不要**在这里另写一套 HTTP：`HttpAccountApi` / `AccountRepository` /
    // `AccountEndpoint` 已经是与 Android 共用的那一份，桌面只补一个凭据落盘实现。
    implementation(project(":core:account"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // 托盘/防休眠要走 AWT + JNA（JNA 由 vlcj 传递，但桌面模块要直接 import 它，
    // 所以显式声明 —— 不依赖“恰好被谁带进来”）。
    implementation(libs.vlcj)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.cinemahall.desktop.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "cinema-hall"
            packageVersion = winVersion
            // ⚠️ 这里**故意只放 ASCII**。
            // Compose 插件把参数写成 UTF-8 的 `@args.txt` 交给 jpackage，而 Windows 上的
            // jpackage 按**系统代码页**读那个文件。本机是 GBK，一旦 `--description` 里出现
            // 中文，jpackage 直接崩在解码上（`Input length = 1`，一行有用的信息都没有）。
            // 换到开了「Beta: Use Unicode UTF-8」的机器又能过 —— 这种「看机器脸色」的
            // 失败最难查，所以宁可让 Windows 属性页显示英文，也不让打包依赖系统区域设置。
            // 中文产品名在窗口标题、界面与文档里，那里不受这个限制。
            description = "cinema-hall desktop player"
            vendor = "cinema-hall"
            // Compose + Swing + JNA 的模块需求很难逐条列全；app-image 的体积代价
            // （约 +40 MB）远小于“到了用户机器上 NoClassDefFoundError”。
            includeAllModules = true
            windows {
                menuGroup = "cinema-hall"
                shortcut = true
            }
        }
    }
}

// ── W5 发布包 ────────────────────────────────────────────────────────────────
// `data/` 是用户数据（库、进度、日志、锁），**不进发布包**：解压即用的 zip 里
// 出现一个别人的 `data/` 会把“首次启动”变成“带着别人的影片库启动”。
// libVLC 则要进包：`vlc/` 与 exe 同级，VlcDiscovery 的打包分支就是按这个位置找的。
val vlcBundleDir = providers.gradleProperty("cinema.vlcDir")
    .orElse("D:/AI/minimax/_spike/third_party/vlc-3.0.21")

// 合规文件的**源**在 `desktopApp/compliance/`，stageRelease 把它们铺到包根。
// 不要只放在 zip 任务里：`dist/cinema-hall/` 本身也要是「可以直接拷给人」的完整包。
val complianceDir = layout.projectDirectory.dir("compliance")

val stageRelease by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage app-image + vlc/ + 使用说明 + 合规文件, 不含 data/"

    dependsOn("createDistributable")

    val appImageRoot = layout.buildDirectory.dir("compose/binaries/main/app/cinema-hall")
    from(appImageRoot) {
        // 在源目录里跑过一次 exe 之后，`data/logs/app.log` 就会留在这里，而 `Sync`
        // 会把它原样搬进 `dist/cinema-hall/` —— 一旦有人直接压这个目录（而不是走
        // releaseZip），开发机的日志就跟着分发出去了。排除必须写在**这一步**，
        // 只靠 releaseZip 的 exclude 挡不住中间产物。
        exclude("data/**")
        exclude("**/*.log")
    }
    from(File(vlcBundleDir.get())) {
        into("vlc")
        // 这两个浏览器嵌入插件（IE / NPAPI）的对应源码**不在 VLC 任何源码包里**
        // （`extras/package/win32/package.mak:88-89` 从另一个项目 npapi-vlc 拷进来，
        // PE 版本号停在 3.0.3）。随包分发它们就无法履行「提供对应源码」的义务；
        // 我们走 vlcj 进程内调 libvlc，从不加载这两个 DLL ⇒ 直接不进包。
        exclude("axvlc.dll", "npvlc.dll")
        // msi/ 是 VLC 官方包里的 WiX 安装脚本（含一段注册 axvlc.dll 的 CLSID），
        // 对解压即用的 zip 没有任何用途；留着只会让「包里有 axvlc 字样」看起来像没剔干净。
        exclude("msi/**")
    }
    // 合规文件：LICENSE（GPLv3 全文）/ SOURCE.txt / NOTICE-third-party.txt
    from(complianceDir) { into("") }
    // 第三方组件清单随包发（源在 docs/，避免两份副本各自漂移）
    from(rootProject.layout.projectDirectory.file("docs/第三方组件清单.md")) { into("") }
    from(rootProject.layout.projectDirectory.file("docs/Windows使用说明.md")) {
        into("")
        rename { "使用说明.md" }
    }
    into(layout.buildDirectory.dir("dist/cinema-hall"))

    // ⚠️ `Sync` 的语义是「镜像」：目标目录里多出来的东西一律删掉，而用户运行后生成的
    // `data/` 正在那个目录里（`AppPaths.dataDir = appRoot/data`）。不 preserve 的话，
    // 每次重建包都会把用户的**账号 token**（`data/credentials.json`）、片单、设置、日志
    // 一起清掉 —— 症状是「昨天还好好的，今天一开机又要激活」，且看不出是被打包清掉的。
    // 注意上面那两条 `exclude("data/**")` 只挡「从 appImageRoot 搬进 dist」，
    // 挡不住 Sync 自己的删除动作，两件事必须分开做。
    preserve {
        include("data/**")
    }
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "W5: 把 stageRelease 的结果打成 zip（不含 data/）"
    dependsOn(stageRelease)
    // ⚠️ `into("cinema-hall")` 不能省。不加它，Gradle 会把 `dist/cinema-hall/` 的**内容**
    // 铺到 zip 根上 —— 用户解压时 exe、app\、vlc\ 直接散进当前目录（连同他自己那一堆
    // 文件），既不是「解压即用」，也没法整个删干净。zip 里必须有且只有一个顶层目录。
    from(layout.buildDirectory.dir("dist/cinema-hall")) {
        into("cinema-hall")
        exclude("**/data/**")
        exclude("**/*.log")
    }
    // 名字必须跟着 packageVersion 走：写死过一次 0.1.1，结果包里是 0.1.2 的内容，
    // 只有版本号变了而文件名没变 —— 这种包发出去没人能一眼看出是哪一版。
    archiveFileName.set("cinema-hall-windows-$winVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
