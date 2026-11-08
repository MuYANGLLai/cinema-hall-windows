package com.cinemahall.desktop.app.ui

import com.cinemahall.desktop.app.Log
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * 无边框窗口的**原生交互**（2026-11-07 用户拍板「选 A」）。
 *
 * ── 这一层做什么 ─────────────────────────────────────────────────────────────
 * 窗口仍是**无边框**（`undecorated = true`，外观、自绘标题栏、主题色都不变），
 * 但把「窗口该怎么动」交还给 Windows：
 *
 * 1. 给窗口补 `WS_THICKFRAME | WS_MAXIMIZEBOX | WS_MINIMIZEBOX | WS_SYSMENU` ——
 *    没有这几个样式位，系统根本不给「可缩放/可最大化」的窗口排队。
 * 2. 拦 `WM_NCCALCSIZE` 返回 0：客户区铺满整个窗口，所以补了样式也**看不到**
 *    系统边框/标题栏。
 * 3. 拦 `WM_NCHITTEST`：边缘 → `HTLEFT/HTRIGHT/…`，标题栏 → `HTCAPTION`，
 *    三颗按钮 → `HTCLIENT`。于是拖动、拖边缩放、双击最大化、Aero 贴边吸附、
 *    拖到屏幕顶部最大化、最大化/还原动画**全部由系统跑**，我们一行手势代码都不写。
 * 4. 拦 `WM_GETMINMAXINFO`：最小尺寸仍是 980×620，最大化的矩形取**工作区**
 *    （不是整屏），所以最大化不会盖住任务栏 —— 这一步是「客户区铺满」的必要补偿。
 *
 * ── 为什么要有这一层而不是直接 `undecorated = false` ────────────────────────
 * 那会把系统标题栏画回来，自绘的三键与主题色就没了；而弹窗又要求无边框，
 * 两套外壳混在一起最难看。这里保住外观、只借系统的**行为**。
 *
 * ── 失败要能退 ──────────────────────────────────────────────────────────────
 * [install] 返回 false（JNA 加载不到、找不到 HWND、样式改不动）时，调用方
 * `WindowChrome` 保持 `nativeFrame = false`，那一套自绘拖动/缩放照旧生效。
 * 也可以用 `--native-frame=0` 强制走自绘路径。
 */
internal object NativeFrame {

    /** 一个窗口的装配参数。 */
    class Spec(
        /** 自绘标题栏的高度（dp）：这块区域返回 `HTCAPTION`，其余是 `HTCLIENT`。 */
        val titleBarDp: Int = 32,
        /** 标题栏右侧的按钮个数（主窗口 3 颗、弹窗 1 颗）：这块区域必须留成 `HTCLIENT`。 */
        val captionButtons: Int = 3,
        val resizable: Boolean = true,
        /**
         * 是否允许「最大化」（`WS_MAXIMIZEBOX`）。
         *
         * 弹窗给 false：挂牌之后双击它的标题栏会把它最大化，而弹窗最大化没有任何意义。
         */
        val maximizable: Boolean = true,
        val minWidth: Int = 980,
        val minHeight: Int = 620,
    )

    // ── 常量（WinUser.h）──────────────────────────────────────────────────────
    private const val GWL_STYLE = -16
    private const val GWL_WNDPROC = -4

    private const val GW_CHILD = 5
    private const val GW_HWNDNEXT = 2

    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_SYSMENU = 0x00080000L
    private const val WS_MINIMIZEBOX = 0x00020000L
    private const val WS_MAXIMIZEBOX = 0x00010000L

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private const val WM_GETMINMAXINFO = 0x0024
    private const val WM_SETCURSOR = 0x0020
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCLBUTTONDOWN = 0x00A1
    private const val WM_NCLBUTTONDBLCLK = 0x00A3
    private const val WM_SYSCOMMAND = 0x0112
    private const val WM_DPICHANGED = 0x02E0

    /**
     * 「这块不算我的」——命中测试交给同线程下面的那个窗口（这里是父窗口）。
     *
     * ⚠️ 这个值是让整套方案成立的关键，见 [install] 里对子窗口的处理：
     * Compose 的 `SunAwtCanvas` 子窗口铺满整个客户区，鼠标永远落在它身上；
     * 只有让它对「标题栏 / 缩放边」回 `HTTRANSPARENT`，系统才会转而问父窗口，
     * 我们写在父窗口上的 `HTCAPTION` 才真的有用。
     */
    private const val HTTRANSPARENT = -1

    private const val HTCLIENT = 1
    private const val HTCAPTION = 2
    private const val HTLEFT = 10
    private const val HTRIGHT = 11
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val HTBOTTOM = 15
    private const val HTBOTTOMLEFT = 16
    private const val HTBOTTOMRIGHT = 17

    const val SC_MINIMIZE = 0xF020
    const val SC_MAXIMIZE = 0xF030
    const val SC_RESTORE = 0xF120
    const val SC_CLOSE = 0xF060
    private const val SC_MOVE = 0xF010
    private const val SC_SIZE = 0xF000

    private const val MONITOR_DEFAULTTONEAREST = 2

    // 系统光标（`IDC_*`）：`LoadCursorW` 用 `MAKEINTRESOURCE` 语义，直接把数字当指针传。
    private const val IDC_SIZENWSE = 32642
    private const val IDC_SIZENESW = 32643
    private const val IDC_SIZEWE = 32644
    private const val IDC_SIZENS = 32645

    // ── user32 ───────────────────────────────────────────────────────────────
    internal interface User32 : Library {
        fun EnumWindows(cb: EnumProc, lParam: Pointer?): Boolean
        fun GetWindowTextW(hWnd: Pointer?, text: CharArray, maxCount: Int): Int
        fun GetWindowThreadProcessId(hWnd: Pointer?, pid: IntByReference?): Int
        fun IsWindowVisible(hWnd: Pointer?): Boolean
        fun GetWindow(hWnd: Pointer?, cmd: Int): Pointer?
        fun GetWindowLongPtrW(hWnd: Pointer?, index: Int): Long
        fun SetWindowLongPtrW(hWnd: Pointer?, index: Int, value: Long): Long
        fun CallWindowProcW(old: Pointer?, hWnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long
        fun SetWindowPos(hWnd: Pointer?, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hWnd: Pointer?, rect: Pointer?): Boolean
        fun IsZoomed(hWnd: Pointer?): Boolean
        fun SendMessageW(hWnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long
        fun GetCursorPos(point: Pointer?): Boolean
        fun MonitorFromWindow(hWnd: Pointer?, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer?, info: Pointer?): Boolean
        fun GetDpiForWindow(hWnd: Pointer?): Int
        fun ReleaseCapture(): Boolean
        fun GetClientRect(hWnd: Pointer?, rect: Pointer?): Boolean
        fun LoadCursorW(hInstance: Pointer?, name: Pointer?): Pointer?
        fun SetCursor(cursor: Pointer?): Pointer?
    }

    internal interface EnumProc : Callback {
        fun callback(hWnd: Pointer?, lParam: Pointer?): Boolean
    }

    // ── dwmapi（Win11 的窗口圆角）────────────────────────────────────────────────
    internal interface Dwm : Library {
        fun DwmSetWindowAttribute(hWnd: Pointer?, attr: Int, value: Pointer?, size: Int): Int
    }

    private val dwm: Dwm? = runCatching {
        Native.load("dwmapi", Dwm::class.java) as Dwm
    }.getOrElse {
        Log.warn("[native] dwmapi 加载失败（窗口不会有圆角）：${it.message}")
        null
    }

    /** `DWMWA_WINDOW_CORNER_PREFERENCE`（Win11 22000+）。 */
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33

    /** `DWM_WINDOW_CORNER_PREFERENCE`：0=系统默认、2=圆角、3=直角。 */
    private const val DWMWCP_ROUND = 2


    internal interface WndProc : Callback {
        fun callback(hWnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long
    }

    private val user32: User32? = runCatching {
        Native.load("user32", User32::class.java) as User32
    }.getOrElse {
        Log.warn("[native] user32 加载失败：${it.message}")
        null
    }

    /** 装好的窗口：把回调与旧 WndProc 都钉住（JNA 回调被回收会导致进程直接崩）。 */
    private class Installed(
        val hwnd: Pointer,
        val window: Window,
        val spec: Spec,
        /** 原来的 WndProc（AWT 自己的）。装钩子之后才拿到，所以是 var。 */
        var oldProc: Pointer?,
        val proc: WndProc,
    ) {
        /**
         * `GetWindowRect` / `GetMonitorInfoW` 的接收缓冲。
         *
         * 复用而不是每次调 `Memory()`：`WM_NCHITTEST` 在鼠标划过窗口时**每个像素都可能来一次**，
         * 每次分配一块本地内存会让 GC 在拖动/缩放时持续抖动。
         */
        val rect: com.sun.jna.Memory = com.sun.jna.Memory(16)
        val monitorInfo: com.sun.jna.Memory = com.sun.jna.Memory(60)

        /** DPI 缓存：`GetDpiForWindow` 本身不贵，但它跑在每条 `WM_NCHITTEST` 上。 */
        @Volatile
        var dpi: Int = 96

        /**
         * 全屏中：一刀切返回 `HTCLIENT`，既不能拖也不能拉边。
         *
         * 与系统独占全屏一致 —— 全屏时窗口不该还能被拖着走（用户会顺着标题栏把它
         * 拽出屏幕，然后以为程序坏了）。
         */
        @Volatile
        var fullscreen: Boolean = false

        /** 拖动/缩放第一次被接管时各打一行日志：门禁与真机排障都靠它。 */
        @Volatile
        var loggedMove: Boolean = false

        @Volatile
        var loggedSize: Boolean = false
    }

    private val installed = java.util.concurrent.ConcurrentHashMap<Pointer, Installed>()
    private val byWindow = java.util.concurrent.ConcurrentHashMap<Window, Installed>()

    /**
     * 盖在客户区上的子窗口（Compose 的 `SunAwtCanvas`）的钩子。
     *
     * 它只做两件事：把「标题栏 / 缩放边」的命中测试回 `HTTRANSPARENT` 让给父窗口，
     * 以及在这些位置上换成对应的缩放光标。其余消息一律原样交回。
     */
    private class ChildHook(
        val hwnd: Pointer,
        val parent: Installed,
        var oldProc: Pointer?,
        val proc: WndProc,
    )

    private val children = java.util.concurrent.ConcurrentHashMap<Pointer, ChildHook>()

    /**
     * 总开关。`--native-frame=0` 在 `main()` 里把它关掉，[install] 随即返回 false，
     * [WindowChrome] 保持 `nativeFrame = false` —— 整套退回**自绘**拖动/缩放/三键。
     *
     * 留这个开关不是为了「能力开关」，是为了**留退路**：WndProc 钩子写错会让窗口
     * 拖不动、拉不动，而那种 bug 只能在真机上看到；有这个开关用户当场就能切回来。
     */
    @Volatile
    var enabled: Boolean = true

    /** 已经装上原生交互的窗口。 */
    fun isNative(window: Window): Boolean = byWindow.containsKey(window)

    /**
     * 给这个窗口装上原生交互。返回 false 表示装不上（调用方保持自绘那一套）。
     */
    fun install(window: Window, spec: Spec): Boolean {
        if (!enabled) {
            Log.info("[native] 已被 --native-frame=0 关掉，走自绘交互")
            return false
        }
        val api = user32 ?: return false
        if (byWindow.containsKey(window)) return true
        val hwnd = findHwnd(window, requireTopLevel = window is java.awt.Frame) ?: run {
            Log.warn("[native] 找不到窗口句柄，退回自绘交互")
            return false
        }
        return runCatching {
            // ⚠️ 顺序要紧：`record` 必须在**挂钩子之前**建好并赋给 lateinit。
            // 反过来的话，挂钩与赋值之间只要来一条消息，回调就会去读未初始化的 record ——
            // 在 native 回调里抛异常是直接崩进程，不是普通异常。
            lateinit var record: Installed
            val impl = object : WndProc {
                override fun callback(hWnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long {
                    val self = record
                    // ⚠️ 回调里抛异常 = **直接崩进程**（异常会穿过 native 的栈帧）。
                    // 所以这里全兜住：出任何岔子都退回原 WndProc 当默认行为，
                    // 最坏结果是「这一下拖不动」，不会把程序带走。
                    val answer = runCatching { customAnswer(api, self, msg, wParam, lParam) }.getOrNull()
                    if (answer != null) return answer
                    return api.CallWindowProcW(self.oldProc, hWnd, msg, wParam, lParam)
                }
            }
            record = Installed(hwnd, window, spec, null, impl)
            record.dpi = api.GetDpiForWindow(hwnd).let { if (it <= 0) 96 else it }
            val ptr = com.sun.jna.CallbackReference.getFunctionPointer(impl)
            record.oldProc = Pointer(api.SetWindowLongPtrW(hwnd, GWL_WNDPROC, Pointer.nativeValue(ptr)))
            installed[hwnd] = record
            byWindow[window] = record

            // ── 挂钩**之后**才动样式位 ────────────────────────────────────────────
            // 顺序反了就前功尽弃（2026-11-07 实测踩过）：`SetWindowPos(SWP_FRAMECHANGED)`
            // 会触发一次 `WM_NCCALCSIZE` 重算。若此时钩子还没挂上，那一次由 AWT 的
            // 旧 WndProc 回答 ⇒ 客户区被按「标题栏 32px + 边框」裁掉，于是**系统标题栏
            // 真的画出来了**，界面上出现两条标题栏（系统的 + 我们自绘的）。
            // 先挂钩，这次重算就由我们返回 0，客户区铺满。
            val style = api.GetWindowLongPtrW(hwnd, GWL_STYLE)
            var next = style or WS_CAPTION or WS_SYSMENU
            if (spec.resizable) next = next or WS_THICKFRAME
            if (spec.resizable && spec.maximizable) next = next or WS_MAXIMIZEBOX or WS_MINIMIZEBOX
            if (next != style) {
                api.SetWindowLongPtrW(hwnd, GWL_STYLE, next)
                api.SetWindowPos(
                    hwnd, null, 0, 0, 0, 0,
                    SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
                )
            }
            hookChildren(api, record)
            // 圆角最后设：它跟样式位无关，放在前面会被 `SetWindowPos(SWP_FRAMECHANGED)`
            // 之后的一次重建盖回去（实测只在重建前设会失效）。
            applyRoundCorners(hwnd)
            true
        }.getOrElse { error ->
            Log.warn("[native] 装配原生交互失败，退回自绘：${error.message}")
            false
        }
    }

    /**
     * 接管「盖满客户区」的子窗口（Compose 的 `SunAwtCanvas`）。
     *
     * ── 为什么非要这一层（2026-11-07 实测，踩了一整轮）─────────────────────────
     * 只给顶层窗口写 `HTCAPTION` 是**完全无效**的：Compose Desktop 的内容挂在
     * 一个 `SunAwtCanvas` 子窗口上，它铺满整个客户区（实测 1440×920 与客户区一致），
     * 鼠标永远落在子窗口身上 —— 系统问的是**子窗口**，父窗口的命中测试从头到尾
     * 没被调用过。表现就是「三键能点（那是界面画的），标题栏拖不动」。
     * （我当时用 `SendMessage(WM_NCHITTEST)` 自检还得出「分区正确」的假结论：
     * 那等于自己问自己，绕过了系统真实的命中测试路径。）
     *
     * 正解：子窗口对**不属于界面**的那些位置回 `HTTRANSPARENT`，系统会顺着同线程
     * 继续问父窗口，父窗口的 `HTCAPTION` / `HTLEFT`… 这才生效。
     */
    private fun hookChildren(api: User32, record: Installed) {
        val client = record.rect
        if (!api.GetClientRect(record.hwnd, client)) return
        val clientW = client.getInt(8)
        val clientH = client.getInt(12)
        if (clientW <= 0 || clientH <= 0) return

        var child = api.GetWindow(record.hwnd, GW_CHILD)
        var guard = 0
        while (child != null && guard++ < 32) {
            val next = api.GetWindow(child, GW_HWNDNEXT)
            hookChild(api, record, child, clientW, clientH)
            child = next
        }
    }

    private fun hookChild(api: User32, parent: Installed, child: Pointer, clientW: Int, clientH: Int) {
        if (children.containsKey(child)) return
        if (!api.IsWindowVisible(child)) return
        val rect = parent.rect
        if (!api.GetWindowRect(child, rect)) return
        val w = rect.getInt(8) - rect.getInt(0)
        val h = rect.getInt(12) - rect.getInt(4)
        // 只接管「几乎盖满客户区」的那个（其余子窗口不参与命中测试，动了反而危险）。
        if (w * 100 < clientW * 90 || h * 100 < clientH * 90) return

        lateinit var hook: ChildHook
        val impl = object : WndProc {
            override fun callback(hWnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long {
                val self = hook
                val answer = runCatching { childAnswer(api, self, msg, lParam) }.getOrNull()
                if (answer != null) return answer
                return api.CallWindowProcW(self.oldProc, hWnd, msg, wParam, lParam)
            }
        }
        hook = ChildHook(child, parent, null, impl)
        val ptr = com.sun.jna.CallbackReference.getFunctionPointer(impl)
        hook.oldProc = Pointer(api.SetWindowLongPtrW(child, GWL_WNDPROC, Pointer.nativeValue(ptr)))
        children[child] = hook
        Log.info("[native] 已接管铺满客户区的子窗口 $child（命中测试让给父窗口）")
    }

    /**
     * 子窗口要抢答的消息。返回 null = 不归我管，交回子窗口原来的 WndProc。
     */
    private fun childAnswer(api: User32, self: ChildHook, msg: Int, lParam: Long): Long? = when (msg) {
        // 标题栏 / 缩放边：让系统去问父窗口。
        WM_NCHITTEST -> {
            val hit = hitTest(api, self.parent, lParam)
            if (hit != null && hit != HTCLIENT) HTTRANSPARENT.toLong() else null
        }

        // 光标：边缘要显示缩放箭头。不接管的话 AWT 的 Canvas 一律给箭头，
        // 用户看到「能拉但没提示」，会以为不能拉。
        WM_SETCURSOR -> when (hitUnderCursor(api, self.parent)) {
            HTLEFT, HTRIGHT -> setCursor(api, IDC_SIZEWE)
            HTTOP, HTBOTTOM -> setCursor(api, IDC_SIZENS)
            HTTOPLEFT, HTBOTTOMRIGHT -> setCursor(api, IDC_SIZENWSE)
            HTTOPRIGHT, HTBOTTOMLEFT -> setCursor(api, IDC_SIZENESW)
            else -> null
        }

        else -> null
    }

    private fun setCursor(api: User32, id: Int): Long? = runCatching {
        val cursor = api.LoadCursorW(null, Pointer(id.toLong())) ?: return null
        api.SetCursor(cursor)
        1L
    }.getOrNull()

    /** 指针此刻落在父窗口的哪个部位（没有指针信息就返回 `null`）。 */
    private fun hitUnderCursor(api: User32, parent: Installed): Int? {
        val pt = recordPoint
        if (!api.GetCursorPos(pt)) return null
        val x = pt.getInt(0)
        val y = pt.getInt(4)
        val lParam = ((y and 0xFFFF).toLong() shl 16) or (x and 0xFFFF).toLong()
        return hitTest(api, parent, lParam)
    }

    /** 给 `GetCursorPos` 用的临时缓冲（只在 UI 线程上用，复用即可）。 */
    private val recordPoint = com.sun.jna.Memory(8)

    /** 窗口尺寸变化后补一次子窗口体检（Compose 会在某些路径上重建内容层）。 */
    fun rehookChildren(window: Window) {
        val api = user32 ?: return
        val record = byWindow[window] ?: return
        runCatching { hookChildren(api, record) }
    }

    /** 已经接管到铺满客户区的子窗口了吗（装钩子之后它才被创建，所以要轮询几次）。 */
    fun childHooked(window: Window): Boolean {
        val record = byWindow[window] ?: return false
        return children.values.any { it.parent === record }
    }

    /** 窗口销毁前必须解挂：把旧 WndProc 换回去，否则回调悬空会让进程崩。 */
    fun uninstall(window: Window) {
        val api = user32 ?: return
        val record = byWindow.remove(window) ?: return
        installed.remove(record.hwnd)
        // 子窗口先解：它们的回调引用了父窗口的 record，留着就是悬空指针。
        runCatching {
            children.entries.removeIf { (child, hook) ->
                if (hook.parent !== record) return@removeIf false
                hook.oldProc?.let { old -> api.SetWindowLongPtrW(child, GWL_WNDPROC, Pointer.nativeValue(old)) }
                true
            }
        }
        runCatching {
            record.oldProc?.let { old ->
                api.SetWindowLongPtrW(record.hwnd, GWL_WNDPROC, Pointer.nativeValue(old))
            }
        }
    }

    /** 发一条系统命令（最小化 / 最大化 / 还原 / 关闭）。 */
    fun sysCommand(window: Window, command: Int) {
        val api = user32 ?: return
        val record = byWindow[window] ?: return
        api.SendMessageW(record.hwnd, WM_SYSCOMMAND, command.toLong(), 0)
    }

    fun isZoomed(window: Window): Boolean {
        val api = user32 ?: return false
        val record = byWindow[window] ?: return false
        return api.IsZoomed(record.hwnd)
    }

    /**
     * 给窗口加 Windows 11 的**原生圆角**（2026-11-08 用户口径「四角倒角平滑」）。
     *
     * ── 为什么交给 DWM，而不是自己画 ────────────────────────────────────────────
     * 自己画只有两条路，两条都不行：
     *  ① 把窗口设成 per-pixel 半透明、四角画成透明 —— Windows 会因此**丢掉整个
     *     窗口的阴影**，还得自己补一层假的；
     *  ② 用 `SetWindowRgn` 抠掉四个角 —— 边缘是**硬锯齿**，正是「倒角不光滑」。
     * `DWMWA_WINDOW_CORNER_PREFERENCE` 让系统按它自己的圆角半径 + 抗锯齿去裁，
     * 与资源管理器/记事本逐像素一致，阴影也照常。
     *
     * ── 三件必须知道的事 ────────────────────────────────────────────────────────
     * 1. 只有 Win11（build ≥ 22000）认这个属性；Win10 会返回非 0 的 HRESULT，
     *    这里就当「没有圆角」放过，不抛。
     * 2. **最大化时系统按设计给直角**，还原后自动恢复圆角 —— 这是 Windows 自己的
     *    行为，不要去硬掰（掰了会跟贴边吸附打架）。
     * 3. 设的时机必须在 `SetWindowPos(SWP_FRAMECHANGED)` **之后**：改样式位会重建
     *    窗口的非客户区，顺带把圆角偏好重置掉。
     *
     * @return 是否真的生效（Win10 / 加载不到 dwmapi 时为 false）。
     */
    fun applyRoundCorners(window: Window, round: Boolean = true): Boolean {
        val hwnd = findHwnd(window, requireTopLevel = window is java.awt.Frame) ?: return false
        return applyRoundCorners(hwnd, round)
    }

    private fun applyRoundCorners(hwnd: Pointer, round: Boolean = true): Boolean {
        val api = dwm ?: return false
        return runCatching {
            // 属性值是一个 4 字节枚举，用显式 malloc 比 JNA 的自动映射稳。
            val buffer = com.sun.jna.Memory(4)
            buffer.setInt(0, if (round) DWMWCP_ROUND else 0)
            val hr = api.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, buffer, 4)
            if (hr != 0) {
                Log.info("[native] 窗口圆角不受支持（Win10？hr=0x${Integer.toHexString(hr)}）")
                false
            } else {
                Log.info("[native] 窗口圆角已交给 DWM（DWMWCP_ROUND）")
                true
            }
        }.getOrElse { error ->
            Log.warn("[native] 设置圆角失败：${error.message}")
            false
        }
    }

    /** 全屏开关：全屏期间不让拖、不让拉边（见 [Installed.fullscreen]）。 */
    fun setFullscreen(window: Window, value: Boolean) {
        byWindow[window]?.fullscreen = value
    }

    /**
     * 进系统的「拖动窗口」循环。
     *
     * `SC_MOVE or 2`：低 4 位是方向/来源标记，鼠标发起时要带 `2`（系统标题栏走的也是这个值）。
     * 这条 `SendMessage` **会一直阻塞到用户松手**，期间系统的模态循环接管一切（含贴边吸附）。
     */
    private fun beginSystemMove(api: User32, record: Installed) {
        if (api.IsZoomed(record.hwnd)) return
        runCatching {
            api.ReleaseCapture()
            api.SendMessageW(record.hwnd, WM_SYSCOMMAND, (SC_MOVE or 0x0002).toLong(), 0)
            if (!record.loggedMove) {
                record.loggedMove = true
                Log.info("[native] 标题栏拖动已由系统接管（SC_MOVE 循环返回，窗口=${windowOf(record)}）")
            }
        }.onFailure { Log.warn("[native] 拖动接管失败：${it.message}") }
    }

    /** 进系统的「拉边改大小」循环。`direction` 取 `WMSZ_LEFT(1)…WMSZ_BOTTOMRIGHT(8)`。 */
    private fun beginSystemSize(api: User32, record: Installed, direction: Int) {
        if (!record.spec.resizable || api.IsZoomed(record.hwnd)) return
        runCatching {
            api.ReleaseCapture()
            api.SendMessageW(record.hwnd, WM_SYSCOMMAND, (SC_SIZE or direction).toLong(), 0)
            if (!record.loggedSize) {
                record.loggedSize = true
                Log.info("[native] 拉边缩放已由系统接管（SC_SIZE 方向=$direction）")
            }
        }.onFailure { Log.warn("[native] 缩放接管失败：${it.message}") }
    }

    private fun windowOf(record: Installed): String = runCatching {
        record.window.bounds.toString()
    }.getOrElse { "?" }

    // ── 门禁用的自检入口 ─────────────────────────────────────────────────────
    /** `hwnd@风格位`，给自检日志用。 */
    fun describe(window: Window): String {
        val api = user32 ?: return "user32=null"
        val record = byWindow[window] ?: return "未装配"
        val style = api.GetWindowLongPtrW(record.hwnd, GWL_STYLE)
        val flags = buildString {
            if (style and WS_THICKFRAME != 0L) append("THICKFRAME ")
            if (style and WS_MAXIMIZEBOX != 0L) append("MAXBOX ")
            if (style and WS_MINIMIZEBOX != 0L) append("MINBOX ")
            if (style and WS_SYSMENU != 0L) append("SYSMENU ")
        }
        return "hwnd=${record.hwnd} style=0x${style.toString(16)} [$flags] zoomed=${api.IsZoomed(record.hwnd)}"
    }

    /**
     * 直接问系统「屏幕这个点上，这个窗口是什么部位」。
     *
     * 这是**走真实 WndProc** 的判据：它会调我们装上去的回调，返回的就是用户在那一刻
     * 按下鼠标时 Windows 拿到的 `HT*`。没有鼠标也能验证拖动/缩放/按钮的分区对不对。
     */
    fun hitTestAt(window: Window, screenX: Int, screenY: Int): Int? {
        val api = user32 ?: return null
        val record = byWindow[window] ?: return null
        val lParam = ((screenY and 0xFFFF).toLong() shl 16) or (screenX and 0xFFFF).toLong()
        return api.SendMessageW(record.hwnd, WM_NCHITTEST, 0, lParam).toInt()
    }

    fun hitTestName(code: Int): String = when (code) {
        HTCAPTION -> "HTCAPTION(可拖动)"
        HTCLIENT -> "HTCLIENT(交给界面)"
        HTTRANSPARENT -> "HTTRANSPARENT(让给父窗口)"
        HTLEFT -> "HTLEFT(左缩放)"
        HTRIGHT -> "HTRIGHT(右缩放)"
        HTTOP -> "HTTOP(上缩放)"
        HTBOTTOM -> "HTBOTTOM(下缩放)"
        HTTOPLEFT -> "HTTOPLEFT(左上)"
        HTTOPRIGHT -> "HTTOPRIGHT(右上)"
        HTBOTTOMLEFT -> "HTBOTTOMLEFT(左下)"
        HTBOTTOMRIGHT -> "HTBOTTOMRIGHT(右下)"
        else -> "HT$code"
    }

    /**
     * 门禁：在窗口的几个关键位置各问一次 `WM_NCHITTEST`，拼成一行可核对的结论。
     *
     * ── 为什么必须这样验 ──────────────────────────────────────────────────────
     * 真鼠标在本机复现不了（自动化脚本里 `Robot` 的事件进不到窗口），所以只能直接问。
     * 但**只问父窗口是不够的**——那样会把「父窗口说 HTCAPTION」当成「拖得动」，
     * 而真实的鼠标其实落在子窗口上（2026-11-07 就是用这个假判据放过了「拖不动」）。
     * 所以这里同时问**子窗口**：它必须在标题栏/边缘回 `HTTRANSPARENT`，
     * 父窗口的答案才有机会生效。
     */
    fun probe(window: Window): String {
        val api = user32 ?: return "user32=null"
        val record = byWindow[window] ?: return "未装配"
        val mem = record.rect
        if (!api.GetWindowRect(record.hwnd, mem)) return "GetWindowRect 失败"
        val left = mem.getInt(0)
        val top = mem.getInt(4)
        val right = mem.getInt(8)
        val bottom = mem.getInt(12)
        val scale = record.dpi / 96.0
        val barY = top + (record.spec.titleBarDp * scale).toInt() / 2
        val btnWidth = (record.spec.captionButtons * CAPTION_BUTTON_DP * scale).toInt()
        val child = children.entries.firstOrNull { it.value.parent === record }?.key

        fun at(name: String, x: Int, y: Int): String {
            val lParam = ((y and 0xFFFF).toLong() shl 16) or (x and 0xFFFF).toLong()
            val parent = api.SendMessageW(record.hwnd, WM_NCHITTEST, 0, lParam).toInt()
            val childName = child?.let {
                hitTestName(api.SendMessageW(it, WM_NCHITTEST, 0, lParam).toInt())
            } ?: "无子窗口"
            return "$name=父:${hitTestName(parent)} 子:$childName"
        }
        return listOf(
            at("标题栏", left + 40, barY),
            at("按钮区", right - btnWidth / 2, barY),
            at("客户区", left + (right - left) / 2, top + (bottom - top) / 2),
            at("左边缘", left + 2, top + (bottom - top) / 2),
            at("右下角", right - 2, bottom - 2),
        ).joinToString("\n                    ")
    }

    fun dpiOf(window: Window): Int {
        val api = user32 ?: return 96
        val record = byWindow[window] ?: return 96
        return api.GetDpiForWindow(record.hwnd).let { if (it <= 0) 96 else it }
    }

    // ── 命中测试 ─────────────────────────────────────────────────────────────
    /**
     * 本层要「抢答」的消息。返回 null = 不归我管，交回原 WndProc。
     */
    private fun customAnswer(api: User32, self: Installed, msg: Int, wParam: Long, lParam: Long): Long? =
        when (msg) {
            // 客户区铺满整个窗口 —— 补了 WS_CAPTION 也**看不到**系统标题栏的那一步。
            //
            // ── 最大化要单独处理（2026-11-07 实测）──────────────────────────────
            // 带厚边框的窗口最大化时，系统给的**窗口矩形**是「工作区各边外扩 8px」
            // （实测 2560x1392 的工作区 → 窗口 -8,-8 2576x1408）。若这里照样返回 0，
            // 客户区就是那一圈更大的矩形 ⇒ 画面会压住任务栏、并溢出屏幕 8px。
            // 系统的本意是「客户区 = 工作区」，所以最大化时把客户区显式写回 `rcWork`。
            WM_NCCALCSIZE -> if (wParam != 0L) {
                if (api.IsZoomed(self.hwnd)) writeWorkArea(api, self, lParam)
                0L
            } else {
                null
            }
            WM_GETMINMAXINFO -> {
                handleMinMax(api, self, lParam)
                0L
            }

            WM_NCHITTEST -> hitTest(api, self, lParam)?.toLong()

            // ── 非客户区按下：自己进系统的移动/缩放循环（2026-11-07 实测必须这么写）──
            // 只把 `HTCAPTION` 交给系统是不够的：**AWT 的窗口过程把
            // `WM_NCLBUTTONDOWN` 吃掉了**，`DefWindowProc` 收不到它，
            // 于是「按下标题栏」什么都不会发生 —— 用户看到的就是「拖不动」。
            // （实测：注入真实鼠标在标题栏按下并拖动 220px，窗口矩形一动不动。）
            //
            // 正解是所有无边框窗口的老办法：先 `ReleaseCapture()`，再自己
            // `SendMessage(WM_SYSCOMMAND, SC_MOVE|2)`；缩放同理发 `SC_SIZE + 方向`。
            // 位移、吸附、拖到屏幕顶部最大化、动画全都还在系统手里，我们只是替它
            // **按下这个键**。返回 0 = 这条消息我们处理完了，不再往 AWT 走。
            WM_NCLBUTTONDOWN -> when (val hit = wParam.toInt()) {
                HTCAPTION -> {
                    beginSystemMove(api, self)
                    0L
                }

                in HTLEFT..HTBOTTOMRIGHT -> {
                    // HTLEFT(10)…HTBOTTOMRIGHT(17) 减 9 正好是 WMSZ_LEFT(1)…WMSZ_BOTTOMRIGHT(8)。
                    beginSystemSize(api, self, hit - 9)
                    0L
                }

                else -> null
            }

            // 标题栏双击：最大化 / 还原。系统只会把 DBLCLK 发给「自己处理了按下」的窗口，
            // 上面那条已经把按下接管了，所以这一条必须也自己接。
            WM_NCLBUTTONDBLCLK -> if (wParam.toInt() == HTCAPTION) {
                api.SendMessageW(
                    self.hwnd,
                    WM_SYSCOMMAND,
                    (if (api.IsZoomed(self.hwnd)) SC_RESTORE else SC_MAXIMIZE).toLong(),
                    0,
                )
                0L
            } else {
                null
            }
            // 换了显示器/改了缩放比例：只刷缓存，**必须**交回系统去做真正的重排。
            WM_DPICHANGED -> {
                val dpi = (wParam and 0xFFFF).toInt()
                if (dpi > 0) self.dpi = dpi
                null
            }

            else -> null
        }

    /**
     * 返回 null 表示「不归我管」——交回 `DefWindowProc`（它会按样式位给出默认答案）。
     */
    private fun hitTest(api: User32, record: Installed, lParam: Long): Int? {
        // lParam 里是屏幕坐标，x 在低 16 位、y 在高 16 位，都是**有符号** short。
        val x = (lParam and 0xFFFF).toShort().toInt()
        val y = ((lParam shr 16) and 0xFFFF).toShort().toInt()
        val mem = record.rect
        if (!api.GetWindowRect(record.hwnd, mem)) return null
        val left = mem.getInt(0)
        val top = mem.getInt(4)
        val right = mem.getInt(8)
        val bottom = mem.getInt(12)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        // 全屏：不接受拖动，也不接受拉边。
        if (record.fullscreen) return HTCLIENT

        val zoomed = api.IsZoomed(record.hwnd)
        val dpi = record.dpi
        val scale = dpi / 96.0

        // 最大化时不留缩放边（与系统标题栏窗口一致）。
        val edge = if (record.spec.resizable && !zoomed) maxOf(6, (8 * scale).toInt()) else 0

        if (edge > 0) {
            val onLeft = x - left < edge
            val onRight = right - x <= edge
            val onTop = y - top < edge
            val onBottom = bottom - y <= edge
            if (onTop && onLeft) return HTTOPLEFT
            if (onTop && onRight) return HTTOPRIGHT
            if (onBottom && onLeft) return HTBOTTOMLEFT
            if (onBottom && onRight) return HTBOTTOMRIGHT
            if (onLeft) return HTLEFT
            if (onRight) return HTRIGHT
            if (onTop) return HTTOP
            if (onBottom) return HTBOTTOM
        }

        // 自绘标题栏：右侧留出按钮区（按钮必须能收到点击 ⇒ HTCLIENT），其余 HTCAPTION。
        val barBottom = top + (record.spec.titleBarDp * scale).toInt()
        if (y < barBottom) {
            val buttonsWidth = (record.spec.captionButtons * com.cinemahall.desktop.app.ui.CAPTION_BUTTON_DP * scale).toInt()
            return if (x > right - buttonsWidth) HTCLIENT else HTCAPTION
        }
        return HTCLIENT
    }

    /**
     * 最小尺寸 + 最大化矩形。
     *
     * 最大化取**工作区**（`rcWork`）而不是整屏：客户区铺满整个窗口之后，系统默认的
     * 「比屏幕大一圈」的补偿就不成立了 —— 不写这一段，最大化会盖住任务栏，
     * 而且四边各多出 8px。
     */
    /** 显示器矩形与工作区（屏幕坐标）。 */
    private class WorkArea(
        val monLeft: Int,
        val monTop: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /**
     * 取 `rcWork`（已扣掉任务栏）。
     *
     * MONITORINFO 布局（共 40 字节）：
     *   +0  cbSize(4)  +4  rcMonitor.left  +8  .top  +12 .right  +16 .bottom
     *   +20 rcWork.left  +24 .top  +28 .right  +32 .bottom   +36 dwFlags
     *
     * ⚠️ `rcWork.bottom` 在 **+32**，不是 +36 —— 早先按 `+8+16+12`=36 取过一次，
     * 读到的是 `dwFlags`（0 或 1），算出来的高度是个负数，最大化直接失效。
     */
    private fun workAreaOf(api: User32, record: Installed): WorkArea? {
        val info = record.monitorInfo
        info.clear()
        info.setInt(0, 40)
        val monitor = api.MonitorFromWindow(record.hwnd, MONITOR_DEFAULTTONEAREST) ?: return null
        if (!api.GetMonitorInfoW(monitor, info)) return null
        val area = WorkArea(
            monLeft = info.getInt(4),
            monTop = info.getInt(8),
            left = info.getInt(20),
            top = info.getInt(24),
            right = info.getInt(28),
            bottom = info.getInt(32),
        )
        if (area.right <= area.left || area.bottom <= area.top) return null
        return area
    }

    /** 最大化时把客户区对齐工作区（见 `WM_NCCALCSIZE` 分支的说明）。 */
    private fun writeWorkArea(api: User32, record: Installed, lParam: Long) {
        val area = workAreaOf(api, record) ?: return
        val p = Pointer(lParam)
        p.setInt(0, area.left)
        p.setInt(4, area.top)
        p.setInt(8, area.right)
        p.setInt(12, area.bottom)
    }

    /**
     * 最小尺寸 + 最大化矩形。
     *
     * 最小尺寸取 `Spec.minWidth/minHeight`（再小会把侧栏压没）；
     * 最大化矩形取**工作区**（`rcWork`）而不是整屏，否则会盖住任务栏。
     */
    private fun handleMinMax(api: User32, record: Installed, lParam: Long) {
        val area = workAreaOf(api, record) ?: return
        val workWidth = area.right - area.left
        val workHeight = area.bottom - area.top

        val scale = record.dpi / 96.0
        // 直接写进系统给的 MINMAXINFO（**只**覆盖这四个字段，ptMaxTrackSize 留着系统的值）：
        //   +0  ptReserved(8)  +8  ptMaxSize(8)  +16 ptMaxPosition(8)
        //   +24 ptMinTrackSize(8)  +32 ptMaxTrackSize(8)
        val mmi = Pointer(lParam)
        mmi.setInt(8, workWidth)
        mmi.setInt(12, workHeight)
        mmi.setInt(16, area.left - area.monLeft)
        mmi.setInt(20, area.top - area.monTop)
        mmi.setInt(24, (record.spec.minWidth * scale).toInt())
        mmi.setInt(28, (record.spec.minHeight * scale).toInt())
    }

    /**
     * 找到这个 AWT 窗口的 HWND。
     *
     * ① 先用 JNA 直取 peer 的句柄（`Native.getWindowPointer`）：不走标题匹配，
     *    两个窗口同名、标题被改、多显示器都不影响，最可靠。
     * ② 取不到（peer 还没建好 / JNA 版本差异）才退回「本进程 + 标题相同」的枚举。
     */
    private fun findHwnd(window: Window, requireTopLevel: Boolean): Pointer? {
        val api = user32 ?: return null
        runCatching { Native.getWindowPointer(window) }
            .getOrNull()
            ?.takeIf { Pointer.nativeValue(it) != 0L }
            ?.let { return it }

        val title = runCatching { (window as? java.awt.Frame)?.title ?: (window as? java.awt.Dialog)?.title }
            .getOrNull() ?: return null
        val pid = runCatching { ProcessHandle.current().pid().toInt() }.getOrNull() ?: return null
        val found = arrayOfNulls<Pointer>(1)
        val buf = CharArray(512)
        val cb = object : EnumProc {
            override fun callback(hWnd: Pointer?, lParam: Pointer?): Boolean {
                if (hWnd == null) return true
                if (!api.IsWindowVisible(hWnd)) return true
                // 主窗口要求「没有 owner」（顶层）；弹窗本来就 owner=主窗口，不能这样筛。
                if (requireTopLevel && api.GetWindow(hWnd, 4) != null) return true
                val len = api.GetWindowTextW(hWnd, buf, buf.size)
                if (len <= 0) return true
                if (String(buf, 0, len) != title) return true
                val out = IntByReference()
                api.GetWindowThreadProcessId(hWnd, out)
                if (out.value != pid) return true
                found[0] = hWnd
                return false
            }
        }
        api.EnumWindows(cb, null)
        return found[0]
    }


}
