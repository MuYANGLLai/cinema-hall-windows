plugins {
    alias(libs.plugins.kotlin.jvm)
}

// W7：`core:rtc` 的 Windows 实现（webrtc-java）。
//
// ── 为什么是独立模块，而不是塞进 desktopApp ──────────────────────────────────
// 与 `core:rtc-android` 对称：端口（`PeerLink` / `CallAudio` / `LinkStats`）住在 `core:rtc`，
// 每个平台一份实现。分模块之后有两件事是能做的：
//  1. `:desktop:rtc:test` 可以**不带 UI、不带 vlcj** 真跑协商状态机（C1 就是靠这个）；
//  2. 以后若要做 mac/Linux，只需再加一个实现模块，`desktopApp` 一行不改。
//
// ── 依赖方向 ────────────────────────────────────────────────────────────────
// desktop:rtc ──> core:{rtc,model}。**不依赖** core:network / core:protocol：
// 出站信令由调用方以 `RtcSignalSink` 注入（与 `core:rtc` 的同一条取向），
// 这样这个模块不需要认识 WebSocket 与信封。

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:rtc"))
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    // WebRTC 的类型**不出现在本模块的公开签名里**（`PeerLink` 端口要求的），
    // 所以用 implementation：换绑定库不该波及调用方。
    implementation(libs.webrtc.java)
    // Windows 原生库（`webrtc-java.dll` ~7.9 MB）。它与上面那个 API jar 是一对：
    // 只有 API jar 时 `NativeLoader` 会在第一次建工厂时失败。
    // 带 classifier 的制品没法走版本目录（catalog 不支持 classifier），只能拼坐标；
    // 版本号仍取自 catalog 的 `webrtcJava`，避免两处写死。
    implementation("dev.onvoid.webrtc:webrtc-java:${libs.versions.webrtcJava.get()}:windows-x86_64")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
