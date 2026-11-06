plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// W2：多根目录扫描 + 两段式（快扫 / 后台解析时长）。
//
// 依赖 `core:library` 是为了**复用**它的 `MovieScanRules`（80 / 30 分钟边界、
// `ScanBucket`）—— 那三条边界在 Android 侧已经被单测钉死，桌面端再抄一份只会
// 让“同一部片在两端归到不同分类”这种事故变得可能。
//
// 本模块**不依赖 vlcj**：时长解析以 `(String) -> Long` 注入，测试里给假实现即可，
// 这样扫描逻辑能在没有 libVLC 的机器上单测。
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:library"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 扫描门禁的语料生成器（只跑测试源集，产物里没有这个类）。
// 用法：
//   gradlew :desktop:library:generateCorpus -PcorpusDir=... -PcorpusCount=500 -PcorpusSource=...
tasks.register<JavaExec>("generateCorpus") {
    group = "verification"
    description = "生成 N 份样本副本，用于 500 部片扫描门禁"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.cinemahall.desktop.library.CorpusGeneratorKt")
    doFirst {
        val dir = providers.gradleProperty("corpusDir").orNull
            ?: error("需要 -PcorpusDir=<目录>")
        val count = providers.gradleProperty("corpusCount").orElse("500").get()
        val source = providers.gradleProperty("corpusSource").orNull
            ?: error("需要 -PcorpusSource=<样本文件>")
        args = listOf(dir, count, source)
    }
}
