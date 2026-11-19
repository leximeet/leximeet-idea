package com.github.weilhuang.leximeetidea.connection

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

@Service(Service.Level.APP)
internal class DesktopLinkService : Disposable {
    val link =
        DesktopConnection(
            desktopProfile(),
            ConnectionStore(PathManager.getConfigDir().resolve("leximeet-idea")),
        )

    private fun desktopProfile(): Path? {
        if (!SystemInfo.isMac && !SystemInfo.isLinux) return null
        // 联调显式指定隔离 Desktop 根目录，日常只读取当前用户的固定发现位置。
        System.getProperty("leximeet.desktop.profile")?.let {
            return Path.of(it).toAbsolutePath().normalize()
        }
        if (System.getProperty("leximeet.test") == "true") return null
        val home = Path.of(System.getProperty("user.home"))
        val appData =
            if (SystemInfo.isMac) home.resolve("Library/Application Support")
            else
                System.getenv("XDG_CONFIG_HOME")?.takeIf { Path.of(it).isAbsolute }?.let(Path::of)
                    ?: home.resolve(".config")
        return appData.resolve("LexiMeet/workspaces/v1")
    }

    override fun dispose() = link.close()
}
