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
import cn.starfallplain.sfplogin.api.connection.disconnectWithMessage
import cn.starfallplain.sfplogin.api.connection.getNettyChannel
import cn.starfallplain.sfplogin.api.event.profile.VerifyInitialGameProfileEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateApiHolder
import cn.starfallplain.sfplogin.auth.floodgate.service.FloodgateAuthService

class FloodgateGameProfileListener(
    private val authService: FloodgateAuthService,
    private val floodgateApiHolder: FloodgateApiHolder,
) {

    @Subscribe
    fun onVerifyInitialGameProfile(event: VerifyInitialGameProfileEvent) {
        debug(HyperZoneDebugType.FLOODGATE) {
            "onVerifyInitialGameProfile channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name} profileId=${event.gameProfile.id}"
        }

        val playerIp = event.connection.remoteAddress.address.hostAddress
        val resolvedIdentity = floodgateApiHolder.resolveLoginIdentity(event.gameProfile.name, playerIp) ?: return

        when (
            val result = authService.acceptInitialProfile(
                channel = event.connection.getNettyChannel(),
                userName = resolvedIdentity.userName,
                userUUID = resolvedIdentity.userUUID,
                xuid = resolvedIdentity.xuid
            )
        ) {
            FloodgateAuthService.VerifyResult.NotFloodgate -> {
                debug(HyperZoneDebugType.FLOODGATE) {
                    "onVerifyInitialGameProfile ignored channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name}: not floodgate"
                }
                return
            }
            is FloodgateAuthService.VerifyResult.Failed -> {
                debug(HyperZoneDebugType.FLOODGATE) {
                    "onVerifyInitialGameProfile failed channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name}"
                }
                event.connection.disconnectWithMessage(result.userMessage)
                return
            }
            FloodgateAuthService.VerifyResult.Accepted -> {
                event.pass = true
                debug(HyperZoneDebugType.FLOODGATE) {
                    "onVerifyInitialGameProfile accepted channel=${event.connection.getNettyChannel()} profileName=${event.gameProfile.name} pass=${event.pass}"
                }
            }
        }
    }
}

