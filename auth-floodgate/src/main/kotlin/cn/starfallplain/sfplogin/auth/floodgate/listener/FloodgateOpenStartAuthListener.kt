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
import cn.starfallplain.sfplogin.api.event.connection.OpenStartAuthEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateApiHolder
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateAuthService

class FloodgateOpenStartAuthListener(
    private val authService: FloodgateAuthService,
    private val floodgateApiHolder: FloodgateApiHolder,
) {
    @Subscribe(priority = Short.MAX_VALUE)
    fun onOpenStartAuth(event: OpenStartAuthEvent) {
        val resolvedIdentity = floodgateApiHolder.resolveLoginIdentity(event.userName, event.playerIp) ?: return
        debug(HyperZoneDebugType.FLOODGATE) {
            "onOpenStartAuth resolved channel=${event.channel} loginName=${event.userName} playerIp=${event.playerIp} floodgateName=${resolvedIdentity.userName} floodgateUuid=${resolvedIdentity.userUUID} xuid=${resolvedIdentity.xuid}"
        }

        when (
            val result = authService.acceptInitialProfile(
                channel = event.channel,
                userName = resolvedIdentity.userName,
                userUUID = resolvedIdentity.userUUID,
                xuid = resolvedIdentity.xuid,
            )
        ) {
            FloodgateAuthService.VerifyResult.NotFloodgate -> {
                debug(HyperZoneDebugType.FLOODGATE) {
                    "onOpenStartAuth ignored channel=${event.channel} loginName=${event.userName}: resolved player is not floodgate"
                }
            }
            is FloodgateAuthService.VerifyResult.Failed -> {
                event.allow = false
                event.disconnectMessage = result.userMessage
                debug(HyperZoneDebugType.FLOODGATE) {
                    "onOpenStartAuth failed channel=${event.channel} loginName=${event.userName}"
                }
            }
            FloodgateAuthService.VerifyResult.Accepted -> {
                debug(HyperZoneDebugType.FLOODGATE) {
                    "onOpenStartAuth accepted channel=${event.channel} loginName=${event.userName} floodgateName=${resolvedIdentity.userName}"
                }
            }
        }
    }
}

