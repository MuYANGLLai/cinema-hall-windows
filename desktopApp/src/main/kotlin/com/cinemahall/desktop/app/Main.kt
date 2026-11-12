package com.cinemahall.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cinemahall.desktop.app.account.DesktopAccount
import com.cinemahall.desktop.app.net.DesktopModule
import com.cinemahall.desktop.app.ui.AppRoot
import com.cinemahall.desktop.app.ui.CinemaAuto
import com.cinemahall.desktop.app.ui.CinemaHolder
import com.cinemahall.desktop.app.ui.Screen
import com.cinemahall.desktop.app.ui.Shortcuts
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.library.DesktopMovieCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * --fullscreen          启动即全屏（同上：脚本按不了 F11）
 * --native-frame=0      关掉「窗口交互交给 Windows」，退回自绘拖动/缩放（真机排障用）
 * --screen-blink=<ms>   切页门禁：N 毫秒后「影厅→影片库→影厅」走一遍，每次切换前后各抓
 *                       10 帧到 `--screenshot` 同目录（`flip_*.png`）。「闪一下白」只存在
 *                       几十毫秒，只能靠逐帧抓图判断
 * ```
 */
fun main(args: Array<String>) {
    // ── libVLC 的字符串编码（必须在 JNA 的 Native 类初始化之前设）───────────────
    //
    // 症状：字幕菜单里冒出「字幕 1（涓€构）」这种乱码（用户 2026-11-07 反馈）。
    //
    // 原因：libVLC 的 C 接口交出来的是 **UTF-8** 字节，而 JNA 的 `String` 映射默认按
    // **平台字符集**解码 —— 中文 Windows 上那是 GBK。UTF-8 的「简」= E7 AE 80，
    // 按 GBK 读就是「涓€」。所以要在 JNA 用之前把默认编码改成 UTF-8。
    //
    // 这条设置同时修正了**相反方向**：Java → native 也按 UTF-8 编码，而 libVLC 本来就
    // 期望 UTF-8 的 MRL/路径。中文文件名能播，就不会因为这条变坏（实测同一部中文名
    // 的片子在改前改后都能起播）。
    System.setProperty("jna.encoding", "UTF-8")

    // ── 绘制后端：默认交给 Skiko 自己选（2026-11-08 定）─────────────────────────
    // 试过强制 `OPENGL`：拖边白条确实少了（22/44 → 4/42 帧），但**全屏过渡会失效** ——
    // 过渡要靠 `Robot` 抓当前这一屏当底片，而 GPU 合成（OpenGL/分层窗口）的画面
    // GDI 截屏抓不到，抓出来是**全黑**，过渡就变成「一块黑方块放大」（正是用户否掉的观感）。
    // 所以保持默认后端，白条改用「拖边期间盖一条原生遮条」解决（见 `NativeFrame`）。
    // 留开关：`CINEMA_RENDER_API=DIRECT3D|OPENGL|SOFTWARE`。
    System.getenv("CINEMA_RENDER_API")?.takeIf { it.isNotBlank() }?.let {
        System.setProperty("skiko.renderApi", it)
    }

    // ── 缩放时不露白边（2026-11-08 用户口径「快速缩放会看到白白的边」）───────────
    // AWT 在重绘前会先用窗口自己的底色**擦一遍背景**；擦与「Compose 画出新一帧」
    // 之间那条刚被拉出来的边，露出的就是这个底色 —— 默认是 **系统白**。
    // 所以真正要改的是**窗口底色**（见 `window.background = ...`），而不是跳过擦除：
    // 实测把 `sun.awt.noerasebackground` 设成 true 之后，新拉出来的那块**没有任何人画**，
    // 露出来的是更刺眼的白（DWM 表面的初始值），反而更糟。

    if (args.contains("--audio-helper")) runAudioHelper(args.toList())

    val options = StartupOptions.parse(args)
    // 无边框窗口的**交互**是否交给 Windows（用户 2026-11-07 拍板「选 A」）。
    // 关掉就退回自绘拖动/缩放/双击 —— 留给真机出问题时的现场退路。
    com.cinemahall.desktop.app.ui.NativeFrame.enabled = options.nativeFrame
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

    // ── 设备激活（W7-C7）──────────────────────────────────────────────────────
    // 凭据按 profile 分文件：`data/` 是所有 profile 共用的（只有单实例锁按 profile 分），
    // 而同机双实例验证要用两个不同账号 —— 共用一个 json 会互相覆盖。
    val account = DesktopAccount(AppPaths.credentialsFile(options.profile))

    // 门禁用：`--activate=<入场码>` 在窗口出现之前把激活做完，走的就是产品同一条
    // `DesktopAccount.activate()`。码本身不进日志（`describe()` 只打「有没有传」）。
    options.activateCode?.let { code ->
        val configured = account.configure(options.signalEndpoint)
        val failure = if (!configured) {
            "信令地址不可用：${options.signalEndpoint}"
        } else {
            kotlinx.coroutines.runBlocking {
                account.activate(
                    code = code,
                    deviceName = options.deviceName ?: DesktopModule.defaultNickname(),
                    nickname = options.nickname,
                )
            }
        }
        if (failure != null) {
            Log.error("[account] --activate 失败：$failure")
            runCatching { instance.close() }
            kotlin.system.exitProcess(2)
        }
        Log.info("[account] --activate 完成 profile=${options.profile} endpoint=${options.signalEndpoint}")
    }

    val controller = PlayerController()
    // 影片库封面：把这个能力注入 `CoverCache`，界面层就不必认识 libVLC。
    // 抽帧是阻塞的（等起播），而 `CoverCache` 已经把它放在 IO 线程 + 串行队列里。
    com.cinemahall.desktop.app.ui.CoverCache.generator = { path, out ->
        controller.captureCover(path, out)
    }
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
        // 托盘图标要在**窗口之前**卸：它自己是一个「还有图形资源要看管」的理由，
        // 留着它，AWT 的自动收尾不会发生（见 `TraySupport.installed` 的注释）。
        runCatching { TraySupport.uninstall() }
        // 信令模块先收：它内部要停采集、拆 PC、释放进程级 native 工厂。
        // 顺序放在播放器之前 —— 链路里还挂着播放相关的回调，先断链路再关播放器。
        runCatching { cinema.close() }
        runCatching { controller.close() }
        runCatching { instance.close() }
        Log.info("退出")
    }

    /**
     * 关窗之后**兜底**把进程结束掉。
     *
     * `exitApplication()` 只是让组合停下来，它**不保证 JVM 退出**：只要有非守护线程
     * 还活着（WebRTC 的原生回调线程、AWT 的 EDT），进程就会一直挂着。用户看到的是
     * 「窗口关了，任务管理器里还有一个 cinema-hall」，再双击则提示「另一个实例已在运行」
     * —— 而那个实例的窗口根本不存在。2026-11-06 真机实测撞到过两次。
     *
     * 5 秒足够 `cleanup()` 把 native 资源收干净；真收不干净时也要退，
     * 否则用户只能去任务管理器杀进程。
     */
    fun forceExitWatchdog() {
        Thread({
            runCatching { Thread.sleep(5_000) }
            Log.warn("退出超时：窗口已关但 JVM 没退（非守护线程还在）→ 强制结束进程")
            Runtime.getRuntime().halt(0)
        }, "exit-watchdog").apply { isDaemon = true }.start()
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
            // placement **固定 Floating**：窗口从一开始就是无边框的，全屏只由下面的
            // `LaunchedEffect(fullscreen)` 改 bounds 实现。用 `WindowPlacement.Fullscreen`
            // 会去切窗口样式，正是「退出全屏黑一下」的来源。
            placement = WindowPlacement.Floating,
        )

        // 全屏是**窗口级**状态（与安卓端 `fullscreen` 由外层持有同一条取向）：
        // 收起来的不仅是画面里的控件，还有左侧导航与右侧成员栏 —— 否则「全屏」
        // 只是把画面放大到两条栏之间，上下黑边永远补不匀。
        // Esc 也退出全屏（Windows 用户的肌肉记忆），与按钮、F11 三条路并存。
        //
        // ⚠️ 真正的「进/出全屏」在下面 `Window` 的内容里做（要用到 AWT 窗口引用）。
        // 这里只保留状态本身。
        var fullscreen by remember { mutableStateOf(options.startFullscreen) }

        /**
         * 全屏切换的**唯一入口**（F11 / Esc / 控制条那颗按钮 / 门禁都走这里）。
         *
         * 保留一个统一入口而不是各处直接改 `fullscreen`：改全屏是「窗口几何 + 界面布局」
         * 两件事，将来要加预处理（比如冻结、打点）只改这一处。
         */
        val requestFullscreen: (Boolean) -> Unit = { next ->
            fullscreen = next
        }

        /**
         * 门禁用的「外部切页」通道（`--screen-blink`）。
         *
         * `null` = 不干预。非 null 时 `AppRoot` 把页切过去 —— 走的是与点侧栏导航完全
         * 相同的一条 `screen = ...` 分支，不绕过任何逻辑。
         */
        var gateScreen by remember { mutableStateOf<Screen?>(null) }

        // 门禁驱动（见 `--fullscreen-blink` 的说明）：到点自己进一次全屏、3 秒后退出来。
        // 走的是与 F11 / 控制条那颗按钮完全相同的一条状态分支。
        LaunchedEffect(options.fullscreenBlinkMs) {
            val at = options.fullscreenBlinkMs
            if (at <= 0 || fullscreen) return@LaunchedEffect
            delay(at.toLong())
            Log.info("[gate] 全屏门禁：进入")
            requestFullscreen(true)
            delay(3_000)
            Log.info("[gate] 全屏门禁：退出")
            requestFullscreen(false)
        }

        Window(
            onCloseRequest = {
                cleanup()
                exitApplication()
                forceExitWatchdog()
            },
            title = "cinema-hall · Windows",
            // 无边框（2026-11-07）：全屏/还原只改窗口尺寸，不切 native 样式 ——
            // 这是「退出全屏不黑一下」的唯一可靠做法（见 `WindowChrome` 的说明）。
            // 标题栏与拖边缩放由 `WindowChrome.kt` 自绘。
            undecorated = true,
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
                        // 全屏：F11 与 Esc 都要能进出。Esc 只在全屏时接管，
                        // 非全屏时放行给控件（否则会吃掉对话框的关闭键）。
                        Key.F11 -> {
                            requestFullscreen(!fullscreen); true
                        }
                        Key.Escape -> {
                            if (fullscreen) {
                                requestFullscreen(false); true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            },
        ) {
            // ── 全屏 = 把无边框窗口铺满显示器 ────────────────────────────────────
            //
            // 早先用 `GraphicsDevice.fullScreenWindow`：进入不黑、**退出必黑一下**。
            // 因为它在退出时要把窗口的 native 样式从 `WS_POPUP` 换回 `WS_OVERLAPPEDWINDOW`，
            // DWM 因此重绘整块窗口（含 libVLC 的 Canvas）。
            //
            // 现在窗口本身就是无边框的，全屏只是改 `bounds` —— 样式一次都不切，两头都不黑。
            // 还原用的 bounds 存在 `savedBounds` 里（每次进入全屏时刷新）。
            val composeWindow = window
            val chrome = remember(composeWindow) {
                com.cinemahall.desktop.app.ui.WindowChrome(composeWindow) {
                    cleanup()
                    exitApplication()
                    forceExitWatchdog()
                }
            }
            var savedBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
            // 启动那次全屏与之后的切换走同一条即时路径（见下面 `LaunchedEffect(fullscreen)`）。

            // 窗口底色 = 主题背景色（缩放时露出来的边就是它，不再是系统白）。
            // 换日间/夜间主题时重设一次。
            //
            // ⚠️ 光设这三层**不够**（0.1.20 的教训）：拖边时真正发白的是
            // ① 系统那把窗口类白刷子 → 由 `NativeFrame` 接管 `WM_ERASEBKGND` 擦成主题色；
            // ② Compose 给 `SwingPanel` 造的 `SwingInteropViewGroup`(#F0F0F0) 与内层白 JPanel
            //    → 前者由 `Backdrops` 顺着组件树改深，后者由 `SwingPanel(background=…)` 指定。
            var backdrop by remember { mutableStateOf(java.awt.Color(0x0A, 0x0A, 0x0B)) }
            LaunchedEffect(composeWindow, settings.theme) {
                val bg = com.cinemahall.desktop.app.cinemaColorsFor(settings.theme == "day").background
                val awt = java.awt.Color(bg.toArgb())
                backdrop = awt
                runCatching {
                    composeWindow.background = awt
                    composeWindow.contentPane.background = awt
                    composeWindow.rootPane.background = awt
                }.onSuccess {
                    Log.info(
                        "[ui] 窗口底色=$awt frame=${composeWindow.background} " +
                            "content=${composeWindow.contentPane.background}",
                    )
                }.onFailure { Log.warn("[ui] 设窗口底色失败：${it.message}") }
                com.cinemahall.desktop.app.ui.NativeFrame.setBackdrop(composeWindow, awt)
                com.cinemahall.desktop.app.ui.Backdrops.apply(composeWindow, awt)
            }

            // 诊断：把组件树连同各自的背景色打出来（见 `--dump-tree`）。
            if (options.dumpTree) {
                LaunchedEffect(composeWindow) {
                    delay(3_500) // 等 Compose 与画面 Canvas 都挂上
                    fun walk(c: java.awt.Component, depth: Int) {
                        val pad = "  ".repeat(depth)
                        val bg = runCatching { (c as? java.awt.Component)?.background }.getOrNull()
                        Log.info(
                            "[tree] $pad${c.javaClass.simpleName} " +
                                "bg=$bg opaque=${c.isOpaque} " +
                                "bounds=${c.x},${c.y} ${c.width}x${c.height}",
                        )
                        if (c is java.awt.Container) c.components.forEach { walk(it, depth + 1) }
                    }
                    walk(composeWindow, 0)
                }
            }


            // ── 把窗口的交互交给 Windows（拖动 / 八向缩放 / 双击最大化 / 贴边吸附）──
            // 外观一点不动（仍是 undecorated + 自绘标题栏），只借系统的**行为**。
            // 重试：`LaunchedEffect` 第一次跑时 peer 可能还没建好，取不到 HWND。
            LaunchedEffect(composeWindow) {
                var tries = 0
                while (tries < 20) {
                    if (chrome.installNative()) break
                    tries++
                    delay(50)
                }
                // 浅色容器改深（见 `Backdrops`）：进影厅页才会造出来的那两层白底
                // 要等它出现，所以是「现在扫一次 + 之后盯 8 秒 + 每次窗口尺寸变化补扫」。
                com.cinemahall.desktop.app.ui.Backdrops.watch(composeWindow) { backdrop }
                // 切页幕布：这里一定拿得到顶层 Window（`PlayerPane` 的 effect 时刻
                // `getWindowAncestor` 可能还是 null）。创建 + 首帧预热都在这里做掉，
                // 之后切页只是把它移进/移出画面区（见 `PlayerCurtain`）。
                com.cinemahall.desktop.app.ui.PlayerCurtain.attach(composeWindow)
            }
            // 原生接管后 `maximized` 由系统发起（贴边吸附/双击/Win+↑）：`WindowChrome`
            // 自己挂了 `componentResized` 去刷 `IsZoomed`，这里不用再管。

            LaunchedEffect(fullscreen) {
                // 全屏期间不接受拖动/拉边（系统独占全屏也是这样）。
                com.cinemahall.desktop.app.ui.NativeFrame.setFullscreen(composeWindow, fullscreen)

                // 换窗口矩形这件事本身（进：铺满显示器；出：回到存下来的那一份）。
                //
                // ── 2026-11-08：全屏切换保持**即时**，不做动画 ─────────────────────
                // 用户要的是「像 Windows 最大化那样自然放大」。三条路都实现并实测过，
                // 在这个栈上都不可用，结论留在这里免得下一个人重走：
                // ① 快照缩放（抓当前画面 → 遮罩里放大）：`Robot` 抓不到 GPU 合成的窗口
                //    内容（整屏「最亮 202」而窗口那块全黑）；`PrintWindow(PW_RENDERFULLCONTENT)`
                //    在窗口态也抓到全黑（全屏态偶尔抓得到）。结果是一块**黑方块放大**。
                // ② 逐帧改 bounds：尺寸序列对（12 级），但 Compose 一帧都追不上，
                //    整段过渡全黑；放到 5 步 × 40ms（软件渲染也试了）依旧全黑 ——
                //    libVLC 的输出重建 + Compose 重排每次都打断上一帧。
                // ③ 暗场遮罩（0.1.20）：用户明确否掉。
                // 所以回到「一步到位」。它是**不闪也不黑**的：下面 `savedBounds` 与
                // `bounds` 都是一次性写死，界面重排在之后的一帧里完成。
                suspend fun applyBounds() {
                    if (fullscreen) {
                        // 系统最大化状态下改 `bounds` 是**无效**的：先还原成普通窗口。
                        // 还原是走 `SendMessage(SC_RESTORE)`，AWT 的 componentResized 稍后才到，
                        // 所以等一拍再读 bounds，否则存下来的「原始尺寸」其实是最大化那一份，
                        // 退出全屏就回不到原来的大小。
                        chrome.restoreIfZoomed()
                        if (chrome.nativeFrame) delay(80)
                        savedBounds = composeWindow.bounds
                        val screen = runCatching {
                            composeWindow.graphicsConfiguration.bounds
                        }.getOrElse {
                            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                        }
                        composeWindow.bounds = screen
                    } else {
                        val back = savedBounds
                        if (back != null) {
                            composeWindow.bounds = back
                        }
                    }
                }

                // 启动那一次（`--fullscreen`）与之后的切换走同一条路：都是即时切换。
                applyBounds()
                // 改完 bounds 再刷一次 DWM 外观：全屏时那圈 1px 边框正好落在**屏幕四边**，
                // 不刷掉就是「全屏之后屏幕有一条白描边」（见 NativeFrame.DWMWA_BORDER_COLOR）。
                com.cinemahall.desktop.app.ui.NativeFrame.refreshDwmLook(composeWindow)
                Log.info("[ui] 全屏=$fullscreen bounds=${composeWindow.bounds}")
            }

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
                        forceExitWatchdog()
                    },
                    initialMovie = initialMovie,
                    // `--no-autoplay` 必须真的生效：它是「从端是不是被引擎拉起来的」这类
                    // 验证的前提，写死 true 会让那些验证全都变成假阳性。
                    initialAutoplay = options.autoplay,
                    cinema = cinema,
                    account = account,
                    initialScreen = screenOf(options.initialScreen),
                    cinemaAuto = if (options.cinemaCreate || options.cinemaJoin != null) {
                        CinemaAuto(options.cinemaCreate, options.cinemaJoin ?: options.roomId)
                    } else {
                        null
                    },
                    identityOverride = options.identity,
                    endpointOverride = options.signalEndpointOverride,
                    fullscreen = fullscreen,
                    onToggleFullscreen = { requestFullscreen(!fullscreen) },
                    chrome = chrome,
                    gateScreen = gateScreen,
                )
            }

            // 门禁：切页闪白 / 黑屏（`--screen-blink=<ms>`）。
            //
            // 「闪一下白」只存在于几十毫秒里：只看落定态永远是好的，必须**逐帧**抓。
            // 这里在影厅正播着的时候切到影片库、再切回影厅，每次切换前后各抓一串帧，
            // 帧号与单帧耗时都进日志 —— 于是「白在第几帧、持续多久」可以数出来，
            // 而不是靠肉眼描述。
            LaunchedEffect(options.screenBlinkMs) {
                val at = options.screenBlinkMs
                if (at <= 0) return@LaunchedEffect
                val dir = (options.screenshotPath?.parentFile ?: File("_tmp")).apply { mkdirs() }
                val robot = runCatching { Robot() }.getOrNull()
                suspend fun burst(tag: String) {
                    repeat(10) { i ->
                        val started = System.currentTimeMillis()
                        val bounds = composeWindow.bounds
                        val image = withContext(Dispatchers.IO) {
                            runCatching { robot?.createScreenCapture(bounds) }.getOrNull()
                        }
                        if (image != null) {
                            val name = "flip_${tag}_${i.toString().padStart(2, '0')}.png"
                            runCatching { ImageIO.write(image, "png", File(dir, name)) }
                        }
                        Log.info("[gate] 帧 $tag#$i 耗时=${System.currentTimeMillis() - started}ms")
                    }
                }
                delay(at.toLong())
                composeWindow.toFront()
                composeWindow.requestFocus()
                composeWindow.isAlwaysOnTop = true
                Log.info("[gate] 切页门禁：影厅 → 影片库")
                gateScreen = Screen.Library
                burst("to_lib")
                delay(1_200)
                Log.info("[gate] 切页门禁：影片库 → 影厅")
                gateScreen = Screen.Cinema
                burst("back")
                delay(1_500)
                burst("steady")
                Log.info("[gate] 切页门禁：结束")
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
                        // ⚠️ 这里**不能**用 `Thread.sleep`：它冻住 EDT，浮层的首帧画不出来，
                        // 截出来的就是一块没画完的窗口。
                        delay(600)
                        // 原生交互自检：`probe` 走的是**真实 WndProc**（`SendMessage(WM_NCHITTEST)`），
                        // 所以「标题栏能不能拖、边缘能不能拉」在没有鼠标的情况下也能判死。
                        Log.info("[native] 截图前复核 ${com.cinemahall.desktop.app.ui.NativeFrame.describe(window)}")
                        Log.info("[native] 截图前命中 ${com.cinemahall.desktop.app.ui.NativeFrame.probe(window)}")
                        // 悬浮控制条只对鼠标有反应，而截图脚本按不了鼠标 —— 门禁给它一条
                        // 直接入口。先叫出来，再等它真的画出第一帧（ComposePanel 首次
                        // 布局要几百毫秒），最后重新计时一次，别让 2.5 秒在快门那一瞬收回。
                        if (options.hoverCenter) {
                            // 先走**真实路径**：把指针挪到画面中心，看「指针轮询」自己能不能
                            // 把控制条叫出来（用户晃鼠标走的就是这条）。Robot 在个别会话里
                            // 挪不动指针，所以挪完还没出来就退回直接调用。
                            val robot = com.cinemahall.desktop.app.ui.PlaybackOverlay.hoverActiveByRobot()
                            delay(500)
                            val viaHover = com.cinemahall.desktop.app.ui.PlaybackOverlay.activeSettled()
                            if (!viaHover) {
                                com.cinemahall.desktop.app.ui.PlaybackOverlay.pokeActive()
                            }
                            var waited = 0
                            while (waited < 3_000 && !com.cinemahall.desktop.app.ui.PlaybackOverlay.activeSettled()) {
                                delay(100)
                                waited += 100
                            }
                            com.cinemahall.desktop.app.ui.PlaybackOverlay.pokeActive()
                            delay(120)
                            Log.info(
                                "[ui] 浮层 robot=$robot hover=$viaHover settled=" +
                                    "${com.cinemahall.desktop.app.ui.PlaybackOverlay.activeSettled()} waited=${waited}ms",
                            )
                        }
                        val bounds: Rectangle = window.bounds
                        val image = withContext(Dispatchers.IO) { Robot().createScreenCapture(bounds) }
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
                forceExitWatchdog()
            }
        }

        // 托盘：关闭窗口 = 退出（本轮不做“最小化到托盘”的行为差异，避免两套退出语义）。
        LaunchedEffect(Unit) {
            TraySupport.install(
                onShow = { windowState.isMinimized = false },
                onExit = {
                    cleanup()
                    exitApplication()
                    forceExitWatchdog()
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
    /**
     * 门禁用：`--activate=<入场码>` —— 在窗口出现之前把激活做完。
     *
     * 为什么需要它：生产是 `AUTH_MODE=required`，双实例验证要两个**不同账号**；
     * 而激活页是 GUI，自动化脚本没法往里打字。这条参数走的就是产品同一条
     * `DesktopAccount.activate()`，不做任何测试专用的旁路。
     *
     * ⚠️ 码本身**不进日志**（它是一次性凭证），`describe()` 只打「有没有传」。
     */
    val activateCode: String?,
    /** 激活时的设备名（默认计算机名）。 */
    val deviceName: String?,
    /** 激活时的昵称（可选；留空由服务端生成）。 */
    val nickname: String?,
    /** 信令服务地址（C5）。默认指向生产；自检与 `--activate` 都用这个解析后的值。 */
    val signalEndpoint: String,
    /**
     * 用户**显式**传了 `--signal-endpoint` 吗？只有显式传才对 UI 生效。
     *
     * 为什么要和 [signalEndpoint] 分开：那个是要给自检用的「解析后」值（永远非空），
     * 而界面侧的优先级是 `--signal-endpoint` > `settings.json` > 内置默认。
     * 早先界面只读 settings，于是「用 `--signal-endpoint` 指向本机服务端跑一次」
     * 会变成：激活打在本机，界面却去连生产 —— 两端对不上，且门禁还以为自己连的是本机。
     */
    val signalEndpointOverride: String?,
    /** 门禁用：启动即全屏。与 `--screen=` 同类 —— 截图脚本没法按 F11。 */
    val startFullscreen: Boolean,
    /**
     * 门禁用：截图前把悬浮控制条「叫出来」并等它画出第一帧。
     *
     * 悬浮控制条（`PlaybackOverlay`）只对「鼠标在画面上移动」有反应，而截图脚本
     * 没法按鼠标（`Robot.mouseMove` 在无人值守会话里也不可靠）。不叫这一下，
     * 截图里永远看不到它，也就没法验证「浮在画面上」这件事。
     */
    val hoverCenter: Boolean,
    /**
     * 门禁用：启动 [fullscreenBlinkMs] 毫秒后进全屏，再过 3 秒退全屏。
     *
     * 为什么要这个参数而不是让脚本按 F11：本机 `keybd_event` 注入的 F11 会被
     * 收到**两次 KeyDown**（实测一次按键触发了两轮 toggle，日志里出现两条遮罩记录、
     * 状态来回横跳），全屏过渡根本没法稳定复现。这个入口走的是产品同一条
     * `fullscreen = true` 分支，不绕过任何逻辑。
     */
    val fullscreenBlinkMs: Int,
    /**
     * 门禁用：`--screen-blink=<ms>` —— 启动 N 毫秒后自己「影厅 → 影片库 → 影厅」走一遍，
     * 每次切换前后各抓 10 帧到 `--screenshot` 同目录（`flip_*.png`）。
     *
     * 为什么要这个参数而不是让脚本注入点击：切页闪白只存在几十毫秒，脚本的
     * `SetCursorPos + mouse_event` 注入本身就要几十毫秒不确定延迟，抓不到那一帧；
     * 而且侧栏坐标会随窗口尺寸/缩放变化，脚本得先标定。这个入口走的是产品里
     * **同一条** `screen = ...` 分支，且切换与抓帧在同一个协程里，时序是确定的。
     */
    val screenBlinkMs: Int,
    /**
     * 门禁用：把窗口的 AWT 组件树（类名 / 背景色 / 尺寸）打进日志。
     *
     * 排查「拖边露白」时靠它定位到底是谁在画白：AWT 在重绘前会拿**各组件自己的背景色**
     * 刷一遍，默认是白。日志里哪一层的 `bg=` 是 `java.awt.Color[r=255,g=255,b=255]`，
     * 那一条就是来源。
     */
    val dumpTree: Boolean,
    /**
     * 无边框窗口的交互是否交给 Windows（默认 true）。
     *
     * `--native-frame=0` 关掉：拖动/八向缩放/双击最大化全部退回自绘那一套。
     * 留在参数里是为了真机出问题时的现场退路 —— 钩子写坏会让窗口拖不动，
     * 那种 bug 在自动化里看不出来（见 `NativeFrame` 的说明）。
     */
    val nativeFrame: Boolean,
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
            "fullscreen=$startFullscreen hoverCenter=$hoverCenter nativeFrame=$nativeFrame " +
            "cinemaJoin=${cinemaJoin ?: "-"} identity=${identity ?: "-"} " +
            "activate=${activateCode != null} deviceName=${deviceName ?: "-"} " +
            "nickname=${nickname ?: "-"} " +
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
                startFullscreen = args.contains("--fullscreen"),
                hoverCenter = args.contains("--hover-center"),
                fullscreenBlinkMs = value("--fullscreen-blink")?.toIntOrNull() ?: 0,
                screenBlinkMs = value("--screen-blink")?.toIntOrNull() ?: 0,
                dumpTree = args.contains("--dump-tree"),
                // 默认开：无边框窗口的拖动/缩放/最大化交给系统。
                nativeFrame = value("--native-frame")?.trim() != "0",
                cinemaCreate = args.contains("--cinema-create"),
                cinemaJoin = value("--cinema-join")?.takeIf { it.isNotBlank() },
                identity = value("--identity")?.takeIf { it.isNotBlank() },
                activateCode = value("--activate")?.takeIf { it.isNotBlank() },
                deviceName = value("--device-name")?.takeIf { it.isNotBlank() },
                nickname = value("--nickname")?.takeIf { it.isNotBlank() },
                signalEndpoint = value("--signal-endpoint")?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_SIGNAL_ENDPOINT,
                signalEndpointOverride = value("--signal-endpoint")?.takeIf { it.isNotBlank() },
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

    /**
     * 装上去的那个图标。**必须留着引用**，否则 [uninstall] 无从下手。
     *
     * ── 为什么一定要卸（2026-11-06 实测）────────────────────────────────────────
     * AWT 的自动收尾（「最后一个窗口关掉就结束 EDT」）在**装了托盘图标**时不成立：
     * 托盘本身算一个「还有图形资源要看管」的理由。于是窗口关了、`exitApplication()`
     * 也调了，JVM 却一直活着 —— 进程还在，`app-<profile>.lock` 还占着，
     * 用户再双击就得到「另一个实例已在运行」，而任务管理器里那个进程看起来
     * 就是个幽灵。真机上这一条正是「到点退出/关窗之后进程不退」的成因。
     */
    private var installed: java.awt.TrayIcon? = null

    fun install(onShow: () -> Unit, onExit: () -> Unit) {
        if (installed != null) return
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
            installed = icon
            Log.info("托盘图标已安装")
        }.onFailure { Log.warn("托盘安装失败：${it.message}") }
    }

    /** 退出前卸掉托盘图标，让 AWT 能自己收尾（见 [installed] 的注释）。幂等。 */
    fun uninstall() {
        val icon = installed ?: return
        installed = null
        runCatching { java.awt.SystemTray.getSystemTray().remove(icon) }
            .onFailure { Log.warn("托盘卸载失败：${it.message}") }
    }
}
