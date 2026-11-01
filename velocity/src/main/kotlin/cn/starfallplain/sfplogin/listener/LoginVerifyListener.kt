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
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import cn.starfallplain.sfplogin.api.connection.disconnectWithMessage
import cn.starfallplain.sfplogin.api.connection.getNettyChannel
import cn.starfallplain.sfplogin.api.event.profile.VerifyInitialGameProfileEvent
import cn.starfallplain.sfplogin.api.log.HyperZoneDebugType
import cn.starfallplain.sfplogin.api.log.debug
import cn.starfallplain.sfplogin.SFPLoginMain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import cn.starfallplain.sfplogin.sfpLoginMain
import cn.starfallplain.sfplogin.manager.LoginRewriteEvents
import cn.starfallplain.sfplogin.message.MessageKeys

class LoginVerifyListener {

    companion object {
        const val PLUGIN_CONFLICT_MESSAGE = "登录失败：检测到插件冲突。"
    }

    @Subscribe
    fun onGameProfileRequestEvent(event: GameProfileRequestEvent) {
        val incomingProfile = event.gameProfile
        val incomingName = incomingProfile.name
        val verifyEvent = VerifyInitialGameProfileEvent(event.connection, incomingProfile)
        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "loginVerify.onGameProfileRequest channel=${event.connection.getNettyChannel()} incomingName=${incomingProfile.name} incomingUuid=${incomingProfile.id}"
        }

        fun disconnectWithError(logMessage: String, messageKey: String, fallback: String) {
            sfpLoginMain().logger.error(logMessage)
            val message = runCatching {
                sfpLoginMain().messageService.render(null as com.velocitypowered.api.command.CommandSource?, messageKey)
            }.getOrNull() ?: Component.text(fallback, NamedTextColor.RED)
            event.connection.disconnectWithMessage(message)
        }

        // 有界等待：卡住的模块最多让本次校验按“不改写”降级，不能拖死登录线程。
        val outcome = LoginRewriteEvents.fire(
            sfpLoginMain().proxy,
            verifyEvent,
            label = "verify-initial-game-profile",
        )
        if (outcome != LoginRewriteEvents.Outcome.COMPLETED) {
            debug(HyperZoneDebugType.OUTPRE_TRACE) {
                "loginVerify.degraded outcome=$outcome channel=${event.connection.getNettyChannel()}"
            }
        }

        debug(HyperZoneDebugType.OUTPRE_TRACE) {
            "loginVerify.afterVerifyEvent channel=${event.connection.getNettyChannel()} incomingName=${incomingProfile.name} pass=${verifyEvent.pass}"
        }

        if (verifyEvent.pass) {
            return
        }else{
            disconnectWithError(
                "GameProfile 名称校验失败：$incomingName，疑似插件冲突",
                MessageKeys.Common.PLUGIN_CONFLICT,
                PLUGIN_CONFLICT_MESSAGE,
            )
            return
        }
    }
}

