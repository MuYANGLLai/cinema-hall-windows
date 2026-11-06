plugins {
    alias(libs.plugins.kotlin.jvm)
    // MediaSignature 带 @Serializable：§7 的摘要要经 DataChannel 送到对端，
    // 与 core:protocol 的 Envelope 是同一条序列化链路。
    alias(libs.plugins.kotlin.serialization)
}

// Phase 1：core:media 改为纯 JVM。
// 播放器实现（Media3 / libVLC）、取帧、ContentResolver、PGS 的 Media3 编排
// 全部在 core:media-android；这里只留端口、纯数据与纯算法。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // §4.1 规定：media ──> model
    api(project(":core:model"))
    // MediaSignature / 摘要载荷用 JsonObject 表达。
    api(libs.kotlinx.serialization.json)

    // 1.5：抽样规则、指纹计算、比对判定都是纯 JVM 逻辑（不碰 Android API），
    // 所以 §7 的三条判定能直接在 test 源集里真跑 —— 不必等真机。
    testImplementation(libs.junit)
}
