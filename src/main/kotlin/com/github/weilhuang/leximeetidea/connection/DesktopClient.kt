package com.github.weilhuang.leximeetidea.connection

import com.github.weilhuang.leximeetidea.reading.ReadingProjection
import com.github.weilhuang.leximeetidea.reading.WordCard
import com.github.weilhuang.leximeetidea.reading.WordChoice
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.Closeable
import java.time.Instant
import java.util.UUID

private const val CONTRACT_DIGEST =
    "61a44cc2f7f73fef81b007c844e1f2fa4deb955bf21a69236ee7b4f713808c49"
private val CAPABILITIES = listOf("lmcp.connection/1", "desktop.reading/1")
private val REQUIRED_METHODS =
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
private val OWNER =
    listOf(
        "pairingId",
        "desktopInstanceId",
        "clientInstanceId",
        "workspaceId",
        "generation",
        "authorizationEpoch",
    )
internal const val MAX_CANDIDATES = 500

internal fun ownerKey(owner: JsonObject) = OWNER.joinToString("/") { owner.string(it) }

internal class DesktopClient(private val wire: LmcpTransport, private val clientId: String) :
    Closeable {
    private val connectionId = UUID.randomUUID().toString()
    private var session: JsonObject? = null
    lateinit var desktopId: String
        private set

    var displayName = ""
        private set

    var revision = ""
        private set

    var leaseUntil: Instant = Instant.EPOCH
        private set

    val owner: String?
        get() = session?.fields("owner")?.let(::ownerKey)

    fun hello() {
        val result =
            rpc(
                "hello",
                obj(
                        "connectionId" to connectionId,
                        "clientInstanceId" to clientId,
                        "clientKind" to "ide",
                        "displayName" to "词遇 for IntelliJ IDEA",
                        "minApiVersion" to "1.0.0",
                        "contractVersion" to "1.0.0",
                        "contractDigest" to CONTRACT_DIGEST,
                    )
                    .apply {
                        addProperty("apiMajor", 1)
                        add("requiredCapabilities", CAPABILITIES.jsonArray())
                    },
            )
        requireProtocol(result.string("connectionId") == connectionId)
        requireProtocol(
            result.string("apiVersion").matches(Regex("1\\.[0-9]+\\.[0-9]+")),
            "UNSUPPORTED_VERSION",
        )
        requireProtocol(
            result.items("capabilities").map { it.asString }.containsAll(CAPABILITIES),
            "CAPABILITY_UNAVAILABLE",
        )
        requireProtocol(
            result.items("methods").map { it.asString }.containsAll(REQUIRED_METHODS),
            "CAPABILITY_UNAVAILABLE",
        )
        desktopId = result.string("desktopInstanceId")
        displayName = result.string("displayName").take(80)
    }

    fun request(invitationId: String? = null): JsonObject =
        rpc(
            "requestConnection",
            obj(
                    "clientInstanceId" to clientId,
                    "action" to if (invitationId == null) "request" else "cancel",
                )
                .apply { if (invitationId != null) addProperty("invitationId", invitationId) },
        )

    fun status(credential: JsonObject? = null, invitationId: String? = null): JsonObject =
        rpc(
                "getConnectionStatus",
                obj("clientInstanceId" to clientId).apply {
                    if (credential != null) add("pairingCredential", credential)
                    if (invitationId != null) addProperty("invitationId", invitationId)
                },
            )
            .also { requireProtocol(it.string("desktopInstanceId") == desktopId, "OWNER_MISMATCH") }

    fun pair(invitation: JsonObject): JsonObject {
        val result =
            rpc(
                "pair",
                obj(
                    "clientInstanceId" to clientId,
                    "invitationId" to invitation.string("invitationId"),
                    "invitationToken" to invitation.string("invitationToken"),
                ),
            )
        val credential = result.fields("pairingCredential")
        credential.string("pairingToken")
        activate(result, ownerKey(credential))
        return credential.deepCopy()
    }

    fun resume(credential: JsonObject) {
        activate(rpc("resumeSession", credential), ownerKey(credential))
    }

    private fun activate(result: JsonObject, expectedOwner: String) {
        val target = result.fields("owner")
        val authorization = result.fields("authorization")
        requireProtocol(
            ownerKey(target) == expectedOwner &&
                target.string("clientInstanceId") == clientId &&
                target.string("desktopInstanceId") == desktopId &&
                target.string("pairingId") == result.string("pairingId") &&
                listOf("workspaceId", "generation", "authorizationEpoch").all {
                    target.string(it) == authorization.string(it)
                },
            "OWNER_MISMATCH",
        )
        requireProtocol(
            result.items("scopes").map { it.asString } == listOf("library:read"),
            "FORBIDDEN",
        )
        result.fields("authorization").string("sessionToken")
        requireProtocol(Instant.now().isBefore(result.time("expiresAt")), "SESSION_EXPIRED")
        session = result
        leaseUntil = lease(result.time("readLeaseUntil"), Instant.now())
    }

    fun workspace() {
        val started = Instant.now()
        val result = authorized("getWorkspace")
        requireProtocol(ownerKey(result.fields("owner")) == owner, "OWNER_MISMATCH")
        revision = result.string("revision")
        leaseUntil = lease(result.time("readLeaseUntil"), started)
    }

    fun heartbeat() {
        val current = session ?: throw LmcpFailure("UNAUTHORIZED")
        if (!Instant.now().plusSeconds(20).isBefore(current.time("expiresAt"))) {
            activate(authorized("renewSession"), ownerKey(current.fields("owner")))
        }
        val started = Instant.now()
        val result = authorized("getChanges", obj("sinceRevision" to revision))
        revision = result.string("revision")
        leaseUntil = lease(result.time("readLeaseUntil"), started)
    }

    fun disconnect() {
        requireProtocol(authorized("disconnect").flag("disconnected"))
        session = null
        leaseUntil = Instant.EPOCH
    }

    /** 只查询去重后的候选词；跨批修订不一致即丢弃整轮，避免混搭桌面资料。 */
    fun match(tokens: List<String>, wanted: () -> Boolean = { true }): ReadingProjection {
        requireProtocol(wanted(), "LEASE_EXPIRED")
        workspace()
        val key = owner ?: throw LmcpFailure("UNAUTHORIZED")
        val expectedRevision = revision
        var deadline = leaseUntil
        val words = linkedMapOf<String, List<WordChoice>>()
        for (batch in tokens.distinct().take(MAX_CANDIDATES).chunked(100)) {
            val started = Instant.now()
            requireProtocol(wanted() && started.isBefore(deadline), "LEASE_EXPIRED")
            val result =
                authorized(
                    "matchWords",
                    obj("kind" to "tokens").apply { add("tokens", batch.jsonArray()) },
                )
            if (result.string("workspaceRevision") != expectedRevision)
                throw LmcpFailure("REVISION_CHANGED")
            deadline = minOf(deadline, refreshDeadline(result, started))
            val seen = hashSetOf<Int>()
            for (element in result.items("results")) {
                val item = element.asJsonObject
                val position = item.get("inputIndex").asInt
                requireProtocol(position in batch.indices && seen.add(position))
                words[batch[position]] =
                    if (item.flag("complete")) {
                        item.items("matches").mapNotNull { entry ->
                            val match = entry.asJsonObject
                            val target = match.flag("inTarget")
                            val collected = match.flag("collected")
                            if (!target && !collected) null
                            else
                                WordChoice(
                                    match.fields("word").deepCopy(),
                                    match.string("headword"),
                                    target,
                                    collected,
                                    match.string("learningStatus"),
                                )
                        }
                    } else emptyList()
            }
            requireProtocol(seen.size == batch.size)
        }
        requireProtocol(Instant.now().isBefore(deadline), "LEASE_EXPIRED")
        return ReadingProjection(key, expectedRevision, deadline, words)
    }

    fun card(choice: WordChoice, projection: ReadingProjection): WordCard {
        requireProtocol(
            owner == projection.ownerKey &&
                revision == projection.revision &&
                Instant.now().isBefore(projection.until),
            "LEASE_EXPIRED",
        )
        val started = Instant.now()
        val summary = authorized("getWord", JsonObject().apply { add("word", choice.ref) })
        requireProtocol(summary.fields("word") == choice.ref, "WORD_MISMATCH")
        val deadline = minOf(projection.until, refreshDeadline(summary, started), leaseUntil)
        var publicEntry: JsonObject? = null
        if (
            choice.ref.string("kind") == "dictionary" &&
                summary.string("resourceStatus") == "available"
        ) {
            val id = choice.ref.string("entryId")
            val release = choice.ref.string("release")
            try {
                val result =
                    authorized("getPublicEntry", obj("entryId" to id, "release" to release))
                val entry = result.fields("entry")
                requireProtocol(
                    result.string("release") == release && entry.string("entry_id") == id,
                    "WORD_MISMATCH",
                )
                publicEntry = entry
            } catch (failure: LmcpFailure) {
                if (failure.code !in setOf("RESOURCE_UNAVAILABLE", "PAYLOAD_TOO_LARGE"))
                    throw failure
            }
        }
        requireProtocol(Instant.now().isBefore(deadline), "LEASE_EXPIRED")
        return readDesktopWordCard(choice.headword, summary, publicEntry)
    }

    private fun lease(until: Instant, started: Instant) =
        minOf(until, started.plusSeconds(30), session!!.time("expiresAt"))

    private fun refreshDeadline(result: JsonObject, started: Instant) =
        started.plusMillis(result.get("refreshAfterMs").asLong.coerceIn(0, 30_000))

    private fun authorized(method: String, input: JsonObject = JsonObject()): JsonObject {
        val active = session ?: throw LmcpFailure("UNAUTHORIZED")
        requireProtocol(Instant.now().isBefore(active.time("expiresAt")), "SESSION_EXPIRED")
        return rpc(method, input, active.fields("authorization"))
    }

    private fun rpc(method: String, params: JsonObject, auth: JsonObject? = null): JsonObject {
        val request =
            obj(
                    "requestId" to UUID.randomUUID().toString(),
                    "connectionId" to connectionId,
                    "method" to method,
                )
                .apply {
                    addProperty("apiMajor", 1)
                    add("params", params)
                    if (auth != null) add("authorization", auth)
                }
        val result = wire.exchange(request)
        requireProtocol(
            listOf("requestId", "connectionId", "method").all {
                request.string(it) == result.string(it)
            } && result.string("apiVersion").startsWith("1.")
        )
        if (!result.flag("ok")) throw LmcpFailure(result.fields("error").string("code"))
        return result.fields("result")
    }

    override fun close() = wire.close()
}

private fun List<String>.jsonArray() = JsonArray().apply { this@jsonArray.forEach { add(it) } }
