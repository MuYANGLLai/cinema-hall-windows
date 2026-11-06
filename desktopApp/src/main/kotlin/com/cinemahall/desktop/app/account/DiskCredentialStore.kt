package com.cinemahall.desktop.app.account

import com.cinemahall.core.account.CredentialStore
import com.cinemahall.core.account.StoredCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 桌面端的凭据落盘：`data/credentials.json`。
 *
 * ── 为什么不复用 [com.cinemahall.core.account.EncryptedCredentialStore] ────────
 * 那个类是「JSON → [com.cinemahall.core.account.CredentialCipher] 加密 → Base64 →
 * KeyValueStore」，它的价值全在 cipher 上。Android 的 cipher 是 Keystore
 * （`core:account-android` 的 `KeystoreCredentialCipher`），而**桌面没有等价物**：
 * JVM 没有系统级密钥保管服务，自己造一个「把密钥写在旁边」的加密等于自欺欺人
 * （`key.bin` 与 `credentials.json` 在同一目录、同一权限下）。
 *
 * ⚠️ **取舍（明写在这里，不要在别处宣称"加密存储"）**：
 * 本实现把 token **明文**写进 `data/credentials.json`。安全保障只有两条 ——
 * 1. 文件在用户自己的 `data/` 目录下，权限跟随该目录（谁能读你的用户配置，
 *    谁本来就能读你的影片库与设置）；
 * 2. token 等价于密码，但它有服务端侧的 `expiresAt`，且可被「撤销设备」立刻作废。
 *
 * 若将来要防「同一台机器上的另一个用户 / 备份文件被拷走」，正确做法是接 Windows
 * DPAPI（`CryptProtectData`，按当前用户加密，JNA 能调），或改用凭据管理器
 * （`CredWrite`）。这两条都留作后续，不在本轮 —— 本轮的目标是「能激活、能记住」。
 *
 * ── 为什么按端点分桶 ────────────────────────────────────────────────────────
 * `data/` 是**所有 profile 共用**的（只有单实例锁按 profile 分），而信令地址可以在
 * 设置页里改。把生产 token 发给本机开发服务的结果是 `AUTH_INVALID` → 清凭据 →
 * 每次切换地址都要重新激活。所以文件里按 `httpBase(信令地址)` 分桶：
 * `wss://101.133.234.226:8443/ws` 与 `ws://127.0.0.1:8080` 各存各的，互不干扰。
 *
 * ── 写入必须原子 ────────────────────────────────────────────────────────────
 * 先写 `credentials.json.tmp` 再 `ATOMIC_MOVE` 覆盖。直接覆盖原文件的话，
 * 写到一半断电/被杀，留下的是**半截 JSON**；下次启动 `load()` 解析失败 → 当成
 * 「没有凭据」→ 用户莫名其妙回到激活页（而 token 其实还有效）。
 * 这里不做「解析失败就删文件」：见 [load] 的说明。
 */
class DiskCredentialStore(
    private val file: File,
    /** 分桶键，传 `AccountEndpoint.httpBase(信令地址)`。 */
    private val scopeKey: String,
) : CredentialStore {

    override suspend fun load(): StoredCredential? = withContext(Dispatchers.IO) {
        readModel().entries[scopeKey]
    }

    override suspend fun save(credential: StoredCredential) = withContext(Dispatchers.IO) {
        val model = readModel()
        writeModel(model.copy(entries = model.entries + (scopeKey to credential)))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val model = readModel()
        if (model.entries.containsKey(scopeKey)) {
            writeModel(model.copy(entries = model.entries - scopeKey))
        }
    }

    /**
     * 读整个文件。
     *
     * 解析失败时**返回空模型而不是抛异常**，也**不删文件**：这一份可能只是别的
     * profile / 别的版本写的、或临时被写坏；删掉会连带清掉所有端点的凭据。
     * 空模型的效果就是「这个端点没凭据 → 去激活页」，与用户的观感一致，
     * 而且下一次 [save] 会用新内容整体覆盖掉坏文件。
     */
    private fun readModel(): FileModel {
        if (!file.isFile) return FileModel()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) FileModel() else Json.decodeFromString<FileModel>(text)
        }.getOrElse { FileModel() }
    }

    private fun writeModel(model: FileModel) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(Json.encodeToString(model))
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // 跨卷/网络盘没有原子改名：退化成「覆盖复制」。仍然比直接写目标强，
            // 因为坏也只可能坏在那一次 move 里，而不是写 JSON 的整段时间。
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 文件格式：`{"version":1,"entries":{"<httpBase>":{...}}}`。 */
    @Serializable
    private data class FileModel(
        val version: Int = 1,
        val entries: Map<String, StoredCredential> = emptyMap(),
    )

    private companion object {
        private val Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
