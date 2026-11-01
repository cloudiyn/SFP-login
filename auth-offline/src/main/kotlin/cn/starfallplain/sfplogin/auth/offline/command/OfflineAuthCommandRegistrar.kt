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

package cn.starfallplain.sfplogin.auth.offline.command

import cn.starfallplain.sfplogin.api.command.ChannelPasswordCommands
import cn.starfallplain.sfplogin.api.command.HyperChatCommandManager
import cn.starfallplain.sfplogin.api.command.HyperChatCommandRegistration
import cn.starfallplain.sfplogin.auth.offline.service.OfflineAuthService

object OfflineAuthCommandRegistrar {
    /**
     * 注册离线模块命令。
     *
     * login / register / changepassword 使用 [ChannelPasswordCommands] 的共享 executor 与共享 brigadier：
     * 这些命令在多个认证模块中语义相同，最终由玩家所属渠道的密码门处理，
     * 因此无论哪个模块的同名注册最终生效，行为都保持一致。
     *
     * 共享 brigadier 统一采用"贪婪字符串透传"：
     * 这样玩家无论少输/多输参数，命令都能到达 gate，
     * 由 gate 给出用法提示，而不是被 brigadier 直接拒绝导致"输错了没反应"。
     */
    fun registerAll(
        commandManager: HyperChatCommandManager,
        authService: OfflineAuthService
    ) {
        commandManager.register(
            HyperChatCommandRegistration(
                name = "login",
                aliases = setOf("l"),
                executor = ChannelPasswordCommands.LoginExecutor(),
                brigadier = ChannelPasswordCommands.greedyArgumentsBrigadier()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "register",
                aliases = setOf("reg"),
                executor = ChannelPasswordCommands.RegisterExecutor(),
                brigadier = ChannelPasswordCommands.greedyArgumentsBrigadier()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "changepassword",
                aliases = setOf("cpass", "changepass"),
                executor = ChannelPasswordCommands.ChangePasswordExecutor(),
                brigadier = ChannelPasswordCommands.greedyArgumentsBrigadier()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "logout",
                executor = LogoutCommand(authService),
                brigadier = OfflineAuthBrigadierCommands.logout()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "email",
                aliases = setOf("mail"),
                executor = EmailCommand(authService),
                brigadier = OfflineAuthBrigadierCommands.email()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "totp",
                aliases = setOf("2fa"),
                executor = TotpCommand(authService),
                brigadier = OfflineAuthBrigadierCommands.totp()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "unregister",
                aliases = setOf("delaccount"),
                executor = UnregisterCommand(authService),
                brigadier = OfflineAuthBrigadierCommands.unregister()
            )
        )
    }
}
