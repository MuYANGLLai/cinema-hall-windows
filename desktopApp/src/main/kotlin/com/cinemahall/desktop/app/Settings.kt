package com.cinemahall.desktop.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 内置默认信令地址：**生产**（与 Android 的 `SignalPreferences.DEFAULT_ENDPOINT` 逐字一致）。
 *
 * 改过一次：W7 时写的是 `ws://127.0.0.1:8080`（本机开发服务），那对「发给朋友的包」
 * 是错的 —— 对方打开看到的是「6 秒内没连上 127.0.0.1」，而 127.0.0.1 在他机器上
 * 根本没有服务。开发时用 `--signal-endpoint=ws://127.0.0.1:8080` 覆盖即可，
 * 自检参数的语义不变。
 */
const val DEFAULT_SIGNAL_ENDPOINT: String = "wss://101.133.234.226:8443/ws"

/**
 * 上报给服务端的版本号（激活时写进设备的 `appVersion`）。
 *
 * 与 `build.gradle.kts` 的 `packageVersion` 手工保持一致 —— 那个值是 jpackage 的
 * 打包参数，运行期拿不到（`jpackage.app-version` 只在安装器上下文里有）。
 * 发版时两处一起改；不一致的后果只是服务端设备列表里显示旧版本号。
 */
const val DESKTOP_APP_VERSION: String = "0.1.26"

/** 上报给服务端的平台标识（与 Android 的 `PLATFORM_ANDROID` 同一套命名）。 */
const val DESKTOP_PLATFORM: String = "windows"

/**
 * 桌面端的用户设置（W3）。
 *
 * 与影片库分开存（`settings.json` vs `library.json`）：库文件是「扫描的产物」，
 * 会被重建/迁移；设置是「用户的选择」，丢了会让界面回到默认主题与音量。
 */
@Serializable
data class AppSettings(
    /** `night` / `day` —— 与 Android 端同一套色板（见 [Theme]）。 */
    val theme: String = "night",
    /** 影片默认音量，0..1。 */
    val volume: Float = 1f,
    /**
     * 选定的麦克风输入源（`Mixer.Info.name`）。null / 空 = 系统默认设备。
     *
     * 为什么存名字而不是索引：`AudioSystem.getMixerInfo()` 的顺序会随「插拔 USB 麦」变化，
     * 存索引等于换一次设备就错位。名字在 Windows 上就是设备全名，稳定。
     * 设备不在了怎么办：helper 会回退到系统默认并往 stderr 写一行（见 `AudioHelper`）。
     */
    val micInput: String? = null,
    /** 播放时阻止系统休眠（W3 要求）。 */
    val keepAwake: Boolean = true,
    /** 上次播放的片子；下次启动显示在「继续观看」。 */
    val lastMoviePath: String? = null,
    /**
     * 信令服务地址（W7）。
     *
     * 存在设置里而不是写死：这一端要连的可能是本机开发服务，也可能是朋友那台机器的
     * 局域网地址，改一次不该重新打包。空串按 [DEFAULT_SIGNAL_ENDPOINT] 处理 ——
     * 老版本 `settings.json` 里没有这个键，反序列化后就是默认值。
     */
    val signalEndpoint: String = DEFAULT_SIGNAL_ENDPOINT,
    /** 房间内显示的昵称。空 = 用系统用户名（见 `DesktopModule.defaultNickname`）。 */
    val signalNickname: String = "",
    val windowWidth: Int = 1440,
    val windowHeight: Int = 920,
)

class SettingsStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): AppSettings =
        runCatching { if (file.isFile) json.decodeFromString<AppSettings>(file.readText()) else AppSettings() }
            .getOrDefault(AppSettings())

    fun save(settings: AppSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(settings))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Log.error("保存设置失败", it) }
    }
}
