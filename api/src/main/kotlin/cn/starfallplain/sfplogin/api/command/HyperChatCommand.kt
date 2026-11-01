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

import com.velocitypowered.api.command.CommandSource

/**
 * 聊天命令执行时暴露给实现方的通用上下文。
 */
interface HyperChatCommandInvocation {
    /**
     * 当前命令发送者。
     */
    fun source(): CommandSource

    /**
     * 已拆分后的命令参数。
     */
    fun arguments(): Array<String>

    /**
     * 本次触发命令所使用的别名。
     */
    fun alias(): String
}

/**
 * 基于聊天文本解析的命令执行器。
 */
interface HyperChatCommandExecutor {
    /**
     * 执行命令主体逻辑。
     */
    fun execute(invocation: HyperChatCommandInvocation)

    /**
     * 判断当前调用上下文是否具备执行权限。
     */
    fun hasPermission(invocation: HyperChatCommandInvocation): Boolean = true
}

/**
 * 一条 HyperZone 聊天命令的完整注册定义。
 *
 * @property name 主命令名
 * @property aliases 可选别名集合
 * @property executor 聊天命令执行器
 * @property brigadier 可选的 Brigadier 树定义
 */
data class HyperChatCommandRegistration(
    val name: String,
    val aliases: Set<String> = emptySet(),
    val executor: HyperChatCommandExecutor,
    val brigadier: HyperChatBrigadierRegistration? = null
)

/**
 * HyperZone 聊天命令的注册与调度入口。
 */
interface HyperChatCommandManager {
    /**
     * 注册一条命令。
     */
    fun register(registration: HyperChatCommandRegistration)

    /**
     * 按主命令名注销一条命令。
     */
    fun unregister(name: String)

    /**
     * 直接执行一段聊天文本并返回是否命中命令。
     */
    fun executeChat(source: CommandSource, chat: String): Boolean

    /**
     * 获取当前已注册的全部命令定义。
     */
    fun getRegisteredCommands(): Collection<HyperChatCommandRegistration>
}

/**
 * 为其他 API 暴露 [HyperChatCommandManager] 的 provider 接口。
 */
interface HyperChatCommandManagerProvider {
    /**
     * 当前运行时的聊天命令管理器。
     */
    val chatCommandManager: HyperChatCommandManager
}
