plugins {
    alias(libs.plugins.kotlin.jvm)
    // WsSignaler 的 payload / 房间解析用 kotlinx.serialization.json
    alias(libs.plugins.kotlin.serialization)
}

// Phase 1：core:network 改为纯 JVM。源码不碰 Android；
// 原 `kotlinx-coroutines-android` 换成 `kotlinx-coroutines-core`。
// Android 的 network security config 在 app 清单，不在本模块。

kotlin {
    jvmToolchain(17)
}

// ── 接缝 1 的核心：本模块的依赖里**不允许**出现任何 feature:* ──────────────
// 这条不是靠注释维持的 —— 根 build.gradle.kts 有配置期断言，
// 往下面加一行 project(":feature:...") 会让整个构建在配置阶段失败。
// 验收：`gradlew :core:network:assemble` 可独立跑通。

dependencies {
    // §4.1 规定：network ──> protocol, model
    api(project(":core:protocol"))
    api(project(":core:model"))
    // Signaler 的公开签名里有 StateFlow / SharedFlow
    api(libs.kotlinx.coroutines.core)
    // Signaler.send 的 payload 是 JsonObject?
    api(libs.kotlinx.serialization.json)

    // WebSocket 客户端。用 implementation 而不是 api：本模块的公开签名里
    // 没有任何 OkHttp 类型（§4.2 的接缝 1 只暴露自己的类型），换实现不该
    // 波及调用方。
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 起一个进程内的假信令服务端（支持 WebSocket upgrade），
    // 于是「退避重连 + resume」这条最容易错的逻辑能在 JVM 上真跑一遍，
    // 不用等两台真机。
    testImplementation(libs.okhttp.mockwebserver)
}
