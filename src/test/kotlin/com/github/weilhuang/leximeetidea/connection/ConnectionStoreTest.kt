package com.github.weilhuang.leximeetidea.connection

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionStoreTest {
    @Test
    fun approvedTicketIsPersistedInPrivateDirectoryAndCanBeRemovedWithoutChangingClientIdentity() {
        val root = Files.createTempDirectory("idea-link-store-")
        try {
            val path = root.resolve("config")
            val connection = ConnectionStore(path)
            val state = connection.load()
            val id = state.string("clientId")
            state.add("pending", obj("invitationId" to "a", "invitationToken" to "test-ticket"))
            connection.save(state)
            val file = path.resolve("desktop-connection.json")
            assertEquals(
                "rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(path)),
            )
            assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
            )
            val restored = ConnectionStore(path).load()
            assertEquals(id, restored.string("clientId"))
            assertEquals("test-ticket", restored.fields("pending").string("invitationToken"))
            restored.remove("pending")
            connection.save(restored)
            assertFalse(connection.load().has("pending"))
            assertEquals(id, connection.load().string("clientId"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun refusesSymlinkAndWorldReadableDescriptor() {
        val path = Files.createTempDirectory("idea-link-private-")
        try {
            val target = path.resolve("other")
            Files.writeString(target, "{}")
            val link = path.resolve("desktop-connection.json")
            Files.createSymbolicLink(link, target)
            val store = ConnectionStore(path)
            assertEquals(
                "UNSAFE_LOCAL_PATH",
                assertThrows(LmcpFailure::class.java, store::load).code,
            )
            Files.delete(link)
            Files.writeString(link, "{}")
            Files.setPosixFilePermissions(link, PosixFilePermissions.fromString("rw-r--r--"))
            assertEquals(
                "UNSAFE_LOCAL_PATH",
                assertThrows(LmcpFailure::class.java, store::load).code,
            )
        } finally {
            path.toFile().deleteRecursively()
        }
    }
}
