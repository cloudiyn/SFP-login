/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
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

package icu.h2l.login.auth.offline.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import icu.h2l.api.command.HyperChatBrigadierContext
import icu.h2l.api.command.HyperChatBrigadierRegistration

object OfflineAuthBrigadierCommands {
    fun login(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(loginPassword(context))
                .then(loginAs(context))
        }
    }

    /**
     * `/login <password> [code]`。
     *
     * 每个中间节点都要挂 `.executes` 透传已输入的参数，
     * 否则玩家只输一部分时会被 brigadier 直接拒绝，看不到用法提示。
     */
    private fun loginPassword(context: HyperChatBrigadierContext) =
        word("password")
            .executes { commandContext ->
                context.execute(
                    commandContext.source,
                    args = arrayOf(
                        StringArgumentType.getString(commandContext, "password")
                    )
                )
            }
            .then(
                word("code")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                StringArgumentType.getString(commandContext, "password"),
                                StringArgumentType.getString(commandContext, "code")
                            )
                        )
                    }
            )

    /**
     * `/login as <username> <password> [code]`。
     *
     * 与 [loginPassword] 同理，中间节点必须挂 `.executes` 透传已输入参数，
     * 否则玩家少输参数时 brigadier 直接拒绝执行，看不到用法提示。
     */
    private fun loginAs(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("as")
            .executes { commandContext ->
                context.execute(
                    commandContext.source,
                    args = arrayOf("as")
                )
            }
            .then(
                word("username")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "as",
                                StringArgumentType.getString(commandContext, "username")
                            )
                        )
                    }
                    .then(
                        word("password")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        "as",
                                        StringArgumentType.getString(commandContext, "username"),
                                        StringArgumentType.getString(commandContext, "password")
                                    )
                                )
                            }
                            .then(
                                word("code")
                                    .executes { commandContext ->
                                        context.execute(
                                            commandContext.source,
                                            args = arrayOf(
                                                "as",
                                                StringArgumentType.getString(commandContext, "username"),
                                                StringArgumentType.getString(commandContext, "password"),
                                                StringArgumentType.getString(commandContext, "code")
                                            )
                                        )
                                    }
                            )
                    )
            )

    fun register(): HyperChatBrigadierRegistration {
        return doublePasswordCommand("register", "password", "confirmPassword")
    }

    fun changePassword(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    word("oldPassword")
                        .executes { commandContext ->
                            context.execute(
                                commandContext.source,
                                args = arrayOf(
                                    StringArgumentType.getString(commandContext, "oldPassword")
                                )
                            )
                        }
                        .then(
                            word("newPassword")
                                .executes { commandContext ->
                                    context.execute(
                                        commandContext.source,
                                        args = arrayOf(
                                            StringArgumentType.getString(commandContext, "oldPassword"),
                                            StringArgumentType.getString(commandContext, "newPassword")
                                        )
                                    )
                                }
                        )
                )
        }
    }

    fun logout(): HyperChatBrigadierRegistration {
        return plainCommand("logout")
    }

    fun unregister(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    word("password")
                        .executes { commandContext ->
                            context.execute(
                                commandContext.source,
                                args = arrayOf(
                                    StringArgumentType.getString(commandContext, "password")
                                )
                            )
                        }
                )
        }
    }

    fun email(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(emailAdd(context))
                .then(emailChange(context))
                .then(emailShow(context))
                .then(emailRecovery(context))
                .then(emailCode(context))
                .then(emailSetPassword(context))
        }
    }

    fun totp(): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal()
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(totpAdd(context, "add"))
                .then(totpAdd(context, "enable"))
                .then(totpConfirm(context))
                .then(totpRemove(context, "remove"))
                .then(totpRemove(context, "disable"))
        }
    }

    private fun emailAdd(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("add")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("add"))
            }
            .then(
                word("currentPassword")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "add",
                                StringArgumentType.getString(commandContext, "currentPassword")
                            )
                        )
                    }
                    .then(
                        word("email")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        "add",
                                        StringArgumentType.getString(commandContext, "currentPassword"),
                                        StringArgumentType.getString(commandContext, "email")
                                    )
                                )
                            }
                            .then(
                                word("confirmEmail")
                                    .executes { commandContext ->
                                        context.execute(
                                            commandContext.source,
                                            args = arrayOf(
                                                "add",
                                                StringArgumentType.getString(commandContext, "currentPassword"),
                                                StringArgumentType.getString(commandContext, "email"),
                                                StringArgumentType.getString(commandContext, "confirmEmail")
                                            )
                                        )
                                    }
                            )
                    )
            )

    private fun emailChange(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("change")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("change"))
            }
            .then(
                word("currentPassword")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "change",
                                StringArgumentType.getString(commandContext, "currentPassword")
                            )
                        )
                    }
                    .then(
                        word("oldEmail")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        "change",
                                        StringArgumentType.getString(commandContext, "currentPassword"),
                                        StringArgumentType.getString(commandContext, "oldEmail")
                                    )
                                )
                            }
                            .then(
                                word("newEmail")
                                    .executes { commandContext ->
                                        context.execute(
                                            commandContext.source,
                                            args = arrayOf(
                                                "change",
                                                StringArgumentType.getString(commandContext, "currentPassword"),
                                                StringArgumentType.getString(commandContext, "oldEmail"),
                                                StringArgumentType.getString(commandContext, "newEmail")
                                            )
                                        )
                                    }
                            )
                    )
            )

    private fun emailShow(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("show")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("show"))
            }
            .then(
                word("currentPassword")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "show",
                                StringArgumentType.getString(commandContext, "currentPassword")
                            )
                        )
                    }
            )

    private fun emailRecovery(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("recovery")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("recovery"))
            }
            .then(
                word("email")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "recovery",
                                StringArgumentType.getString(commandContext, "email")
                            )
                        )
                    }
            )

    private fun emailCode(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("code")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("code"))
            }
            .then(
                word("verificationCode")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "code",
                                StringArgumentType.getString(commandContext, "verificationCode")
                            )
                        )
                    }
            )

    private fun emailSetPassword(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("setpassword")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("setpassword"))
            }
            .then(
                word("newPassword")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "setpassword",
                                StringArgumentType.getString(commandContext, "newPassword")
                            )
                        )
                    }
                    .then(
                        word("confirmPassword")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        "setpassword",
                                        StringArgumentType.getString(commandContext, "newPassword"),
                                        StringArgumentType.getString(commandContext, "confirmPassword")
                                    )
                                )
                            }
                    )
            )

    private fun totpAdd(context: HyperChatBrigadierContext, literal: String) =
        BrigadierCommand.literalArgumentBuilder(literal)
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf(literal))
            }
            .then(
                word("password")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                literal,
                                StringArgumentType.getString(commandContext, "password")
                            )
                        )
                    }
            )

    private fun totpConfirm(context: HyperChatBrigadierContext) =
        BrigadierCommand.literalArgumentBuilder("confirm")
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf("confirm"))
            }
            .then(
                word("code")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                "confirm",
                                StringArgumentType.getString(commandContext, "code")
                            )
                        )
                    }
            )

    private fun totpRemove(context: HyperChatBrigadierContext, literal: String) =
        BrigadierCommand.literalArgumentBuilder(literal)
            .executes { commandContext ->
                context.execute(commandContext.source, args = arrayOf(literal))
            }
            .then(
                word("password")
                    .executes { commandContext ->
                        context.execute(
                            commandContext.source,
                            args = arrayOf(
                                literal,
                                StringArgumentType.getString(commandContext, "password")
                            )
                        )
                    }
                    .then(
                        word("code")
                            .executes { commandContext ->
                                context.execute(
                                    commandContext.source,
                                    args = arrayOf(
                                        literal,
                                        StringArgumentType.getString(commandContext, "password"),
                                        StringArgumentType.getString(commandContext, "code")
                                    )
                                )
                            }
                    )
            )

    /**
     * 双密码参数命令（如 `/register <password> <confirmPassword>`）。
     *
     * 注意：每个参数节点都必须挂 `.executes`，否则玩家只输入一部分参数时
     * brigadier 会因为"找不到可执行节点"直接拒绝执行，命令根本到不了 gate，
     * 玩家也就看不到任何用法提示（表现为"输错了没反应"）。
     * 这里把已经输入的部分参数原样透传，由 gate 负责给出 usage / 不匹配提示。
     */
    private fun doublePasswordCommand(
        name: String,
        firstArgumentName: String,
        secondArgumentName: String
    ): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal(name)
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
                .then(
                    word(firstArgumentName)
                        .executes { commandContext ->
                            context.execute(
                                commandContext.source,
                                args = arrayOf(
                                    StringArgumentType.getString(commandContext, firstArgumentName)
                                )
                            )
                        }
                        .then(
                            word(secondArgumentName)
                                .executes { commandContext ->
                                    context.execute(
                                        commandContext.source,
                                        args = arrayOf(
                                            StringArgumentType.getString(commandContext, firstArgumentName),
                                            StringArgumentType.getString(commandContext, secondArgumentName)
                                        )
                                    )
                                }
                        )
                )
        }
    }

    private fun word(name: String): RequiredArgumentBuilder<CommandSource, String> {
        return BrigadierCommand.requiredArgumentBuilder(name, StringArgumentType.word())
    }

    private fun plainCommand(name: String): HyperChatBrigadierRegistration {
        return HyperChatBrigadierRegistration { context ->
            context.literal(name)
                .executes { commandContext ->
                    context.execute(commandContext.source)
                }
        }
    }
}
