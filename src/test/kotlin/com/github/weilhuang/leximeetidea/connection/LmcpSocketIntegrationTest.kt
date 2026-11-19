package com.github.weilhuang.leximeetidea.connection

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用独立 UDS 进程边界的字节流模拟坏响应，真实 Desktop 对接由联调套件覆盖。 */
class LmcpSocketIntegrationTest {
    private val instance = UUID.randomUUID().toString()
    private val clientId = UUID.randomUUID().toString()
    private val pairId = UUID.randomUUID().toString()
    private val workspaceId = UUID.randomUUID().toString()
    private val generation = UUID.randomUUID().toString()
    private val auth =
        obj(
            "workspaceId" to workspaceId,
            "generation" to generation,
            "authorizationEpoch" to "1",
            "sessionId" to UUID.randomUUID().toString(),
            "sessionToken" to "o".repeat(64),
        )
    private val owner =
        obj(
            "workspaceId" to workspaceId,
            "generation" to generation,
            "authorizationEpoch" to "1",
            "pairingId" to pairId,
            "desktopInstanceId" to instance,
            "clientInstanceId" to clientId,
        )
    private val token = "b".repeat(64)
    private val ticket =
        obj("invitationId" to UUID.randomUUID().toString(), "invitationToken" to "c".repeat(64))

    @Test
    fun readsBatchesAndIgnoresIncompleteOrOutOfScopeWords() {
        val batches = mutableListOf<Int>()
        runClient(
            respond = { request ->
                when (request.string("method")) {
                    "matchWords" -> {
                        val tokens = request.fields("params").items("tokens").map { it.asString }
                        batches.add(tokens.size)
                        match(tokens)
                    }
                    else -> standard(request)
                }
            }
        ) { client ->
            client.hello()
            client.pair(ticket)
            val result =
                client.match(
                    listOf("resilience", "other", "incomplete") + (1..99).map { "token" + it }
                )
            assertEquals(listOf(100, 2), batches)
            assertEquals("resilience", result.choices("resilience").single().headword)
            assertTrue(result.choices("incomplete").isEmpty())
            assertTrue(result.choices("other").isEmpty())
            assertTrue(result.choices("resilience", result.until).isEmpty())
            client.disconnect()
            assertFalse(client.owner != null)
        }
    }

    @Test
    fun discardsResponsesWithWrongIdentityAndMixedWorkspaceRevisions() {
        runClient(
            respond = { request ->
                val method = request.string("method")
                if (method == "hello") standard(request)
                else if (method == "matchWords")
                    match(listOf("resilience")).apply { addProperty("workspaceRevision", "8") }
                else standard(request)
            }
        ) { client ->
            client.hello()
            client.pair(ticket)
            assertEquals(
                "REVISION_CHANGED",
                assertThrows(LmcpFailure::class.java) { client.match(listOf("resilience")) }.code,
            )
        }
        runClient(transform = { it.addProperty("requestId", UUID.randomUUID().toString()) }) {
            client ->
            assertEquals(
                "INVALID_RESPONSE",
                assertThrows(LmcpFailure::class.java, client::hello).code,
            )
        }
    }

    @Test
    fun refusesAConnectedSessionThatGrantsWrites() {
        runClient(
            respond = { request ->
                standard(request).apply {
                    if (request.string("method") == "pair") items("scopes").add("capture:write")
                }
            }
        ) { client ->
            client.hello()
            assertEquals(
                "FORBIDDEN",
                assertThrows(LmcpFailure::class.java) { client.pair(ticket) }.code,
            )
            assertEquals(null, client.owner)
        }
    }

    private fun runClient(
        respond: (JsonObject) -> JsonObject = ::standard,
        transform: (JsonObject) -> Unit = {},
        block: (DesktopClient) -> Unit,
    ) {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "idea-lmcp-")
        val socket = directory.resolve("test.sock")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                val handled =
                    CompletableFuture.runAsync {
                        server.accept().use { channel ->
                            val input = DataInputStream(Channels.newInputStream(channel))
                            val output = Channels.newOutputStream(channel)
                            fun receive(): JsonObject {
                                val count = Integer.reverseBytes(input.readInt())
                                assertTrue(count in 1..FRAME_LIMIT)
                                return parseObject(String(input.readNBytes(count), Charsets.UTF_8))
                            }
                            fun send(value: JsonObject) {
                                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                                // 拆开发送头和正文，防止客户端错误地依赖一次 socket read。
                                val first =
                                    java.nio.ByteBuffer.allocate(4)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                output.write(first.putInt(bytes.size).array())
                                output.write(bytes)
                            }
                            val hello = receive()
                            assertEquals(
                                obj("kind" to "idea-transport-auth", "token" to token),
                                hello,
                            )
                            send(obj("kind" to "transport-ready", "instanceId" to instance))
                            while (true) {
                                val request =
                                    try {
                                        receive()
                                    } catch (_: java.io.EOFException) {
                                        break
                                    }
                                val envelope =
                                    obj(
                                            "apiVersion" to "1.0.0",
                                            "requestId" to request.string("requestId"),
                                            "connectionId" to request.string("connectionId"),
                                            "method" to request.string("method"),
                                        )
                                        .apply {
                                            addProperty("ok", true)
                                            add("result", respond(request))
                                        }
                                transform(envelope)
                                send(envelope)
                            }
                        }
                    }
                DesktopClient(
                        LmcpTransport.open(DesktopEndpoint(socket, instance, token)),
                        clientId,
                    )
                    .use(block)
                handled.get(10, TimeUnit.SECONDS)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun standard(request: JsonObject): JsonObject =
        when (val method = request.string("method")) {
            "hello" -> {
                assertEquals("ide", request.fields("params").string("clientKind"))
                obj(
                        "apiVersion" to "1.0.0",
                        "desktopInstanceId" to instance,
                        "displayName" to "测试桌面端",
                        "connectionId" to request.string("connectionId"),
                    )
                    .apply {
                        add("capabilities", request.fields("params").items("requiredCapabilities"))
                        add(
                            "methods",
                            JsonArray().apply {
                                listOf(
                                        "hello",
                                        "requestConnection",
                                        "getConnectionStatus",
                                        "pair",
                                        "resumeSession",
                                        "renewSession",
                                        "disconnect",
                                        "getWorkspace",
                                        "getChanges",
                                        "matchWords",
                                        "getWord",
                                        "getPublicEntry",
                                    )
                                    .forEach { add(it) }
                            },
                        )
                    }
            }
            "pair" ->
                obj(
                        "pairingId" to pairId,
                        "expiresAt" to Instant.now().plusSeconds(180).toString(),
                        "readLeaseUntil" to Instant.now().plusSeconds(30).toString(),
                    )
                    .apply {
                        add("scopes", JsonArray().apply { add("library:read") })
                        add("authorization", auth)
                        add("owner", owner)
                        add(
                            "pairingCredential",
                            owner.deepCopy().apply { addProperty("pairingToken", token) },
                        )
                    }
            "getWorkspace" -> {
                assertEquals(auth, request.fields("authorization"))
                obj("revision" to "7", "readLeaseUntil" to Instant.now().plusSeconds(30).toString())
                    .apply { add("owner", owner) }
            }
            "disconnect" -> JsonObject().apply { addProperty("disconnected", true) }
            else -> error("unexpected " + method)
        }

    private fun match(tokens: List<String>) =
        obj("workspaceRevision" to "7").apply {
            addProperty("refreshAfterMs", 30_000)
            add(
                "results",
                JsonArray().apply {
                    tokens.forEachIndexed { i, word ->
                        add(
                            obj().apply {
                                addProperty("inputIndex", i)
                                addProperty("complete", word != "incomplete")
                                add(
                                    "matches",
                                    JsonArray().apply {
                                        add(
                                            obj("headword" to word, "learningStatus" to "new")
                                                .apply {
                                                    addProperty(
                                                        "collected",
                                                        word == "resilience" || word == "incomplete",
                                                    )
                                                    addProperty("inTarget", false)
                                                    add(
                                                        "word",
                                                        obj(
                                                            "kind" to "custom",
                                                            "headword" to word,
                                                            "customId" to
                                                                UUID.randomUUID().toString(),
                                                            "language" to "en",
                                                        ),
                                                    )
                                                }
                                        )
                                    },
                                )
                            }
                        )
                    }
                },
            )
        }
}
