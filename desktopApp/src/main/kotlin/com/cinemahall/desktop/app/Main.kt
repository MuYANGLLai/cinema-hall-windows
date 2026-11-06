package com.cinemahall.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cinemahall.desktop.app.ui.AppRoot
import com.cinemahall.desktop.app.ui.CinemaAuto
import com.cinemahall.desktop.app.ui.CinemaHolder
import com.cinemahall.desktop.app.ui.Screen
import com.cinemahall.desktop.app.ui.Shortcuts
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.library.DesktopMovieCatalog
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/**
 * 桌面端入口（W3 + W5）。
 *
 * 参数：
 * ```text
 * --audio-helper        采集子进程（W7 用，本轮只预留分支）
 * --profile=b           第二个实例（默认 default，单实例锁按 profile 分）
 * --open=<path>         启动即选片
 * --no-autoplay         选片但不自动播放
 * --exit-after=<sec>    跑 N 秒后自动退出（冒烟/门禁脚本用）
 * --screenshot=<path>   退出前抓一张窗口截图（同上）
 * ```
 */
fun main(args: Array<String>) {
    if (args.contains("--audio-helper")) runAudioHelper()

    val options = StartupOptions.parse(args)
    AppPaths.ensure()
    Log.init(File(AppPaths.logsDir, "app.log"))
    Log.info("启动 ${options.describe()} · ${AppPaths.describe()}")

    // ── C2/C3 门禁自检（两个进程建一条真 WebRTC 链路；C3 还会起 helper 采麦）────
    // 必须在单实例锁**之前**：两个自检进程都用默认 profile，抢锁会让第二个直接退出，
    // 而它正是门禁要的那一半。
    options.rtcSelfTest?.let { role ->
        val code = com.cinemahall.desktop.app.rtc.runRtcSelfTest(
            role = role,
            port = options.rtcPort,
            seconds = options.rtcSeconds,
        )
        Log.info("[selftest] role=$role exit=$code")
        kotlin.system.exitProcess(code)
    }
    options.rtcCallTest?.let { role ->
        val code = com.cinemahall.desktop.app.rtc.runRtcCallTest(
            role = role,
            port = options.rtcPort,
            seconds = options.rtcSeconds,
            tone = options.rtcTone,
        )
        Log.info("[call] role=$role exit=$code")
        kotlin.system.exitProcess(code)
    }
    options.roomTest?.let { role ->
        val code = com.cinemahall.desktop.app.net.runRoomSelfTest(
            role = role,
            endpoint = options.signalEndpoint,
            roomId = options.roomId,
            seconds = options.rtcSeconds,
        )
        Log.info("[room] role=$role exit=$code")
        kotlin.system.exitProcess(code)
    }
    options.sessionTest?.let { role ->
        val code = com.cinemahall.desktop.app.net.runSessionSelfTest(
            role = role,
            endpoint = options.signalEndpoint,
            roomId = options.roomId,
            seconds = options.rtcSeconds,
        )
        Log.info("[session] role=$role exit=$code")
        kotlin.system.exitProcess(code)
    }

    val instance = SingleInstance.acquire(AppPaths.lockFile(options.profile))
    if (instance == null) {
        Log.warn("已有实例在运行（profile=${options.profile}）；如需并行测试请用 --profile=b")
        println("cinema-hall 已在运行。若要在同一台机器上跑第二个实例，请加 --profile=b")
        return
    }

    val controller = PlayerController()
    // 信令模块的持有者：建在窗口之外，因为**退出路径**必须能拿到它。
    // 交给 Composable 持有的话，`exitApplication()` 与 JVM 退出之间没有同步点，
    // 关闭动作可能根本没执行 —— 症状就是退出码 0xC0000005（native 工厂没释放）。
    val cinema = CinemaHolder()
    val settingsStore = SettingsStore(AppPaths.settingsFile)
    val catalog = DesktopMovieCatalog(
        storeFile = AppPaths.libraryFile,
        probe = { path -> controller.durationProbe(path) },
        probeConcurrency = controller.probeParallelism,
    )

    val initialMovie = options.openPath?.let { path ->
        val file = File(path)
        DesktopMovie(
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            modifiedAtMs = file.lastModified(),
            durationMs = 0,
        )
    }

    var cleanedUp = false
    fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        KeepAwake.disable()
        // 信令模块先收：它内部要停采集、拆 PC、释放进程级 native 工厂。
        // 顺序放在播放器之前 —— 链路里还挂着播放相关的回调，先断链路再关播放器。
        runCatching { cinema.close() }
        runCatching { controller.close() }
        runCatching { instance.close() }
        Log.info("退出")
    }

    Runtime.getRuntime().addShutdownHook(Thread { cleanup() })

    // ── 脚本化入口（W6 门禁用；不影响正常双击启动）────────────────────────────
    options.addRoot?.let { path ->
        val result = catalog.addRoot(File(path))
        Log.info("--add-root $path -> $result")
    }
    if (options.scanOnly) {
        kotlinx.coroutines.runBlocking { catalog.scan() }
        val stats = catalog.stats.value
        val line = "[scan] phase=${stats.phase} found=${stats.found} probed=${stats.probed} " +
            "failed=${stats.probeFailed} fast=${stats.fastElapsedMs}ms probe=${stats.probeElapsedMs}ms " +
            "skippedExt=${stats.skippedExtension} skippedHidden=${stats.skippedHidden} " +
            "skippedSystem=${stats.skippedSystemDirs} roots=${catalog.roots.value.size}"
        println(line)
        // 打包态是 GUI 子系统，exe 没有控制台 —— `println` 会直接丢掉。门禁要能在
        // 打包后取到读数，所以同一行也进日志（`data/logs/app.log`）。
        Log.info(line)
        cleanup()
        return
    }
    // 门禁用：只在指定目录上跑一遍两段式扫描，**不写进真实影片库**。
    options.measureRoot?.let { path ->
        val measuring = DesktopMovieCatalog(
            storeFile = File(AppPaths.dataDir, "measure.json"),
            probe = { file -> controller.durationProbe(file) },
            probeConcurrency = controller.probeParallelism,
        )
        measuring.addRoot(File(path))
        val started = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking { measuring.scan() }
        val stats = measuring.stats.value
        val total = System.currentTimeMillis() - started
        val line = "[measure] root=$path phase=${stats.phase} found=${stats.found} probed=${stats.probed} " +
            "failed=${stats.probeFailed} fast=${stats.fastElapsedMs}ms probe=${stats.probeElapsedMs}ms " +
            "total=${total}ms skippedExt=${stats.skippedExtension} skippedHidden=${stats.skippedHidden} " +
            "skippedSystem=${stats.skippedSystemDirs}"
        println(line)
        Log.info(line)
        cleanup()
        return
    }

    application {
        var settings by remember { mutableStateOf(settingsStore.load()) }
        if (settings.keepAwake) KeepAwake.enable()

        val windowState = rememberWindowState(
            size = DpSize(settings.windowWidth.dp, settings.windowHeight.dp),
        )

        Window(
            onCloseRequest = {
                cleanup()
                exitApplication()
            },
            title = "cinema-hall · Windows",
            state = windowState,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Spacebar -> {
                            Shortcuts.onPlayPause(); true
                        }
                        Key.DirectionLeft -> {
                            Shortcuts.onSeekBy(-10_000); true
                        }
                        Key.DirectionRight -> {
                            Shortcuts.onSeekBy(10_000); true
                        }
                        Key.DirectionUp -> {
                            Shortcuts.onVolumeBy(0.05f); true
                        }
                        Key.DirectionDown -> {
                            Shortcuts.onVolumeBy(-0.05f); true
                        }
                        Key.O -> {
                            if (event.isCtrlPressed) {
                                Shortcuts.onOpenFile(); true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            },
        ) {
            CinemaTheme(day = settings.theme == "day") {
                AppRoot(
                    controller = controller,
                    catalog = catalog,
                    settings = settings,
                    onSettingsChange = { updated ->
                        settings = updated
                        settingsStore.save(updated)
                    },
                    onExit = {
                        cleanup()
                        exitApplication()
                    },
                    initialMovie = initialMovie,
                    cinema = cinema,
                    initialScreen = screenOf(options.initialScreen),
                    cinemaAuto = if (options.cinemaCreate || options.cinemaJoin != null) {
                        CinemaAuto(options.cinemaCreate, options.cinemaJoin ?: options.roomId)
                    } else {
                        null
                    },
                    identityOverride = options.identity,
                )
            }

            // 冒烟/门禁：跑满 N 秒后抓图并退出（不影响正常使用，只有带参数才生效）。
            LaunchedEffect(Unit) {
                if (options.exitAfterSeconds <= 0) return@LaunchedEffect
                delay(options.exitAfterSeconds * 1_000L)
                options.screenshotPath?.let { target ->
                    runCatching {
                        // Robot 抓的是屏幕坐标上的那块矩形；窗口被别的窗口盖住时会拍到别人。
                        // 冒烟场景先置顶一次再拍，避免"截图拍到编辑器"。
                        window.toFront()
                        window.requestFocus()
                        window.isAlwaysOnTop = true
                        Thread.sleep(600)
                        val bounds: Rectangle = window.bounds
                        val image = Robot().createScreenCapture(bounds)
                        window.isAlwaysOnTop = false
                        target.parentFile?.mkdirs()
                        ImageIO.write(image, "png", target)
                        Log.info("截图已保存：${target.absolutePath} bounds=$bounds")
                    }.onFailure { Log.error("截图失败", it) }
                }
                val target = controller.target
                Log.info(
                    "退出前状态：playing=${target?.isPlaying} position=${target?.currentPositionMs} " +
                        "duration=${target?.durationMs} ready=${target?.isReady}",
                )
                cleanup()
                exitApplication()
            }
        }

        // 托盘：关闭窗口 = 退出（本轮不做“最小化到托盘”的行为差异，避免两套退出语义）。
        LaunchedEffect(Unit) {
            TraySupport.install(
                onShow = { windowState.isMinimized = false },
                onExit = {
                    cleanup()
                    exitApplication()
                },
            )
        }
    }
}

/**
 * `--screen=` 的名字 → 导航项。写错就回首页：门禁参数不该让程序起不来。
 * 中文别名一并接受 —— 截图脚本里写「影厅」比写 `cinema` 更像人话。
 */
private fun screenOf(name: String?): Screen = when (name?.trim()?.lowercase()) {
    "cinema", "影厅" -> Screen.Cinema
    "library", "影片库" -> Screen.Library
    "settings", "设置" -> Screen.Settings
    "diagnostics", "诊断" -> Screen.Diagnostics
    else -> Screen.Home
}

private data class StartupOptions(
    val profile: String,
    val openPath: String?,
    val autoplay: Boolean,
    val exitAfterSeconds: Int,
    val screenshotPath: File?,
    val addRoot: String?,
    val scanOnly: Boolean,
    val measureRoot: String?,
    /** C2 门禁：`a`（监听）/ `b`（连接）。与 UI 无关，跑完即退出。 */
    val rtcSelfTest: String?,
    /** C3 门禁：`a` 开麦 / `b` 只收。 */
    val rtcCallTest: String?,
    /** C4 门禁：两端都用固定频率正弦代替麦克风，验「有声内容真的到了对端」。 */
    val rtcTone: Boolean,
    /** C5 地基自检：`a` 建房 / `b` 加入。只验信令与房间，不建 RTC 链路。 */
    val roomTest: String?,
    /** C5 整链自检：信令 → 房间 → WebRTC → hello → 片源一致。 */
    val sessionTest: String?,
    /** 门禁用：启动时直接进某一页（`cinema` / `library` / `settings` / `diagnostics`）。 */
    val initialScreen: String?,
    /** 门禁用：进影厅页后自动建房（房号取 [roomId]）。 */
    val cinemaCreate: Boolean,
    /** 门禁用：进影厅页后自动加入这个房间。 */
    val cinemaJoin: String?,
    /**
     * 门禁用：覆盖本机身份。
     *
     * 同一台机器上跑两个实例时**必须**分开：身份存在 `data/identity.txt` 里，
     * 两边读到同一个 id 就会被服务端当成同一个人（后进的那个会把先进的那个顶掉）。
     */
    val identity: String?,
    /** 信令服务地址（C5）。默认指向本机开发服务。 */
    val signalEndpoint: String,
    /** C5 自检用的固定房号。 */
    val roomId: String,
    val rtcPort: Int,
    val rtcSeconds: Int,
) {
    fun describe(): String =
        "profile=$profile open=${openPath ?: "-"} autoplay=$autoplay exitAfter=$exitAfterSeconds " +
            "addRoot=${addRoot ?: "-"} scanOnly=$scanOnly measureRoot=${measureRoot ?: "-"} " +
            "rtcSelfTest=${rtcSelfTest ?: "-"} rtcCallTest=${rtcCallTest ?: "-"} " +
            "rtcTone=$rtcTone roomTest=${roomTest ?: "-"} sessionTest=${sessionTest ?: "-"} " +
            "roomId=$roomId screen=${initialScreen ?: "home"} cinemaCreate=$cinemaCreate " +
            "cinemaJoin=${cinemaJoin ?: "-"} identity=${identity ?: "-"} " +
            "signal=$signalEndpoint rtcPort=$rtcPort"

    companion object {
        fun parse(args: Array<String>): StartupOptions {
            fun value(name: String): String? =
                args.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

            return StartupOptions(
                profile = value("--profile")?.takeIf { it.isNotBlank() } ?: "default",
                openPath = value("--open")?.takeIf { it.isNotBlank() },
                autoplay = !args.contains("--no-autoplay"),
                exitAfterSeconds = value("--exit-after")?.toIntOrNull() ?: 0,
                screenshotPath = value("--screenshot")?.takeIf { it.isNotBlank() }?.let(::File),
                addRoot = value("--add-root")?.takeIf { it.isNotBlank() },
                scanOnly = args.contains("--scan"),
                measureRoot = value("--measure-root")?.takeIf { it.isNotBlank() },
                rtcSelfTest = value("--rtc-selftest")?.takeIf { it.isNotBlank() },
                rtcCallTest = value("--rtc-call-test")?.takeIf { it.isNotBlank() },
                rtcTone = args.contains("--rtc-tone"),
                roomTest = value("--room-test")?.takeIf { it.isNotBlank() },
                sessionTest = value("--session-test")?.takeIf { it.isNotBlank() },
                initialScreen = value("--screen")?.takeIf { it.isNotBlank() },
                cinemaCreate = args.contains("--cinema-create"),
                cinemaJoin = value("--cinema-join")?.takeIf { it.isNotBlank() },
                identity = value("--identity")?.takeIf { it.isNotBlank() },
                signalEndpoint = value("--signal-endpoint")?.takeIf { it.isNotBlank() }
                    ?: "ws://127.0.0.1:8080",
                // 房号规则：6 位、且不含 `0/O/1/I/L`（服务端校验）。自检默认值必须合规，
                // 否则自检连「进房」都到不了，看起来像 RTC 的问题。
                roomId = value("--room-id")?.takeIf { it.isNotBlank() } ?: "ABCDEF",
                rtcPort = value("--rtc-port")?.toIntOrNull() ?: 39321,
                rtcSeconds = value("--rtc-seconds")?.toIntOrNull() ?: 20,
            )
        }
    }
}

/**
 * 托盘图标（W3 要求）。
 *
 * 用 AWT `SystemTray` 而不是 Compose 的 `Tray`：后者的 `icon` 要一个 Compose `Painter`，
 * 在纯 JVM 里要多绕一层 Skia 位图转换；而托盘本身是 AWT 的 API。功能完全相同，
 * 少一层依赖。不支持托盘的机器（部分服务器/无桌面会话）直接跳过。
 */
private object TraySupport {

    fun install(onShow: () -> Unit, onExit: () -> Unit) {
        if (!java.awt.SystemTray.isSupported()) {
            Log.warn("本机不支持系统托盘，跳过")
            return
        }
        runCatching {
            val tray = java.awt.SystemTray.getSystemTray()
            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = java.awt.Color(0xD3, 0xA2, 0x4A)
            g.fillRoundRect(1, 2, 14, 12, 4, 4)
            g.dispose()

            val popup = java.awt.PopupMenu()
            val show = java.awt.MenuItem("显示主窗口")
            show.addActionListener { onShow() }
            val quit = java.awt.MenuItem("退出")
            quit.addActionListener { onExit() }
            popup.add(show)
            popup.addSeparator()
            popup.add(quit)

            val icon = java.awt.TrayIcon(image, "cinema-hall", popup)
            icon.addActionListener { onShow() }
            tray.add(icon)
            Log.info("托盘图标已安装")
        }.onFailure { Log.warn("托盘安装失败：${it.message}") }
    }
}
