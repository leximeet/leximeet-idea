import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "leximeet-idea"

pluginManagement {
    plugins { id("org.jetbrains.kotlin.jvm") version "2.3.20" }
    repositories {
        // 托管机共享出口易被 Maven Central 限流。镜像提供插件依赖，插件标记仍可从 Portal 获取。
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2")
        }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2")
        }
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more:
        // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform { defaultRepositories() }
    }
}
