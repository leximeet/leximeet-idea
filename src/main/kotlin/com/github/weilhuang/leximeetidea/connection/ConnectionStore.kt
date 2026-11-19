package com.github.weilhuang.leximeetidea.connection

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** 只保存客户端标识与明确授权的连接控制信息。使用 IDE 的本机 config 目录，不进入项目、 设置同步或词库。会话 token 与词卡始终只保留在内存。 */
internal class ConnectionStore(private val directory: Path) {
    private val file = directory.resolve("desktop-connection.json")

    fun load(): JsonObject =
        try {
            readPrivateObject(file).also {
                requireProtocol(it.string("format") == "leximeet.idea-connection/1")
                val id = it.string("clientId")
                requireProtocol(
                    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                )
            }
        } catch (_: NoSuchFileException) {
            obj(
                    "format" to "leximeet.idea-connection/1",
                    "clientId" to UUID.randomUUID().toString(),
                )
                .also(::save)
        }

    fun save(value: JsonObject) {
        Files.createDirectories(directory, permissions("rwx------"))
        privateAttributes(directory, PRIVATE_DIR)
        if (Files.exists(file, NOFOLLOW_LINKS)) privateAttributes(file, REGULAR)
        val temporary =
            Files.createTempFile(directory, "connection-", ".tmp", permissions("rw-------"))
        try {
            Files.writeString(temporary, value.toString())
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun permissions(mode: String) =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))
}
