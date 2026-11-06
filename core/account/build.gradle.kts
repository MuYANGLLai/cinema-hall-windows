plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// W3–W4（1a）：账号客户端。
//
// 这里只放**纯 JVM** 的部分：HTTP 接口、启动状态机、凭据的加解密接缝、码归一化。
// Android 的 Keystore 实现在 `core:account-android`；两者的关系与 `core:library`
// / `core:library-android` 完全同形（Phase 1 定下的模式）。
//
// 为什么单独成模块而不是塞进 `core:network`（方案 §8.1）：那儿的职责是「信令
// WebSocket 客户端」，塞进 HTTP 账号接口会让「网络模块可被独立引用」这条验收变含糊。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // UserId / UserIdProvider：AccountUserIdProvider 实现它。
    api(project(":core:model"))

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // HttpAccountApi 的构造器里出现 OkHttpClient ⇒ 走 api，调用方要能构造它。
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 真起一个本地 HTTP 服务，把请求形状与错误体逐条钉住（不 mock OkHttp）。
    testImplementation(libs.okhttp.mockwebserver)
}
