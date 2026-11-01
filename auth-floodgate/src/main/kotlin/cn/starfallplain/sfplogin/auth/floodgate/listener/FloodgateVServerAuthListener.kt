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

package cn.starfallplain.sfplogin.auth.floodgate.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleRequestEvent
import cn.starfallplain.sfplogin.api.event.auth.LoginHandleResult
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.api.player.getChannel
import cn.starfallplain.sfplogin.auth.floodgate.FloodgateMessages
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateAuthService
import java.util.concurrent.CompletableFuture

class FloodgateVServerAuthListener(
    private val authService: FloodgateAuthService
) {

    @Subscribe(priority = Short.MAX_VALUE)
    fun onLoginHandleRequest(event: LoginHandleRequestEvent) {
        event.claim("auth-floodgate:primary") { context ->
            debug(HyperZoneDebugType.FLOODGATE) {
                "onLoginHandleRequest before complete channel=${context.proxyPlayer.getChannel()} player=${context.session.originalName()} attachedProfile=${context.session.hasAttachedProfile()}"
            }
            val result = authService.complete(
                channel = context.proxyPlayer.getChannel(),
                playerName = context.session.originalName()
            )
            debug(HyperZoneDebugType.FLOODGATE) {
                "onLoginHandleRequest after complete channel=${context.proxyPlayer.getChannel()} player=${context.session.originalName()} handled=${result.handled} passed=${result.passed} disconnectOnFailure=${result.disconnectOnFailure} attachedProfile=${context.session.hasAttachedProfile()}"
            }
            if (!result.handled) {
                return@claim CompletableFuture.completedFuture(LoginHandleResult.failed("not a floodgate player"))
            }
            if (!result.passed) {
                if (result.disconnectOnFailure) {
                    context.proxyPlayer.disconnect(result.userMessage ?: FloodgateMessages.genericDisconnect())
                } else {
                    result.userMessage?.let(context.session::sendMessage)
                }
                return@claim CompletableFuture.completedFuture(
                    LoginHandleResult.failed("floodgate authentication rejected")
                )
            }

            val success = {
                LoginHandleResult.success(
                    credential = result.credential,
                    profileIdHint = result.profileIdHint
                )
            }

            CompletableFuture.completedFuture(success())
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val channel = event.player.getChannel()
        authService.clear(channel)
    }
}
