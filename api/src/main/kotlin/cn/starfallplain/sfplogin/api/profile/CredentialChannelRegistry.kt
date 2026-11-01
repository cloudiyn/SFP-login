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

package cn.starfallplain.sfplogin.api.profile

/**
 * 凭证渠道注册表。
 *
 * 子模块在注册时需向此注册表声明自己将使用的凭证渠道 ID（即 [HyperZoneCredential.channelId]）。
 * 核心层可通过此注册表了解系统中存在哪些认证渠道，并支持后续的定向行为控制。
 */
interface CredentialChannelRegistry {

    /**
     * 注册一个凭证渠道 ID，并返回该渠道当前的能力描述。
     *
     * 同一渠道 ID 可被重复注册（幂等），不会报错，但每次调用均会返回最新能力。
     *
     * @param channelId 凭证渠道唯一标识，与 [HyperZoneCredential.channelId] 对应
     * @return 由核心层根据当前配置计算出的 [CredentialChannelAbility]
     */
    fun registerChannel(channelId: String): CredentialChannelAbility

    /**
     * 注销一个凭证渠道 ID。
     *
     * 模块卸载或重新注册时应调用，避免旧模块占用的渠道 ID 残留。
     *
     * @param channelId 凭证渠道唯一标识
     * @return 若该渠道此前已注册并被移除则返回 `true`
     */
    fun unregisterChannel(channelId: String): Boolean

    /**
     * 判断指定凭证渠道 ID 是否已注册。
     */
    fun isRegistered(channelId: String): Boolean

    /**
     * 返回当前已注册的所有凭证渠道 ID 的快照（不可变集合）。
     */
    fun getRegisteredChannelIds(): Set<String>

    /**
     * 获取指定渠道的能力描述。
     *
     * @return 若该渠道已注册则返回对应 [CredentialChannelAbility]，否则返回 `null`。
     */
    fun getChannelAbility(channelId: String): CredentialChannelAbility?
}

/**
 * [CredentialChannelRegistry] 的全局访问器。
 */
object CredentialChannelRegistryProvider {
    @Volatile
    private var registry: CredentialChannelRegistry? = null

    /**
     * 绑定当前运行时的 [CredentialChannelRegistry] 实例。
     */
    fun bind(registry: CredentialChannelRegistry) {
        this.registry = registry
    }

    /**
     * 解除绑定（插件卸载时调用），之后 [getOrNull] 返回 `null`。
     */
    fun unbind() {
        registry = null
    }

    /**
     * 获取已绑定的注册表，若尚未初始化则抛错。
     */
    fun get(): CredentialChannelRegistry =
        registry ?: error("HyperZone CredentialChannelRegistry is not available yet")

    /**
     * 获取已绑定的注册表，若当前不可用则返回 `null`。
     */
    fun getOrNull(): CredentialChannelRegistry? = registry
}
