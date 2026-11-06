import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.desktop) apply false
}

// ── 接缝约束的机械保证（与上游工程同一份规则）─────────────────────────────────
//
// `core:*` 必须可被独立复用：任何 core 模块都不得依赖玩法层。这里做成**配置期断言**，
// 违反时构建在配置阶段就失败，不需要等到编译。
//
// 在本仓库里它还有第二重意义：`desktop:*` / `desktopApp` 不得依赖 Android 侧的
// `feature:*` / `app` —— 那两个模块不在本仓库里，一旦有人加了这样的依赖，
// 构建会在配置阶段明确报错，而不是让人对着「找不到符号」猜半天。
subprojects {
    afterEvaluate {
        val desktopLane = path.startsWith(":desktop:") || path == ":desktopApp"
        if (!path.startsWith(":core:") && !desktopLane) return@afterEvaluate

        val violations = configurations.flatMap { configuration ->
            configuration.dependencies
                .filterIsInstance<ProjectDependency>()
                .filter { it.path.startsWith(":feature:") || it.path == ":app" }
                .map { "${configuration.name} -> ${it.path}" }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("接缝约束被破坏：$path 不得依赖 feature:* 或 :app，但发现 ——\n")
                    violations.forEach { append("    $it\n") }
                    append("core:* 必须可被独立复用；玩法相关的依赖只允许出现在 app 与 feature:* 中，")
                    append("而那两个模块不在本仓库范围内。")
                }
            )
        }
    }
}
