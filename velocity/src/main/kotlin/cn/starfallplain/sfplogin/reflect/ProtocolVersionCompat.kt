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

package cn.starfallplain.sfplogin.reflect

import com.velocitypowered.api.network.ProtocolVersion

/**
 * 跨 Velocity 版本的 ProtocolVersion 常量兼容层。
 *
 * Velocity 3.4.0-SNAPSHOT 可能不包含 1.21.6、1.21.11、26.1 等较新的协议版本常量；
 * 本对象通过反射按名称查找枚举值，不存在时提供原始协议 ID 回退。
 */
object ProtocolVersionCompat {

    private fun findVersion(name: String): ProtocolVersion? = runCatching {
        ProtocolVersion.valueOf(name)
    }.getOrNull()

    // ── 已知枚举可能在旧版 Velocity 中缺失的常量 ──

    /** Minecraft Java 1.21.6 (protocol 771), added in Velocity 3.5+. */
    val MINECRAFT_1_21_6: ProtocolVersion? = findVersion("MINECRAFT_1_21_6")

    /** Minecraft Java 1.21.11 (protocol ~772+), added in Velocity 3.5+. */
    val MINECRAFT_1_21_11: ProtocolVersion? = findVersion("MINECRAFT_1_21_11")

    /** Minecraft Java Edition 26.1 (new naming scheme, protocol ~780+), added in Velocity 3.5+. */
    val MINECRAFT_26_1: ProtocolVersion? = findVersion("MINECRAFT_26_1")

    // ── 原始协议 ID 回退值（仅在对应枚举常量缺失时使用） ──
    const val RAW_1_21_6: Int = 771
    const val RAW_1_21_11: Int = 772
    const val RAW_26_1: Int = 780

    // ── 辅助扩展：接受可空 ProtocolVersion 并回退到原始 ID 比较 ──

    /**
     * 返回 `this >= version`；若 [version] 为 null，则与原始协议 [rawFallbackId] 比较。
     */
    fun ProtocolVersion.noLessThanCompat(version: ProtocolVersion?, rawFallbackId: Int): Boolean =
        if (version != null) noLessThan(version) else protocol >= rawFallbackId

    /**
     * 返回 `this <= version`；若 [version] 为 null，则与原始协议 [rawFallbackId] 比较。
     */
    fun ProtocolVersion.noGreaterThanCompat(version: ProtocolVersion?, rawFallbackId: Int): Boolean =
        if (version != null) noGreaterThan(version) else protocol <= rawFallbackId
}

