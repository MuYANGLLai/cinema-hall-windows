package com.cinemahall.core.account

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * 凭据的读写接缝（契约 §8.5）。
 *
 * 接口本身没有任何 Android 依赖 —— Android 的实现（Keystore）在 `core:account-android`，
 * 纯 JVM 的通用实现是 [EncryptedCredentialStore]（密钥由 [CredentialCipher] 注入）。
 */
interface CredentialStore {
    suspend fun load(): StoredCredential?
    suspend fun save(credential: StoredCredential)
    suspend fun clear()
}

/**
 * 最小键值落盘接缝（与 `core:library` 的 `LibraryStorage` 同形）。
 *
 * 只抽两个方法，是为了让 [EncryptedCredentialStore] 的「编码 → 加密 → Base64 → 写」
 * 这一整条链能在 JVM 单测里真跑。Android 侧的实现就是一行 `SharedPreferences`。
 */
interface AccountKeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * 加解密接缝。
 *
 * 生产实现是 Android Keystore 的 AES-256-GCM（`core:account-android` 的
 * `KeystoreCredentialCipher`）；单测注入一个「异或 + 反转」的假实现即可 ——
 * 要钉住的是 [EncryptedCredentialStore] 的编码与异常路径，不是 AES 本身
 * （那是平台的，测不到也不该在这里测）。见方案 §8.6 最后一条。
 *
 * 约定：实现对任意长度的字节数组都要能往返；解密失败**抛异常**（不要返回原文或空）。
 */
interface CredentialCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

/**
 * 通用实现：JSON → [CredentialCipher] 加密 → Base64 → [AccountKeyValueStore]。
 *
 * ── 为什么明文绝不落盘 ──────────────────────────────────────────────────────
 * token 等价于密码（服务端 `hashToken` 后比对）。明文写进 SharedPreferences 的话，
 * root 设备或 `adb backup`（若 allowBackup 为 true）就能直接拿走；而本项目的
 * allowBackup 已显式关闭（方案 §8.3），这里是第二道保险。
 *
 * ── 为什么 Base64 而不是直接写 ByteArray ────────────────────────────────────
 * SharedPreferences 只存 String。Base64 是唯一不引入新依赖的选择
 * （`java.util.Base64` 在 minSdk 35 上一直可用）。
 *
 * ── `load()` 的三种失败都返回 null ───────────────────────────────────────────
 * 键不存在、Base64 坏了、解密失败（换机后 Keystore 密钥丢了 / 系统升级换了密钥）。
 * 三者对调用方的语义完全相同：**没有可用凭据，去激活页**。这里刻意不抛异常 ——
 * 启动路径上抛出去只会让「密钥丢了」变成一次闪退，而它其实只是「重新激活」。
 */
class EncryptedCredentialStore(
    private val storage: AccountKeyValueStore,
    private val cipher: CredentialCipher,
) : CredentialStore {

    override suspend fun load(): StoredCredential? {
        val encoded = storage.read(KEY_CREDENTIAL) ?: return null
        return try {
            val encrypted = Base64.getDecoder().decode(encoded)
            val plain = cipher.decrypt(encrypted)
            Json.decodeFromString<StoredCredential>(plain.decodeToString())
        } catch (_: Exception) {
            // 解不开 = 没有凭据。顺手擦掉，免得每次启动都白试一次解密。
            storage.write(KEY_CREDENTIAL, null)
            null
        }
    }

    override suspend fun save(credential: StoredCredential) {
        val plain = Json.encodeToString(credential).encodeToByteArray()
        val encrypted = cipher.encrypt(plain)
        storage.write(KEY_CREDENTIAL, Base64.getEncoder().encodeToString(encrypted))
    }

    override suspend fun clear() {
        storage.write(KEY_CREDENTIAL, null)
    }

    companion object {
        /** SharedPreferences 的键名。带 `v1`：将来换存储格式时能并行读旧键。 */
        const val KEY_CREDENTIAL = "account.credential.v1"

        private val Json = Json {
            ignoreUnknownKeys = true
        }
    }
}
