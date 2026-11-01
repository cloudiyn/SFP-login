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

package cn.starfallplain.sfplogin.api.event.connection

import com.velocitypowered.api.event.annotation.AwaitingEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.*

/**
 * 在代理层进入 PreLogin 阶段后触发，用于决定连接应走在线还是离线登录链路。
 *
 * Velocity typically fires this event asynchronously and does not wait for a response. However,
 * it will wait for all [DisconnectEvent]s for every player on the proxy to fire
 * successfully before the proxy shuts down. This event is the sole exception to the
 * [AwaitingEvent] contract.
 *
 * @property uuid 客户端上报的初始 UUID，可能为 null（此时应以在线模式处理）
 * @property userName 客户端上报的初始用户名
 * @property host 玩家连接时使用的目标主机名
 * @property playerIp 玩家 IP 字符串
 * @property channel 当前登录连接对应的 Netty channel
 */
@AwaitingEvent
class OpenPreLoginEvent(
    val uuid: UUID?,
    val userName: String,
    val host: String,
    val playerIp: String,
    val channel: Channel
) {
    /**
     * 当前连接最终是否应视为在线模式玩家。
     */
    var isOnline: Boolean = true

    /**
     * 当前事件链是否允许连接继续后续登录流程。
     */
    var allow: Boolean = true

    /**
     * 当 [allow] 为 `false` 时展示给玩家的断开消息。
     */
    var disconnectMessage: Component = Component.text("未知的登录前置拦截原因")
}
