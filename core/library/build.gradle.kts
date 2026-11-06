plugins {
    alias(libs.plugins.kotlin.jvm)
    // 库与进度都以 JSON 落盘（§16.4），而 `MovieEntry` 里嵌着带 `@Serializable` 的
    // `MediaSignature` —— 与 core:media / core:model 用同一个插件是同一个理由。
    alias(libs.plugins.kotlin.serialization)
}

// Phase 1：core:library 改为纯 JVM。
// SharedPreferences / MediaStore / DocumentFile 的实现全部在 core:library-android；
// 这里只留库与进度的纯逻辑、以及公开的 LibraryStorage 接缝。

kotlin {
    jvmToolchain(17)
}

dependencies {
    // §4.1 规定：library ──> media, model。
    api(project(":core:media"))

    // `MovieLibraryStore.snapshot` / `.progress` 是 StateFlow，出现在公开签名里。
    api(libs.kotlinx.coroutines.core)

    // 序列化运行时只在本模块内部用（公开签名里只有 `String` 与本模块自己的数据类），
    // 所以是 implementation。注意 core:media 已经 api 了同一个制品 —— 这里显式再写一遍
    // 是为了让「本模块自己用了什么」在这份文件里看得见，而不是靠传递依赖。
    implementation(libs.kotlinx.serialization.json)

    // 1b 片单同步：HttpLibrarySyncApi 的构造器里出现 OkHttpClient ⇒ 走 api，
    // 调用方（app 的装配点）要能自己传一个客户端进来。OkHttp 本身仍是纯 JVM，不影响
    // 本模块「能在 JVM 单测里真跑」这条（MockWebServer 直接起一个真 HTTP 服务）。
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
