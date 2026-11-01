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

package cn.starfallplain.sfplogin.api.util

import com.velocitypowered.api.util.GameProfile
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.random.Random

/**
 * 生成登录链路中临时 remap 档案的工具集合。
 */
object RemapUtils {
    /**
     * 系统期望的随机临时名称前缀。
     */
    const val EXPECTED_NAME_PREFIX = "HZL"

    /**
     * 等待区 remap 档案使用的 UUID 前缀命名空间。
     */
    const val REMAP_PREFIX = "check"
    private const val PROFILE_PREFIX = "h2l"

    /**
     * 为指定用户名生成一个临时 [GameProfile]。
     */
    fun genProfile(username: String, prefix: String): GameProfile {
        return GameProfile(
            genUUID(username, prefix), username,
            Collections.emptyList()
        )
    }

    /**
     * 根据名称与命名空间前缀生成稳定 UUID。
     */
    fun genUUID(username: String, prefix: String): UUID {
        return UUID.nameUUIDFromBytes(("$prefix:$username").toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 解析本次登录应使用的「实际 UUID」。
     *
     * 不变式（兼容基线，见 R1-1）：
     * - 若前置流程（正版校验 / Floodgate）已经确定了 UUID，直接沿用，不再重算；
     * - 否则（离线、未认证阶段）按 `remap.prefix` 生成稳定 UUID：同一用户名 + 同一前缀始终得到同一 UUID；
     * - 认证完成后，正式 Profile UUID 来自数据库，转发给后端使用该 UUID。
     *
     * 因此迁移时必须保留 `remap.prefix` 的默认值（当前为 `HyperZone`），否则未认证阶段的 UUID 会漂移。
     */
    fun resolveActualUuid(holderUuid: UUID?, username: String, prefix: String): UUID {
        return holderUuid ?: genUUID(username, prefix)
    }

    /**
     * 根据正式用户名生成稳定的 Profile UUID。
     */
    fun genProfileUUID(username: String): UUID {
        val normalized = username.lowercase()
        return genUUID(normalized, PROFILE_PREFIX)
    }

    /**
     * 生成一个带随机后缀的临时 remap 档案。
     */
    fun randomProfile(): GameProfile {
        val randomId = String.format("%06d", Random.nextInt(1_000_000))
        val newName = "$EXPECTED_NAME_PREFIX$randomId"
        return genProfile(newName, REMAP_PREFIX)
    }
}
