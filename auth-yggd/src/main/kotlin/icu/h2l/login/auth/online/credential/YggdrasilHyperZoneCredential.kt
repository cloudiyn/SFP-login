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

package icu.h2l.login.auth.online.credential

import icu.h2l.api.profile.HyperZoneCredential
import icu.h2l.login.auth.online.db.EntryDatabaseHelper
import java.util.*

class YggdrasilHyperZoneCredential(
    private val entryDatabaseHelper: EntryDatabaseHelper,
    /** 该凭证来源的入口 ID（如 `mojang`、`littleskin`）。 */
    val entryId: String,
    private val authenticatedName: String,
    /** 远端 Yggdrasil 认证给出的可信 UUID。 */
    val authenticatedUUID: UUID,
    private val suggestedProfileCreateUuid: UUID?,
    private val knownProfileId: UUID? = null
) : HyperZoneCredential {
    override val channelId: String = CHANNEL_ID

    /**
     * 展示渠道标识取具体入口 ID（`mojang` / `littleskin` / `elyby`），
     * 使登录提示能够区分玩家本次实际使用的验证来源。
     */
    override val displayChannelId: String = entryId

    override val credentialId: String = "$entryId:$authenticatedUUID"

    override fun getRegistrationName(): String = authenticatedName

    override fun getBoundProfileId(): UUID? {
        return knownProfileId ?: entryDatabaseHelper.findEntryByUuid(entryId, authenticatedUUID)
    }

    override fun getSuggestedProfileCreateUuid(): UUID? {
        return suggestedProfileCreateUuid
    }

    companion object {
        private const val CHANNEL_ID = "yggdrasil"
    }
}
