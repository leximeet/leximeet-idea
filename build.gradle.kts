import groovy.json.JsonSlurper
import java.io.File
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("com.diffplug.spotless") version "8.3.0"
}

kotlin {
    jvmToolchain(21)
    compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_2_1)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.54").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.54").kotlinlangStyle()
    }
}

val minimumIdeaBuild = 252
val fallbackIdeaVersion = "2025.2.6.2"

// 只检查安装元数据和可执行入口，避免扫描磁盘或启动用户日常 IDEA。
fun isUsableIdea(home: File): Boolean {
    val info =
        listOf(
                home.resolve("product-info.json"),
                home.resolve("Contents/Resources/product-info.json"),
                home.resolve("Resources/product-info.json"),
            )
            .firstOrNull(File::isFile) ?: return false
    val os = System.getProperty("os.name").lowercase()
    val productOs =
        when {
            os.contains("mac") -> "macOS"
            os.contains("linux") -> "Linux"
            os.contains("windows") -> "Windows"
            else -> return false
        }
    val arch =
        when (System.getProperty("os.arch")) {
            "arm64",
            "aarch64" -> "aarch64"
            "x86_64",
            "amd64" -> "amd64"
            else -> return false
        }
    return runCatching {
            val product = JsonSlurper().parse(info) as Map<*, *>
            val branch =
                product["buildNumber"].toString().substringAfter('-').substringBefore('.').toInt()
            val launches = product["launch"] as List<*>
            product["productCode"] in setOf("IU", "IC") &&
                branch >= minimumIdeaBuild &&
                launches.filterIsInstance<Map<*, *>>().any { launch ->
                    launch["os"] == productOs &&
                        launch["arch"] == arch &&
                        info.parentFile.resolve(launch["launcherPath"].toString()).canExecute() &&
                        info.parentFile
                            .resolve(launch["javaExecutablePath"].toString())
                            .canExecute()
                }
        }
        .getOrDefault(false)
}

fun ideaInstallations(): Sequence<File> {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    val pathInstalls =
        System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .flatMap { dir -> sequenceOf("idea", "idea.sh", "idea64.exe").map { File(dir, it) } }
            .filter(File::isFile)
            .map { it.canonicalFile.parentFile.parentFile }
    return when {
        os.contains("mac") ->
            sequenceOf(File("/Applications"), home.resolve("Applications"))
                .flatMap { it.listFiles().orEmpty().asSequence().sortedBy(File::getName) }
                .filter { it.name.startsWith("IntelliJ IDEA") && it.extension == "app" } +
                pathInstalls
        os.contains("linux") ->
            sequenceOf(
                File("/opt/idea"),
                File("/opt/intellij-idea"),
                File("/usr/local/idea"),
                File("/snap/intellij-idea-ultimate/current"),
                File("/snap/intellij-idea-community/current"),
            ) +
                pathInstalls +
                home
                    .resolve(".local/share/JetBrains/Toolbox/apps")
                    .walkTopDown()
                    .maxDepth(4)
                    .filter { it.isDirectory && it.resolve("product-info.json").isFile }
        else ->
            sequenceOf(File(System.getenv("ProgramFiles") ?: "C:/Program Files", "JetBrains"))
                .flatMap { it.listFiles().orEmpty().asSequence() }
                .filter { it.name.startsWith("IntelliJ IDEA") } + pathInstalls
    }
}

val requestedIdea =
    providers
        .gradleProperty("leximeetIdeHome")
        .orElse(providers.environmentVariable("LEXIMEET_IDE_APP"))
        .orElse(providers.environmentVariable("IDEA_HOME"))
val localIde =
    requestedIdea.orNull?.let { value ->
        file(value).also { require(isUsableIdea(it)) { "指定的 IDEA 缺少兼容产品元数据、启动器或内置 JBR：$it" } }
    } ?: ideaInstallations().firstOrNull(::isUsableIdea)

val uiTest by sourceSets.creating { compileClasspath += sourceSets.main.get().compileClasspath }

// UI 驱动独立打包，正式插件不携带测试启动器。
tasks.register<Jar>("uiTestJar") {
    archiveFileName = "leximeet-idea-ui-test.jar"
    from(uiTest.output) { exclude("META-INF/plugin.xml") }
    // 让平台 Gradle 的 patchPluginXml 只处理正式描述符。
    from("src/uiTest/plugin.xml") { into("META-INF") }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more:
    // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(fallbackIdeaVersion)
        testFramework(TestFrameworkType.Platform)
        zipSigner("0.1.43")
    }
}

intellijPlatform {
    // 本插件直接构造 Swing 组件，没有需要插桩的 GUI Designer 表单。
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = minimumIdeaBuild.toString()
            untilBuild = provider { null }
        }
    }
}

val selectedIdeaPath =
    providers.provider { intellijPlatform.platformPath.toAbsolutePath().toString() }
val testIdeaPathFile = layout.buildDirectory.file("leximeet/idea-home.txt")
val prepareTestIde =
    tasks.register("prepareTestIde") {
        val selected = selectedIdeaPath
        val selectionFile = testIdeaPathFile
        val source = if (localIde != null) "本地安装" else "Gradle 解析的 $fallbackIdeaVersion"
        inputs.property("idePath", selected)
        outputs.file(selectionFile)
        doLast {
            val destination = selectionFile.get().asFile
            destination.parentFile.mkdirs()
            destination.writeText(selected.get() + "\n")
            logger.lifecycle("IDEA 测试平台（$source）\n${selected.get()}")
        }
    }

tasks.named("uiTestJar") { dependsOn(prepareTestIde) }

tasks.jar {
    from("LICENSE") { into("META-INF/leximeet") }
    from("THIRD_PARTY_LICENSES") { into("META-INF/leximeet/third-party") }
}

// Gradle 只解析 ZIP 签名工具。发布凭据由独立签名、上传进程读取。
tasks.register<Copy>("prepareReleaseSigner") {
    from(configurations.named("marketplaceZipSigner"))
    into(layout.buildDirectory.dir("leximeet/release-tools"))
    rename { "marketplace-zip-signer-cli.jar" }
}

tasks.test {
    // 每次仅访问测试进程的资料和本机临时 Socket，不连接日常桌面端。
    systemProperty("leximeet.test", "true")
}

tasks.named<RunIdeTask>("runIde") {
    // Gradle 任务可从 IDEA 调试配置传入 Desktop 资料目录，终端也可用环境变量。
    providers
        .gradleProperty("leximeetDesktopProfile")
        .orElse(providers.environmentVariable("LEXIMEET_IDE_DESKTOP_PROFILE"))
        .orNull
        ?.let { path -> systemProperty("leximeet.desktop.profile", path) }
}
