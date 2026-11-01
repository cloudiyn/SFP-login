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

package cn.starfallplain.sfplogin.util

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import cn.starfallplain.sfplogin.api.message.HyperZoneMessagePlaceholder
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.listener.PlayerAreaLifecycleListener
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.message.MessageKeys
import cn.starfallplain.sfplogin.sfpLoginMain

internal data class ConnectMessageKeys(
    val noTarget: String,
    val serverMissing: String,
    val failureException: String,
    val failureReason: String,
) {
    companion object {
        val EXIT = ConnectMessageKeys(
            noTarget = MessageKeys.BackendAuth.EXIT_NO_TARGET,
            serverMissing = MessageKeys.BackendAuth.EXIT_SERVER_MISSING,
            failureException = MessageKeys.BackendAuth.EXIT_FAILURE_EXCEPTION,
            failureReason = MessageKeys.BackendAuth.EXIT_FAILURE_REASON,
        )
        val VERIFIED = ConnectMessageKeys(
            noTarget = MessageKeys.BackendAuth.VERIFIED_NO_TARGET,
            serverMissing = MessageKeys.BackendAuth.VERIFIED_SERVER_MISSING,
            failureException = MessageKeys.BackendAuth.VERIFIED_FAILURE_EXCEPTION,
            failureReason = MessageKeys.BackendAuth.VERIFIED_FAILURE_REASON,
        )
    }
}

/**
 * 将玩家连接到指定服务器，处理"无目标"、"服务器不存在"、"连接失败"等各种异常情况并发送相应消息。
 *
 * @param resolvedServerName 已经解析好的目标服务器名称，null 表示没有可用目标。
 */
internal fun connectPlayerToServer(
    player: Player,
    proxyServer: ProxyServer,
    resolvedServerName: String?,
    keys: ConnectMessageKeys,
): Boolean {
    val messages = sfpLoginMain().messageService
    val hyperPlayer = HyperZonePlayerManager.getByPlayerOrNull(player)

    if (resolvedServerName == null) {
        PlayerAreaLifecycleListener.clearPendingWaitingAreaLeave(player)
        hyperPlayer?.resumeMessageDelivery()
        player.sendMessage(messages.render(player, keys.noTarget))
        return false
    }

    val target = proxyServer.getServer(resolvedServerName).orElse(null)
    if (target == null) {
        PlayerAreaLifecycleListener.clearPendingWaitingAreaLeave(player)
        hyperPlayer?.resumeMessageDelivery()
        player.sendMessage(
            messages.render(
                player,
                keys.serverMissing,
                HyperZoneMessagePlaceholder.text("server", resolvedServerName),
            )
        )
        return false
    }

    hyperPlayer?.suspendMessageDelivery()
    player.createConnectionRequest(target).connect().whenComplete { result, throwable ->
        if (throwable != null) {
            PlayerAreaLifecycleListener.clearPendingWaitingAreaLeave(player)
            hyperPlayer?.resumeMessageDelivery()
            player.sendMessage(
                messages.render(
                    player,
                    keys.failureException,
                    HyperZoneMessagePlaceholder.text("reason", throwable.message ?: "Unknown error"),
                )
            )
            return@whenComplete
        }

        if (result == null || !result.isSuccessful) {
            PlayerAreaLifecycleListener.clearPendingWaitingAreaLeave(player)
            hyperPlayer?.resumeMessageDelivery()
            val reason = result.reasonComponent?.map { it.toString() }?.orElse("未知原因") ?: "未知原因"
            player.sendMessage(
                messages.render(
                    player,
                    keys.failureReason,
                    HyperZoneMessagePlaceholder.text("reason", reason),
                )
            )
        }
    }
    return true
}
