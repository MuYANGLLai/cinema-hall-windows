plugins {
    alias(libs.plugins.kotlin.jvm)
    // Envelope 带 @Serializable
    alias(libs.plugins.kotlin.serialization)
}

// Phase 1：core:protocol 改为纯 JVM。源码本身不碰 Android，只有构建插件变化。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // §4.1 规定：protocol ──> model
    api(project(":core:model"))
    // Envelope.payload: JsonObject?
    api(libs.kotlinx.serialization.json)

    // 信封编解码是纯 JVM 逻辑（不碰 Android API），所以能直接在 test 源集里真跑。
    testImplementation(libs.junit)
}
