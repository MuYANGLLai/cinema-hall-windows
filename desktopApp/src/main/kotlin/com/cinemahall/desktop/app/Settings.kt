package com.cinemahall.desktop.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 内置默认信令地址：本机开发服务（与 `server/src/config.mjs` 的默认端口一致）。 */
const val DEFAULT_SIGNAL_ENDPOINT: String = "ws://127.0.0.1:8080"

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
