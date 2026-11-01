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

package cn.starfallplain.sfplogin.api.player

import com.velocitypowered.api.proxy.Player
import io.netty.channel.Channel
import java.lang.reflect.Field

/**
 * 获取当前代理层 [Player] 连接对应的 Netty channel。
 *
 * 通过反射访问 `ConnectedPlayer.connection`（MinecraftConnection）内部字段，
 * 避免在 api 模块静态依赖 velocityProxy 内部类。
 */
fun Player.getChannel(): Channel {
    // 1. 在类层次中找到名为 "connection" 的字段
    val connectionField: Field = generateSequence<Class<*>>(javaClass) { it.superclass }
        .firstNotNullOfOrNull { cls ->
            runCatching { cls.getDeclaredField("connection").also { it.isAccessible = true } }.getOrNull()
        } ?: error("Cannot find 'connection' field in ${javaClass.name}")

    val connection = connectionField.get(this)

    // 2. 在 MinecraftConnection 的类层次中找到类型为 Channel 的字段
    val channelField: Field? = generateSequence<Class<*>>(connection.javaClass) { it.superclass }
        .firstNotNullOfOrNull { cls ->
            cls.declaredFields.firstOrNull { Channel::class.java.isAssignableFrom(it.type) }
                ?.also { it.isAccessible = true }
        }
    if (channelField != null) return channelField.get(connection) as Channel

    // 3. 回退：通过无参方法 channel() 获取
    return connection.javaClass.getMethod("channel").invoke(connection) as Channel
}
