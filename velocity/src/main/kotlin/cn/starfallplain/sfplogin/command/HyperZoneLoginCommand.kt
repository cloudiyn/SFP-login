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

package cn.starfallplain.sfplogin.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import cn.starfallplain.sfplogin.api.message.HyperZoneMessagePlaceholder
import cn.starfallplain.sfplogin.api.password.ChannelPasswordAdminService
import cn.starfallplain.sfplogin.api.profile.HyperZoneProfileServiceProvider
import cn.starfallplain.sfplogin.SFPLoginMain
import cn.starfallplain.sfplogin.manager.HyperZonePlayerManager
import cn.starfallplain.sfplogin.message.MessageKeys

class HyperZoneLoginCommand {
    fun createCommand(): BrigadierCommand {
        return BrigadierCommand(
            BrigadierCommand.literalArgumentBuilder("hzl")
                .executes { context ->
                    showUsage(context.source)
                    Command.SINGLE_SUCCESS
                }
                .then(
                    BrigadierCommand.literalArgumentBuilder("reload")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .executes { context ->
                            executeReload(context.source)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("re")
                        .executes { context ->
                            executeReAuth(context.source)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("uuid")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .executes { context ->
                            executeUuid(context.source)
                        }
                )
                .then(
                    BrigadierCommand.literalArgumentBuilder("password")
                        .requires { source -> source.hasPermission(ADMIN_PERMISSION) }
                        .executes { context ->
                            sendPasswordUsage(context.source)
                            Command.SINGLE_SUCCESS
                        }
                        .then(
                            BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes { context ->
                                    sendPasswordUsage(context.source)
                                    Command.SINGLE_SUCCESS
                                }
                                .then(
                                    BrigadierCommand.requiredArgumentBuilder(
                                        "newPassword",
                                        StringArgumentType.word()
                                    )
                                        .executes { context ->
                                            executeForcePassword(
                                                context.source,
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "newPassword")
                                            )
                                        }
                                )
                        )
                )
        )
    }

    private fun showUsage(sender: CommandSource) {
        val messages = SFPLoginMain.getInstance().messageService
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.send(sender, MessageKeys.HzlCommand.USAGE_RELOAD)
        }
        messages.send(sender, MessageKeys.HzlCommand.USAGE_RE)
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.send(sender, MessageKeys.HzlCommand.USAGE_UUID)
            messages.send(sender, MessageKeys.HzlCommand.USAGE_PASSWORD)
        }
    }

    private fun sendPasswordUsage(sender: CommandSource) {
        val messages = SFPLoginMain.getInstance().messageService
        messages.send(sender, MessageKeys.HzlCommand.PASSWORD_USAGE)
    }

    /**
     * 管理员按玩家名强制改密（跨渠道自动定位）。
     */
    private fun executeForcePassword(sender: CommandSource, playerName: String, newPassword: String): Int {
        val messages = SFPLoginMain.getInstance().messageService
        if (newPassword.isBlank()) {
            messages.send(sender, MessageKeys.HzlCommand.PASSWORD_USAGE)
            return Command.SINGLE_SUCCESS
        }

        val service = ChannelPasswordAdminService()
        val changed = runCatching {
            service.forceSetPasswordByName(playerName, newPassword)
        }.getOrElse { error ->
            SFPLoginMain.getInstance().logger.warn(
                "强制改密失败 player=$playerName: ${error.message}", error
            )
            null
        }

        when {
            changed == null -> messages.send(
                sender,
                MessageKeys.HzlCommand.PASSWORD_NOT_FOUND,
                HyperZoneMessagePlaceholder.text("name", playerName)
            )

            changed == 0 -> messages.send(
                sender,
                MessageKeys.HzlCommand.PASSWORD_SET_FAILED,
                HyperZoneMessagePlaceholder.text("name", playerName)
            )

            else -> {
                val channels = service.locateByName(playerName)
                    .map { it.channel.id }
                    .distinct()
                    .joinToString(", ")
                messages.send(
                    sender,
                    MessageKeys.HzlCommand.PASSWORD_SET_SUCCESS,
                    HyperZoneMessagePlaceholder.text("name", playerName),
                    HyperZoneMessagePlaceholder.text("count", changed),
                    HyperZoneMessagePlaceholder.text("channels", channels)
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeReload(sender: CommandSource): Int {
        val main = SFPLoginMain.getInstance()
        main.reloadRuntimeConfigs()
        main.messageService.send(sender, MessageKeys.HzlCommand.RELOADED)
        return Command.SINGLE_SUCCESS
    }

    private fun executeReAuth(sender: CommandSource): Int {
        val messages = SFPLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        messages.send(sender, MessageKeys.HzlCommand.REAUTH_START)
        SFPLoginMain.getInstance().triggerVServerReJoinForPlayer(sender)
        return Command.SINGLE_SUCCESS
    }

    private fun executeUuid(sender: CommandSource): Int {
        val messages = SFPLoginMain.getInstance().messageService
        if (sender !is Player) {
            messages.send(sender, MessageKeys.Common.ONLY_PLAYER)
            return Command.SINGLE_SUCCESS
        }

        val proxyPlayer = sender
        val hyperZonePlayer = HyperZonePlayerManager.getByPlayer(proxyPlayer)
        val profileService = HyperZoneProfileServiceProvider.get()
        val profile = profileService.getAttachedProfile(hyperZonePlayer)

        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_PROXY_PLAYER,
                HyperZoneMessagePlaceholder.text("name", proxyPlayer.username),
                HyperZoneMessagePlaceholder.text("uuid", proxyPlayer.uniqueId)
            )
        )
        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_CLIENT_ORIGINAL,
                HyperZoneMessagePlaceholder.text("name", hyperZonePlayer.clientOriginalName),
                HyperZoneMessagePlaceholder.text("uuid", hyperZonePlayer.clientOriginalUUID)
            )
        )
        sender.sendMessage(
            messages.render(
                sender,
                MessageKeys.HzlCommand.UUID_HYPER_PLAYER,
                    HyperZoneMessagePlaceholder.text("attached_profile", hyperZonePlayer.hasAttachedProfile()),
                    HyperZoneMessagePlaceholder.text("registration_name",
                            hyperZonePlayer.getSubmittedCredentials().firstOrNull()?.getRegistrationName()
                                ?: hyperZonePlayer.clientOriginalName),
                HyperZoneMessagePlaceholder.text(
                        "can_create_profile",
                        (profileService as? cn.starfallplain.sfplogin.profile.VelocityHyperZoneProfileService)
                            ?.canCreate(hyperZonePlayer.getSubmittedCredentials().firstOrNull()?.getRegistrationName()
                                ?: hyperZonePlayer.clientOriginalName) ?: false
                ),
                HyperZoneMessagePlaceholder.text("credentials", hyperZonePlayer.getSubmittedCredentials().size)
            )
        )
        if (profile != null) {
            sender.sendMessage(
                messages.render(
                    sender,
                    MessageKeys.HzlCommand.UUID_PROFILE,
                    HyperZoneMessagePlaceholder.text("id", profile.id),
                    HyperZoneMessagePlaceholder.text("name", profile.name),
                    HyperZoneMessagePlaceholder.text("uuid", profile.uuid)
                )
            )
        } else {
            messages.send(sender, MessageKeys.HzlCommand.UUID_PROFILE_NULL)
        }

        return Command.SINGLE_SUCCESS
    }

    companion object {
        private const val ADMIN_PERMISSION = "hyperzonelogin.admin"
    }
} 