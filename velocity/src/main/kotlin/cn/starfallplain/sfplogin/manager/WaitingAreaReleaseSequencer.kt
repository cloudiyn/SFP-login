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
import cn.starfallplain.sfplogin.api.event.area.PlayerAreaTransitionReason
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileService
import cn.starfallplain.sfplogin.api.profile.WaitingAreaReleaseGate
import cn.starfallplain.sfplogin.api.profile.WaitingAreaReleaseGateRegistryProvider
import cn.starfallplain.sfplogin.listener.PlayerAreaLifecycleListener
import cn.starfallplain.sfplogin.message.MessageKeys
import cn.starfallplain.sfplogin.player.VelocityHyperZonePlayer
import cn.starfallplain.sfplogin.sfpLoginMain
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 等待区放行的顺序执行器。
 *
 * 认证通过且档案 attach 之后，玩家离开等待区要经过三级关卡，顺序不能乱：
 *
 * 1. **档案冲突**：同一档案不允许同时有两个会进入游戏的会话（防止复制人）；
 * 2. **放行闸门**：`WaitingAreaReleaseGateRegistry` 里的所有闸门必须放行（如邮箱验证、IP 限制）；
 * 3. **事务放行**：先写离场标记再交给 serverAdapter，失败则回滚并保留重试机会。
 *
 * 这一整套状态机从 LoginManager 里抽出来，LoginManager 只保留“凭证 → 档案”的编排职责。
 */
internal class WaitingAreaReleaseSequencer(
    private val server: ProxyServer,
    private val profileService: HyperZoneProfileService,
) {

    /** 正在为某个档案执行离场的玩家，保证同一档案只有一个释放流程在跑。 */
    private val readyTransitionOwners = ConcurrentHashMap<UUID, VelocityHyperZonePlayer>()

    /** 已经成功放行的玩家；用于冲突判定时区分"还在等待区"与"也要进游戏"。 */
    private val notifiedReadyPlayers = ConcurrentHashMap.newKeySet<VelocityHyperZonePlayer>()

    /** 上次通知过的冲突集合，避免每 tick 重复刷同一条提示。 */
    private val lastReadyConflictPlayerIds = ConcurrentHashMap<VelocityHyperZonePlayer, Set<UUID>>()

    /**
     * 清掉某个玩家的放行相关瞬时状态。
     *
     * 登出、重置验证、重新进入等待区时调用；不清会让玩家被判定为"已放行"，后续重试被静默丢弃。
     */
    fun resetTransientState(player: VelocityHyperZonePlayer) {
        notifiedReadyPlayers.remove(player)
        lastReadyConflictPlayerIds.remove(player)
    }

    fun clear() {
        readyTransitionOwners.clear()
        notifiedReadyPlayers.clear()
        lastReadyConflictPlayerIds.clear()
    }

    /**
     * 在档案已 attach 之后尝试把玩家送出等待区。
     *
     * 任何一级关卡拒绝都会安静返回（或按闸门要求断开连接），玩家继续留在等待区等待人工处理。
     */
    fun tryLeaveWaiting(hyperPlayer: VelocityHyperZonePlayer) {
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "loginManager.tryLeaveWaiting player=${hyperPlayer.clientOriginalName} " +
                "attachedProfile=${hyperPlayer.hasAttachedProfile()} " +
                "proxyBound=${hyperPlayer.getProxyPlayerOrNull() != null}"
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
                        "loginManager.tryLeaveWaiting denied and disconnected " +
                            "player=${hyperPlayer.clientOriginalName} profile=${attachedProfile.name}"
                    }
                    player.disconnect(gateDecision.message ?: Component.empty())
                }
                return
            }
            val releaseNotice = (gateDecision as? WaitingAreaReleaseGate.Decision.Allow)?.notice

            lastReadyConflictPlayerIds.remove(hyperPlayer)
            if (notifiedReadyPlayers.contains(hyperPlayer)) {
                return
            }

            // 放行必须事务化：先真正执行离场（标记 + 交给 serverAdapter），成功之后才把玩家记入
            // notifiedReadyPlayers。反过来的话，任何一步抛错都会留下“已通知但没放行”的状态，
            // 之后的重试会被 notifiedReadyPlayers 挡掉，玩家会永久卡在等待区。
            if (!releaseFromWaitingArea(hyperPlayer, player, releaseNotice)) {
                return
            }
            notifiedReadyPlayers.add(hyperPlayer)
        } finally {
            readyTransitionOwners.remove(attachedProfileId, hyperPlayer)
        }
    }

    /**
     * 执行一次等待区放行，成功返回 `true`。
     *
     * 失败时回滚已经写入的离场标记并保留重试机会；只有真正放行成功后，调用方才应把玩家
     * 记入 `notifiedReadyPlayers`。
     */
    private fun releaseFromWaitingArea(
        hyperPlayer: VelocityHyperZonePlayer,
        player: Player,
        releaseNotice: Component?,
    ): Boolean {
        return WaitingAreaReleaseTransaction.run(
            markLeavePending = {
                PlayerAreaLifecycleListener.markWaitingAreaLeavePending(player, PlayerAreaTransitionReason.VERIFIED)
            },
            clearLeavePending = {
                PlayerAreaLifecycleListener.clearPendingWaitingAreaLeave(player)
            },
            release = {
                val adapter = sfpLoginMain().serverAdapter
                if (adapter == null) {
                    debug(HyperZoneDebugType.OUTPRE_TRACE) {
                        "loginManager.release no server adapter, player=${hyperPlayer.clientOriginalName}"
                    }
                } else {
                    adapter.onVerified(player)
                }
                releaseNotice?.let { player.sendMessage(it) }
            },
            onFailure = { t ->
                logger.error(
                    "等待区放行失败，已回滚标记并保留重试机会: player={}",
                    hyperPlayer.clientOriginalName,
                    t,
                )
            },
        )
    }

    private fun notifyProfileConflict(
        player: VelocityHyperZonePlayer,
        conflictingPlayers: List<VelocityHyperZonePlayer>,
    ) {
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

    private companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(WaitingAreaReleaseSequencer::class.java)
    }
}
