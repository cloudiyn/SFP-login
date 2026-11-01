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
import cn.starfallplain.sfplogin.api.event.area.PlayerAreaTransitionReason
import cn.starfallplain.sfplogin.api.event.auth.LoginClaim
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleContext
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleRequestEvent
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleResult
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleSession
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.api.profile.ChannelPasswordGateRegistry
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredentialFlow
import cn.starfallplain.sfplogin.api.profile.HyperZoneCredential
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.api.profile.WaitingAreaReleaseGate
import cn.starfallplain.sfplogin.api.profile.WaitingAreaReleaseGateRegistryProvider
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.listener.PlayerAreaLifecycleListener
import cn.starfallplain.sfplogin.api.message.HyperZoneMessagePlaceholder
import cn.starfallplain.sfplogin.message.MessageKeys
import cn.starfallplain.sfplogin.message.MessageService
import cn.starfallplain.sfplogin.player.VelocityHyperZonePlayer
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import cn.starfallplain.sfplogin.sfpLoginMain

class LoginManager(
    private val server: ProxyServer,
    private val profileService: HyperZoneProfileService,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : HyperZoneCredentialFlow {
    private val credentialsByChannel = ConcurrentHashMap<Channel, HyperZoneCredential>()
    private val readyTransitionOwners = ConcurrentHashMap<UUID, VelocityHyperZonePlayer>()
    private val notifiedReadyPlayers = ConcurrentHashMap.newKeySet<VelocityHyperZonePlayer>()
    private val lastReadyConflictPlayerIds = ConcurrentHashMap<VelocityHyperZonePlayer, Set<UUID>>()

    /** 当前正在进行的 claim 与其 session 的对应关系，用于判定 claim 是否在等待玩家手动输入。 */
    private val awaitingSessions = CopyOnWriteArrayList<AwaitingClaim>()

    private class AwaitingClaim(
        val future: CompletableFuture<LoginHandleResult>,
        val session: LoginHandleSession,
    )

    override fun submitCredential(player: HyperZonePlayer, credential: HyperZoneCredential) {
        submitCredentialByChannel(resolveChannel(player), credential)
    }

    override fun replaceCredential(player: HyperZonePlayer, oldChannelId: String, credential: HyperZoneCredential) {
        val channel = resolveChannel(player)
        val existing = credentialsByChannel[channel]
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 当前没有可替换凭证")
        if (existing.channelId != oldChannelId) {
            throw IllegalStateException("玩家 ${player.clientOriginalName} 当前凭证渠道与期望不一致: expected=$oldChannelId actual=${existing.channelId}")
        }
        credentialsByChannel[channel] = credential
    }

    override fun getSubmittedCredentials(player: HyperZonePlayer): List<HyperZoneCredential> {
        val channel = resolveChannel(player)
        return credentialsByChannel[channel]?.let(::listOf).orEmpty()
    }

    override fun clearCredentials(player: HyperZonePlayer) {
        resolveChannelOrNull(player)?.let(credentialsByChannel::remove)
    }

    fun clearCredentialsByChannel(channel: Channel) {
        credentialsByChannel.remove(channel)
    }

    override fun overVerify(player: HyperZonePlayer) {
        val attachedProfile = profileService.attachVerifiedCredentialProfile(player)
        if (attachedProfile == null) {
            player.sendMessage(sfpLoginMain().messageService.render(player, MessageKeys.Player.VERIFIED_UNBOUND))
            return
        }
        // 渠道提示只是信息性文案，任何异常都不应影响已经完成的登录流程
        runCatching { sendAuthChannelNotice(player) }.onFailure { throwable ->
            debug(HyperZoneDebugType.GENERAL) { "发送认证渠道提示失败: ${throwable.message}" }
        }
        onProfileAttached(player)
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
            tryLeaveWaiting(velocityPlayer)
        }.onFailure { throwable ->
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "loginManager.onProfileAttached transition failed player=${velocityPlayer.clientOriginalName} reason=${throwable.message}"
            }
        }
    }

    override fun resetVerify(player: HyperZonePlayer) {
        clearCredentials(player)
        if (player is VelocityHyperZonePlayer) {
            resetTransientLoginState(player)
        }
    }

    private fun resetTransientLoginState(player: VelocityHyperZonePlayer) {
        notifiedReadyPlayers.remove(player)
        lastReadyConflictPlayerIds.remove(player)
    }

    private fun tryLeaveWaiting(hyperPlayer: VelocityHyperZonePlayer) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "loginManager.tryLeaveWaiting player=${hyperPlayer.clientOriginalName} attachedProfile=${hyperPlayer.hasAttachedProfile()} proxyBound=${hyperPlayer.getProxyPlayerOrNull() != null}"
        }

        if (!hyperPlayer.hasAttachedProfile()) {
            return
        }

        val player = hyperPlayer.getProxyPlayerOrNull() ?: return
        val main = sfpLoginMain()
        val attachedProfileId = profileService.getAttachedProfile(hyperPlayer)?.id ?: return

        val transitionOwner = readyTransitionOwners.putIfAbsent(attachedProfileId, hyperPlayer)
        if (transitionOwner != null && transitionOwner !== hyperPlayer) {
            notifyProfileConflict(hyperPlayer, listOf(transitionOwner))
            return
        }

        try {
            val conflictingPlayers = server.allPlayers.asSequence()
                .mapNotNull { onlinePlayer ->
                    val otherHyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(onlinePlayer) ?: return@mapNotNull null
                    if (otherHyperPlayer === hyperPlayer) {
                        return@mapNotNull null
                    }

                    if (profileService.getAttachedProfile(otherHyperPlayer)?.id != attachedProfileId) {
                        return@mapNotNull null
                    }

                    val isStillInWaitingArea = main.serverAdapter?.isPlayerInWaitingArea(onlinePlayer) == true
                    if (isStillInWaitingArea && !notifiedReadyPlayers.contains(otherHyperPlayer)) {
                        return@mapNotNull null
                    }

                    otherHyperPlayer
                }
                .toList()

            if (conflictingPlayers.isNotEmpty()) {
                notifyProfileConflict(hyperPlayer, conflictingPlayers)
                return
            }

            // 等待区放行闸门：认证通过且档案已 attach 后，真正离开等待区之前必须由全部闸门放行。
            // 该闸门是同步阻塞判定，拒绝时玩家停留在等待区（或按闸门要求断开连接）。
            val attachedProfile = profileService.getAttachedProfile(hyperPlayer) ?: return
            val gateDecision = WaitingAreaReleaseGateRegistryProvider.getOrNull()
                ?.evaluate(player, attachedProfile)
                ?: WaitingAreaReleaseGate.Decision.Allow()
            if (gateDecision is WaitingAreaReleaseGate.Decision.Deny) {
                gateDecision.message?.let { player.sendMessage(it) }
                if (gateDecision.disconnect) {
                    debug(HyperZoneDebugType.OUTPRE_TRACE) {
                        "loginManager.tryLeaveWaiting denied and disconnected player=${hyperPlayer.clientOriginalName} profile=${attachedProfile.name}"
                    }
                    player.disconnect(gateDecision.message ?: Component.empty())
                }
                return
            }
            val releaseNotice = (gateDecision as? WaitingAreaReleaseGate.Decision.Allow)?.notice

            lastReadyConflictPlayerIds.remove(hyperPlayer)
            if (!notifiedReadyPlayers.add(hyperPlayer)) {
                return
            }

            PlayerAreaLifecycleListener.markWaitingAreaLeavePending(player, PlayerAreaTransitionReason.VERIFIED)
            main.serverAdapter?.onVerified(player)
            releaseNotice?.let { player.sendMessage(it) }
        } finally {
            readyTransitionOwners.remove(attachedProfileId, hyperPlayer)
        }
    }

    private fun notifyProfileConflict(player: VelocityHyperZonePlayer, conflictingPlayers: List<VelocityHyperZonePlayer>) {
        val conflictPlayerIds = conflictingPlayers.asSequence()
            .filter { it !== player }
            .mapNotNull { it.clientOriginalUUID }
            .toSet()
        if (conflictPlayerIds.isEmpty()) {
            return
        }

        val previousConflictPlayerIds = lastReadyConflictPlayerIds.put(player, conflictPlayerIds)
        if (previousConflictPlayerIds == conflictPlayerIds) {
            return
        }

        val messageService = sfpLoginMain().messageService
        player.sendMessage(messageService.render(player, MessageKeys.Player.PROFILE_CONFLICT_SELF))
        conflictingPlayers.forEach { conflictingPlayer ->
            if (conflictingPlayer === player) {
                return@forEach
            }
            conflictingPlayer.sendMessage(
                messageService.render(
                    conflictingPlayer,
                    MessageKeys.Player.PROFILE_CONFLICT_OTHER
                )
            )
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
            awaitingSessions += AwaitingClaim(claimFuture, session)
            claimFuture.whenComplete { _, _ ->
                awaitingSessions.removeIf { it.future === claimFuture }
            }
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
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
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
                        completeOnce(
                            Result(
                                status = Status.ALL_FAILED,
                                failures = failures.toList(),
                                reason = "all claims failed"
                            )
                        )
                    }
                    return@execute
                }

                val handledResult = result ?: LoginHandleResult.failed("claim completed without result")
                if (handledResult.success) {
                    if (hyperZonePlayer.hasAttachedProfile()) {
                        completeOnce(Result(status = Status.SUCCESS, winnerModuleId = claim.moduleId))
                        return@execute
                    }

                    val credential = handledResult.credential
                    if (credential == null) {
                        failures += ModuleFailure(claim.moduleId, "claim succeeded without credential")
                        if (remaining.decrementAndGet() == 0) {
                            completeOnce(
                                Result(
                                    status = Status.ALL_FAILED,
                                    failures = failures.toList(),
                                    reason = "all claims failed"
                                )
                            )
                        }
                                return@execute
                    }

                    val submitThrowable = runCatching {
                        submitCredentialByChannel(playerChannel, credential)
                    }.exceptionOrNull()
                    if (submitThrowable != null) {
                        failures += ModuleFailure(
                            claim.moduleId,
                            "manager submitCredential failed: ${submitThrowable.message ?: "unknown error"}"
                        )
                        if (remaining.decrementAndGet() == 0) {
                            completeOnce(
                                Result(
                                    status = Status.ALL_FAILED,
                                    failures = failures.toList(),
                                    reason = "all claims failed"
                                )
                            )
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
                            "manager overVerify failed: ${verifyThrowable.message ?: "unknown error"}"
                        )
                    } else if (hyperZonePlayer.hasAttachedProfile()) {
                        completeOnce(Result(status = Status.SUCCESS, winnerModuleId = claim.moduleId))
                        return@execute
                    } else {
                        clearCredentialsByChannel(playerChannel)
                        failures += ModuleFailure(claim.moduleId, "claimed success but profile was not attached")
                    }
                } else {
                    failures += ModuleFailure(claim.moduleId, handledResult.reason ?: "claim rejected")
                }

                if (remaining.decrementAndGet() == 0) {
                    completeOnce(
                        Result(
                            status = Status.ALL_FAILED,
                            failures = failures.toList(),
                            reason = "all claims failed"
                        )
                    )
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
     * 该状态由 [LoginHandleSession.awaitingPlayerInput] 暴露，调用方也可据此决定是否提前把玩家放行到等待区。
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
            if (startedClaims.any { (_, future) -> !future.isDone && isClaimAwaitingPlayerInput(future) }) {
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
                    deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
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
                    deadlineNanos = deadlineNanos
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
                        reason = "login dispatch timeout after ${timeoutMillis}ms"
                    )
                )
            }
        }
    }

    /**
     * 判断某个 claim 当前是否处于"等待玩家手动输入"状态。
     *
     * 这里通过 claim 自己持有的 [LoginHandleSession] 判定，避免 LoginManager 依赖具体认证模块。
     */
    private fun isClaimAwaitingPlayerInput(future: CompletableFuture<LoginHandleResult>): Boolean {
        return runCatching {
            awaitingSessions.firstOrNull { entry -> entry.future === future }?.session?.awaitingPlayerInput() == true
        }.getOrDefault(false)
    }

    private fun submitCredentialByChannel(channel: Channel, credential: HyperZoneCredential) {
        val existing = credentialsByChannel[channel]
        if (existing != null) {
            throw IllegalStateException(
                "玩家 channel=$channel 已存在凭证 ${existing.channelId}，必须先替换后再提交"
            )
        }
        credentialsByChannel[channel] = credential
    }


    private fun resolveChannel(player: HyperZonePlayer): Channel {
        return resolveChannelOrNull(player)
            ?: throw IllegalStateException("玩家 ${player.clientOriginalName} 当前未绑定 Proxy Player，无法定位 channel")
    }

    private fun resolveChannelOrNull(player: HyperZonePlayer): Channel? {
        return player.getProxyPlayerOrNull()?.getChannel()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 12_000
        private val PLAIN_TEXT = PlainTextComponentSerializer.plainText()
    }
}

private class VelocityLoginHandleSession(
    private val player: VelocityHyperZonePlayer
) : LoginHandleSession {
    override fun originalName(): String = player.clientOriginalName

    override fun hasAttachedProfile(): Boolean = player.hasAttachedProfile()

    /**
     * 通过渠道密码门注册表判定当前会话是否正等待玩家手动输入密码。
     *
     * 这样登录编排层无需依赖任何具体认证模块，即可把"等待玩家输入"的耗时排除出 claim 超时。
     */
    override fun awaitingPlayerInput(): Boolean {
        return runCatching {
            ChannelPasswordGateRegistry.resolveGate(player)
                ?.getGateStatus(player) != null
        }.getOrDefault(false)
    }

    override fun sendMessage(message: net.kyori.adventure.text.Component) {
        player.sendMessage(message)
    }
}

