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
    val label: String get() = if (description.isBlank() || description == name) name else description
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
     * 顺序：系统默认那条放最前，其余按名字排序（Windows 上 `getMixerInfo()` 的原始顺序
     * 不稳定，直接展示会每次打开下拉框都换一遍位置）。
     */
    fun list(): List<AudioInputDevice> {
        val devices = mutableListOf<AudioInputDevice>()
        runCatching {
            val default = runCatching { AudioSystem.getMixerInfo().firstOrNull() }.getOrNull()
            for (info in AudioSystem.getMixerInfo()) {
                val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
                val supported = runCatching { mixer.isLineSupported(LINE_INFO) }.getOrDefault(false)
                if (!supported) continue
                devices += AudioInputDevice(
                    name = info.name,
                    description = info.description.ifBlank { info.name },
                    isDefault = info == default,
                )
            }
        }
        return devices.sortedWith(compareByDescending<AudioInputDevice> { it.isDefault }.thenBy { it.name })
    }

    /** 系统默认设备（`getLine(DataLine.Info)` 走的就是它）。 */
    fun defaultAudio(): AudioInputDevice? = runCatching {
        if (!AudioSystem.isLineSupported(LINE_INFO)) return null
        AudioSystem.getMixerInfo().firstOrNull()?.let {
            AudioInputDevice(it.name, it.description.ifBlank { it.name }, isDefault = true)
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
