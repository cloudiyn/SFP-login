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

package cn.starfallplain.sfplogin.qq.whitelist.config

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.util.UUID

/**
 * QQ 绑定白名单模块配置。
 */
@ConfigSerializable
class QqWhitelistConfig {

    // 是否启用模块
    @Comment("config.qq-whitelist.enabled")
    var enabled: Boolean = true

    // 是否强制拦截未绑定玩家
    @Comment("config.qq-whitelist.enforce")
    var enforce: Boolean = true

    // 群内文案使用的语言
    @Comment("config.qq-whitelist.group-language")
    var groupLanguage: String = "zh_cn"

    // OneBot v11 连接配置
    @Comment("config.qq-whitelist.onebot")
    var onebot: OneBotConfig = OneBotConfig()

    // 允许响应的群号
    @Comment("config.qq-whitelist.groups")
    var groups: List<Long> = emptyList()

    // 群内命令配置
    @Comment("config.qq-whitelist.commands")
    var commands: CommandConfig = CommandConfig()

    // 绑定规则
    @Comment("config.qq-whitelist.binding")
    var binding: BindingConfig = BindingConfig()

    // 豁免名单
    @Comment("config.qq-whitelist.bypass")
    var bypass: BypassConfig = BypassConfig()
}

@ConfigSerializable
class OneBotConfig {
    // 正向 WebSocket 地址
    @Comment("config.qq-whitelist.onebot.url")
    var url: String = "ws://127.0.0.1:3001"

    // 访问令牌
    @Comment("config.qq-whitelist.onebot.token")
    var token: String = ""

    // 重连配置
    @Comment("config.qq-whitelist.onebot.reconnect")
    var reconnect: ReconnectConfig = ReconnectConfig()

    // 请求超时
    @Comment("config.qq-whitelist.onebot.request-timeout-seconds")
    var requestTimeoutSeconds: Long = 10

    // 心跳超时
    @Comment("config.qq-whitelist.onebot.heartbeat-timeout-seconds")
    var heartbeatTimeoutSeconds: Long = 90

    // OneBot 断开时是否放行（避免机器人掉线导致全服无法进入）
    @Comment("config.qq-whitelist.onebot.fail-open-when-disconnected")
    var failOpenWhenDisconnected: Boolean = true
}

@ConfigSerializable
class ReconnectConfig {
    // 是否自动重连
    @Comment("config.qq-whitelist.onebot.reconnect.enable")
    var enable: Boolean = true

    // 最小重连间隔（秒）
    @Comment("config.qq-whitelist.onebot.reconnect.min-delay-seconds")
    var minDelaySeconds: Long = 3

    // 最大重连间隔（秒）
    @Comment("config.qq-whitelist.onebot.reconnect.max-delay-seconds")
    var maxDelaySeconds: Long = 60
}

@ConfigSerializable
class CommandConfig {
    // 绑定关键字
    @Comment("config.qq-whitelist.commands.bind-keyword")
    var bindKeyword: String = "绑定"

    // 解绑关键字
    @Comment("config.qq-whitelist.commands.unbind-keyword")
    var unbindKeyword: String = "解绑"

    // 查询关键字
    @Comment("config.qq-whitelist.commands.status-keyword")
    var statusKeyword: String = "绑定查询"

    // 回复时是否 @ 发送者
    @Comment("config.qq-whitelist.commands.reply-at-sender")
    var replyAtSender: Boolean = true
}

@ConfigSerializable
class BindingConfig {
    // 单个 QQ 最多绑定账号数
    @Comment("config.qq-whitelist.binding.max-per-qq")
    var maxPerQq: Int = 2

    // 验证码长度
    @Comment("config.qq-whitelist.binding.code-length")
    var codeLength: Int = 6

    // 验证码有效期（秒）
    @Comment("config.qq-whitelist.binding.code-ttl-seconds")
    var codeTtlSeconds: Long = 600

    // 验证码失败尝试上限
    @Comment("config.qq-whitelist.binding.code-attempt-limit")
    var codeAttemptLimit: Int = 5

    // 绑定后首次进入游戏是否提示
    @Comment("config.qq-whitelist.binding.notify-once-on-login")
    var notifyOnceOnLogin: Boolean = true
}

@ConfigSerializable
class BypassConfig {
    // 跳过校验的权限节点
    @Comment("config.qq-whitelist.bypass.permission")
    var permission: String = "hyperzonelogin.qq.whitelist.bypass"

    // 跳过校验的档案 UUID
    @Comment("config.qq-whitelist.bypass.profiles")
    var profiles: Set<UUID> = emptySet()
}
