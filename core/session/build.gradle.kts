plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Phase 1：core:session —— 纯 JVM 的会话编排。
//
// 住在这里的东西：PeerCoordinator（信令 ↔ RTC 的接线员）、CinemaHandler（玩法处理器）、
// CinemaSyncEngine（播放同步算法）及其 7 个同步支撑文件。
//
// 平台能力一律以端口注入（MonotonicClock / CoroutineDispatcher / PlaybackTarget），
// 本模块不出现任何 `android.*` —— 这是它能被 JVM 单测直接跑的前提。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 公开签名里直接出现的类型 ⇒ 全部走 api：
    //  - Room / UserId / MonotonicClock（core:model）
    //  - PlayModeHandler / Envelope / PeerMessenger（core:protocol）
    //  - Signaler（core:network）
    //  - PeerLink / CallAudio（core:rtc）
    //  - MediaSignature / PlaybackTarget（core:media）
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(project(":core:network"))
    api(project(":core:rtc"))
    api(project(":core:media"))

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
