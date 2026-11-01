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

package cn.starfallplain.sfplogin.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.event.area.*
import cn.starfallplain.sfplogin.api.event.vServer.VServerJoinEvent
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.message.MessageKeys
import io.netty.channel.Channel
import java.util.concurrent.ConcurrentHashMap

object PlayerAreaLifecycleListener {
    private enum class AreaState {
        WAITING,
        GAME,
    }

    private data class SessionState(
        var currentArea: AreaState? = null,
        var pendingWaitingLeaveReason: PlayerAreaTransitionReason? = null,
    )

    private val sessionStates = ConcurrentHashMap<Channel, SessionState>()

    fun markWaitingAreaLeavePending(player: Player, reason: PlayerAreaTransitionReason) {
        sessionStates.compute(player.getChannel()) { _, existing ->
            (existing ?: SessionState()).apply {
                pendingWaitingLeaveReason = reason
            }
        }
    }

    fun clearPendingWaitingAreaLeave(player: Player) {
        sessionStates.computeIfPresent(player.getChannel()) { _, existing ->
            existing.pendingWaitingLeaveReason = null
            existing
        }
    }

    fun onDisconnect(player: Player, hyperZonePlayer: HyperZonePlayer) {
        val state = sessionStates.remove(player.getChannel()) ?: return
        when (state.currentArea) {
            AreaState.WAITING -> fireWaitingAreaLeave(
                proxyPlayer = player,
                hyperZonePlayer = hyperZonePlayer,
                reason = state.pendingWaitingLeaveReason ?: PlayerAreaTransitionReason.DISCONNECT,
            )

            AreaState.GAME -> fireGameAreaLeave(
                proxyPlayer = player,
                hyperZonePlayer = hyperZonePlayer,
                reason = PlayerAreaTransitionReason.DISCONNECT,
            )

            null -> Unit
        }
    }

    @Subscribe
    fun onWaitingAreaJoin(event: VServerJoinEvent) {
        val channel = event.proxyPlayer.getChannel()
        when (sessionStates[channel]?.currentArea) {
            AreaState.WAITING -> {
                sessionStates[channel] = SessionState(currentArea = AreaState.WAITING)
                return
            }

            AreaState.GAME -> fireGameAreaLeave(
                proxyPlayer = event.proxyPlayer,
                hyperZonePlayer = event.hyperZonePlayer,
                reason = PlayerAreaTransitionReason.AUTH_REQUIRED,
            )

            null -> Unit
        }

        sessionStates[channel] = SessionState(currentArea = AreaState.WAITING)
        fireWaitingAreaEnter(
            proxyPlayer = event.proxyPlayer,
            hyperZonePlayer = event.hyperZonePlayer,
            reason = PlayerAreaTransitionReason.AUTH_REQUIRED,
        )
    }

    @Subscribe
    fun onServerConnected(event: ServerPostConnectEvent) {
        val player = event.player
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayerOrNull(player) ?: return
        val adapterWaiting = SFPLoginMain.getInstance().serverAdapter?.isPlayerInWaitingArea(player) == true
        val logicalWaiting = !hyperZonePlayer.hasAttachedProfile() || adapterWaiting

        if (logicalWaiting) {
            return
        }

        val channel = player.getChannel()
        val currentState = sessionStates[channel]

        if (currentState?.currentArea == AreaState.WAITING) {
            val waitingLeaveReason = currentState.pendingWaitingLeaveReason
                ?: if (logicalWaiting) {
                    PlayerAreaTransitionReason.EXIT_REQUEST
                } else {
                    PlayerAreaTransitionReason.VERIFIED
                }

            fireWaitingAreaLeave(
                proxyPlayer = player,
                hyperZonePlayer = hyperZonePlayer,
                reason = waitingLeaveReason,
            )

            if (logicalWaiting) {
                sessionStates.remove(channel)
                return
            }

            sessionStates[channel] = SessionState(currentArea = AreaState.GAME)
            fireGameAreaEnter(
                proxyPlayer = player,
                hyperZonePlayer = hyperZonePlayer,
                reason = PlayerAreaTransitionReason.VERIFIED,
            )
            return
        }

        if (!logicalWaiting && currentState?.currentArea != AreaState.GAME) {
            sessionStates[channel] = SessionState(currentArea = AreaState.GAME)
            fireGameAreaEnter(
                proxyPlayer = player,
                hyperZonePlayer = hyperZonePlayer,
                reason = PlayerAreaTransitionReason.DIRECT_JOIN,
            )
        }
    }

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        val player = event.player
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayerOrNull(player) ?: return
        hyperZonePlayer.resumeMessageDelivery()
    }

    private fun fireWaitingAreaEnter(
        proxyPlayer: Player,
        hyperZonePlayer: HyperZonePlayer,
        reason: PlayerAreaTransitionReason,
    ) {
        val main = SFPLoginMain.getInstance()
        main.proxy.eventManager.fire(WaitingAreaEnterEvent(proxyPlayer, hyperZonePlayer, reason))
        main.messageService.send(hyperZonePlayer, MessageKeys.Player.ENTER_WAITING_AREA)
    }

    private fun fireWaitingAreaLeave(
        proxyPlayer: Player,
        hyperZonePlayer: HyperZonePlayer,
        reason: PlayerAreaTransitionReason,
    ) {
        val main = SFPLoginMain.getInstance()
        main.proxy.eventManager.fire(WaitingAreaLeaveEvent(proxyPlayer, hyperZonePlayer, reason))
        main.messageService.send(hyperZonePlayer, MessageKeys.Player.LEAVE_WAITING_AREA)
    }

    private fun fireGameAreaEnter(
        proxyPlayer: Player,
        hyperZonePlayer: HyperZonePlayer,
        reason: PlayerAreaTransitionReason,
    ) {
        val main = SFPLoginMain.getInstance()
        main.proxy.eventManager.fire(GameAreaEnterEvent(proxyPlayer, hyperZonePlayer, reason))
        main.messageService.send(hyperZonePlayer, MessageKeys.Player.ENTER_GAME_AREA)
    }

    private fun fireGameAreaLeave(
        proxyPlayer: Player,
        hyperZonePlayer: HyperZonePlayer,
        reason: PlayerAreaTransitionReason,
    ) {
        val main = SFPLoginMain.getInstance()
        main.proxy.eventManager.fire(GameAreaLeaveEvent(proxyPlayer, hyperZonePlayer, reason))
        main.messageService.send(hyperZonePlayer, MessageKeys.Player.LEAVE_GAME_AREA)
    }
}
