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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 渠道密码门统一使用的密码哈希工具。
 *
 * 与离线认证模块的密码格式保持一致（SHA-256 十六进制），
 * 便于跨渠道的密码校验与后续迁移。
 */
object PasswordHasher {

    /** 本项目默认密码哈希格式。 */
    const val FORMAT_SHA256 = "sha256"

    /**
     * 计算密码的 SHA-256 十六进制摘要。
     */
    fun sha256(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * 校验明文密码是否匹配已存储的哈希。
     *
     * 存储值为空（未设置密码）时恒返回 false。
     */
    fun verify(password: String, storedHash: String?, storedFormat: String?): Boolean {
        if (storedHash.isNullOrBlank()) {
            return false
        }
        return when (storedFormat?.lowercase()) {
            null, FORMAT_SHA256 -> sha256(password) == storedHash
            else -> sha256(password) == storedHash
        }
    }
}
