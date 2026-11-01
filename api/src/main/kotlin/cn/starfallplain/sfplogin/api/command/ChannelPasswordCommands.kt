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

package cn.starfallplain.sfplogin.api.command

import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.HyperZoneApiProvider
import cn.starfallplain.sfplogin.api.message.HyperZoneMessageServiceProvider
import cn.starfallplain.sfplogin.api.player.HyperZonePlayer
import cn.starfallplain.sfplogin.api.profile.ChannelPasswordGate
import cn.starfallplain.sfplogin.api.profile.ChannelPasswordGateRegistry
import net.kyori.adventure.text.Component

/**
 * 渠道密码共享命令实现。
 *
 * login / register / changepassword 三个命令在不同认证模块中语义一致：
 * 命令入口把执行请求路由到玩家当前所属认证渠道的 [ChannelPasswordGate]。
 *
 * 由于底层命令管理器按“后注册覆盖”的语义处理同名命令，
 * 各模块注册的这些命令都应使用本对象的共享 executor，
 * 这样无论最终哪个模块的注册生效，命令行为都保持一致。
 */
object ChannelPasswordCommands {

    private const val KEY_ONLY_PLAYER = "common.only-player"
    private const val KEY_NO_GATE = "common.no-password-gate"

    /** /login 命令的共享 executor。 */
    class LoginExecutor : HyperChatCommandExecutor {
        override fun execute(invocation: HyperChatCommandInvocation) {
            dispatch(invocation) { gate, player ->
                gate.handleLogin(player, invocation.arguments())
            }
        }
    }

    /** /register 命令的共享 executor。 */
    class RegisterExecutor : HyperChatCommandExecutor {
        override fun execute(invocation: HyperChatCommandInvocation) {
            dispatch(invocation) { gate, player ->
                gate.handleRegister(player, invocation.arguments())
            }
        }
    }

    /** /changepassword 命令的共享 executor。 */
    class ChangePasswordExecutor : HyperChatCommandExecutor {
        override fun execute(invocation: HyperChatCommandInvocation) {
            dispatch(invocation) { gate, player ->
                gate.handleChangePassword(player, invocation.arguments())
            }
        }
    }

    /**
     * 把三个共享密码命令注册到命令管理器。
     *
     * 每个启用了渠道密码的模块都应调用一次，确保即使其他认证模块未启用，
     * 玩家也能在等待区执行 /login、/register、/changepassword。
     */
    fun registerPasswordCommands(commandManager: HyperChatCommandManager) {
        commandManager.register(
            HyperChatCommandRegistration(
                name = "login",
                aliases = setOf("l"),
                executor = LoginExecutor(),
                brigadier = greedyArgumentsBrigadier()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "register",
                aliases = setOf("reg"),
                executor = RegisterExecutor(),
                brigadier = greedyArgumentsBrigadier()
            )
        )
        commandManager.register(
            HyperChatCommandRegistration(
                name = "changepassword",
                aliases = setOf("cpass", "changepass"),
                executor = ChangePasswordExecutor(),
                brigadier = greedyArgumentsBrigadier()
            )
        )
    }

    /**
     * 通用参数的 Brigadier 命令树：根命令 + 贪婪字符串参数。
     *
     * 各渠道的特殊参数格式（如离线验证的 /login as <用户名> <密码>）
     * 由各 gate 在 handleXxx 中自行解析，命令树只负责透传原始参数。
     */
    fun greedyArgumentsBrigadier(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(context.greedyArguments())
        }
    }

    private inline fun dispatch(
        invocation: HyperChatCommandInvocation,
        handler: (ChannelPasswordGate, Player) -> Component
    ) {
        val source = invocation.source()
        if (source !is Player) {
            source.sendMessage(renderMessage(KEY_ONLY_PLAYER, "该命令只能由玩家执行。"))
            return
        }

        val hyperPlayer = resolveHyperPlayer(source)
        val resolvedGate = if (hyperPlayer != null) {
            ChannelPasswordGateRegistry.resolveGate(hyperPlayer)
        } else {
            null
        }
        if (resolvedGate == null) {
            source.sendMessage(renderMessage(KEY_NO_GATE, "当前没有可用的认证模块处理该命令。"))
            return
        }

        source.sendMessage(handler(resolvedGate, source))
    }

    private fun resolveHyperPlayer(player: Player): HyperZonePlayer? {
        return runCatching {
            HyperZoneApiProvider.getOrNull()?.hyperZonePlayers?.getByPlayer(player)
        }.getOrNull()
    }

    private fun renderMessage(key: String, fallback: String): Component {
        val service = HyperZoneMessageServiceProvider.getOrNull() ?: return Component.text(fallback)
        if (!service.hasKey(key)) {
            return Component.text(fallback)
        }
        return service.render(key)
    }
}
