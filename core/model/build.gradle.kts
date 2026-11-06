plugins {
    alias(libs.plugins.kotlin.jvm)
    // IceServer 带 @Serializable（阶段一 1.4 从 core:network 迁移过来），
    // 它要从服务端下发的 iceServers[] 里解出来。
    alias(libs.plugins.kotlin.serialization)
}

// Phase 1：core:model 改为纯 JVM。
// Android 实现（DeviceIdProvider）在 core:model-android，接口与纯数据留在这里。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Room.modeState 是 JsonObject，出现在公开 API 里，消费者需要它 ⇒ 用 api。
    // 本模块刻意**不**依赖任何 feature:*（接缝 1，由根构建脚本的配置期断言强制）。
    api(libs.kotlinx.serialization.json)

    // 阶段二 2.1 起本模块有测试：`IceServer` 的三个派生属性（isRelay /
    // expiryEpochSeconds / earliestExpiryEpochSeconds）是凭证续签判据的全部依据，
    // 而它们此前一条用例都没有 —— 判据错一个等号，症状是「续签永远慢一拍」。
    testImplementation(libs.junit)
}
