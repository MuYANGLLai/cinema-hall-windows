plugins {
    alias(libs.plugins.kotlin.jvm)
}

// W1：vlcj 的 PlaybackTarget 实现。
//
// 依赖方向：`desktop:media` → `core:media`（端口）。**不依赖** `desktop:library` /
// `desktopApp`，也不依赖任何 Android 制品 —— 这样同一个 `PlaybackTarget` 契约在
// 两端是同一份字节码，而不是两份“看起来一样”的实现。
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:media"))

    // vlcj 4.12.1：POM 声明 GPLv3（Phase 0 §3.4 已记录）。
    implementation(libs.vlcj)

    testImplementation(libs.junit)
}
