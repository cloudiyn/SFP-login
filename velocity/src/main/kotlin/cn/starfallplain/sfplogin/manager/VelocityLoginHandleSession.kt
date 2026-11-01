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

import cn.starfallplain.sfplogin.api.event.auth.LoginHandleSession
import cn.starfallplain.sfplogin.api.profile.ChannelPasswordGateRegistry
import cn.starfallplain.sfplogin.player.VelocityHyperZonePlayer
import net.kyori.adventure.text.Component

/**
 * 暴露给认证模块的会话视图。
 *
 * 认证模块只能看到"原始名、是否已绑定档案、是否在等玩家输入、怎么发消息"，看不到
 * proxy 连接细节，这样模块代码无法绕过登录编排直接操作连接。
 */
internal class VelocityLoginHandleSession(
    private val player: VelocityHyperZonePlayer,
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

    override fun sendMessage(message: Component) {
        player.sendMessage(message)
    }
}
