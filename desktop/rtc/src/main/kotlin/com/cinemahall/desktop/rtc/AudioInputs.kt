package com.cinemahall.desktop.rtc

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/**
 * 一个可用的麦克风输入源。
 *
 * [name] 是 `Mixer.Info.name` —— **存进设置里的就是它**：`getMixerInfo()` 的顺序会随
 * 插拔 USB 设备变化，存索引换一次设备就错位。名字在 Windows 上是设备全名，稳定。
 */
data class AudioInputDevice(
    val name: String,
    val description: String,
    /** 系统默认设备（下面那几条是「可选的那几个」）。 */
    val isDefault: Boolean = false,
) {
    /**
     * 界面上显示的那一行。
     *
     * ⚠️ 必须优先用 [name]（2026-11-12 实测）：Windows 上三条采集设备的 `description`
     * **全都是**「Direct Audio Device: DirectSound Capture」，只有 `name` 才是
     * 「麦克风阵列 (Realtek(R) Audio)」这种能分辨的东西。原来优先取 description，
     * 于是下拉里出现三行一模一样的名字，用户根本没法选（也选不动 —— 见 [name] 的说明）。
     */
    val label: String get() = name.ifBlank { description }
}

/**
 * 采集格式与设备枚举（父进程侧）。
 *
 * ── 为什么枚举放在父进程、而真正开麦在子进程 ──────────────────────────────────
 * 采集必须是独立进程（Phase 0 §6：Windows 的麦克风使用记录绑进程，只有 kill 进程才释放），
 * 但**枚举**没有这个约束。让界面在本进程里枚举，选中后把 `Mixer.Info.name` 通过命令行
 * 传给子进程，子进程按名字去 `getMixer()` —— 两边跑的是同一个 JRE、同一台设备，
 * 名字能对上（对不上时子进程回退系统默认，不会因此没声音）。
 */
object AudioInputs {

    /** 采集格式：48 kHz / 16-bit / 单声道 / 小端（与 `AudioUplink` 的块长 960 字节一致）。 */
    val FORMAT: AudioFormat = AudioFormat(48_000f, 16, 1, true, false)

    private val LINE_INFO: DataLine.Info = DataLine.Info(TargetDataLine::class.java, FORMAT)

    /**
     * 当前机器上能用 48k/16/1 采集的设备。
     *
     * 排序：按名字排（Windows 上 `getMixerInfo()` 的原始顺序不稳定，直接展示会每次
     * 打开下拉框都换一遍位置）。
     *
     * **同名去重**：`name` 是「存进设置 + 传给子进程」的唯一键，两条同名的设备在后端
     * 根本分不开（`open()` 只会命中第一条），所以只保留第一条 —— 让用户选到一条
     * 「选了没反应」的条目比少一条更糟。
     */
    fun list(): List<AudioInputDevice> {
        val devices = LinkedHashMap<String, AudioInputDevice>()
        runCatching {
            for (info in AudioSystem.getMixerInfo()) {
                val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
                val supported = runCatching { mixer.isLineSupported(LINE_INFO) }.getOrDefault(false)
                if (!supported) continue
                val name = info.name.orEmpty()
                if (name.isBlank() || devices.containsKey(name)) continue
                devices[name] = AudioInputDevice(
                    name = name,
                    description = info.description.orEmpty(),
                )
            }
        }
        return devices.values.sortedBy { it.name }
    }

    /** 系统默认设备（`getLine(DataLine.Info)` 走的就是它）。 */
    fun defaultAudio(): AudioInputDevice? = runCatching {
        if (!AudioSystem.isLineSupported(LINE_INFO)) return null
        // 「默认」不能靠 `getMixerInfo().first()` 猜：实测本机排第一的是混音器，
        // 真正被 `AudioSystem.getLine` 选中的那条在中间。这里只回报「有没有可用的默认设备」，
        // 具体是哪一只由 Java 自己决定（界面给的是「系统默认」这一项，不点名）。
        AudioSystem.getMixerInfo().firstOrNull()?.let {
            AudioInputDevice(it.name.orEmpty(), it.description.orEmpty(), isDefault = true)
        }
    }.getOrNull()

    /**
     * 按名字打开一条采集线；名字为 null / 找不到 / 该设备不支持这个格式时回退系统默认。
     *
     * 只在**子进程**里调用（`AudioHelper`）：父进程枚举只为了展示。
     */
    fun open(name: String?): TargetDataLine? {
        if (!name.isNullOrBlank()) {
            for (info in runCatching { AudioSystem.getMixerInfo() }.getOrNull() ?: emptyArray()) {
                if (info.name != name) continue
                val mixer: Mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
                val supported = runCatching { mixer.isLineSupported(LINE_INFO) }.getOrDefault(false)
                if (!supported) {
                    System.err.println("[helper] 设备不支持 48k/16/1：$name")
                    continue
                }
                runCatching { mixer.getLine(LINE_INFO) as TargetDataLine }
                    .onSuccess {
                        System.err.println("[helper] 使用指定设备：$name")
                        return it
                    }
                    .onFailure { System.err.println("[helper] 打开指定设备失败：$name: ${it.message}") }
            }
            System.err.println("[helper] 指定的麦克风不可用：$name，回退系统默认")
        }
        return runCatching {
            if (!AudioSystem.isLineSupported(LINE_INFO)) null else AudioSystem.getLine(LINE_INFO) as TargetDataLine
        }.getOrNull()
    }
}
