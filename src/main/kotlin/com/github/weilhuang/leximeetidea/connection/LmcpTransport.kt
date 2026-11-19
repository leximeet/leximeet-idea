package com.github.weilhuang.leximeetidea.connection

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.security.auth.module.UnixSystem
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val FRAME_LIMIT = 256 * 1024
private const val FILE_LIMIT = 16 * 1024
private const val TYPE_MASK = 0xf000
internal const val PRIVATE_DIR = 0x4000
internal const val REGULAR = 0x8000
private const val SOCKET = 0xc000

internal class LmcpFailure(val code: String) : IOException(code)

internal fun obj(vararg entries: Pair<String, String>) =
    JsonObject().apply { entries.forEach { (key, value) -> addProperty(key, value) } }

internal fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw LmcpFailure("INVALID_RESPONSE")

internal fun JsonObject.fields(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: throw LmcpFailure("INVALID_RESPONSE")

internal fun JsonObject.items(key: String) =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: throw LmcpFailure("INVALID_RESPONSE")

internal fun JsonObject.textOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.flag(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: throw LmcpFailure("INVALID_RESPONSE")

internal fun JsonObject.time(key: String): Instant =
    try {
        Instant.parse(string(key))
    } catch (_: java.time.DateTimeException) {
        throw LmcpFailure("INVALID_RESPONSE")
    }

internal fun parseObject(content: String): JsonObject =
    try {
        JsonParser.parseString(content).asJsonObject
    } catch (_: RuntimeException) {
        throw LmcpFailure("INVALID_RESPONSE")
    }

internal fun requireProtocol(valid: Boolean, code: String = "INVALID_RESPONSE") {
    if (!valid) throw LmcpFailure(code)
}

internal fun uid(): Long = UnixSystem().uid

/** 与 Desktop 的发现文件约束一致：不跟随链接、只允许当前用户及约定的文件类型。 */
internal fun privateAttributes(path: Path, type: Int): Map<String, Any> {
    val attrs = Files.readAttributes(path, "unix:uid,mode,size", NOFOLLOW_LINKS)
    val mode = (attrs.getValue("mode") as Number).toInt()
    requireProtocol(
        (attrs.getValue("uid") as Number).toLong() == uid() &&
            mode and TYPE_MASK == type &&
            mode and 0x3f == 0,
        "UNSAFE_LOCAL_PATH",
    )
    return attrs
}

internal fun readPrivateObject(path: Path): JsonObject {
    privateAttributes(path.parent, PRIVATE_DIR)
    val size = (privateAttributes(path, REGULAR).getValue("size") as Number).toLong()
    requireProtocol(size in 1..FILE_LIMIT.toLong(), "UNSAFE_LOCAL_PATH")
    val buffer = ByteBuffer.allocate(FILE_LIMIT + 1)
    Files.newByteChannel(path, setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // 限长读取，文件即使在读取期间增长也不会分配更多内存。
        }
    }
    requireProtocol(buffer.position() <= FILE_LIMIT, "UNSAFE_LOCAL_PATH")
    return parseObject(String(buffer.array(), 0, buffer.position(), Charsets.UTF_8))
}

internal class DesktopEndpoint(val socket: Path, val instanceId: String, val token: String) {
    companion object {
        fun read(profile: Path): DesktopEndpoint {
            val discovery = readPrivateObject(profile.resolve("idea-connector/lmcp-uds.json"))
            requireProtocol(
                discovery.string("format") == "leximeet.idea-uds/1",
                "UNSUPPORTED_VERSION",
            )
            val token = discovery.string("token")
            requireProtocol(token.matches(Regex("[0-9a-f]{64}")), "UNSAFE_LOCAL_PATH")
            val instance = discovery.string("instanceId")
            requireProtocol(
                runCatching { UUID.fromString(instance).toString() == instance }.getOrDefault(false)
            )
            val path = Path.of(discovery.string("socketPath"))
            val base = Path.of("/tmp/leximeet-lmcp-" + uid())
            val id = instance.substring(0, 8)
            requireProtocol(
                path == path.normalize() &&
                    path.parent?.parent == base &&
                    path.parent.fileName.toString().matches(Regex("[0-9a-f]{20}")) &&
                    path.fileName.toString() == "$id.sock",
                "UNSAFE_LOCAL_PATH",
            )
            privateAttributes(base, PRIVATE_DIR)
            privateAttributes(path.parent, PRIVATE_DIR)
            privateAttributes(path, SOCKET)
            return DesktopEndpoint(path, instance, token)
        }
    }
}

/** 一条串行请求通道，采用 LMCP 的小端长度帧。读写共用截止时间，半帧不会卡住 IDE。 */
internal class LmcpTransport
private constructor(private val channel: SocketChannel, private val selector: Selector) :
    Closeable {
    private val key = channel.register(selector, 0)

    fun exchange(request: JsonObject): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        requireProtocol(bytes.size in 1..FRAME_LIMIT, "PAYLOAD_TOO_LARGE")
        val frame = ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(bytes.size).put(bytes).flip()
        transfer(frame, SelectionKey.OP_WRITE, deadline) { channel.write(it) }
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        transfer(header, SelectionKey.OP_READ, deadline) { channel.read(it) }
        val length = header.getInt(0)
        requireProtocol(length in 1..FRAME_LIMIT, "PAYLOAD_TOO_LARGE")
        val body = ByteBuffer.allocate(length)
        transfer(body, SelectionKey.OP_READ, deadline) { channel.read(it) }
        return parseObject(Charsets.UTF_8.newDecoder().decode(body.flip()).toString())
    }

    private fun transfer(buffer: ByteBuffer, op: Int, deadline: Long, step: (ByteBuffer) -> Int) {
        while (buffer.hasRemaining()) {
            if (Thread.currentThread().isInterrupted || deadline <= System.nanoTime()) {
                throw LmcpFailure("DEADLINE_EXCEEDED")
            }
            val size = step(buffer)
            if (size < 0) throw EOFException()
            if (size == 0) waitFor(op, deadline)
        }
    }

    private fun waitFor(op: Int, deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || Thread.currentThread().isInterrupted)
            throw LmcpFailure("DEADLINE_EXCEEDED")
        key.interestOps(op)
        selector.select(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
        selector.selectedKeys().clear()
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            selector.close()
        }
    }

    companion object {
        fun open(endpoint: DesktopEndpoint): LmcpTransport {
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.configureBlocking(false)
                val transport = LmcpTransport(channel, Selector.open())
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    if (!channel.connect(UnixDomainSocketAddress.of(endpoint.socket))) {
                        while (!channel.finishConnect()) transport.waitFor(
                            SelectionKey.OP_CONNECT,
                            deadline,
                        )
                    }
                    val response =
                        transport.exchange(
                            obj("kind" to "idea-transport-auth", "token" to endpoint.token)
                        )
                    requireProtocol(
                        response.string("kind") == "transport-ready" &&
                            response.string("instanceId") == endpoint.instanceId
                    )
                    return transport
                } catch (failure: Exception) {
                    transport.close()
                    throw failure
                }
            } catch (failure: Exception) {
                channel.close()
                throw failure
            }
        }
    }
}
