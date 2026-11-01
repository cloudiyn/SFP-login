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

package icu.h2l.login.auth.offline.db

import icu.h2l.api.password.ChannelPasswordEntry
import icu.h2l.api.password.ChannelPasswordRepository
import icu.h2l.api.password.PasswordChannel
import icu.h2l.login.auth.offline.api.db.OfflineAuthEntry
import java.util.*

/**
 * 离线认证数据仓库。
 *
 * 自统一密码存储改造后，本仓库不再直接读写 `offline_auth` 表，
 * 而是把全部读写转发到**统一密码表**（[ChannelPasswordRepository]，channel = offline）。
 *
 * 之所以保留本类与既有方法签名，是为了让上层（[icu.h2l.login.auth.offline.service.OfflineAuthService]
 * 及其凭证、密码门）无需改动；离线渠道的对外语义完全不变。
 *
 * 唯一身份键为规范化（小写）后的玩家名，对应统一表的 `channel_key`。
 */
class OfflineAuthRepository(
    private val passwordRepository: ChannelPasswordRepository
) {
    fun create(
        name: String,
        passwordHash: String,
        hashFormat: String,
        profileId: UUID,
        email: String? = null
    ): Boolean {
        val normalizedName = name.lowercase()
        val created = passwordRepository.setPassword(
            channel = PasswordChannel.OFFLINE,
            channelKey = normalizedName,
            hash = passwordHash,
            format = hashFormat,
            name = normalizedName,
            profileId = profileId
        )
        if (created && email != null) {
            getByName(normalizedName)?.let { passwordRepository.updateEmail(it.id, email) }
        }
        return created
    }

    fun getByName(name: String): OfflineAuthEntry? {
        val normalizedName = name.lowercase()
        return passwordRepository.find(PasswordChannel.OFFLINE, normalizedName)?.toOfflineEntry()
    }

    fun getByProfileId(profileId: UUID): OfflineAuthEntry? {
        return passwordRepository.findByProfileId(profileId)?.toOfflineEntry()
    }

    fun getByEmail(email: String): OfflineAuthEntry? {
        return passwordRepository.findByEmail(email)?.toOfflineEntry()
    }

    fun updatePassword(profileId: UUID, passwordHash: String, hashFormat: String): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.updatePasswordById(entry.id, passwordHash, hashFormat)
    }

    fun updateEmail(profileId: UUID, email: String?): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.updateEmail(entry.id, email)
    }

    fun updateLoginProtection(profileId: UUID, failCount: Int, blockedUntil: Long?): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.updateLoginProtection(entry.id, failCount, blockedUntil)
    }

    fun resetLoginProtection(profileId: UUID): Boolean = updateLoginProtection(profileId, 0, null)

    fun startRecovery(profileId: UUID, recoveryCodeHash: String, expireAt: Long, requestedAt: Long): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.startRecovery(entry.id, recoveryCodeHash, expireAt, requestedAt)
    }

    fun incrementRecoveryVerifyTries(profileId: UUID): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.incrementRecoveryVerifyTries(entry.id)
    }

    fun markRecoveryVerified(profileId: UUID, verifiedUntil: Long): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.markRecoveryVerified(entry.id, verifiedUntil)
    }

    fun clearRecoveryState(profileId: UUID): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.clearRecoveryState(entry.id)
    }

    fun issueSession(profileId: UUID, sessionIp: String?, issuedAt: Long, expiresAt: Long): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.issueSession(entry.id, sessionIp, issuedAt, expiresAt)
    }

    fun clearSession(profileId: UUID): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.clearSession(entry.id)
    }

    fun updateTotpSecret(profileId: UUID, totpSecret: String?): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.updateTotpSecret(entry.id, totpSecret)
    }

    fun deleteByProfileId(profileId: UUID): Boolean {
        val entry = getByProfileId(profileId) ?: return false
        return passwordRepository.deleteById(entry.id)
    }

    private fun ChannelPasswordEntry.toOfflineEntry(): OfflineAuthEntry {
        return OfflineAuthEntry(
            id = id,
            name = name,
            passwordHash = passwordHash.orEmpty(),
            hashFormat = hashFormat.orEmpty(),
            profileId = profileId ?: UUID(0L, 0L),
            email = email,
            recoveryCodeHash = recoveryCodeHash,
            recoveryCodeExpireAt = recoveryCodeExpireAt,
            recoveryRequestedAt = recoveryRequestedAt,
            recoveryVerifyTries = recoveryVerifyTries,
            resetPasswordVerifiedUntil = resetPasswordVerifiedUntil,
            loginFailCount = loginFailCount,
            loginBlockedUntil = loginBlockedUntil,
            sessionIp = sessionIp,
            sessionIssuedAt = sessionIssuedAt,
            sessionExpiresAt = sessionExpiresAt,
            totpSecret = totpSecret
        )
    }
}
