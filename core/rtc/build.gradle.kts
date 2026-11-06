plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Phase 1：core:rtc 改为纯 JVM。
// WebRTC / AudioManager / JavaAudioDeviceModule 的实现全部在 core:rtc-android；
// 这里只留 PeerLink / CallAudio / AudioDirection / RoomAudioSession 等端口与纯逻辑。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // §4.1 规定：rtc ──> model。ICE 配置与 RTC 信令值类型都住在 core:model，
    // 所以这里既不需要 core:network，也不需要 core:protocol —— 一个纯数据类
    // 不该把整个信令栈拖进来（也见 core:model/IceServer.kt 的 KDoc）。
    api(project(":core:model"))
    // PeerLink 的公开签名里有 StateFlow / SharedFlow
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
