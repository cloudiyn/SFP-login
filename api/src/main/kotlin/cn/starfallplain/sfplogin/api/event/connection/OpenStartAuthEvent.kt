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
import com.velocitypowered.api.util.GameProfile
import io.netty.channel.Channel
import net.kyori.adventure.text.Component
import java.util.*

/**
 * 在开始执行在线认证或类似外部鉴权前触发。先于OpenStartAuthEvent。
 *
 * Velocity typically fires this event asynchronously and does not wait for a response. However,
 * it will wait for all [DisconnectEvent]s for every player on the proxy to fire
 * successfully before the proxy shuts down. This event is the sole exception to the
 * [AwaitingEvent] contract.
 *
 * @property userName 当前参与认证的用户名
 * @property userUUID 当前参与认证的 UUID
 * @property serverId 本次会话使用的认证 serverId
 * @property playerIp 玩家 IP 字符串
 * @property channel 当前登录连接对应的 Netty channel
 * @property isOnline 进入该阶段前判定出的在线模式标记
 */
@AwaitingEvent
class OpenStartAuthEvent(
    val userName: String,
    val userUUID: UUID,
    val serverId: String,
    val playerIp: String,
    val channel: Channel,
    val isOnline: Boolean
) {
    /**
     * 认证阶段产出的初始 [GameProfile]，可由监听器写入。
     */
    var gameProfile: GameProfile? = null

    /**
     * 当前事件链是否允许继续后续认证流程。
     */
    var allow: Boolean = true

    /**
     * 当 [allow] 为 `false` 时展示给玩家的断开消息。
     */
    var disconnectMessage: Component = Component.text("未知下层不允许原因")
}
