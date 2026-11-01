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

package cn.starfallplain.sfplogin.vServer.outpre

/**
 * 一次登录中声明的身份（代理转发给认证服的那一份）。
 *
 * @property uuid 声明的 UUID；`null` 表示本次握手没有携带 UUID 字段
 * @property name 声明的用户名
 */
data class OutPreClaimedIdentity(
    val uuid: java.util.UUID?,
    val name: String,
)

/**
 * 认证服回传的档案与登录声明不一致时的差异描述。
 */
data class OutPreClaimMismatch(
    val expected: OutPreClaimedIdentity,
    val returned: OutPreClaimedIdentity,
) {
    fun describe(): String {
        return "expected(uuid=${expected.uuid}, name=${expected.name}) " +
            "returned(uuid=${returned.uuid}, name=${returned.name})"
    }
}

/**
 * 认证服回传档案的校验器。
 *
 * outpre 模式下，代理先把"声明的身份"通过 modern forwarding 发给认证服，认证服在
 * `ServerLoginSuccess` 里回传它认可的档案。两者不一致意味着链路中的某一方改写了身份
 * （配置错误的 forwarding secret、额外的代理层、或非预期插件），继续放行会把玩家以
 * 错误身份写进后端世界，因此必须中止本次登录并退回等待区。
 */
object OutPreLoginClaimVerifier {

    /**
     * 校验认证服回传的身份是否与声明一致。
     *
     * 用户名按去空格、忽略大小写比较；UUID 只在双方都提供时比较——老版本握手可能
     * 不携带 UUID 字段，这种握手本身已经通过 forwarding 校验，不应因为缺少字段而拒绝。
     *
     * @return 一致时返回 `null`，否则返回差异描述
     */
    fun verify(expected: OutPreClaimedIdentity, returned: OutPreClaimedIdentity): OutPreClaimMismatch? {
        val nameMatches = expected.name.trim().equals(returned.name.trim(), ignoreCase = true)
        val uuidMatches = expected.uuid == null || returned.uuid == null || expected.uuid == returned.uuid
        return if (nameMatches && uuidMatches) null else OutPreClaimMismatch(expected, returned)
    }
}
