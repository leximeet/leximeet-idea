package com.github.weilhuang.leximeetidea.connection

import com.github.weilhuang.leximeetidea.reading.ReadingProjection
import com.github.weilhuang.leximeetidea.reading.WordCard
import com.github.weilhuang.leximeetidea.reading.WordChoice
import com.google.gson.JsonObject
import java.io.Closeable
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class LinkPhase {
    LOOKING,
    MISSING,
    FOUND,
    INVITED,
    CONNECTED,
    INTERRUPTED,
    UNAVAILABLE,
}

internal data class LinkView(
    val phase: LinkPhase = LinkPhase.LOOKING,
    val name: String = "",
    val error: String? = null,
    val desktopInitiated: Boolean = false,
    val owner: String? = null,
    val revision: String = "",
    val lease: Instant = Instant.EPOCH,
) {
    val readable
        get() = phase == LinkPhase.CONNECTED && Instant.now().isBefore(lease)

    fun allows(projection: ReadingProjection): Boolean =
        readable &&
            owner == projection.ownerKey &&
            revision == projection.revision &&
            Instant.now().isBefore(projection.until)
}

/** 每个 IDE 安装共用一个配对，所有状态变更和 LMCP 收发在同一后台队列完成。 */
internal class DesktopConnection(private val profile: Path?, private val store: ConnectionStore) :
    Closeable {
    private val executor =
        Executors.newSingleThreadScheduledExecutor { job ->
            Thread(job, "LexiMeet IDEA 桌面连接").apply { isDaemon = true }
        }
    private val changing = AtomicBoolean()
    private val started = AtomicBoolean()
    @Volatile private var stopped = false
    @Volatile private var client: DesktopClient? = null
    private var saved: JsonObject? = null
    private var invitation: JsonObject? = null
    @Volatile
    var view: LinkView = LinkView()
        private set

    val busy
        get() = changing.get()

    fun start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay({ perform(::refresh) }, 0, 3, TimeUnit.SECONDS)
        }
    }

    fun discover() = control(::refresh)

    fun request() = control {
        val active = open()
        checkNoPending()
        updateInvitation(active, active.request())
    }

    fun cancel() = control {
        val active = open()
        val id = invitation?.string("invitationId") ?: return@control
        updateInvitation(active, active.request(id))
    }

    fun confirm() = control {
        val active = open()
        val ticket = invitation ?: return@control
        requireProtocol(Instant.now().isBefore(ticket.time("expiresAt")), "INVITATION_EXPIRED")
        checkNoPending()
        // 先持久记录用户已经确认的同一票据。ACK 丢失时只恢复这次授权。
        persist {
            add(
                "pending",
                ticket.deepCopy().apply { addProperty("desktopInstanceId", active.desktopId) },
            )
        }
        finishPair(active, ticket)
    }

    fun disconnect() = control {
        val active = client ?: return@control
        if (active.owner == null) return@control
        persist { addProperty("disconnectPending", true) }
        // 明确断开后马上隐藏词卡，即使确认响应暂时没有回来。
        view = LinkView(LinkPhase.INTERRUPTED, active.displayName)
        finishDisconnect(active)
    }

    fun match(
        tokens: List<String>,
        wanted: () -> Boolean = { true },
    ): CompletableFuture<ReadingProjection> = query { current ->
        val result = current.match(tokens, wanted)
        publishConnected(current)
        result
    }

    fun card(choice: WordChoice, projection: ReadingProjection): CompletableFuture<WordCard> =
        query { current ->
            current.card(choice, projection)
        }

    private fun refresh() {
        val active = open()
        if (active.owner != null) {
            if (stored().get("disconnectPending")?.asBoolean == true) {
                finishDisconnect(active)
            } else {
                active.heartbeat()
                publishConnected(active)
            }
            return
        }
        val data = stored()
        val pending = data.getAsJsonObject("pending")
        if (pending != null && pending.string("desktopInstanceId") == active.desktopId) {
            val status = active.status(invitationId = pending.string("invitationId"))
            if (status.string("invitationState") in setOf("accepted", "pending")) {
                finishPair(active, pending)
                return
            }
            persist { remove("pending") }
        }
        val credential = data.getAsJsonObject("credential")
        if (credential != null && credential.string("desktopInstanceId") == active.desktopId) {
            val status = active.status(credential)
            if (status.string("connectionState") == "connected") {
                active.resume(credential)
                if (data.get("disconnectPending")?.asBoolean == true) {
                    finishDisconnect(active)
                } else {
                    active.workspace()
                    publishConnected(active)
                }
                return
            }
            clearCredential()
        }
        updateInvitation(active, active.status())
    }

    private fun finishPair(active: DesktopClient, ticket: JsonObject) {
        val credential = active.pair(ticket)
        active.workspace()
        persist {
            remove("pending")
            remove("disconnectPending")
            add("credential", credential)
        }
        invitation = null
        publishConnected(active)
    }

    private fun finishDisconnect(active: DesktopClient) {
        active.disconnect()
        clearCredential()
        // 已连接期间发现记录可能过期，明确断开成功后重新登记本端。
        active.hello()
        updateInvitation(active, active.status())
    }

    private fun clearCredential() = persist {
        remove("credential")
        remove("disconnectPending")
    }

    private fun updateInvitation(active: DesktopClient, status: JsonObject) {
        invitation = status.get("invitation")?.takeIf { it.isJsonObject }?.asJsonObject
        view =
            LinkView(
                if (invitation == null) LinkPhase.FOUND else LinkPhase.INVITED,
                active.displayName,
                desktopInitiated = invitation?.textOrNull("requestedBy") == "desktop",
            )
    }

    private fun publishConnected(active: DesktopClient) {
        view =
            LinkView(
                LinkPhase.CONNECTED,
                active.displayName,
                owner = active.owner,
                revision = active.revision,
                lease = active.leaseUntil,
            )
    }

    private fun open(): DesktopClient {
        if (stopped) throw LmcpFailure("CONNECTION_CLOSED")
        client?.let {
            return it
        }
        val target = profile ?: throw LmcpFailure("PLATFORM_UNAVAILABLE")
        val endpoint = DesktopEndpoint.read(target)
        val clientId = stored().string("clientId")
        val active = DesktopClient(LmcpTransport.open(endpoint), clientId)
        try {
            active.hello()
            if (stopped) throw LmcpFailure("CONNECTION_CLOSED")
            client = active
            return active
        } catch (failure: Exception) {
            active.close()
            throw failure
        }
    }

    private fun stored() = saved ?: store.load().also { saved = it }

    private fun persist(update: JsonObject.() -> Unit) {
        val next = stored().deepCopy().apply(update)
        store.save(next)
        saved = next
    }

    private fun checkNoPending() {
        val ticket = stored().getAsJsonObject("pending") ?: return
        if (ticket.string("desktopInstanceId") == client?.desktopId) {
            throw LmcpFailure("CONFIRMATION_PENDING")
        }
    }

    private fun control(action: () -> Unit) {
        if (stopped || !changing.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    perform(action)
                } finally {
                    changing.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            changing.set(false)
        }
    }

    private fun perform(action: () -> Unit) {
        if (stopped) return
        try {
            action()
        } catch (failure: Exception) {
            linkFailed(failure)
        }
    }

    private fun <T> query(read: (DesktopClient) -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try {
            executor.execute {
                if (result.isCancelled) return@execute
                try {
                    val active =
                        client?.takeIf { view.readable && !stopped }
                            ?: throw LmcpFailure("LEASE_EXPIRED")
                    result.complete(read(active))
                } catch (failure: Exception) {
                    // 单词范围过期或桌面资料刚变更，由项目触发下一次匹配，不撤销整个连接。
                    if (
                        (failure as? LmcpFailure)?.code !in
                            setOf(
                                "REVISION_CHANGED",
                                "LEASE_EXPIRED",
                                "RATE_LIMITED",
                                "PAYLOAD_TOO_LARGE",
                            )
                    ) {
                        linkFailed(failure)
                    }
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: RejectedExecutionException) {
            result.completeExceptionally(failure)
        }
        return result
    }

    private fun linkFailed(failure: Exception) {
        val code = (failure as? LmcpFailure)?.code
        val oldView = view
        val hadAuthorization = saved?.has("credential") == true || saved?.has("pending") == true
        view =
            when {
                code == "PLATFORM_UNAVAILABLE" ->
                    LinkView(LinkPhase.UNAVAILABLE, error = "此平台暂未提供 IDEA 连接")
                code in setOf("UNSUPPORTED_VERSION", "CAPABILITY_UNAVAILABLE") ->
                    LinkView(LinkPhase.UNAVAILABLE, error = "请启动支持 IDEA 连接的词遇桌面端")
                code == "UNSAFE_LOCAL_PATH" ->
                    LinkView(LinkPhase.UNAVAILABLE, error = "本机连接文件的权限无效")
                code in
                    setOf("INVITATION_EXPIRED", "INVITATION_CANCELLED", "INVITATION_INVALID") -> {
                    runCatching { if (saved?.has("pending") == true) persist { remove("pending") } }
                    LinkView(LinkPhase.FOUND, oldView.name, "邀请已失效，请重新连接")
                }
                code in
                    setOf(
                        "PAIRING_REVOKED",
                        "CONNECTION_ENDED",
                        "GENERATION_MISMATCH",
                        "OWNER_MISMATCH",
                    ) -> {
                    runCatching {
                        persist {
                            remove("credential")
                            remove("pending")
                            remove("disconnectPending")
                        }
                    }
                    LinkView(LinkPhase.FOUND, oldView.name, "桌面端授权已结束，请重新连接")
                }
                hadAuthorization -> LinkView(LinkPhase.INTERRUPTED, oldView.name, "等待桌面端恢复连接")
                failure is NoSuchFileException ->
                    LinkView(LinkPhase.MISSING, error = "启动支持 IDEA 连接的词遇桌面端后会自动发现")
                else -> LinkView(LinkPhase.MISSING, error = "暂未连上桌面端，可重新检测")
            }
        invitation = null
        val old = client
        client = null
        runCatching { old?.close() }
    }

    override fun close() {
        stopped = true
        view = LinkView(LinkPhase.INTERRUPTED)
        executor.shutdownNow()
        runCatching { client?.close() }
    }
}
