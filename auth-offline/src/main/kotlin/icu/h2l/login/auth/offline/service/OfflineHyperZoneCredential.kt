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

package icu.h2l.login.auth.offline.service

import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.login.auth.offline.db.OfflineAuthRepository
import icu.h2l.login.auth.offline.util.ExtraUuidUtils
import java.util.*

class OfflineHyperZoneCredential(
    private val repository: OfflineAuthRepository,
    private val registrationName: String,
    private val normalizedName: String,
    private val knownProfileId: UUID? = null,
    private val passProfileCreateUuid: Boolean = false
) : HyperZoneCredential {
    override val channelId: String = CHANNEL_ID
    override val credentialId: String = normalizedName

    override fun getRegistrationName(): String = registrationName

    override fun getBoundProfileId(): UUID? {
        return knownProfileId ?: repository.getByName(normalizedName)?.profileId
    }

    override fun getSuggestedProfileCreateUuid(): UUID? {
        if (!passProfileCreateUuid) {
            return null
        }

        return ExtraUuidUtils.getNormalOfflineUUID(registrationName)
    }

    companion object {
        private const val CHANNEL_ID = "offline"
    }
}
