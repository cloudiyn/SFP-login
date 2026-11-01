/*
 * This file is part of HyperZoneLogin, licensed under the GNU Affero General Public License v3.0 or later.
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

package icu.h2l.api.password

/**
 * 统一渠道密码表记录。
 *
 * 三个渠道共用该模型；与渠道无关的字段（如离线找回相关）在非 offline 渠道下为空。
 */
data class ChannelPasswordEntry(
    val id: Int,
    val channel: String,
    val entryId: String,
    val name: String,
    val channelKey: String,
    val profileId: java.util.UUID?,
    val passwordHash: String?,
    val hashFormat: String?,
    val loginFailCount: Int,
    val loginBlockedUntil: Long?,
    val email: String?,
    val recoveryCodeHash: String?,
    val recoveryCodeExpireAt: Long?,
    val recoveryRequestedAt: Long?,
    val recoveryVerifyTries: Int,
    val resetPasswordVerifiedUntil: Long?,
    val sessionIp: String?,
    val sessionIssuedAt: Long?,
    val sessionExpiresAt: Long?,
    val totpSecret: String?,
) {
    /** 是否已设置本地密码。 */
    val hasPassword: Boolean
        get() = !passwordHash.isNullOrBlank()

    /** 当前是否处于密码错误封锁中。 */
    fun isBlocked(now: Long = System.currentTimeMillis()): Boolean =
        loginBlockedUntil != null && loginBlockedUntil > now
}

/**
 * 认证渠道标识。
 *
 * 与 [icu.h2l.api.profile.HyperZoneCredential.channelId] 保持一致。
 */
enum class PasswordChannel(val id: String) {
    OFFLINE("offline"),
    YGGDRASIL("yggdrasil"),
    FLOODGATE("floodgate"),
    ;

    companion object {
        fun fromId(id: String?): PasswordChannel? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
