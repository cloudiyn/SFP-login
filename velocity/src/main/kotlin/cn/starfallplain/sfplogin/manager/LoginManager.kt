/*
 * This file is part of SFP-login, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package cn.starfallplain.sfplogin.manager

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.db.Profile
import cn.starfallplain.sfplogin.api.event.auth.LoginClaim
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleContext
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleRequestEvent
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleResult
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.message.HyperZoneMessagePlaceholder
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredential
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlow
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.message.MessageKeys
import cn.starfallplain.sfplogin.message.MessageService
import cn.starfallplain.sfplogin.player.VelocityHyperZonePlayer
import cn.starfallplain.sfplogin.sfpLoginMain
import io.netty.channel.Channel
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 登录编排：把"认证模块产出的凭证"变成"已 attach 的档案"。
 *
 * 职责边界（拆分后）：
 * - 凭证存取交给 [CredentialClaimStore]；
 * - 等待玩家输入的会话登记交给 [AwaitingLoginSessions]；
 * - 认证通过后离开等待区的三级关卡交给 [WaitingAreaReleaseSequencer]；
 * - 本类只保留 claim 分发、超时、凭证 → 档案的转换与渠道提示。
 */
class LoginManager(
    private val server: ProxyServer,
    private val profileService: HyperZoneProfileService,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : HyperZoneCredentialFlow {

    private val credentialStore = CredentialClaimStore()
    private val awaitingSessions = AwaitingLoginSessions()
    private val releaseSequencer = WaitingAreaReleaseSequencer(server, profileService)

    override fun submitCredential(player: HyperZonePlayer, credential: HyperZoneCredential) {
        credentialStore.submit(player, credential)
    }

    override fun replaceCredential(player: HyperZonePlayer, oldChannelId: String, credential: HyperZoneCredential) {
        credentialStore.replace(player, oldChannelId, credential)
    }

    override fun getSubmittedCredentials(player: HyperZonePlayer): List<HyperZoneCredential> {
        return credentialStore.get(player)
    }

    override fun clearCredentials(player: HyperZonePlayer) {
        credentialStore.clear(player)
    }

    fun clearCredentialsByChannel(channel: Channel) {
        credentialStore.clearByChannel(channel)
    }

    override fun overVerify(player: HyperZonePlayer): UUID? {
        val attachedProfile = profileService.attachVerifiedCredentialProfile(player)
        if (attachedProfile == null) {
            player.sendMessage(sfpLoginMain().messageService.render(player, MessageKeys.Player.VERIFIED_UNBOUND))
            return null
        }
        // 渠道提示只是信息性文案，任何异常都不应影响已经完成的登录流程
        runCatching { sendAuthChannelNotice(player) }.onFailure { throwable ->
            debug(HyperZoneDebugType.GENERAL) { "发送认证渠道提示失败: ${throwable.message}" }
        }
        onProfileAttached(player)
        return attachedProfile.id
    }

    /**
     * 验证通过后告知玩家本次实际使用的验证方式，并提醒不同验证方式的档案数据互不相通。
     *
     * 渠道显示名优先取 `player.auth-channel-name.<channelId>` 的本地化文案，
     * 缺失时回退为原始 channelId。
     */
    private fun sendAuthChannelNotice(player: HyperZonePlayer) {
        val channelId = player.authChannelId?.takeUnless { it.isBlank() } ?: return
        val messageService = sfpLoginMain().messageService
        if (!messageService.hasKey(MessageKeys.Player.AUTH_CHANNEL_NOTICE)) {
            return
        }

        // 优先按具体入口取展示名（如 Yggdrasil 下的 mojang / littleskin），
        // 缺失时逐级回退到渠道级名字，最后退回原始标识，保证任何配置下都有可读提示。
        val displayChannelId = player.authDisplayChannelId?.takeUnless { it.isBlank() } ?: channelId
        val displayName = resolveChannelDisplayName(player, messageService, displayChannelId)
            ?: resolveChannelDisplayName(player, messageService, channelId)
            ?: displayChannelId

        player.sendMessage(
            messageService.render(
                player,
                MessageKeys.Player.AUTH_CHANNEL_NOTICE,
                HyperZoneMessagePlaceholder.text("channel", displayName),
                HyperZoneMessagePlaceholder.text("channel_id", channelId)
            )
        )
    }

    /**
     * 解析渠道展示名：取 `player.auth-channel-name.<channelId>` 的本地化文案，
     * 未配置时返回 null，交由调用方继续回退。
     */
    private fun resolveChannelDisplayName(
        player: HyperZonePlayer,
        messageService: MessageService,
        channelId: String
    ): String? {
        val key = "${MessageKeys.Player.AUTH_CHANNEL_NAME_PREFIX}.${channelId.lowercase()}"
        if (!messageService.hasKey(key)) {
            return null
        }
        return PLAIN_TEXT.serialize(messageService.render(player, key))
    }

//    和login的区别是 这个会尝试离开等待区
    fun attachProfile(player: HyperZonePlayer, profileId: UUID): Profile? {
        val attachedProfile = profileService.attachProfile(player, profileId) ?: return null
        onProfileAttached(player)
        return attachedProfile
    }

    fun onProfileAttached(player: HyperZonePlayer) {
        val velocityPlayer = player as? VelocityHyperZonePlayer ?: return
        runCatching {
            releaseSequencer.tryLeaveWaiting(velocityPlayer)
        }.onFailure { throwable ->
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "loginManager.onProfileAttached transition failed " +
                    "player=${velocityPlayer.clientOriginalName} reason=${throwable.message}"
            }
        }
    }

    override fun resetVerify(player: HyperZonePlayer) {
        clearCredentials(player)
        if (player is VelocityHyperZonePlayer) {
            releaseSequencer.resetTransientState(player)
        }
    }

    data class ModuleFailure(
        val moduleId: String,
        val reason: String
    )

    enum class Status {
        SUCCESS,
        ALREADY_VERIFIED,
        NO_CLAIMS,
        ALL_FAILED,
        TIMEOUT,
        INTERNAL_ERROR
    }

    data class Result(
        val status: Status,
        val winnerModuleId: String? = null,
        val failures: List<ModuleFailure> = emptyList(),
        val reason: String? = null,
    )

    fun dispatch(
        proxyPlayer: Player,
        hyperZonePlayer: VelocityHyperZonePlayer,
        metadata: Map<String, String> = emptyMap(),
    ): CompletableFuture<Result> {
        if (hyperZonePlayer.hasAttachedProfile()) {
            return CompletableFuture.completedFuture(Result(status = Status.ALREADY_VERIFIED))
        }

        val output = CompletableFuture<Result>()
        val requestEvent = LoginHandleRequestEvent(proxyPlayer, hyperZonePlayer, metadata)

        server.eventManager.fire(requestEvent).whenComplete { _, eventThrowable ->
            if (eventThrowable != null) {
                output.complete(
                    Result(
                        status = Status.INTERNAL_ERROR,
                        reason = eventThrowable.message ?: "login handle request event failed",
                    )
                )
                return@whenComplete
            }

            val claims = requestEvent.claims()
            if (claims.isEmpty()) {
                output.complete(Result(status = Status.NO_CLAIMS))
                return@whenComplete
            }

            runClaims(output, proxyPlayer, hyperZonePlayer, metadata, claims)
        }

        return output
    }

    private fun runClaims(
        output: CompletableFuture<Result>,
        proxyPlayer: Player,
        hyperZonePlayer: VelocityHyperZonePlayer,
        metadata: Map<String, String>,
        claims: List<LoginClaim>,
    ) {
        val completed = AtomicBoolean(false)
        val remaining = AtomicInteger(claims.size)
        val failures = CopyOnWriteArrayList<ModuleFailure>()
        val cancelSignal = CompletableFuture<Void>()
        val runningTasks = CopyOnWriteArrayList<Pair<String, CompletableFuture<LoginHandleResult>>>()
        val playerChannel = proxyPlayer.getChannel()

        fun completeOnce(result: Result) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            cancelSignal.complete(null)
            runningTasks.forEach { (_, future) -> future.cancel(true) }
            output.complete(result)
        }

        fun completeAllFailed() {
            completeOnce(
                Result(
                    status = Status.ALL_FAILED,
                    failures = failures.toList(),
                    reason = "all claims failed",
                )
            )
        }

        val startedClaims = claims.map { claim ->
            val session = VelocityLoginHandleSession(hyperZonePlayer)
            val claimFuture = try {
                claim.handler.handle(
                    LoginHandleContext(
                        proxyPlayer = proxyPlayer,
                        session = session,
                        metadata = metadata,
                        cancelSignal = cancelSignal,
                    )
                )
            } catch (throwable: Throwable) {
                CompletableFuture.completedFuture(
                    LoginHandleResult.failed(throwable.message ?: "claim threw exception")
                )
            }
            // 登记 session 供超时检查判定"是否正在等待玩家输入"
            awaitingSessions.track(claimFuture, session)
            runningTasks += claim.moduleId to claimFuture
            claim to claimFuture
        }

        scheduleDispatchTimeout(
            output = output,
            completed = completed,
            cancelSignal = cancelSignal,
            runningTasks = runningTasks,
            failures = failures,
            startedClaims = startedClaims,
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
        )

        startedClaims.forEach { (claim, claimFuture) ->
            claimFuture.whenComplete { result, throwable ->
                playerChannel.eventLoop().execute {
                    if (completed.get()) {
                        return@execute
                    }

                    if (throwable != null) {
                        failures += ModuleFailure(claim.moduleId, throwable.message ?: "claim future failed")
                        if (remaining.decrementAndGet() == 0) {
                            completeAllFailed()
                        }
                        return@execute
                    }

                    val handledResult = result ?: LoginHandleResult.failed("claim completed without result")
                    if (!handledResult.success) {
                        failures += ModuleFailure(claim.moduleId, handledResult.reason ?: "claim rejected")
                        if (remaining.decrementAndGet() == 0) {
                            completeAllFailed()
                        }
                        return@execute
                    }

                    if (hyperZonePlayer.hasAttachedProfile()) {
                        completeOnce(Result(status = Status.SUCCESS, winnerModuleId = claim.moduleId))
                        return@execute
                    }

                    val credential = handledResult.credential
                    if (credential == null) {
                        failures += ModuleFailure(claim.moduleId, "claim succeeded without credential")
                        if (remaining.decrementAndGet() == 0) {
                            completeAllFailed()
                        }
                        return@execute
                    }

                    val submitThrowable = runCatching {
                        credentialStore.submitToChannel(playerChannel, credential)
                    }.exceptionOrNull()
                    if (submitThrowable != null) {
                        failures += ModuleFailure(
                            claim.moduleId,
                            "manager submitCredential failed: ${submitThrowable.message ?: "unknown error"}",
                        )
                        if (remaining.decrementAndGet() == 0) {
                            completeAllFailed()
                        }
                        return@execute
                    }

                    val verifyThrowable = runCatching {
                        overVerify(hyperZonePlayer)
                    }.exceptionOrNull()
                    if (verifyThrowable != null) {
                        clearCredentialsByChannel(playerChannel)
                        failures += ModuleFailure(
                            claim.moduleId,
                            "manager overVerify failed: ${verifyThrowable.message ?: "unknown error"}",
                        )
                    } else if (hyperZonePlayer.hasAttachedProfile()) {
                        completeOnce(Result(status = Status.SUCCESS, winnerModuleId = claim.moduleId))
                        return@execute
                    } else {
                        clearCredentialsByChannel(playerChannel)
                        failures += ModuleFailure(claim.moduleId, "claimed success but profile was not attached")
                    }

                    if (remaining.decrementAndGet() == 0) {
                        completeAllFailed()
                    }
                }
            }
        }
    }

    /**
     * 为一次 dispatch 调度超时。
     *
     * 关键行为：当仍有 claim 处于"等待玩家手动输入"状态（例如渠道本地密码门正在等 `/register`、`/login`）时，
     * 本次检查不判定超时，而是把截止时间整体顺延一个检查周期。
     *
     * 这样可以避免"玩家看提示、打命令"的这段时间被计入固定超时：
     * 一旦超时提前触发，玩家随后提交的凭证将没有任何 claim 在等待，档案永远无法 attach。
     * 该状态由 [cn.starfallplain.sfplogin.api.event.auth.LoginHandleSession.awaitingPlayerInput] 暴露。
     *
     * @param deadlineNanos 当前的超时截止时间戳；等待玩家输入时会被顺延
     */
    private fun scheduleDispatchTimeout(
        output: CompletableFuture<Result>,
        completed: AtomicBoolean,
        cancelSignal: CompletableFuture<Void>,
        runningTasks: CopyOnWriteArrayList<Pair<String, CompletableFuture<LoginHandleResult>>>,
        failures: CopyOnWriteArrayList<ModuleFailure>,
        startedClaims: List<Pair<LoginClaim, CompletableFuture<LoginHandleResult>>>,
        deadlineNanos: Long,
    ) {
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute {
            if (completed.get()) {
                return@execute
            }

            // 仍有 claim 在等待玩家输入：暂停计时，整体顺延一个检查周期
            val awaitingInput = startedClaims.any { (_, future) ->
                !future.isDone && awaitingSessions.isAwaitingPlayerInput(future)
            }
            if (awaitingInput) {
                debug(HyperZoneDebugType.GENERAL) {
                    "login dispatch timeout paused: claim is awaiting player input"
                }
                scheduleDispatchTimeout(
                    output = output,
                    completed = completed,
                    cancelSignal = cancelSignal,
                    runningTasks = runningTasks,
                    failures = failures,
                    startedClaims = startedClaims,
                    deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
                )
                return@execute
            }

            // 玩家输入已结束，但可能只消耗了很短时间：以最初截止时间为准重新计时
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos > 0) {
                scheduleDispatchTimeout(
                    output = output,
                    completed = completed,
                    cancelSignal = cancelSignal,
                    runningTasks = runningTasks,
                    failures = failures,
                    startedClaims = startedClaims,
                    deadlineNanos = deadlineNanos,
                )
                return@execute
            }

            if (completed.compareAndSet(false, true)) {
                cancelSignal.complete(null)
                runningTasks.forEach { (_, future) -> future.cancel(true) }
                output.complete(
                    Result(
                        status = Status.TIMEOUT,
                        failures = failures.toList(),
                        reason = "login dispatch timeout after ${timeoutMillis}ms",
                    )
                )
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 12_000
        private val PLAIN_TEXT = PlainTextComponentSerializer.plainText()
    }
}
