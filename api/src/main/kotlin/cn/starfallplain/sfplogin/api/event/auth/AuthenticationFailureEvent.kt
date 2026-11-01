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

package cn.starfallplain.sfplogin.api.event.auth

/**
 * 认证模块在登录链路中判定失败时抛出的统一事件。
 *
 * @property userName 本次认证请求对应的用户名
 * @property playerIp 玩家 IP，若不可用则为 `null`
 * @property authType 当前认证渠道类型
 * @property reason 失败原因枚举
 * @property reasonMessage 适合日志与提示链路记录的失败摘要
 * @property providerId 具体命中的认证提供者标识
 * @property throwableSummary 可选的异常概要信息
 * @property occurredAt 失败发生的时间戳，单位为毫秒
 */
class AuthenticationFailureEvent(
    val userName: String,
    val playerIp: String?,
    val authType: AuthType,
    val reason: Reason,
    val reasonMessage: String,
    val providerId: String? = null,
    val throwableSummary: String? = null,
    val occurredAt: Long = System.currentTimeMillis()
) {
    /**
     * 触发失败事件的认证渠道类型。
     */
    enum class AuthType {
        /**
         * 离线认证链路。
         */
        OFFLINE,

        /**
         * Yggdrasil/正版认证链路。
         */
        YGGDRASIL
    }

    /**
     * 认证失败原因分类。
     */
    enum class Reason {
        /** 用户名/密码等主要凭证无效。 */
        INVALID_CREDENTIALS,

        /** 远端服务或本地规则触发限流。 */
        RATE_LIMITED,

        /** 当前流程需要二步验证码，但尚未提供。 */
        TOTP_REQUIRED,

        /** 提供的二步验证码不正确。 */
        TOTP_INVALID,

        /** 会话校验被远端拒绝。 */
        SESSION_REJECTED,

        /** 远端认证服务主动拒绝当前请求。 */
        REMOTE_REJECTED,

        /** 与认证服务通信超时。 */
        TIMEOUT,

        /** 当前没有任何可用的认证提供者。 */
        NO_PROVIDERS,

        /** 玩家当前状态不允许继续认证。 */
        PLAYER_STATE_REJECTED,

        /** 未分类的未知错误。 */
        UNKNOWN
    }
}

