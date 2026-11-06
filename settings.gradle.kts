pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cinema-hall-windows"

// ── 本仓库的范围：Windows 分发包的**对应源码** ───────────────────────────────
// 参见同目录 SOURCE.txt 与 desktopApp/compliance/。
//
// 只包含桌面端与它依赖的模块，全部是纯 Kotlin/JVM，**不需要 Android SDK**：
//   desktopApp  → desktop:{rtc,media,library} → core:{session,network,protocol,model,rtc,media,library}
//
// 不在本仓库内的部分（Android 客户端、Node.js 信令服务）是**另一个可独立分发的作品**，
// 与这份源码无关，也不需要它才能构建本包。
include(":core:model")
include(":core:protocol")
include(":core:network")
include(":core:rtc")
include(":core:media")
include(":core:library")
include(":core:account")
include(":core:session")

include(":desktop:media")
include(":desktop:library")
include(":desktop:rtc")
include(":desktopApp")
