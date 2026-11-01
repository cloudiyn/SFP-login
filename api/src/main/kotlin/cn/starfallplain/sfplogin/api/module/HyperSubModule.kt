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

package cn.starfallplain.sfplogin.api.module

import cn.starfallplain.sfplogin.api.HyperZoneApi

/**
 * SFP-login 子模块的标准接入点。
 */
interface HyperSubModule {
    /**
     * 该子模块将向核心层提交的凭证渠道 ID 集合（对应 [cn.starfallplain.sfplogin.api.profile.HyperZoneCredential.channelId]）。
     *
     * 核心层在调用 [register] 之前会自动将这些渠道 ID 写入
     * [cn.starfallplain.sfplogin.api.profile.CredentialChannelRegistry]，以便后续对子模块行为进行定向控制。
     *
     * 不提交凭证的子模块（如 data-merge、safe）可使用默认空集合。
     */
    val credentialChannelIds: Set<String>
        get() = emptySet()

    /**
     * 将当前子模块注册到指定的 [HyperZoneApi] 运行时中。
     */
    fun register(api: HyperZoneApi)

    /**
     * 模块被卸载或被注册回滚时调用，用于释放 [register] 中登记的资源
     * （监听器、命令、注册表条目等）。
     *
     * 默认空实现。实现应幂等，且尽量不抛异常：核心层捕获异常后只记录警告，
     * 并继续完成其余卸载步骤。
     */
    fun unregister(api: HyperZoneApi) {
        // 默认没有需要释放的资源
    }
}
