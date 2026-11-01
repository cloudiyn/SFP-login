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

package cn.starfallplain.sfplogin.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.net.InetSocketAddress

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
@ConfigSerializable
data class VServerConfig(
    // 登录服实现模式。2.0.0 起只保留 outpre；旧配置里的 backend 会在启动时自动迁移。
    @Comment("config.vserver.mode")
    val mode: String = "outpre",


    // 记住认证时收到的服务器跳转请求
    @Comment("config.vserver.remember-requested-server")
    val rememberRequestedServerDuringAuth: Boolean = true,

    val outpre: OutpreConfig = OutpreConfig()
) {
    @Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
    @ConfigSerializable
    data class OutpreConfig(
        // 认证服的逻辑名，仅用于日志/状态标识；不需要在 Velocity 的 servers 中注册。
        // 如果使用 ViaVersion，你需要在 Velocity 的 servers 中添加注册条目，如 outpre-auth = "127.0.0.1:30066"，但不需要将其配置到 try 队列。
        @Comment("config.vserver.outpre.auth-label")
        val authLabel: String = "outpre-auth",

        // 认证服的直连 Host
        @Comment("config.vserver.outpre.auth-host")
        val authHost: String = "127.0.0.1",

        // 认证服的直连 Port
        @Comment("config.vserver.outpre.auth-port")
        val authPort: Int = 30066,

        // 转接给认证服时，在连接握手中对后端暴露的 Host；留空时使用 authHost
        @Comment("config.vserver.outpre.presented-host")
        val presentedHost: String = "",

        // 转接给认证服时，在连接握手中对后端暴露的 Port；<=0 时使用 authPort
        @Comment("config.vserver.outpre.presented-port")
        val presentedPort: Int = -1,

        // 转接给认证服时，在连接握手中对后端暴露的玩家源 IP；留空时使用玩家真实 IP
        @Comment("config.vserver.outpre.presented-player-ip")
        val presentedPlayerIp: String = "",

        // 对在线模式玩家（Velocity 已完成 Mojang 验证）启用直通（struck）模式：
        // 玩家连入时阻塞等待认证完成，验证通过后直接进入游戏，跳过等待区。
        @Comment("config.vserver.outpre.struck-online-mode")
        val struckOnlineMode: Boolean = true,
    ) {
        fun resolveOutpreAuthAddress(): InetSocketAddress? {
            val host = authHost.trim()
            if (host.isBlank()) return null
            if (authPort !in 1..65535) return null
            return InetSocketAddress.createUnresolved(host, authPort)
        }

        fun outpreAuthTargetLabel(): String {
            return authLabel.trim().ifBlank { "${authHost.trim()}:$authPort" }
        }

        fun resolveOutprePresentedHost(authAddress: InetSocketAddress): String {
            return presentedHost.trim().ifBlank { authAddress.hostString }
        }

        fun resolveOutprePresentedPort(authAddress: InetSocketAddress): Int {
            return presentedPort.takeIf { it in 1..65535 } ?: authAddress.port
        }
    }
}
