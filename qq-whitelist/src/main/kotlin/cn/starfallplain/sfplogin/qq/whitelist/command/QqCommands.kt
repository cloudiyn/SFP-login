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

package cn.starfallplain.sfplogin.qq.whitelist.command

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.HyperZoneApi
import cn.starfallplain.sfplogin.api.command.HyperChatCommandExecutor
import cn.starfallplain.sfplogin.api.command.HyperChatCommandInvocation
import cn.starfallplain.sfplogin.api.command.HyperChatCommandRegistration
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.qq.whitelist.config.QqWhitelistConfig
import cn.starfallplain.sfplogin.qq.whitelist.message.QqGroupMessages
import cn.starfallplain.sfplogin.qq.whitelist.service.QqBindingService
import cn.starfallplain.sfplogin.qq.whitelist.service.QqUnbindOutcome
import net.kyori.adventure.text.Component

/**
 * 玩家侧与管理员侧聊天命令。
 *
 * - `/qq`：仅游戏区可用，查看本人绑定 / 自助解绑；
 * - `/qqadmin`：管理员强制解绑、重载文案。
 */
object QqCommands {
    private const val ADMIN_PERMISSION = "hyperzonelogin.admin"

    fun register(
        api: HyperZoneApi,
        config: QqWhitelistConfig,
        service: QqBindingService,
        messages: QqGroupMessages
    ) {
        api.chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "qq",
                executor = object : HyperChatCommandExecutor {
                    override fun execute(invocation: HyperChatCommandInvocation) {
                        val player = invocation.source() as? Player
                        if (player == null) {
                            invocation.source().sendMessage(Component.text(messages.playerText(null, "player.only-player")))
                            return
                        }
                        val hyperPlayer = runCatching { api.hyperZonePlayers.getByPlayer(player) }.getOrNull()
                        val profile = hyperPlayer?.let { runCatching { HyperZoneProfileServiceProvider.get().getAttachedProfile(it) }.getOrNull() }
                        if (profile == null) {
                            player.sendMessage(Component.text(messages.playerText(localeOf(player), "player.command-only-game")))
                            return
                        }
                        val args = invocation.arguments()
                        if (args.isEmpty()) {
                            sendStatus(player, service, messages, profile)
                            return
                        }
                        when (args[0].lowercase()) {
                            "unbind", "解绑" -> {
                                when (val outcome = service.unbindByProfile(profile.id)) {
                                    is QqUnbindOutcome.Ok -> player.sendMessage(
                                        Component.text(
                                            messages.playerText(
                                                localeOf(player),
                                                "player.unbind-success",
                                                "player" to outcome.binding.playerName
                                            )
                                        )
                                    )

                                    is QqUnbindOutcome.NotFound -> player.sendMessage(
                                        Component.text(messages.playerText(localeOf(player), "player.unbind-not-found"))
                                    )
                                }
                            }

                            else -> player.sendMessage(
                                Component.text(
                                    messages.playerText(
                                        localeOf(player),
                                        "player.usage",
                                        "keyword" to config.commands.bindKeyword
                                    )
                                )
                            )
                        }
                    }
                }
            )
        )

        api.chatCommandManager.register(
            HyperChatCommandRegistration(
                name = "qqadmin",
                executor = object : HyperChatCommandExecutor {
                    override fun execute(invocation: HyperChatCommandInvocation) {
                        val source = invocation.source()
                        val args = invocation.arguments()
                        if (args.isEmpty()) {
                            source.sendMessage(Component.text(messages.playerText(localeOf(source), "admin.usage")))
                            return
                        }
                        when (args[0].lowercase()) {
                            "unbind", "解绑" -> {
                                val target = args.getOrNull(1)
                                if (target.isNullOrBlank()) {
                                    source.sendMessage(Component.text(messages.playerText(localeOf(source), "admin.usage")))
                                    return
                                }
                                when (val outcome = service.adminUnbindByName(target)) {
                                    is QqUnbindOutcome.Ok -> source.sendMessage(
                                        Component.text(
                                            messages.playerText(
                                                localeOf(source),
                                                "admin.unbind-success",
                                                "player" to outcome.binding.playerName,
                                                "qq" to outcome.binding.qqId.toString()
                                            )
                                        )
                                    )

                                    is QqUnbindOutcome.NotFound -> source.sendMessage(
                                        Component.text(
                                            messages.playerText(localeOf(source), "admin.unbind-not-found", "player" to target)
                                        )
                                    )
                                }
                            }

                            "reload", "重载" -> {
                                messages.reload()
                                source.sendMessage(Component.text(messages.playerText(localeOf(source), "admin.reload-done")))
                            }

                            else -> source.sendMessage(Component.text(messages.playerText(localeOf(source), "admin.usage")))
                        }
                    }

                    override fun hasPermission(invocation: HyperChatCommandInvocation): Boolean {
                        return invocation.source().hasPermission(ADMIN_PERMISSION)
                    }
                }
            )
        )
    }

    private fun sendStatus(
        player: Player,
        service: QqBindingService,
        messages: QqGroupMessages,
        profile: cn.starfallplain.sfplogin.api.db.Profile
    ) {
        val binding = service.bindingOf(profile.id)
        val locale = localeOf(player)
        if (binding == null) {
            player.sendMessage(Component.text(messages.playerText(locale, "player.status-unbound")))
            return
        }
        player.sendMessage(
            Component.text(
                messages.playerText(
                    locale,
                    "player.status-bound",
                    "qq" to binding.qqId.toString(),
                    "player" to binding.playerName
                )
            )
        )
    }

    private fun localeOf(player: Player): String? {
        return runCatching { player.effectiveLocale?.toLanguageTag() }.getOrNull()
    }

    private fun localeOf(source: CommandSource): String? {
        return (source as? Player)?.let(::localeOf)
    }
}
