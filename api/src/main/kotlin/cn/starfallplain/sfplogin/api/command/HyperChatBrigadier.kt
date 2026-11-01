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

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource

/**
 * 为 [HyperChatCommandRegistration] 提供 Brigadier 命令树的工厂接口。
 */
fun interface HyperChatBrigadierRegistration {
    /**
     * 根据当前命令注册信息创建 Brigadier 根节点。
     */
    fun create(context: HyperChatBrigadierContext): LiteralArgumentBuilder<CommandSource>
}

/**
 * 构建 Brigadier 命令树时可复用的上下文工具。
 *
 * @property registration 当前正在构建的命令注册定义
 */
class HyperChatBrigadierContext(
    val registration: HyperChatCommandRegistration,
    private val visibility: (CommandSource) -> Boolean,
    private val executor: (CommandSource, String, Array<String>) -> Int
) {
    /**
     * 判断给定发送者是否可见当前命令。
     */
    fun isVisibleTo(source: CommandSource): Boolean = visibility(source)

    /**
     * 创建一个 literal 根节点，默认使用 [registration] 的主命令名。
     */
    fun literal(name: String = registration.name): LiteralArgumentBuilder<CommandSource> {
        return BrigadierCommand.literalArgumentBuilder(name)
            .requires(visibility)
    }

    /**
     * 直接调用底层命令执行器。
     */
    fun execute(
        source: CommandSource,
        alias: String = registration.name,
        args: Array<String> = emptyArray()
    ): Int {
        return executor(source, alias, args)
    }

    /**
     * 用原始字符串参数执行命令，并自动按空白拆分参数。
     */
    fun executeGreedy(
        source: CommandSource,
        rawArguments: String,
        alias: String = registration.name
    ): Int {
        return execute(source, alias, splitArguments(rawArguments))
    }

    /**
     * 创建一个 greedy string 参数节点，并在执行时回调到 [executeGreedy]。
     */
    fun greedyArguments(
        argumentName: String = "arguments",
        alias: String = registration.name
    ) = BrigadierCommand.requiredArgumentBuilder(argumentName, StringArgumentType.greedyString())
        .executes { context ->
            executeGreedy(
                context.source,
                StringArgumentType.getString(context, argumentName),
                alias
            )
        }


    /**
     * 按连续空白拆分命令参数。
     */
    fun splitArguments(rawArguments: String): Array<String> {
        return rawArguments.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .toTypedArray()
    }
}

