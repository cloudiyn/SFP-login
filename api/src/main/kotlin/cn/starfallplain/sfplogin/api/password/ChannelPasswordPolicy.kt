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

package cn.starfallplain.sfplogin.api.password

/**
 * 渠道密码校验策略。
 *
 * 三个认证渠道（offline / yggdrasil / floodgate）在统一密码存储后共用同一套校验流程，
 * 但各渠道的密码长度、失败封锁与会话参数仍由各自配置决定，因此统一抽象成该数据类，
 * 由各渠道从自己的 config 映射后传入 [ChannelPasswordService]。
 *
 * @param minLength 密码最短长度
 * @param maxLength 密码最长长度
 * @param denyNameInPassword 是否禁止密码包含玩家名
 * @param maxAttempts 连续错误多少次后封锁
 * @param blockSeconds 封锁时长（秒）
 * @param sessionEnabled 是否启用短期会话自动登录
 * @param sessionExpireMinutes 会话有效期（分钟）
 * @param sessionBindIp 会话是否与玩家 IP 绑定
 * @param sessionIssueOnRegister 注册成功后是否立刻签发会话
 */
data class ChannelPasswordPolicy(
    val minLength: Int = 6,
    val maxLength: Int = 64,
    val denyNameInPassword: Boolean = true,
    val maxAttempts: Int = 5,
    val blockSeconds: Int = 300,
    val sessionEnabled: Boolean = true,
    val sessionExpireMinutes: Int = 30,
    val sessionBindIp: Boolean = true,
    val sessionIssueOnRegister: Boolean = true,
) {
    companion object {
        /** 供测试与保守默认使用的策略。 */
        val DEFAULT = ChannelPasswordPolicy()
    }
}
