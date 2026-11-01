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

package cn.starfallplain.sfplogin.vServer.command

import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.command.HyperChatCommandExecutor
import cn.starfallplain.sfplogin.api.command.HyperChatCommandInvocation
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.message.MessageKeys
import cn.starfallplain.sfplogin.sfpLoginMain

class ExitVServerCommand : HyperChatCommandExecutor {
    override fun execute(invocation: HyperChatCommandInvocation) {
        val messages = sfpLoginMain().messageService
        val source = invocation.source()
        if (source !is Player) {
            messages.send(source, MessageKeys.Common.ONLY_PLAYER)
            return
        }

        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(source)
        if (!hyperZonePlayer.hasAttachedProfile()) {
            messages.send(source, MessageKeys.Exit.STILL_IN_WAITING_AREA)
            return
        }

        val exited = sfpLoginMain().serverAdapter?.exitWaitingArea(source) == true
        if (!exited) {
            messages.send(source, MessageKeys.Exit.NOT_IN_WAITING_AREA)
            return
        }

        messages.send(source, MessageKeys.Exit.ATTEMPTED)
    }

    override fun hasPermission(invocation: HyperChatCommandInvocation): Boolean {
        return true
    }
}