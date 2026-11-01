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

package cn.starfallplain.sfplogin.profile

import cn.starfallplain.sfplogin.api.profile.CredentialChannelAbility
import cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistry
import cn.starfallplain.sfplogin.config.AuthConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * [CredentialChannelRegistry] 的 Velocity 侧实现。
 *
 * 使用 [ConcurrentHashMap] 存储渠道信息，保证并发安全。
 * 注册操作是幂等的，重复注册同一渠道 ID 不会产生副作用。
 *
 * @param authConfig 核心 auth 配置，用于计算各渠道的能力（如是否允许注册）。
 */
class CredentialChannelRegistryImpl(
    private val authConfig: AuthConfig,
) : CredentialChannelRegistry {

    private val abilities: MutableMap<String, CredentialChannelAbility> = ConcurrentHashMap()

    override fun registerChannel(channelId: String): CredentialChannelAbility {
        val ability = buildAbility(channelId)
        abilities[channelId] = ability
        return ability
    }

    override fun isRegistered(channelId: String): Boolean {
        return channelId in abilities
    }

    override fun getRegisteredChannelIds(): Set<String> {
        return abilities.keys.toSet()
    }

    override fun getChannelAbility(channelId: String): CredentialChannelAbility? {
        return abilities[channelId]
    }

    private fun buildAbility(channelId: String): CredentialChannelAbility {
        val canRegister = channelId !in authConfig.disableRegistration
        return CredentialChannelAbility(
            channelId = channelId,
            canRegister = canRegister,
        )
    }
}
